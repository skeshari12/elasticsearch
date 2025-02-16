/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.index.engine;

import org.apache.lucene.index.DirectoryReader;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.admin.cluster.state.ClusterStateResponse;
import org.elasticsearch.action.admin.indices.stats.IndicesStatsResponse;
import org.elasticsearch.action.delete.DeleteResponse;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.SearchType;
import org.elasticsearch.action.support.IndicesOptions;
import org.elasticsearch.cluster.block.ClusterBlockException;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.routing.RecoverySource;
import org.elasticsearch.common.CheckedFunction;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.index.Index;
import org.elasticsearch.index.IndexModule;
import org.elasticsearch.index.IndexService;
import org.elasticsearch.index.query.MatchAllQueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.index.shard.IndexSearcherWrapper;
import org.elasticsearch.index.shard.IndexShard;
import org.elasticsearch.index.shard.IndexShardTestCase;
import org.elasticsearch.indices.IndicesService;
import org.elasticsearch.indices.recovery.RecoveryState;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.search.SearchService;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.internal.AliasFilter;
import org.elasticsearch.search.internal.ShardSearchLocalRequest;
import org.elasticsearch.test.ESSingleNodeTestCase;
import org.elasticsearch.xpack.core.XPackClient;
import org.elasticsearch.xpack.core.XPackPlugin;
import org.elasticsearch.xpack.core.action.TransportFreezeIndexAction;
import org.hamcrest.Matchers;
import org.junit.Before;

import java.io.IOException;
import java.util.Collection;
import java.util.EnumSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.elasticsearch.action.support.WriteRequest.RefreshPolicy.IMMEDIATE;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertAcked;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertHitCount;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

public class FrozenIndexTests extends ESSingleNodeTestCase {

    @Override
    protected Collection<Class<? extends Plugin>> getPlugins() {
        return pluginList(XPackPlugin.class, SearcherWrapperPlugin.class);
    }

    static final AtomicReference<CheckedFunction<DirectoryReader, DirectoryReader, IOException>> readerWrapper = new AtomicReference<>();

    @Before
    public void resetReaderWrapper() throws Exception {
        readerWrapper.set(null);
    }

    public static class SearcherWrapperPlugin extends Plugin {
        @Override
        public void onIndexModule(IndexModule indexModule) {
            super.onIndexModule(indexModule);
            if (randomBoolean()) {
                indexModule.setSearcherWrapper(indexService -> new IndexSearcherWrapper() {
                    @Override
                    protected DirectoryReader wrap(DirectoryReader reader) throws IOException {
                        final CheckedFunction<DirectoryReader, DirectoryReader, IOException> wrapper = readerWrapper.get();
                        return wrapper != null ? wrapper.apply(reader) : reader;
                    }
                });
            }
        }
    }


    public void testCloseFreezeAndOpen() throws ExecutionException, InterruptedException {
        createIndex("index", Settings.builder().put("index.number_of_shards", 2).build());
        client().prepareIndex("index", "_doc", "1").setSource("field", "value").setRefreshPolicy(IMMEDIATE).get();
        client().prepareIndex("index", "_doc", "2").setSource("field", "value").setRefreshPolicy(IMMEDIATE).get();
        client().prepareIndex("index", "_doc", "3").setSource("field", "value").setRefreshPolicy(IMMEDIATE).get();
        XPackClient xPackClient = new XPackClient(client());
        assertAcked(xPackClient.freeze(new TransportFreezeIndexAction.FreezeRequest("index")));
        expectThrows(ClusterBlockException.class, () -> client().prepareIndex("index", "_doc", "4").setSource("field", "value")
            .setRefreshPolicy(IMMEDIATE).get());
        IndicesService indexServices = getInstanceFromNode(IndicesService.class);
        Index index = resolveIndex("index");
        IndexService indexService = indexServices.indexServiceSafe(index);
        IndexShard shard = indexService.getShard(0);
        Engine engine = IndexShardTestCase.getEngine(shard);
        assertEquals(0, shard.refreshStats().getTotal());
        boolean useDFS = randomBoolean();
        assertHitCount(client().prepareSearch().setIndicesOptions(IndicesOptions.STRICT_EXPAND_OPEN_FORBID_CLOSED)
            .setSearchType(useDFS ? SearchType.DFS_QUERY_THEN_FETCH : SearchType.QUERY_THEN_FETCH).get(), 3);
        assertThat(engine, Matchers.instanceOf(FrozenEngine.class));
        assertEquals(useDFS ? 3 : 2, shard.refreshStats().getTotal());
        assertFalse(((FrozenEngine)engine).isReaderOpen());
        assertTrue(indexService.getIndexSettings().isSearchThrottled());
        try (Engine.Searcher searcher = shard.acquireSearcher("test")) {
            assertNotNull(FrozenEngine.unwrapLazyReader(searcher.getDirectoryReader()));
        }
        // now scroll
        SearchResponse searchResponse = client().prepareSearch().setIndicesOptions(IndicesOptions.STRICT_EXPAND_OPEN_FORBID_CLOSED)
            .setScroll(TimeValue.timeValueMinutes(1)).setSize(1).get();
        do {
            assertHitCount(searchResponse, 3);
            assertEquals(1, searchResponse.getHits().getHits().length);
            SearchService searchService = getInstanceFromNode(SearchService.class);
            assertThat(searchService.getActiveContexts(), Matchers.greaterThanOrEqualTo(1));
            for (int i = 0; i < 2; i++) {
                shard = indexService.getShard(i);
                engine = IndexShardTestCase.getEngine(shard);
                assertFalse(((FrozenEngine) engine).isReaderOpen());
            }
            searchResponse = client().prepareSearchScroll(searchResponse.getScrollId()).setScroll(TimeValue.timeValueMinutes(1)).get();
        } while (searchResponse.getHits().getHits().length > 0);
    }

    public void testSearchAndGetAPIsAreThrottled() throws InterruptedException, IOException, ExecutionException {
        XContentBuilder mapping = XContentFactory.jsonBuilder().startObject().startObject("_doc")
            .startObject("properties").startObject("field").field("type", "text").field("term_vector", "with_positions_offsets_payloads")
            .endObject().endObject()
            .endObject().endObject();
        createIndex("index", Settings.builder().put("index.number_of_shards", 2).build(), "_doc", mapping);
        for (int i = 0; i < 10; i++) {
            client().prepareIndex("index", "_doc", "" + i).setSource("field", "foo bar baz").get();
        }
        XPackClient xPackClient = new XPackClient(client());
        assertAcked(xPackClient.freeze(new TransportFreezeIndexAction.FreezeRequest("index")));
        int numRequests = randomIntBetween(20, 50);
        CountDownLatch latch = new CountDownLatch(numRequests);
        ActionListener listener = ActionListener.wrap(latch::countDown);
        int numRefreshes = 0;
        for (int i = 0; i < numRequests; i++) {
            numRefreshes++;
            switch (randomIntBetween(0, 3)) {
                case 0:
                    client().prepareGet("index", "_doc", "" + randomIntBetween(0, 9)).execute(listener);
                    break;
                case 1:
                    client().prepareSearch("index").setIndicesOptions(IndicesOptions.STRICT_EXPAND_OPEN_FORBID_CLOSED)
                        .setSearchType(SearchType.QUERY_THEN_FETCH)
                        .execute(listener);
                    // in total 4 refreshes 1x query & 1x fetch per shard (we have 2)
                    numRefreshes += 3;
                    break;
                case 2:
                    client().prepareTermVectors("index", "_doc", "" + randomIntBetween(0, 9)).execute(listener);
                    break;
                case 3:
                    client().prepareExplain("index", "_doc", "" + randomIntBetween(0, 9)).setQuery(new MatchAllQueryBuilder())
                        .execute(listener);
                    break;
                    default:
                        assert false;
            }
        }
        latch.await();
        IndicesStatsResponse index = client().admin().indices().prepareStats("index").clear().setRefresh(true).get();
        assertEquals(numRefreshes, index.getTotal().refresh.getTotal());
    }

    public void testFreezeAndUnfreeze() throws InterruptedException, ExecutionException {
        createIndex("index", Settings.builder().put("index.number_of_shards", 2).build());
        client().prepareIndex("index", "_doc", "1").setSource("field", "value").setRefreshPolicy(IMMEDIATE).get();
        client().prepareIndex("index", "_doc", "2").setSource("field", "value").setRefreshPolicy(IMMEDIATE).get();
        client().prepareIndex("index", "_doc", "3").setSource("field", "value").setRefreshPolicy(IMMEDIATE).get();
        if (randomBoolean()) {
            // sometimes close it
            assertAcked(client().admin().indices().prepareClose("index").get());
        }
        XPackClient xPackClient = new XPackClient(client());
        assertAcked(xPackClient.freeze(new TransportFreezeIndexAction.FreezeRequest("index")));
        {
            IndicesService indexServices = getInstanceFromNode(IndicesService.class);
            Index index = resolveIndex("index");
            IndexService indexService = indexServices.indexServiceSafe(index);
            assertTrue(indexService.getIndexSettings().isSearchThrottled());
            IndexShard shard = indexService.getShard(0);
            assertEquals(0, shard.refreshStats().getTotal());
        }
        assertAcked(xPackClient.freeze(new TransportFreezeIndexAction.FreezeRequest("index").setFreeze(false)));
        {
            IndicesService indexServices = getInstanceFromNode(IndicesService.class);
            Index index = resolveIndex("index");
            IndexService indexService = indexServices.indexServiceSafe(index);
            assertFalse(indexService.getIndexSettings().isSearchThrottled());
            IndexShard shard = indexService.getShard(0);
            Engine engine = IndexShardTestCase.getEngine(shard);
            assertThat(engine, Matchers.instanceOf(InternalEngine.class));
        }
        client().prepareIndex("index", "_doc", "4").setSource("field", "value").setRefreshPolicy(IMMEDIATE).get();
    }

    private void assertIndexFrozen(String idx) {
        IndicesService indexServices = getInstanceFromNode(IndicesService.class);
        Index index = resolveIndex(idx);
        IndexService indexService = indexServices.indexServiceSafe(index);
        assertTrue(indexService.getIndexSettings().isSearchThrottled());
        assertTrue(FrozenEngine.INDEX_FROZEN.get(indexService.getIndexSettings().getSettings()));
    }

    public void testDoubleFreeze() throws ExecutionException, InterruptedException {
        createIndex("test-idx", Settings.builder().put("index.number_of_shards", 2).build());
        XPackClient xPackClient = new XPackClient(client());
        assertAcked(xPackClient.freeze(new TransportFreezeIndexAction.FreezeRequest("test-idx")));
        ExecutionException executionException = expectThrows(ExecutionException.class,
            () -> xPackClient.freeze(new TransportFreezeIndexAction.FreezeRequest("test-idx")
                .indicesOptions(new IndicesOptions(EnumSet.noneOf(IndicesOptions.Option.class),
                EnumSet.of(IndicesOptions.WildcardStates.OPEN)))));
        assertEquals("no index found to freeze", executionException.getCause().getMessage());
    }

    public void testUnfreezeClosedIndices() throws ExecutionException, InterruptedException {
        createIndex("idx", Settings.builder().put("index.number_of_shards", 1).build());
        client().prepareIndex("idx", "_doc", "1").setSource("field", "value").setRefreshPolicy(IMMEDIATE).get();
        createIndex("idx-closed", Settings.builder().put("index.number_of_shards", 1).build());
        client().prepareIndex("idx-closed", "_doc", "1").setSource("field", "value").setRefreshPolicy(IMMEDIATE).get();
        XPackClient xPackClient = new XPackClient(client());
        assertAcked(xPackClient.freeze(new TransportFreezeIndexAction.FreezeRequest("idx")));
        assertAcked(client().admin().indices().prepareClose("idx-closed").get());
        assertAcked(xPackClient.freeze(new TransportFreezeIndexAction.FreezeRequest("idx*").setFreeze(false)
            .indicesOptions(IndicesOptions.strictExpand())));
        ClusterStateResponse stateResponse = client().admin().cluster().prepareState().get();
        assertEquals(IndexMetaData.State.CLOSE, stateResponse.getState().getMetaData().index("idx-closed").getState());
        assertEquals(IndexMetaData.State.OPEN, stateResponse.getState().getMetaData().index("idx").getState());
        assertHitCount(client().prepareSearch().get(), 1L);
    }

    public void testFreezePattern() throws ExecutionException, InterruptedException {
        createIndex("test-idx", Settings.builder().put("index.number_of_shards", 1).build());
        client().prepareIndex("test-idx", "_doc", "1").setSource("field", "value").setRefreshPolicy(IMMEDIATE).get();
        createIndex("test-idx-1", Settings.builder().put("index.number_of_shards", 1).build());
        client().prepareIndex("test-idx-1", "_doc", "1").setSource("field", "value").setRefreshPolicy(IMMEDIATE).get();
        XPackClient xPackClient = new XPackClient(client());
        assertAcked(xPackClient.freeze(new TransportFreezeIndexAction.FreezeRequest("test-idx")));
        assertIndexFrozen("test-idx");

        IndicesStatsResponse index = client().admin().indices().prepareStats("test-idx").clear().setRefresh(true).get();
        assertEquals(0, index.getTotal().refresh.getTotal());
        assertHitCount(client().prepareSearch("test-idx").setIndicesOptions(IndicesOptions.STRICT_EXPAND_OPEN_FORBID_CLOSED).get(), 1);
        index = client().admin().indices().prepareStats("test-idx").clear().setRefresh(true).get();
        assertEquals(1, index.getTotal().refresh.getTotal());

        assertAcked(xPackClient.freeze(new TransportFreezeIndexAction.FreezeRequest("test*")));
        assertIndexFrozen("test-idx");
        assertIndexFrozen("test-idx-1");
        index = client().admin().indices().prepareStats("test-idx").clear().setRefresh(true).get();
        assertEquals(1, index.getTotal().refresh.getTotal());
        index = client().admin().indices().prepareStats("test-idx-1").clear().setRefresh(true).get();
        assertEquals(0, index.getTotal().refresh.getTotal());
    }

    public void testCanMatch() throws ExecutionException, InterruptedException, IOException {
        if (randomBoolean()) {
            readerWrapper.set(reader -> {
                throw new AssertionError("can_match must not wrap the reader");
            });
        }
        createIndex("index");
        client().prepareIndex("index", "_doc", "1").setSource("field", "2010-01-05T02:00").setRefreshPolicy(IMMEDIATE).execute()
            .actionGet();
        client().prepareIndex("index", "_doc", "2").setSource("field", "2010-01-06T02:00").setRefreshPolicy(IMMEDIATE).execute()
            .actionGet();
        {
            IndicesService indexServices = getInstanceFromNode(IndicesService.class);
            Index index = resolveIndex("index");
            IndexService indexService = indexServices.indexServiceSafe(index);
            IndexShard shard = indexService.getShard(0);
            assertFalse(indexService.getIndexSettings().isSearchThrottled());
            SearchService searchService = getInstanceFromNode(SearchService.class);
            assertTrue(searchService.canMatch(new ShardSearchLocalRequest(shard.shardId(), 1, SearchType.QUERY_THEN_FETCH, null,
                Strings.EMPTY_ARRAY, false, new AliasFilter(null, Strings.EMPTY_ARRAY), 1f, true, null, null)));

            SearchSourceBuilder sourceBuilder = new SearchSourceBuilder();
            sourceBuilder.query(QueryBuilders.rangeQuery("field").gte("2010-01-03||+2d").lte("2010-01-04||+2d/d"));
            assertTrue(searchService.canMatch(new ShardSearchLocalRequest(shard.shardId(), 1, SearchType.QUERY_THEN_FETCH, sourceBuilder,
                Strings.EMPTY_ARRAY, false, new AliasFilter(null, Strings.EMPTY_ARRAY), 1f, true, null, null)));

            sourceBuilder = new SearchSourceBuilder();
            sourceBuilder.query(QueryBuilders.rangeQuery("field").gt("2010-01-06T02:00").lt("2010-01-07T02:00"));
            assertFalse(searchService.canMatch(new ShardSearchLocalRequest(shard.shardId(), 1, SearchType.QUERY_THEN_FETCH, sourceBuilder,
                Strings.EMPTY_ARRAY, false, new AliasFilter(null, Strings.EMPTY_ARRAY), 1f, true, null, null)));
        }


        XPackClient xPackClient = new XPackClient(client());
        assertAcked(xPackClient.freeze(new TransportFreezeIndexAction.FreezeRequest("index")));
        {

            IndicesService indexServices = getInstanceFromNode(IndicesService.class);
            Index index = resolveIndex("index");
            IndexService indexService = indexServices.indexServiceSafe(index);
            IndexShard shard = indexService.getShard(0);
            assertTrue(indexService.getIndexSettings().isSearchThrottled());
            SearchService searchService = getInstanceFromNode(SearchService.class);
            assertTrue(searchService.canMatch(new ShardSearchLocalRequest(shard.shardId(), 1, SearchType.QUERY_THEN_FETCH, null,
                Strings.EMPTY_ARRAY, false, new AliasFilter(null, Strings.EMPTY_ARRAY), 1f, true, null, null)));

            SearchSourceBuilder sourceBuilder = new SearchSourceBuilder();
            sourceBuilder.query(QueryBuilders.rangeQuery("field").gte("2010-01-03||+2d").lte("2010-01-04||+2d/d"));
            assertTrue(searchService.canMatch(new ShardSearchLocalRequest(shard.shardId(), 1, SearchType.QUERY_THEN_FETCH, sourceBuilder,
                Strings.EMPTY_ARRAY, false, new AliasFilter(null, Strings.EMPTY_ARRAY), 1f, true, null, null)));

            sourceBuilder = new SearchSourceBuilder();
            sourceBuilder.query(QueryBuilders.rangeQuery("field").gt("2010-01-06T02:00").lt("2010-01-07T02:00"));
            assertFalse(searchService.canMatch(new ShardSearchLocalRequest(shard.shardId(), 1, SearchType.QUERY_THEN_FETCH, sourceBuilder,
                Strings.EMPTY_ARRAY, false, new AliasFilter(null, Strings.EMPTY_ARRAY), 1f, true, null, null)));

            IndicesStatsResponse response = client().admin().indices().prepareStats("index").clear().setRefresh(true).get();
            assertEquals(0, response.getTotal().refresh.getTotal()); // never opened a reader
        }
    }

    public void testWriteToFrozenIndex() throws ExecutionException, InterruptedException {
        createIndex("idx", Settings.builder().put("index.number_of_shards", 1).build());
        client().prepareIndex("idx", "_doc", "1").setSource("field", "value").setRefreshPolicy(IMMEDIATE).get();
        XPackClient xPackClient = new XPackClient(client());
        assertAcked(xPackClient.freeze(new TransportFreezeIndexAction.FreezeRequest("idx")));
        assertIndexFrozen("idx");
        expectThrows(ClusterBlockException.class, () ->
        client().prepareIndex("idx", "_doc", "2").setSource("field", "value").setRefreshPolicy(IMMEDIATE).get());
    }

    public void testIgnoreUnavailable() throws ExecutionException, InterruptedException {
        createIndex("idx", Settings.builder().put("index.number_of_shards", 1).build());
        createIndex("idx-close", Settings.builder().put("index.number_of_shards", 1).build());
        assertAcked(client().admin().indices().prepareClose("idx-close"));
        XPackClient xPackClient = new XPackClient(client());
        assertAcked(xPackClient.freeze(new TransportFreezeIndexAction.FreezeRequest("idx*", "not_available")
            .indicesOptions(IndicesOptions.fromParameters(null, "true", null, null, IndicesOptions.strictExpandOpen()))));
        assertIndexFrozen("idx");
        assertEquals(IndexMetaData.State.CLOSE,
            client().admin().cluster().prepareState().get().getState().metaData().index("idx-close").getState());
    }

    public void testUnfreezeClosedIndex() throws ExecutionException, InterruptedException {
        createIndex("idx", Settings.builder().put("index.number_of_shards", 1).build());
        XPackClient xPackClient = new XPackClient(client());
        assertAcked(xPackClient.freeze(new TransportFreezeIndexAction.FreezeRequest("idx")));
        assertAcked(client().admin().indices().prepareClose("idx"));
        assertEquals(IndexMetaData.State.CLOSE,
            client().admin().cluster().prepareState().get().getState().metaData().index("idx").getState());
        expectThrows(ExecutionException.class,
            () -> xPackClient.freeze(new TransportFreezeIndexAction.FreezeRequest("id*").setFreeze(false)
                .indicesOptions(new IndicesOptions(EnumSet.noneOf(IndicesOptions.Option.class),
                    EnumSet.of(IndicesOptions.WildcardStates.OPEN)))));
        // we don't resolve to closed indices
        assertAcked(xPackClient.freeze(new TransportFreezeIndexAction.FreezeRequest("idx").setFreeze(false)));
        assertEquals(IndexMetaData.State.OPEN,
            client().admin().cluster().prepareState().get().getState().metaData().index("idx").getState());
    }

    public void testFreezeIndexIncreasesIndexSettingsVersion() throws ExecutionException, InterruptedException {
        final String index = "test";
        createIndex(index, Settings.builder().put("index.number_of_shards", 1).put("index.number_of_replicas", 0).build());
        client().prepareIndex(index, "_doc").setSource("field", "value").execute().actionGet();

        final long settingsVersion = client().admin().cluster().prepareState().get()
            .getState().metaData().index(index).getSettingsVersion();

        XPackClient xPackClient = new XPackClient(client());
        assertAcked(xPackClient.freeze(new TransportFreezeIndexAction.FreezeRequest(index)));
        assertIndexFrozen(index);
        assertThat(client().admin().cluster().prepareState().get().getState().metaData().index(index).getSettingsVersion(),
            equalTo(settingsVersion + 1));
    }

    public void testFreezeEmptyIndexWithTranslogOps() throws Exception {
        final String indexName = "empty";
        createIndex(indexName, Settings.builder()
            .put("index.number_of_shards", 1)
            .put("index.number_of_replicas", 0)
            .put("index.refresh_interval", TimeValue.MINUS_ONE)
            .build());

        final long nbNoOps = randomIntBetween(1, 10);
        for (long i = 0; i < nbNoOps; i++) {
            final DeleteResponse deleteResponse = client().prepareDelete(indexName, "_doc", Long.toString(i)).get();
            assertThat(deleteResponse.status(), is(RestStatus.NOT_FOUND));
        }

        final IndicesService indicesService = getInstanceFromNode(IndicesService.class);
        assertBusy(() -> {
            final Index index = client().admin().cluster().prepareState().get().getState().metaData().index(indexName).getIndex();
            final IndexService indexService = indicesService.indexService(index);
            assertThat(indexService.hasShard(0), is(true));
            assertThat(indexService.getShard(0).getGlobalCheckpoint(), greaterThanOrEqualTo(nbNoOps - 1L));
        });

        assertAcked(new XPackClient(client()).freeze(new TransportFreezeIndexAction.FreezeRequest(indexName)));
        assertIndexFrozen(indexName);
    }

    public void testRecoveryState() throws ExecutionException, InterruptedException {
        final String indexName = "index_recovery_state";
        createIndex(indexName, Settings.builder()
            .put("index.number_of_replicas", 0)
            .build());

        final long nbDocs = randomIntBetween(0, 50);
        for (long i = 0; i < nbDocs; i++) {
            final IndexResponse indexResponse = client().prepareIndex(indexName, "_doc", Long.toString(i)).setSource("field", i).get();
            assertThat(indexResponse.status(), is(RestStatus.CREATED));
        }

        assertAcked(new XPackClient(client()).freeze(new TransportFreezeIndexAction.FreezeRequest(indexName)));
        assertIndexFrozen(indexName);

        final IndexMetaData indexMetaData = client().admin().cluster().prepareState().get().getState().metaData().index(indexName);
        final IndexService indexService = getInstanceFromNode(IndicesService.class).indexService(indexMetaData.getIndex());
        for (int i = 0; i < indexMetaData.getNumberOfShards(); i++) {
            final IndexShard indexShard = indexService.getShardOrNull(i);
            assertThat("Shard [" + i + "] is missing for index " + indexMetaData.getIndex(), indexShard, notNullValue());
            final RecoveryState recoveryState = indexShard.recoveryState();
            assertThat(recoveryState.getRecoverySource(), is(RecoverySource.ExistingStoreRecoverySource.INSTANCE));
            assertThat(recoveryState.getStage(), is(RecoveryState.Stage.DONE));
            assertThat(recoveryState.getTargetNode(), notNullValue());
            assertThat(recoveryState.getIndex().totalFileCount(), greaterThan(0));
            assertThat(recoveryState.getIndex().reusedFileCount(), greaterThan(0));
            assertThat(recoveryState.getTranslog().recoveredOperations(), equalTo(0));
            assertThat(recoveryState.getTranslog().totalOperations(), equalTo(0));
            assertThat(recoveryState.getTranslog().recoveredPercent(), equalTo(100.0f));
        }
    }
}
