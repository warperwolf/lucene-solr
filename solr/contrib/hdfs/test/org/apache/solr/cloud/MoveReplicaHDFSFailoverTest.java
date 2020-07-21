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

import java.io.IOException;

import com.carrotsearch.randomizedtesting.annotations.Nightly;
import com.carrotsearch.randomizedtesting.annotations.ThreadLeakFilters;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.apache.lucene.util.QuickPatchThreadsFilter;
import org.apache.solr.SolrIgnoredThreadsFilter;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.client.solrj.response.CollectionAdminResponse;
import org.apache.solr.cloud.hdfs.HdfsTestUtil;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.cloud.ClusterStateUtil;
import org.apache.solr.common.cloud.DocCollection;
import org.apache.solr.common.cloud.Replica;
import org.apache.solr.common.cloud.ZkConfigManager;
import org.apache.solr.common.cloud.ZkStateReader;
import org.apache.solr.util.BadHdfsThreadsFilter;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

@ThreadLeakFilters(defaultFilters = true, filters = {
    SolrIgnoredThreadsFilter.class,
    QuickPatchThreadsFilter.class,
    BadHdfsThreadsFilter.class // hdfs currently leaks thread(s)
})
@Nightly // test is too long for non nightly
public class MoveReplicaHDFSFailoverTest extends SolrCloudTestCase {
  private static MiniDFSCluster dfsCluster;

  @BeforeClass
  public static void setupClass() throws Exception {
    SolrCloudTestCase.configureCluster(2)
        .addConfig("conf1", SolrTestCaseJ4.TEST_PATH().resolve("configsets").resolve("cloud-dynamic").resolve("conf"))
        .configure();

    dfsCluster = HdfsTestUtil.setupClass(LuceneTestCase.createTempDir().toFile().getAbsolutePath());

    ZkConfigManager configManager = new ZkConfigManager(SolrCloudTestCase.zkClient());
    configManager.uploadConfigDir(SolrTestCaseJ4.configset("cloud-hdfs"), "conf1");
  }

  @AfterClass
  public static void teardownClass() throws Exception {
    try {
      SolrCloudTestCase.shutdownCluster();
    } finally {
      try {
        HdfsTestUtil.teardownClass(dfsCluster);
      } finally {
        dfsCluster = null;
      }
    }
  }

  @Test
  public void testDataDirAndUlogAreMaintained() throws Exception {
    String coll = "movereplicatest_coll2";
    CollectionAdminRequest.createCollection(coll, "conf1", 1, 1)
        .setCreateNodeSet("")
        .process(SolrCloudTestCase.cluster.getSolrClient());
    String hdfsUri = HdfsTestUtil.getURI(dfsCluster);
    String dataDir = hdfsUri + "/dummyFolder/dataDir";
    String ulogDir = hdfsUri + "/dummyFolder2/ulogDir";
    CollectionAdminResponse res = CollectionAdminRequest
        .addReplicaToShard(coll, "shard1")
        .setDataDir(dataDir)
        .setUlogDir(ulogDir)
        .setNode(SolrCloudTestCase.cluster.getJettySolrRunner(0).getNodeName())
        .process(SolrCloudTestCase.cluster.getSolrClient());

    ulogDir += "/tlog";
    ZkStateReader zkStateReader = SolrCloudTestCase.cluster.getSolrClient().getZkStateReader();
    Assert.assertTrue(ClusterStateUtil.waitForAllActiveAndLiveReplicas(zkStateReader, 120000));

    DocCollection docCollection = zkStateReader.getClusterState().getCollection(coll);
    Replica replica = docCollection.getReplicas().iterator().next();
    Assert.assertTrue(replica.getStr("ulogDir"), replica.getStr("ulogDir").equals(ulogDir) || replica.getStr("ulogDir").equals(ulogDir+'/'));
    Assert.assertTrue(replica.getStr("dataDir"),replica.getStr("dataDir").equals(dataDir) || replica.getStr("dataDir").equals(dataDir+'/'));

    new CollectionAdminRequest.MoveReplica(coll, replica.getName(), SolrCloudTestCase.cluster.getJettySolrRunner(1).getNodeName())
        .process(SolrCloudTestCase.cluster.getSolrClient());
    Assert.assertTrue(ClusterStateUtil.waitForAllActiveAndLiveReplicas(zkStateReader, 120000));
    docCollection = zkStateReader.getClusterState().getCollection(coll);
    Assert.assertEquals(1, docCollection.getSlice("shard1").getReplicas().size());
    Replica newReplica = docCollection.getReplicas().iterator().next();
    Assert.assertEquals(newReplica.getNodeName(), SolrCloudTestCase.cluster.getJettySolrRunner(1).getNodeName());
    Assert.assertTrue(newReplica.getStr("ulogDir"), newReplica.getStr("ulogDir").equals(ulogDir) || newReplica.getStr("ulogDir").equals(ulogDir+'/'));
    Assert.assertTrue(newReplica.getStr("dataDir"),newReplica.getStr("dataDir").equals(dataDir) || newReplica.getStr("dataDir").equals(dataDir+'/'));

    Assert.assertEquals(replica.getName(), newReplica.getName());
    Assert.assertEquals(replica.getCoreName(), newReplica.getCoreName());
    Assert.assertFalse(replica.getNodeName().equals(newReplica.getNodeName()));
    final int numDocs = 100;
    addDocs(coll, numDocs);  // indexed but not committed

    SolrCloudTestCase.cluster.getJettySolrRunner(1).stop();
    Thread.sleep(5000);
    new CollectionAdminRequest.MoveReplica(coll, newReplica.getName(), SolrCloudTestCase.cluster.getJettySolrRunner(0).getNodeName())
        .process(SolrCloudTestCase.cluster.getSolrClient());
    Assert.assertTrue(ClusterStateUtil.waitForAllActiveAndLiveReplicas(zkStateReader, 120000));

    // assert that the old core will be removed on startup
    SolrCloudTestCase.cluster.getJettySolrRunner(1).start();
    Assert.assertTrue(ClusterStateUtil.waitForAllActiveAndLiveReplicas(zkStateReader, 120000));
    docCollection = zkStateReader.getClusterState().getCollection(coll);
    Assert.assertEquals(1, docCollection.getReplicas().size());
    newReplica = docCollection.getReplicas().iterator().next();
    Assert.assertEquals(newReplica.getNodeName(), SolrCloudTestCase.cluster.getJettySolrRunner(0).getNodeName());
    Assert.assertTrue(newReplica.getStr("ulogDir"), newReplica.getStr("ulogDir").equals(ulogDir) || newReplica.getStr("ulogDir").equals(ulogDir+'/'));
    Assert.assertTrue(newReplica.getStr("dataDir"),newReplica.getStr("dataDir").equals(dataDir) || newReplica.getStr("dataDir").equals(dataDir+'/'));

    Assert.assertEquals(0, SolrCloudTestCase.cluster.getJettySolrRunner(1).getCoreContainer().getCores().size());

    SolrCloudTestCase.cluster.getSolrClient().commit(coll);
    Assert.assertEquals(numDocs, SolrCloudTestCase.cluster.getSolrClient().query(coll, new SolrQuery("*:*")).getResults().getNumFound());
    CollectionAdminRequest.deleteCollection(coll).process(SolrCloudTestCase.cluster.getSolrClient());
  }

  @Test
  public void testOldReplicaIsDeleted() throws Exception {
    String coll = "movereplicatest_coll3";
    CollectionAdminRequest.createCollection(coll, "conf1", 1, 1)
        .setCreateNodeSet(SolrCloudTestCase.cluster.getJettySolrRunner(0).getNodeName())
        .process(SolrCloudTestCase.cluster.getSolrClient());
    addDocs(coll, 2);
    Replica replica = SolrCloudTestCase.getCollectionState(coll).getReplicas().iterator().next();

    SolrCloudTestCase.cluster.getJettySolrRunners().get(0).stop();
    Assert.assertTrue(ClusterStateUtil.waitForAllReplicasNotLive(SolrCloudTestCase.cluster.getSolrClient().getZkStateReader(), 20000));

    // move replica from node0 -> node1
    new CollectionAdminRequest.MoveReplica(coll, replica.getName(), SolrCloudTestCase.cluster.getJettySolrRunner(1).getNodeName())
        .process(SolrCloudTestCase.cluster.getSolrClient());
    Assert.assertTrue(ClusterStateUtil.waitForAllActiveAndLiveReplicas(SolrCloudTestCase.cluster.getSolrClient().getZkStateReader(), 20000));

    SolrCloudTestCase.cluster.getJettySolrRunners().get(1).stop();
    Assert.assertTrue(ClusterStateUtil.waitForAllReplicasNotLive(SolrCloudTestCase.cluster.getSolrClient().getZkStateReader(), 20000));

    // node0 will delete it replica because of CloudUtil.checkSharedFSFailoverReplaced()
    SolrCloudTestCase.cluster.getJettySolrRunners().get(0).start();
    Thread.sleep(5000);
    Assert.assertTrue(ClusterStateUtil.waitForAllReplicasNotLive(SolrCloudTestCase.cluster.getSolrClient().getZkStateReader(), 20000));

    SolrCloudTestCase.cluster.getJettySolrRunners().get(1).start();
    Assert.assertTrue(ClusterStateUtil.waitForAllActiveAndLiveReplicas(SolrCloudTestCase.cluster.getSolrClient().getZkStateReader(), 20000));

    Assert.assertEquals(1, SolrCloudTestCase.getCollectionState(coll).getReplicas().size());
    Assert.assertEquals(2, SolrCloudTestCase.cluster.getSolrClient().query(coll, new SolrQuery("*:*")).getResults().getNumFound());
    CollectionAdminRequest.deleteCollection(coll).process(SolrCloudTestCase.cluster.getSolrClient());
  }

  @Test
  public void testOldReplicaIsDeletedInRaceCondition() throws Exception {
    String coll = "movereplicatest_coll4";
    CollectionAdminRequest.createCollection(coll, "conf1", 1, 1)
        .setCreateNodeSet(SolrCloudTestCase.cluster.getJettySolrRunner(0).getNodeName())
        .process(SolrCloudTestCase.cluster.getSolrClient());
    addDocs(coll, 100);
    Replica replica = SolrCloudTestCase.getCollectionState(coll).getReplicas().iterator().next();

    SolrCloudTestCase.cluster.getJettySolrRunners().get(0).stop();
    Assert.assertTrue(ClusterStateUtil.waitForAllReplicasNotLive(SolrCloudTestCase.cluster.getSolrClient().getZkStateReader(), 20000));

    // move replica from node0 -> node1
    new CollectionAdminRequest.MoveReplica(coll, replica.getName(), SolrCloudTestCase.cluster.getJettySolrRunner(1).getNodeName())
        .process(SolrCloudTestCase.cluster.getSolrClient());
    Assert.assertTrue(ClusterStateUtil.waitForAllActiveAndLiveReplicas(SolrCloudTestCase.cluster.getSolrClient().getZkStateReader(), 20000));

    SolrCloudTestCase.cluster.getJettySolrRunners().get(1).stop();
    Assert.assertTrue(ClusterStateUtil.waitForAllReplicasNotLive(SolrCloudTestCase.cluster.getSolrClient().getZkStateReader(), 20000));

    SolrCloudTestCase.cluster.getJettySolrRunners().get(1).start();
    // node0 will delete it replica because of CloudUtil.checkSharedFSFailoverReplaced()
    SolrCloudTestCase.cluster.getJettySolrRunners().get(0).start();
    Thread.sleep(5000);
    Assert.assertTrue(ClusterStateUtil.waitForAllActiveAndLiveReplicas(SolrCloudTestCase.cluster.getSolrClient().getZkStateReader(), 20000));

    Assert.assertEquals(1, SolrCloudTestCase.getCollectionState(coll).getReplicas().size());
    Assert.assertEquals(100, SolrCloudTestCase.cluster.getSolrClient().query(coll, new SolrQuery("*:*")).getResults().getNumFound());
    CollectionAdminRequest.deleteCollection(coll).process(SolrCloudTestCase.cluster.getSolrClient());
  }

  private void addDocs(String collection, int numDocs) throws SolrServerException, IOException {
    SolrClient solrClient = SolrCloudTestCase.cluster.getSolrClient();
    for (int docId = 1; docId <= numDocs; docId++) {
      SolrInputDocument doc = new SolrInputDocument();
      doc.addField("id", docId);
      solrClient.add(collection, doc);
    }
  }
}
