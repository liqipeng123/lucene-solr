package org.apache.solr.schema;
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

import org.apache.solr.client.solrj.SolrServer;
import org.apache.solr.client.solrj.impl.HttpSolrServer;
import org.apache.solr.cloud.AbstractFullDistribZkTestBase;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrException.ErrorCode;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.util.BaseTestHarness;
import org.apache.solr.util.RESTfulServerProvider;
import org.apache.solr.util.RestTestHarness;
import org.eclipse.jetty.servlet.ServletHolder;
import org.restlet.ext.servlet.ServerServlet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.solr.update.DirectUpdateHandler2;

import org.junit.BeforeClass;
import org.junit.Before;

import java.io.IOException;

import java.lang.Math;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Tests a schemaless collection configuration with SolrCloud
 */
public class TestCloudManagedSchemaless extends AbstractFullDistribZkTestBase {
  private static final Logger log = LoggerFactory.getLogger(TestCloudManagedSchemaConcurrent.class);
  private static final String SUCCESS_XPATH = "/response/lst[@name='responseHeader']/int[@name='status'][.='0']";

  @Before
  @Override
  public void setUp() throws Exception {

    super.setUp();

    System.setProperty("numShards", Integer.toString(sliceCount));
    System.setProperty("solr.xml.persist", "true");
  }

  public TestCloudManagedSchemaless() {
    schemaString = "schema-add-schema-fields-update-processor.xml";
    fixShardCount = true;

    sliceCount = 4;
    shardCount = 8;
  }

  @BeforeClass
  public static void initSysProperties() {
    System.setProperty("managed.schema.mutable", "true");
    System.setProperty("enable.update.log", "true");
  }

  @Override
  protected String getCloudSolrConfig() {
    return "solrconfig-schemaless.xml";
  }

  @Override
  public SortedMap<ServletHolder,String> getExtraServlets() {
    final SortedMap<ServletHolder,String> extraServlets = new TreeMap<ServletHolder, String>();
    final ServletHolder solrRestApi = new ServletHolder("SolrSchemaRestApi", ServerServlet.class);
    solrRestApi.setInitParameter("org.restlet.application", "org.apache.solr.rest.SolrSchemaRestApi");
    extraServlets.put(solrRestApi, "/schema/*");  // '/schema/*' matches '/schema', '/schema/', and '/schema/whatever...'
    return extraServlets;
  }

  private List<RestTestHarness> restTestHarnesses = new ArrayList<RestTestHarness>();

  private void setupHarnesses() {
    for (final SolrServer client : clients) {
      RestTestHarness harness = new RestTestHarness(new RESTfulServerProvider() {
        @Override
        public String getBaseURL() {
          return ((HttpSolrServer)client).getBaseURL();
        }
      });
      restTestHarnesses.add(harness);
    }
  }

  private String[] getExpectedFieldResponses(int numberOfDocs) {
    String[] expectedAddFields = new String[1 + numberOfDocs];
    expectedAddFields[0] = SUCCESS_XPATH;

    for (int i = 0; i < numberOfDocs; ++i) {
      String newFieldName = "newTestFieldInt" + i;
      expectedAddFields[1 + i] =
        "/response/arr[@name='fields']/lst/str[@name='name'][.='" + newFieldName + "']";
    }
    return expectedAddFields;
  }

  @Override
  public void doTest() throws Exception {
    // First, add a bunch of documents in a single update with the same new field.
    // This tests that the replicas properly handle schema additions.

    int slices =  getCommonCloudSolrServer().getZkStateReader().getClusterState()
      .getActiveSlices("collection1").size();
    int trials = 50;
    // generate enough docs so that we can expect at least a doc per slice
    int numDocsPerTrial = (int)(slices * (Math.log(slices) + 1));
    HttpSolrServer ss = (HttpSolrServer)clients.get(0);
    int docNumber = 0;
    for (int i = 0; i < trials; ++i) {
      List<SolrInputDocument> docs = new ArrayList<SolrInputDocument>();
      for (int j =0; j < numDocsPerTrial; ++j) {
        SolrInputDocument doc = new SolrInputDocument();
        doc.addField("id", Long.toHexString(Double.doubleToLongBits(random().nextDouble())));
        doc.addField("newTestFieldInt" + docNumber++, "123");
        doc.addField("constantField", "3.14159");
        docs.add(doc);
      }

      ss.add(docs);
    }
    ss.commit();

    String [] expectedFields = getExpectedFieldResponses(docNumber);
    // Check that all the fields were added
    for (RestTestHarness client : restTestHarnesses) {
      String request = "/schema/fields?wt=xml";
      String response = client.query(request);
      String result = BaseTestHarness.validateXPath(response, expectedFields);
      if (result != null) {
        String msg = "QUERY FAILED: xpath=" + result + "  request=" + request + "  response=" + response;
        log.error(msg);
        fail(msg);
      }
    }

    // Now, let's ensure that writing the same field with two different types fails
    int failTrials = 50;
    for (int i = 0; i < failTrials; ++i) {
      List<SolrInputDocument> docs = null;

      SolrInputDocument intDoc = new SolrInputDocument();
      intDoc.addField("id", Long.toHexString(Double.doubleToLongBits(random().nextDouble())));
      intDoc.addField("longOrDateField" + i, "123");

      SolrInputDocument dateDoc = new SolrInputDocument();
      dateDoc.addField("id", Long.toHexString(Double.doubleToLongBits(random().nextDouble())));
      dateDoc.addField("longOrDateField" + i, "1995-12-31T23:59:59Z");

      // randomize the order of the docs
      if (random().nextBoolean()) {
        docs = Arrays.asList(intDoc, dateDoc);
      } else {
        docs = Arrays.asList(dateDoc, intDoc);
      }

      try {
        ss.add(docs);
        ss.commit();
        fail("Expected Bad Request Exception");
      } catch (SolrException se) {
        assertEquals(ErrorCode.BAD_REQUEST, ErrorCode.getErrorCode(se.code()));
      }
    }
  }

  @Override
  public void tearDown() throws Exception {
    super.tearDown();
    System.clearProperty("numShards");
    System.clearProperty("zkHost");
    System.clearProperty("solr.xml.persist");

    // insurance
    DirectUpdateHandler2.commitOnClose = true;
  }
}
