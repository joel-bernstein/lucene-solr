/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.solr.cloud;

import java.lang.invoke.MethodHandles;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.SolrResponse;
import org.apache.solr.client.solrj.embedded.JettySolrRunner;
import org.apache.solr.client.solrj.impl.CloudSolrClient;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.client.solrj.request.RequestWriter;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.client.solrj.response.SolrResponseBase;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.cloud.CollectionStatePredicate;
import org.apache.solr.common.cloud.CollectionStateWatcher;
import org.apache.solr.common.cloud.DocCollection;
import org.apache.solr.common.cloud.Replica;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.core.SolrCore;
import org.apache.solr.core.SolrEventListener;
import org.apache.solr.search.SolrIndexSearcher;
import org.apache.solr.servlet.SolrDispatchFilter;
import org.apache.solr.util.LogLevel;
import org.apache.solr.util.RefCounted;
import org.apache.solr.util.TestInjection;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.solr.common.params.CommonParams.JSON_MIME;

/**
 * Tests related to SOLR-6086
 */
@LogLevel("org.apache.solr.cloud.overseer.*=DEBUG,org.apache.solr.cloud.Overseer=DEBUG,org.apache.solr.cloud.ZkController=DEBUG")
public class TestCloudSearcherWarming extends SolrCloudTestCase {
  public static final AtomicReference<String> coreNodeNameRef = new AtomicReference<>(null),
      coreNameRef = new AtomicReference<>(null);
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
  private static final AtomicInteger sleepTime = new AtomicInteger(-1);

  @BeforeClass
  public static void setupCluster() throws Exception {
    useFactory("solr.StandardDirectoryFactory"); // necessary to find the index+tlog intact after restart
    configureCluster(1)
        .addConfig("conf", configset("cloud-minimal"))
        .configure();
  }

  @Before
  public void before() {
    coreNameRef.set(null);
    coreNodeNameRef.set(null);
    sleepTime.set(-1);

    try {
      CollectionAdminRequest.deleteCollection("testRepFactor1LeaderStartup").process(cluster.getSolrClient());
    } catch (Exception e) {
      // ignore
    }
    try {
      CollectionAdminRequest.deleteCollection("testPeersyncFailureReplicationSuccess").process(cluster.getSolrClient());
    } catch (Exception e) {
      // ignore
    }
  }

  @Test
  public void testRepFactor1LeaderStartup() throws Exception {
    CloudSolrClient solrClient = cluster.getSolrClient();

    String collectionName = "testRepFactor1LeaderStartup";
    CollectionAdminRequest.Create create = CollectionAdminRequest.createCollection(collectionName, 1, 1)
        .setCreateNodeSet(cluster.getJettySolrRunner(0).getNodeName());
    create.process(solrClient);

    waitForState("The collection should have 1 shard and 1 replica", collectionName, clusterShape(1, 1));

    solrClient.setDefaultCollection(collectionName);

    String addListenerCommand = "{" +
        "'add-listener' : {'name':'newSearcherListener','event':'newSearcher', 'class':'" + SleepingSolrEventListener.class.getName() + "'}" +
        "'add-listener' : {'name':'firstSearcherListener','event':'firstSearcher', 'class':'" + SleepingSolrEventListener.class.getName() + "'}" +
        "}";

    ConfigRequest request = new ConfigRequest(SolrRequest.METHOD.POST, "/config", addListenerCommand);
    solrClient.request(request);

    solrClient.add(new SolrInputDocument("id", "1"));
    solrClient.commit();

    AtomicInteger expectedDocs = new AtomicInteger(1);
    AtomicReference<String> failingCoreNodeName = new AtomicReference<>();
    CollectionStateWatcher stateWatcher = createActiveReplicaSearcherWatcher(expectedDocs, failingCoreNodeName);

    JettySolrRunner runner = cluster.getJettySolrRunner(0);
    cluster.stopJettySolrRunner(0);
    waitForState("", collectionName, clusterShape(1, 0));
    // restart
    sleepTime.set(10000);
    cluster.startJettySolrRunner(runner);
    cluster.getSolrClient().getZkStateReader().registerCollectionStateWatcher(collectionName, stateWatcher);
    waitForState("", collectionName, clusterShape(1, 1));
    assertNull("No replica should have been active without registering a searcher, found: " + failingCoreNodeName.get(), failingCoreNodeName.get());
    cluster.getSolrClient().getZkStateReader().removeCollectionStateWatcher(collectionName, stateWatcher);
  }

  public void testPeersyncFailureReplicationSuccess() throws Exception {
    CloudSolrClient solrClient = cluster.getSolrClient();

    String collectionName = "testPeersyncFailureReplicationSuccess";
    CollectionAdminRequest.Create create = CollectionAdminRequest.createCollection(collectionName, 1, 1)
        .setCreateNodeSet(cluster.getJettySolrRunner(0).getNodeName());
    create.process(solrClient);

    waitForState("The collection should have 1 shard and 1 replica", collectionName, clusterShape(1, 1));

    solrClient.setDefaultCollection(collectionName);

    String addListenerCommand = "{" +
        "'add-listener' : {'name':'newSearcherListener','event':'newSearcher', 'class':'" + SleepingSolrEventListener.class.getName() + "'}" +
        "'add-listener' : {'name':'firstSearcherListener','event':'firstSearcher', 'class':'" + SleepingSolrEventListener.class.getName() + "'}" +
        "}";

    ConfigRequest request = new ConfigRequest(SolrRequest.METHOD.POST, "/config", addListenerCommand);
    solrClient.request(request);

    solrClient.add(new SolrInputDocument("id", "1"));
    solrClient.commit();

    AtomicInteger expectedDocs = new AtomicInteger(1);
    AtomicReference<String> failingCoreNodeName = new AtomicReference<>();

    QueryResponse response = solrClient.query(new SolrQuery("*:*"));
    assertEquals(1, response.getResults().getNumFound());

    // reset
    coreNameRef.set(null);
    coreNodeNameRef.set(null);
    failingCoreNodeName.set(null);
    sleepTime.set(5000);

    CollectionStateWatcher stateWatcher = createActiveReplicaSearcherWatcher(expectedDocs, failingCoreNodeName);
    cluster.getSolrClient().getZkStateReader().registerCollectionStateWatcher(collectionName, stateWatcher);

    JettySolrRunner newNode = cluster.startJettySolrRunner();
    CollectionAdminRequest.addReplicaToShard(collectionName, "shard1")
        .setNode(newNode.getNodeName())
        .process(solrClient);

    waitForState("The collection should have 1 shard and 2 replica", collectionName, clusterShape(1, 2));
    assertNull("No replica should have been active without registering a searcher, found: " + failingCoreNodeName.get(), failingCoreNodeName.get());

    // stop the old node
    log.info("Stopping old node 1");
    AtomicReference<String> oldNodeName = new AtomicReference<>(cluster.getJettySolrRunner(0).getNodeName());
    JettySolrRunner oldNode = cluster.stopJettySolrRunner(0);
    // the newly created replica should become leader
    waitForState("The collection should have 1 shard and 1 replica", collectionName, clusterShape(1, 1));
    // the above call is not enough because we want to assert that the down'ed replica is not active
    // but clusterShape will also return true if replica is not live -- which we don't want
    CollectionStatePredicate collectionStatePredicate = (liveNodes, collectionState) -> {
      for (Replica r : collectionState.getReplicas()) {
        if (r.getNodeName().equals(oldNodeName.get())) {
          return r.getState() == Replica.State.DOWN;
        }
      }
      return false;
    };
    waitForState("", collectionName, collectionStatePredicate);
    assertNotNull(solrClient.getZkStateReader().getLeaderRetry(collectionName, "shard1"));

    // reset
    coreNameRef.set(null);
    coreNodeNameRef.set(null);
    failingCoreNodeName.set(null);
    sleepTime.set(5000);

    // inject wrong signature output
    TestInjection.wrongIndexFingerprint = "true:100";
    // now lets restart the old node
    log.info("Starting old node 1");
    cluster.startJettySolrRunner(oldNode);
    waitForState("", collectionName, clusterShape(1, 2));
    // invoke statewatcher explicitly to avoid race condition where the assert happens before the state watcher is invoked by ZkStateReader
    cluster.getSolrClient().getZkStateReader().registerCollectionStateWatcher(collectionName, stateWatcher);
    assertNull("No replica should have been active without registering a searcher, found: " + failingCoreNodeName.get(), failingCoreNodeName.get());

    oldNodeName.set(cluster.getJettySolrRunner(1).getNodeName());
    assertSame(oldNode, cluster.stopJettySolrRunner(1)); // old node is now at 1
    log.info("Stopping old node 2");
    waitForState("", collectionName, clusterShape(1, 1));
    waitForState("", collectionName, collectionStatePredicate);

    // reset
    coreNameRef.set(null);
    coreNodeNameRef.set(null);
    failingCoreNodeName.set(null);
    sleepTime.set(14000);  // has to be higher than the twice the recovery wait pause between attempts plus some margin

    // inject failure
    TestInjection.failIndexFingerprintRequests = "true:100";
    // now lets restart the old node again
    log.info("Starting old node 2");
    cluster.startJettySolrRunner(oldNode);
    waitForState("", collectionName, clusterShape(1, 2));
    // invoke statewatcher explicitly to avoid race condition where the assert happens before the state watcher is invoked by ZkStateReader
    cluster.getSolrClient().getZkStateReader().registerCollectionStateWatcher(collectionName, stateWatcher);
    assertNull("No replica should have been active without registering a searcher, found: " + failingCoreNodeName.get(), failingCoreNodeName.get());
    cluster.getSolrClient().getZkStateReader().removeCollectionStateWatcher(collectionName, stateWatcher);
  }

  private CollectionStateWatcher createActiveReplicaSearcherWatcher(AtomicInteger expectedDocs, AtomicReference<String> failingCoreNodeName) {
    return new CollectionStateWatcher() {
      @Override
      public boolean onStateChanged(Set<String> liveNodes, DocCollection collectionState) {
        try {
          String coreNodeName = coreNodeNameRef.get();
          String coreName = coreNameRef.get();
          if (coreNodeName == null || coreName == null) return false;
          Replica replica = collectionState.getReplica(coreNodeName);
          if (replica == null) return false;
          log.info("Collection state: {}", collectionState);
          if (replica.isActive(liveNodes)) {
            log.info("Active replica: {}", coreNodeName);
            for (int i = 0; i < cluster.getJettySolrRunners().size(); i++) {
              JettySolrRunner jettySolrRunner = cluster.getJettySolrRunner(i);
              log.info("Checking node: {}", jettySolrRunner.getNodeName());
              if (jettySolrRunner.getNodeName().equals(replica.getNodeName())) {
                SolrDispatchFilter solrDispatchFilter = jettySolrRunner.getSolrDispatchFilter();
                try (SolrCore core = solrDispatchFilter.getCores().getCore(coreName)) {
                  if (core.getSolrConfig().useColdSearcher) {
                    log.error("useColdSearcher is enabled! It should not be enabled for this test!");
                    assert false;
                    return false;
                  }
                  log.info("Found SolrCore: {}, id: {}", core.getName(), core);
                  RefCounted<SolrIndexSearcher> registeredSearcher = core.getRegisteredSearcher();
                  if (registeredSearcher != null) {
                    log.error("registered searcher not null, maxdocs = {}", registeredSearcher.get().maxDoc());
                    if (registeredSearcher.get().maxDoc() != expectedDocs.get()) {
                      failingCoreNodeName.set(coreNodeName);
                      registeredSearcher.decref();
                      return false;
                    } else {
                      registeredSearcher.decref();
                      return false;
                    }
                  } else {
                    log.error("registered searcher was null!");
                    RefCounted<SolrIndexSearcher> newestSearcher = core.getNewestSearcher(false);
                    if (newestSearcher != null) {
                      SolrIndexSearcher searcher = newestSearcher.get();
                      log.warn("newest searcher was: {}", searcher);
                      newestSearcher.decref();
                    } else {
                      log.error("newest searcher was also null!");
                    }
                    // no registered searcher but replica is active!
                    failingCoreNodeName.set(coreNodeName);
                  }
                }
              }
            }
          }
        } catch (Exception e) {
          log.error("Unexpected exception in state watcher", e);
        }
        return false;
      }
    };
  }

  public static class SleepingSolrEventListener implements SolrEventListener {
    @Override
    public void init(NamedList args) {
      new RuntimeException().printStackTrace();
      System.out.println(args);
    }

    @Override
    public void postCommit() {

    }

    @Override
    public void postSoftCommit() {

    }

    @Override
    public void newSearcher(SolrIndexSearcher newSearcher, SolrIndexSearcher currentSearcher) {
      if (sleepTime.get() > 0) {
        TestCloudSearcherWarming.coreNodeNameRef.set(newSearcher.getCore().getCoreDescriptor().getCloudDescriptor().getCoreNodeName());
        TestCloudSearcherWarming.coreNameRef.set(newSearcher.getCore().getName());
        log.info("Sleeping for {} on newSearcher: {}, currentSearcher: {} belonging to (newest) core: {}, id: {}", sleepTime.get(), newSearcher, currentSearcher, newSearcher.getCore().getName(), newSearcher.getCore());
        try {
          Thread.sleep(sleepTime.get());
        } catch (InterruptedException e) {
          e.printStackTrace();
        }
        log.info("Finished sleeping for {} on newSearcher: {}, currentSearcher: {} belonging to (newest) core: {}, id: {}", sleepTime.get(), newSearcher, currentSearcher, newSearcher.getCore().getName(), newSearcher.getCore());
      }
    }
  }

  public static class ConfigRequest extends SolrRequest {
    protected final String message;

    public ConfigRequest(METHOD m, String path, String message) {
      super(m, path);
      this.message = message;
    }

    @Override
    public SolrParams getParams() {
      return null;
    }

   /* @Override
    public Collection<ContentStream> getContentStreams() throws IOException {
      return message != null ? Collections.singletonList(new ContentStreamBase.StringStream(message)) : null;
    }*/

    @Override
    public RequestWriter.ContentWriter getContentWriter(String expectedType) {
      return message == null? null: new RequestWriter.StringPayloadContentWriter(message, JSON_MIME);
    }

    @Override
    protected SolrResponse createResponse(SolrClient client) {
      return new SolrResponseBase();
    }
  }
}
