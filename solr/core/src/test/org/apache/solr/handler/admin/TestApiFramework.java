package org.apache.solr.handler.admin;

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

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.solr.SolrTestCaseJ4;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.common.params.MapSolrParams;
import org.apache.solr.common.util.Predicate;
import org.apache.solr.common.util.StrUtils;
import org.apache.solr.common.util.Utils;
import org.apache.solr.core.CoreContainer;
import org.apache.solr.core.PluginBag;
import org.apache.solr.handler.PingRequestHandler;
import org.apache.solr.handler.SchemaHandler;
import org.apache.solr.handler.SolrConfigHandler;
import org.apache.solr.request.LocalSolrQueryRequest;
import org.apache.solr.request.SolrRequestHandler;
import org.apache.solr.response.SolrQueryResponse;
import org.apache.solr.api.Api;
import org.apache.solr.api.V2HttpCall;
import org.apache.solr.util.CommandOperation;

import static org.apache.solr.client.solrj.SolrRequest.METHOD.GET;
import static org.apache.solr.client.solrj.SolrRequest.METHOD.POST;
import static org.apache.solr.common.params.CommonParams.COLLECTIONS_HANDLER_PATH;
import static org.apache.solr.common.params.CommonParams.CORES_HANDLER_PATH;
import static org.apache.solr.common.util.Map2.NOT_NULL;

public class TestApiFramework extends SolrTestCaseJ4 {

  public void testFramework() {
    Map<String, Object[]> calls = new HashMap<>();
    Map<String, Object> out = new HashMap<>();
    CoreContainer mockCC = TestCoreAdminApis.getCoreContainerMock(calls, out);
    PluginBag<SolrRequestHandler> containerHandlers = new PluginBag<>(SolrRequestHandler.class, null, false);
    containerHandlers.put(COLLECTIONS_HANDLER_PATH, new TestCollectionAPIs.MockCollectionsHandler(), true);
    containerHandlers.put(CORES_HANDLER_PATH, new CoreAdminHandler(mockCC), true);
    out.put("getRequestHandlers", containerHandlers);

    PluginBag<SolrRequestHandler> coreHandlers = new PluginBag<>(SolrRequestHandler.class, null, false);
    coreHandlers.put("/schema", new SchemaHandler(), true);
    coreHandlers.put("/config", new SolrConfigHandler(), true);
    coreHandlers.put("/admin/ping", new PingRequestHandler(), true);

    Map<String, String> parts = new HashMap<>();
    String fullPath = "/collections/hello/shards";
    Api api = V2HttpCall.getApiInfo(containerHandlers, fullPath, "POST",
        mockCC, "collections", fullPath, parts);
    assertNotNull(api);
    assertConditions(api.getSpec(), Utils.makeMap(
        "/methods[0]", "POST",
        "/commands/create", NOT_NULL));
    assertEquals("hello", parts.get("collection"));



    parts = new HashMap<>();
    api = V2HttpCall.getApiInfo(containerHandlers, "/collections/hello/shards/shard1", "POST",
        mockCC, "collections", null, parts);
    assertConditions(api.getSpec(), Utils.makeMap(
        "/methods[0]", "POST",
        "/commands/split", NOT_NULL,
        "/commands/add-replica", NOT_NULL,
        "/commands/force-leader", NOT_NULL
    ));
    assertEquals("hello", parts.get("collection"));
    assertEquals("shard1", parts.get("shard"));


    parts = new HashMap<>();
    api = V2HttpCall.getApiInfo(containerHandlers, "/collections/hello/shards/shard1/replica1", "POST",
        mockCC, "collections", null, parts);
    assertConditions(api.getSpec(), Utils.makeMap(
        "/methods[0]", "POST",
        "/commands/set", NOT_NULL
    ));
    assertEquals("hello", parts.get("collection"));
    assertEquals("shard1", parts.get("shard"));
    assertEquals("replica1", parts.get("replica"));

    api = V2HttpCall.getApiInfo(containerHandlers, "/collections/hello/shards/shard1/replica1", "DELETE",
        mockCC, "collections", null, parts);
    assertConditions(api.getSpec(), Utils.makeMap(
        "/methods[0]", "DELETE",
        "/url/params/onlyIfDown/type", "boolean"
    ));
    assertEquals("hello", parts.get("collection"));
    assertEquals("shard1", parts.get("shard"));
    assertEquals("replica1", parts.get("replica"));

    SolrQueryResponse rsp = invoke(containerHandlers, "/collections/_introspect", GET, mockCC);

    assertConditions(rsp.getValues().asMap(2), Utils.makeMap(
        "/spec[0]/methods[0]", "POST",
        "/spec[0]/methods[1]", "GET"));

    rsp = invoke(coreHandlers, "/collections/hello/schema/_introspect", GET, mockCC);
    assertConditions(rsp.getValues().asMap(2), Utils.makeMap(
        "/spec[0]/methods[0]", "POST",
        "/spec[0]/commands", NOT_NULL,
        "/spec[1]/methods[0]", "GET"));

    rsp = invoke(coreHandlers, "/collections/hello", GET, mockCC);
    assertConditions(rsp.getValues().asMap(2), Utils.makeMap(
        "/availableSubPaths", NOT_NULL,
        "availableSubPaths /collections/hello/config/jmx", NOT_NULL,
        "availableSubPaths /collections/hello/schema", NOT_NULL,
        "availableSubPaths /collections/hello/shards", NOT_NULL,
        "availableSubPaths /collections/hello/shards/{shard}", NOT_NULL,
        "availableSubPaths /collections/hello/shards/{shard}/{replica}", NOT_NULL
    ));

  }

  private SolrQueryResponse invoke(PluginBag<SolrRequestHandler> reqHandlers, String path, SolrRequest.METHOD method,
                                   CoreContainer mockCC) {
    HashMap<String, String> parts = new HashMap<>();
    boolean containerHandlerLookup = mockCC.getRequestHandlers() == reqHandlers;
    String fullPath = path;
    String prefix = null;
    if (!containerHandlerLookup) {
      int idx = path.indexOf('/', 1);
      prefix = path.substring(1, idx);
      if (idx > 0) idx = path.indexOf('/', idx + 1);
      path = idx == -1 ? "/" : path.substring(idx);
    }

    Api api = V2HttpCall.getApiInfo(reqHandlers, path, "GET", mockCC, prefix, fullPath, parts);
    SolrQueryResponse rsp = new SolrQueryResponse();
    LocalSolrQueryRequest req = new LocalSolrQueryRequest(null, new MapSolrParams(new HashMap<>())){
      @Override
      public List<CommandOperation> getCommands(boolean validateInput) {
        return Collections.emptyList();
      }
    };

    api.call(req,rsp);
    return rsp;

  }


  private void assertConditions(Map root, Map conditions) {
    for (Object o : conditions.entrySet()) {
      Map.Entry e = (Map.Entry) o;
      String path = (String) e.getKey();
      List<String> parts = StrUtils.splitSmart(path, path.charAt(0) == '/' ?  '/':' ');
      if (parts.get(0).isEmpty()) parts.remove(0);
      Object val = Utils.getObjectByPath(root, false, parts);
      if (e.getValue() instanceof Predicate) {
        Predicate value = (Predicate) e.getValue();
        String err = value.test(val);
        if(err != null){
          assertEquals(err + " for " + e.getKey() + " in :" + Utils.toJSONString(root), e.getValue(), val);
        }

      } else {
        assertEquals("incorrect value for path " + e.getKey() + " in :" + Utils.toJSONString(root), e.getValue(), val);
      }
    }

  }


}