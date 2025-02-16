/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.security.rest.action.saml;

import org.elasticsearch.client.node.NodeClient;
import org.elasticsearch.common.ParseField;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.ObjectParser;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.license.XPackLicenseState;
import org.elasticsearch.rest.BytesRestResponse;
import org.elasticsearch.rest.RestController;
import org.elasticsearch.rest.RestHandler;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.rest.RestRequestFilter;
import org.elasticsearch.rest.RestResponse;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.rest.action.RestBuilderListener;
import org.elasticsearch.xpack.core.security.action.saml.SamlAuthenticateRequestBuilder;
import org.elasticsearch.xpack.core.security.action.saml.SamlAuthenticateResponse;
import org.elasticsearch.xpack.core.security.client.SecurityClient;

import java.io.IOException;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.elasticsearch.rest.RestRequest.Method.POST;

/**
 * A REST handler that attempts to authenticate a user based on the provided SAML response/assertion.
 */
public class RestSamlAuthenticateAction extends SamlBaseRestHandler implements RestHandler, RestRequestFilter {

    static class Input {
        String content;
        List<String> ids;

        void setContent(String content) {
            this.content = content;
        }

        void setIds(List<String> ids) {
            this.ids = ids;
        }
    }

    static final ObjectParser<Input, Void> PARSER = new ObjectParser<>("saml_authenticate", Input::new);

    static {
        PARSER.declareString(Input::setContent, new ParseField("content"));
        PARSER.declareStringArray(Input::setIds, new ParseField("ids"));
    }

    public RestSamlAuthenticateAction(Settings settings, RestController controller,
                                      XPackLicenseState licenseState) {
        super(settings, licenseState);
        controller.registerHandler(POST, "/_xpack/security/saml/authenticate", this);
        controller.registerHandler(POST, "/_security/saml/authenticate", this);
    }

    @Override
    public String getName() {
        return "xpack_security_saml_authenticate_action";
    }

    @Override
    public RestChannelConsumer innerPrepareRequest(RestRequest request, NodeClient client) throws IOException {
        try (XContentParser parser = request.contentParser()) {
            final Input input = PARSER.parse(parser, null);
            logger.trace("SAML Authenticate: [{}...] [{}]", Strings.cleanTruncate(input.content, 128), input.ids);
            return channel -> {
                final byte[] bytes = decodeBase64(input.content);
                final SamlAuthenticateRequestBuilder requestBuilder = new SecurityClient(client).prepareSamlAuthenticate(bytes, input.ids);
                requestBuilder.execute(new RestBuilderListener<SamlAuthenticateResponse>(channel) {
                    @Override
                    public RestResponse buildResponse(SamlAuthenticateResponse response, XContentBuilder builder) throws Exception {
                        builder.startObject()
                                .field("username", response.getPrincipal())
                                .field("access_token", response.getTokenString())
                                .field("refresh_token", response.getRefreshToken())
                                .field("expires_in", response.getExpiresIn().seconds())
                                .endObject();
                        return new BytesRestResponse(RestStatus.OK, builder);
                    }
                });
            };
        }
    }

    private byte[] decodeBase64(String content) {
        content = content.replaceAll("\\s+", "");
        try {
            return Base64.getDecoder().decode(content);
        } catch (IllegalArgumentException e) {
            logger.info("Failed to decode base64 string [{}] - {}", content, e.toString());
            throw e;
        }
    }

    private static final Set<String> FILTERED_FIELDS = Collections.singleton("content");

    @Override
    public Set<String> getFilteredFields() {
        return FILTERED_FIELDS;
    }
}
