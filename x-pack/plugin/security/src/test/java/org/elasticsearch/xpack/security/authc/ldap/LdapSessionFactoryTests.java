/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.security.authc.ldap;

import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.LDAPURL;
import com.unboundid.ldap.sdk.SimpleBindRequest;
import org.elasticsearch.common.network.NetworkAddress;
import org.elasticsearch.common.settings.SecureString;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.concurrent.ThreadContext;
import org.elasticsearch.common.util.concurrent.UncategorizedExecutionException;
import org.elasticsearch.env.Environment;
import org.elasticsearch.env.TestEnvironment;
import org.elasticsearch.threadpool.TestThreadPool;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.watcher.ResourceWatcherService;
import org.elasticsearch.xpack.core.security.authc.RealmConfig;
import org.elasticsearch.xpack.core.security.authc.RealmSettings;
import org.elasticsearch.xpack.core.security.authc.ldap.support.LdapSearchScope;
import org.elasticsearch.xpack.core.security.authc.ldap.support.SessionFactorySettings;
import org.elasticsearch.xpack.core.ssl.SSLConfigurationReloader;
import org.elasticsearch.xpack.core.ssl.SSLService;
import org.elasticsearch.xpack.security.authc.ldap.support.LdapSession;
import org.elasticsearch.xpack.security.authc.ldap.support.LdapTestCase;
import org.junit.After;
import org.junit.Before;

import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.ExecutionException;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;

public class LdapSessionFactoryTests extends LdapTestCase {
    private Settings globalSettings;
    private SSLService sslService;
    private ThreadPool threadPool;
    private Path ldapCaPath;

    @Override
    protected boolean openLdapsPort() {
        // Support LDAPS, because it's used in some test
        return true;
    }

    @Before
    public void setup() throws Exception {
        final Path origCa = getDataPath("/org/elasticsearch/xpack/security/authc/ldap/support/ldap-ca.crt");
        ldapCaPath = createTempFile();
        Files.copy(origCa, ldapCaPath, StandardCopyOption.REPLACE_EXISTING);
        globalSettings = Settings.builder()
            .put("path.home", createTempDir())
            .putList(RealmSettings.PREFIX + "ldap_realm.ssl.certificate_authorities", ldapCaPath.toString())
            .build();
        sslService = new SSLService(globalSettings, TestEnvironment.newEnvironment(globalSettings));
        threadPool = new TestThreadPool("LdapSessionFactoryTests thread pool");
    }

    @After
    public void shutdown() throws InterruptedException {
        terminate(threadPool);
    }

    public void testBindWithReadTimeout() throws Exception {
        InMemoryDirectoryServer ldapServer = randomFrom(ldapServers);
        String protocol = randomFrom("ldap", "ldaps");
        InetAddress listenAddress = ldapServer.getListenAddress(protocol);
        if (listenAddress == null) {
            listenAddress = InetAddress.getLoopbackAddress();
        }
        String ldapUrl = new LDAPURL(protocol, NetworkAddress.format(listenAddress), ldapServer.getListenPort(protocol),
            null, null, null, null).toString();
        String groupSearchBase = "o=sevenSeas";
        String userTemplates = "cn={0},ou=people,o=sevenSeas";

        Settings settings = Settings.builder()
            .put(buildLdapSettings(ldapUrl, userTemplates, groupSearchBase, LdapSearchScope.SUB_TREE))
            .put(SessionFactorySettings.TIMEOUT_TCP_READ_SETTING, "1ms") //1 millisecond
            .put("path.home", createTempDir())
            .build();

        RealmConfig config = new RealmConfig("ldap_realm", settings, globalSettings,
            TestEnvironment.newEnvironment(globalSettings), new ThreadContext(globalSettings));
        LdapSessionFactory sessionFactory = new LdapSessionFactory(config, sslService, threadPool);
        String user = "Horatio Hornblower";
        SecureString userPass = new SecureString("pass");

        ldapServer.setProcessingDelayMillis(500L);
        try {
            UncategorizedExecutionException e =
                expectThrows(UncategorizedExecutionException.class, () -> session(sessionFactory, user, userPass));
            assertThat(e.getCause(), instanceOf(ExecutionException.class));
            assertThat(e.getCause().getCause(), instanceOf(LDAPException.class));
            assertThat(e.getCause().getCause().getMessage(), containsString("A client-side timeout was encountered while waiting "));
        } finally {
            ldapServer.setProcessingDelayMillis(0L);
        }
    }

    public void testBindWithTemplates() throws Exception {
        String groupSearchBase = "o=sevenSeas";
        String[] userTemplates = new String[]{
            "cn={0},ou=something,ou=obviously,ou=incorrect,o=sevenSeas",
            "wrongname={0},ou=people,o=sevenSeas",
            "cn={0},ou=people,o=sevenSeas", //this last one should work
        };
        RealmConfig config = new RealmConfig("ldap_realm",
            buildLdapSettings(ldapUrls(), userTemplates, groupSearchBase, LdapSearchScope.SUB_TREE),
            globalSettings, TestEnvironment.newEnvironment(globalSettings), new ThreadContext(globalSettings));

        LdapSessionFactory sessionFactory = new LdapSessionFactory(config, sslService, threadPool);

        String user = "Horatio Hornblower";
        SecureString userPass = new SecureString("pass");
        final SimpleBindRequest bindRequest = new SimpleBindRequest("cn=Horatio Hornblower,ou=people,o=sevenSeas", "pass");

        try (LdapSession ldap = session(sessionFactory, user, userPass)) {
            assertConnectionValid(ldap.getConnection(), bindRequest);
            String dn = ldap.userDn();
            assertThat(dn, containsString(user));
        }
    }

    public void testBindWithBogusTemplates() throws Exception {
        String groupSearchBase = "o=sevenSeas";
        String[] userTemplates = new String[]{
            "cn={0},ou=something,ou=obviously,ou=incorrect,o=sevenSeas",
            "wrongname={0},ou=people,o=sevenSeas",
            "asdf={0},ou=people,o=sevenSeas", //none of these should work
        };
        RealmConfig config = new RealmConfig("ldap_realm",
            buildLdapSettings(ldapUrls(), userTemplates, groupSearchBase, LdapSearchScope.SUB_TREE),
            globalSettings, TestEnvironment.newEnvironment(globalSettings), new ThreadContext(globalSettings));

        LdapSessionFactory ldapFac = new LdapSessionFactory(config, sslService, threadPool);

        String user = "Horatio Hornblower";
        SecureString userPass = new SecureString("pass");
        UncategorizedExecutionException e = expectThrows(UncategorizedExecutionException.class, () -> session(ldapFac, user, userPass));
        assertThat(e.getCause(), instanceOf(ExecutionException.class));
        assertThat(e.getCause().getCause(), instanceOf(LDAPException.class));
        assertThat(e.getCause().getCause().getMessage(), containsString("Unable to bind as user"));
        Throwable[] suppressed = e.getCause().getCause().getSuppressed();
        assertThat(suppressed.length, is(2));
    }

    public void testGroupLookupSubtree() throws Exception {
        String groupSearchBase = "o=sevenSeas";
        String userTemplate = "cn={0},ou=people,o=sevenSeas";
        RealmConfig config = new RealmConfig("ldap_realm",
            buildLdapSettings(ldapUrls(), userTemplate, groupSearchBase, LdapSearchScope.SUB_TREE),
            globalSettings, TestEnvironment.newEnvironment(globalSettings), new ThreadContext(globalSettings));

        LdapSessionFactory ldapFac = new LdapSessionFactory(config, sslService, threadPool);

        String user = "Horatio Hornblower";
        SecureString userPass = new SecureString("pass");
        final SimpleBindRequest bindRequest = new SimpleBindRequest("cn=Horatio Hornblower,ou=people,o=sevenSeas", "pass");

        try (LdapSession ldap = session(ldapFac, user, userPass)) {
            assertConnectionValid(ldap.getConnection(), bindRequest);
            List<String> groups = groups(ldap);
            assertThat(groups, contains("cn=HMS Lydia,ou=crews,ou=groups,o=sevenSeas"));
        }
    }

    public void testGroupLookupOneLevel() throws Exception {
        String groupSearchBase = "ou=crews,ou=groups,o=sevenSeas";
        String userTemplate = "cn={0},ou=people,o=sevenSeas";
        RealmConfig config = new RealmConfig("ldap_realm",
            buildLdapSettings(ldapUrls(), userTemplate, groupSearchBase, LdapSearchScope.ONE_LEVEL),
            globalSettings, TestEnvironment.newEnvironment(globalSettings), new ThreadContext(globalSettings));

        LdapSessionFactory ldapFac = new LdapSessionFactory(config, sslService, threadPool);

        String user = "Horatio Hornblower";
        final SimpleBindRequest bindRequest = new SimpleBindRequest("cn=Horatio Hornblower,ou=people,o=sevenSeas", "pass");

        try (LdapSession ldap = session(ldapFac, user, new SecureString("pass"))) {
            assertConnectionValid(ldap.getConnection(), bindRequest);
            List<String> groups = groups(ldap);
            assertThat(groups, contains("cn=HMS Lydia,ou=crews,ou=groups,o=sevenSeas"));
        }
    }

    public void testGroupLookupBase() throws Exception {
        String groupSearchBase = "cn=HMS Lydia,ou=crews,ou=groups,o=sevenSeas";
        String userTemplate = "cn={0},ou=people,o=sevenSeas";
        RealmConfig config = new RealmConfig("ldap_realm",
            buildLdapSettings(ldapUrls(), userTemplate, groupSearchBase, LdapSearchScope.BASE),
            globalSettings, TestEnvironment.newEnvironment(globalSettings), new ThreadContext(globalSettings));

        LdapSessionFactory ldapFac = new LdapSessionFactory(config, sslService, threadPool);

        String user = "Horatio Hornblower";
        SecureString userPass = new SecureString("pass");
        final SimpleBindRequest bindRequest = new SimpleBindRequest("cn=Horatio Hornblower,ou=people,o=sevenSeas", "pass");

        try (LdapSession ldap = session(ldapFac, user, userPass)) {
            assertConnectionValid(ldap.getConnection(), bindRequest);
            List<String> groups = groups(ldap);
            assertThat(groups.size(), is(1));
            assertThat(groups, contains("cn=HMS Lydia,ou=crews,ou=groups,o=sevenSeas"));
        }
    }

    /**
     * This test connects to the in memory LDAP server over SSL using 2 different CA certificates.
     * One certificate is valid, the other is not.
     * The path to the certificate never changes, but the contents are copied in place.
     * If the realm's CA path is monitored for changes and the underlying SSL context is reloaded, then we will get two different outcomes
     * (one failure, one success) depending on which file content is in place.
     */
    public void testSslTrustIsReloaded() throws Exception {
        InMemoryDirectoryServer ldapServer = randomFrom(ldapServers);
        InetAddress listenAddress = ldapServer.getListenAddress("ldaps");
        if (listenAddress == null) {
            listenAddress = InetAddress.getLoopbackAddress();
        }
        String ldapUrl = new LDAPURL("ldaps", NetworkAddress.format(listenAddress), ldapServer.getListenPort("ldaps"),
            null, null, null, null).toString();
        String groupSearchBase = "o=sevenSeas";
        String userTemplates = "cn={0},ou=people,o=sevenSeas";

        Settings settings = Settings.builder()
            .put(globalSettings)
            .put(buildLdapSettings(ldapUrl, userTemplates, groupSearchBase, LdapSearchScope.SUB_TREE))
            .build();

        // !!!make sure that the file size on disk for the two pem CAs is different!!!
        // otherwise, the resource watcher has to rely on the last modified timestamp to detect changes,
        // and the resolution for that can be as low as a second, and the test would spuriously fail
        final Path realCa = getDataPath("/org/elasticsearch/xpack/security/authc/ldap/support/ldap-ca.crt");
        final Path fakeCa = getDataPath("/org/elasticsearch/xpack/security/authc/ldap/support/ad.crt");

        final Environment environment = TestEnvironment.newEnvironment(settings);
        RealmConfig config = new RealmConfig("ldap_realm", settings, globalSettings, environment, new ThreadContext(settings));
        LdapSessionFactory sessionFactory = new LdapSessionFactory(config, sslService, threadPool);
        String user = "Horatio Hornblower";
        SecureString userPass = new SecureString("pass");

        final ResourceWatcherService resourceWatcher = new ResourceWatcherService(settings, threadPool);
        new SSLConfigurationReloader(environment, sslService, resourceWatcher);

        Files.copy(fakeCa, ldapCaPath, StandardCopyOption.REPLACE_EXISTING);
        // resourceWatcher looks at the file size and last access timestamp to detect changes
        resourceWatcher.notifyNow(ResourceWatcherService.Frequency.HIGH);

        UncategorizedExecutionException e =
            expectThrows(UncategorizedExecutionException.class, () -> session(sessionFactory, user, userPass));
        assertThat(e.getCause(), instanceOf(ExecutionException.class));
        assertThat(e.getCause().getCause(), instanceOf(LDAPException.class));
        assertThat(e.getCause().getCause().getMessage(), containsString("SSLPeerUnverifiedException"));

        Files.copy(realCa, ldapCaPath, StandardCopyOption.REPLACE_EXISTING);
        resourceWatcher.notifyNow(ResourceWatcherService.Frequency.HIGH);

        final LdapSession session = session(sessionFactory, user, userPass);
        assertThat(session.userDn(), is("cn=Horatio Hornblower,ou=people,o=sevenSeas"));

        session.close();
    }

    public void testDeprecationWarningIfTls1IsUsed() throws Exception {
        InMemoryDirectoryServer ldapServer = randomFrom(ldapServers);
        String ldapUrl = new LDAPURL("ldaps", "localhost", ldapServer.getListenPort("ldaps-tls1"), null, null, null, null).toString();
        String groupSearchBase = "o=sevenSeas";
        String userTemplates = "cn={0},ou=people,o=sevenSeas";

        Settings settings = Settings.builder()
            .put(globalSettings)
            .put(buildLdapSettings(ldapUrl, userTemplates, groupSearchBase, LdapSearchScope.SUB_TREE))
            .build();

        final Environment environment = TestEnvironment.newEnvironment(settings);
        RealmConfig config = new RealmConfig("ldap_realm", settings, globalSettings, environment, new ThreadContext(settings));
        LdapSessionFactory sessionFactory = new LdapSessionFactory(config, sslService, threadPool);
        String user = "Horatio Hornblower";
        SecureString userPass = new SecureString("pass");

        LdapSession session = session(sessionFactory, user, userPass);
        assertThat(session.userDn(), is("cn=Horatio Hornblower,ou=people,o=sevenSeas"));
        session.close();

        assertWarnings("a TLS v1.0 session was used for [ldap host localhost]," +
            " this protocol will be disabled by default in a future version." +
            " The [xpack.security.authc.realms.ldap_realm.ssl.supported_protocols] setting can be used to control this.");

    }
}
