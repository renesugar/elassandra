/*
 * Copyright (c) 2017 Strapdata (http://www.strapdata.com)
 * Contains some code from Elasticsearch (http://www.elastic.co)
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.elassandra;

import com.carrotsearch.randomizedtesting.generators.RandomPicks;
import com.google.common.net.InetAddresses;

import org.apache.cassandra.db.ConsistencyLevel;
import org.apache.cassandra.db.marshal.DoubleType;
import org.apache.cassandra.db.marshal.TupleType;
import org.apache.cassandra.serializers.SimpleDateSerializer;
import org.apache.cassandra.service.StorageService;
import org.apache.cassandra.utils.UUIDGen;
import org.apache.lucene.search.join.ScoreMode;
import org.elasticsearch.action.DocWriteResponse;
import org.elasticsearch.action.admin.indices.create.CreateIndexRequestBuilder;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.geo.GeoPoint;
import org.elasticsearch.common.geo.GeoUtils;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.test.ESSingleNodeTestCase;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertAcked;
import static org.hamcrest.Matchers.equalTo;

/**
 * Elassandra CQL types mapping tests.
 * @author vroyer
 *
 */
//mvn test -Pdev -pl com.strapdata.elasticsearch:elasticsearch -Dtests.seed=622A2B0618CE4676 -Dtests.class=org.elassandra.CqlTypesTests -Des.logger.level=ERROR -Dtests.assertion.disabled=false -Dtests.security.manager=false -Dtests.heap.size=1024m -Dtests.locale=ro-RO -Dtests.timezone=America/Toronto
public class CqlTypesTests extends ESSingleNodeTestCase {
    
    public void testTest() throws Exception {
        createIndex("cmdb");
        ensureGreen("cmdb");
        
        process(ConsistencyLevel.ONE,"CREATE TABLE cmdb.server ( name text, ip inet, netmask int, prod boolean, primary key (name))");
        XContentBuilder mapping1 = XContentFactory.jsonBuilder()
                .startObject()
                    .field("discover", ".*")
                    .startObject("properties")
                        .startObject("name")
                            .field("type", "keyword")
                        .endObject()
                    .endObject()
                .endObject();
        assertAcked(client().admin().indices().preparePutMapping("cmdb")
                .setType("server")
                .setSource(mapping1)
                .get());
        
        process(ConsistencyLevel.ONE,"insert into cmdb.server (name,ip,netmask,prod) VALUES ('localhost','127.0.0.1',8,true)");
        process(ConsistencyLevel.ONE,"insert into cmdb.server (name,ip,netmask,prod) VALUES ('my-server','123.45.67.78',24,true)");
        StorageService.instance.forceKeyspaceFlush("cmdb","server"); // flush to build the index correctly.
        
        assertThat(client().prepareGet().setIndex("cmdb").setType("server").setId("my-server").get().isExists(), equalTo(true));
        assertThat(client().prepareGet().setIndex("cmdb").setType("server").setId("localhost").get().isExists(), equalTo(true));
        
        assertEquals(client().prepareIndex("cmdb", "server", "bigserver234")
            .setSource("{\"ip\": \"22.22.22.22\", \"netmask\":32, \"prod\" : true, \"description\": \"my big server\" }", XContentType.JSON)
            .get().getResult(), DocWriteResponse.Result.CREATED);
        
        assertThat(client().prepareSearch().setIndices("cmdb").setTypes("server").setQuery(QueryBuilders.matchAllQuery()).get().getHits().getTotalHits(), equalTo(3L));
    }

    @Test
    public void testAllTypesTest() throws Exception {
        createIndex("ks1");
        ensureGreen("ks1");
        
        process(ConsistencyLevel.ONE,
                "CREATE TABLE ks1.natives (c1 text primary key, c2 text, c3 timestamp, c4 int, c5 bigint, c6 double, c7 float, c8 boolean, c9 blob, c10 uuid, c11 timeuuid)");
        assertAcked(client().admin().indices()
                .preparePutMapping("ks1")
                .setType("natives")
                .setSource("{ \"natives\" : { \"discover\" : \".*\", \"properties\": { \"c2\":{ \"type\":\"keyword\" }}}}", XContentType.JSON)
                .get());
        
        // {"c2": "toto", "c3" : "2016-10-10", "c4": 1, "c5":44, "c6":1.0, "c7":2.22, "c8": true, "c9":"U29tZSBiaW5hcnkgYmxvYg==" }
        assertThat(client().prepareIndex("ks1", "natives", "1")
                .setSource("{\"c2\": \"toto\", \"c3\" : \"2016-10-10\", \"c4\": 1, \"c5\":44, \"c6\":1.0, \"c7\":2.22, \"c8\": true, \"c9\":\"U29tZSBiaW5hcnkgYmxvYg==\", \"c10\":\"ae8c9260-dd02-11e6-b9d5-bbfb41c263ba\",\"c11\":\"ae8c9260-dd02-11e6-b9d5-bbfb41c263ba\"  }", XContentType.JSON)
                .get().getResult(), equalTo(DocWriteResponse.Result.CREATED));
        Map<String,Object> fields = client().prepareSearch("ks1").setTypes("natives").setQuery(QueryBuilders.queryStringQuery("c2:toto"))
                .get().getHits().getHits()[0]
                .getSourceAsMap();
        assertThat(fields.get("c2"),equalTo("toto"));
        assertThat(fields.get("c3").toString(),equalTo("2016-10-10T00:00:00.000Z"));
        assertThat(fields.get("c4"),equalTo(1));
        assertThat(fields.get("c5"),equalTo(44));
        assertThat(fields.get("c6"),equalTo(1.0));
        assertThat(fields.get("c7"),equalTo(2.22));
        assertThat(fields.get("c8"),equalTo(true));
        assertThat(fields.get("c9"),equalTo("U29tZSBiaW5hcnkgYmxvYg=="));
        
        process(ConsistencyLevel.ONE,"insert into ks1.natives (c1,c2,c3,c4,c5,c6,c7,c8,c9,c10,c11) VALUES ('tutu', 'titi', '2016-11-11', 1, 45, 1.0, 2.23, false,textAsBlob('bdb14fbe076f6b94444c660e36a400151f26fc6f'),ae8c9260-dd02-11e6-b9d5-bbfb41c263ba,ae8c9260-dd02-11e6-b9d5-bbfb41c263ba)");
        assertThat(client().prepareSearch().setIndices("ks1").setTypes("natives").setQuery(QueryBuilders.matchAllQuery()).get().getHits().getTotalHits(), equalTo(2L));
        
        fields = client().prepareSearch().setIndices("ks1").setTypes("natives").setQuery(QueryBuilders.queryStringQuery("c5:45")).get().getHits().getHits()[0].getSourceAsMap();
        
        assertThat(fields.get("c2"), equalTo("titi"));
        //assertThat(fields.get("c3"), equalTo(new SimpleDateFormat("yyyy-MM-dd").parse("2016-11-11").toLocaleString()));
        assertThat(fields.get("c4"),equalTo(1));
        assertThat(fields.get("c5"),equalTo(45));
        assertThat(fields.get("c6"),equalTo(1.0));
        assertThat(fields.get("c7"),equalTo(2.23));
        assertThat(fields.get("c8"),equalTo(false));
    }

    // mvn test -Pdev -pl com.strapdata.elasticsearch:elasticsearch -Dtests.seed=622A2B0618CE4676 -Dtests.class=org.elassandra.CqlTypesTests -Dtests.method="testSinglePkTypesTest" -Des.logger.level=ERROR -Dtests.assertion.disabled=false -Dtests.security.manager=false -Dtests.heap.size=1024m -Dtests.locale=ro-RO -Dtests.timezone=America/Toronto
    @Test
    public void testSinglePkTypesTest() throws Exception {
        createIndex("ks");
        ensureGreen("ks");
        
        String[] types = new String[] { "text","int","bigint","double","float","boolean","blob","timestamp","inet","uuid" };
        Object[] values = new Object[] { "foo",1,2L, new Double(3.14), new Float(3.14), true, ByteBuffer.wrap("toto".getBytes("UTF-8")), new Date(), InetAddresses.forString("127.0.0.1"), UUID.randomUUID() };
        for(int i=0; i < types.length; i++) {
            String type = types[i];
            Object value = values[i];
            System.out.println("insert pk type="+type);
            process(ConsistencyLevel.ONE,String.format(Locale.ROOT,"CREATE TABLE ks.t%s (pk%s %s PRIMARY KEY, v text)", type, type, type));
            process(ConsistencyLevel.ONE,String.format(Locale.ROOT,"INSERT INTO ks.t%s (pk%s, v) VALUES (?, 'foobar')", type, type), value);
        }
        
        // flush for rebuild_index
        StorageService.instance.forceKeyspaceFlush("ks");
        for(int i=0; i < types.length; i++) {
            String type = types[i];
            System.out.println("discover pk type="+type+ " index ks"+i);
            CreateIndexRequestBuilder builder = client().admin().indices().prepareCreate("ks"+i);
            builder.request()
                .settings(Settings.builder().put("index.keyspace","ks"))
                .mapping(String.format(Locale.ROOT,"t%s",type), String.format(Locale.ROOT,"{ \"t%s\" : { \"discover\" : \".*\" }}",type), XContentType.JSON);
            assertAcked(builder.get());
        }
        
        // search
        Thread.sleep(2000);
        for(int i=0; i < types.length; i++) {
            String type = types[i];
            System.out.println("search pk type="+type+" in index ks"+i);
        
            assertThat(client().prepareSearch()
                    .setIndices("ks"+i)
                    .setTypes(String.format(Locale.ROOT,"t%s",type))
                    .setQuery(QueryBuilders.matchAllQuery())
                    .storedFields("_id","_routing","_ttl","_timestamp","_source","v")
                    .get().getHits().getTotalHits(), equalTo(1L));
        }
    }
    
    // mvn test -Pdev -pl com.strapdata.elasticsearch:elasticsearch -Dtests.seed=622A2B0618CE4676 -Dtests.class=org.elassandra.CqlTypesTests -Dtests.method="testCompoundPkTypesTest" -Des.logger.level=ERROR -Dtests.assertion.disabled=false -Dtests.security.manager=false -Dtests.heap.size=1024m -Dtests.locale=ro-RO -Dtests.timezone=America/Toronto
    @Test
    public void testCompoundPkTypesTest() throws Exception {
        createIndex("ks");
        ensureGreen("ks");
        
        String[] types = new String[] { "text", "int","bigint","double","float","boolean","blob","timestamp","inet","uuid" };
        Object[] values = new Object[] { "foo", 1, 2L, new Double(3.14), new Float(3.14), true, ByteBuffer.wrap("toto".getBytes("UTF-8")), new Date(), InetAddresses.forString("127.0.0.1"), UUID.randomUUID() };
        int randomCk = randomInt(types.length-1);
        int randomVal= randomInt(types.length-1);
        for(int i=0; i < types.length; i++) {
            String type = types[i];
            System.out.println("insert pk type="+type);
            process(ConsistencyLevel.ONE,String.format(Locale.ROOT,"CREATE TABLE ks.t%s (pk%s %s, ck %s, v %s, PRIMARY KEY (pk%s,ck))", type, type, type, types[randomCk], types[randomVal], type));
            process(ConsistencyLevel.ONE,String.format(Locale.ROOT,"INSERT INTO ks.t%s (pk%s, ck, v) VALUES (?, ?, ?)", type, type), values[i], values[randomCk], values[randomVal]);
        }
        
        // flush for rebuild_index
        StorageService.instance.forceKeyspaceFlush("ks");
        for(int i=0; i < types.length; i++) {
            String type = types[i];
            System.out.println("discover pk type="+type+ " index ks"+i);
            CreateIndexRequestBuilder builder = client().admin().indices().prepareCreate("ks"+i);
            builder.request()
                .settings(Settings.builder().put("index.keyspace","ks"))
                .mapping(String.format(Locale.ROOT,"t%s",type), String.format(Locale.ROOT,"{ \"t%s\" : { \"discover\" : \".*\" }}",type), XContentType.JSON);
            assertAcked(builder.get());
        }
        
        // search
        Thread.sleep(2000);
        for(int i=0; i < types.length; i++) {
            String type = types[i];
            System.out.println("search pk type="+type+" in index ks"+i);
        
            assertThat(client().prepareSearch()
                    .setIndices("ks"+i)
                    .setTypes(String.format(Locale.ROOT,"t%s",type))
                    .setQuery(QueryBuilders.matchAllQuery())
                    .storedFields("_id","_routing","_ttl","_timestamp","_source","ck","v")
                    .get().getHits().getTotalHits(), equalTo(1L));
        }
    }
    
    @Test
    public void testTextGeohashMapping() throws Exception {
        createIndex("test");
        ensureGreen("test");
        
        process(ConsistencyLevel.ONE,"create type test.geo_point (lat double, lon double);");
        process(ConsistencyLevel.ONE,"create table test.geoloc (geohash text, id uuid, coord frozen<geo_point>, comment text, primary key ((geohash),id));");
        assertAcked(client().admin().indices().preparePutMapping("test").setType("geoloc")
                .setSource("{ \"geoloc\" : { \"discover\":\"^((?!geohash).*)\", \"properties\": { \"geohash\": { \"type\": \"geo_point\", \"cql_collection\":\"singleton\",\"cql_partition_key\" : true,\"cql_primary_key_order\" : 0 } }}}", XContentType.JSON).get());

        GeoPoint geo_point = new GeoPoint(-25.068403, 29.411767);
        ByteBuffer[] elements = new ByteBuffer[] {
                ClusterService.serialize("test", "geoloc", DoubleType.instance, GeoUtils.LATITUDE, -25.068403, null),
                ClusterService.serialize("test", "geoloc", DoubleType.instance, GeoUtils.LONGITUDE, 29.411767, null)
        };
        process(ConsistencyLevel.ONE,"INSERT INTO test.geoloc (geohash, id, coord, comment) VALUES (?,?,?,?)",
                geo_point.geohash(), UUID.randomUUID(), TupleType.buildValue(elements), "blabla");
        SearchResponse rsp = client().prepareSearch().setIndices("test").setTypes("geoloc")
                .setQuery(QueryBuilders.boolQuery()
                    .must(QueryBuilders.matchAllQuery())
                    .filter(QueryBuilders.geoDistanceQuery("geohash").distance("20km").point(-25.068403, 29.411767)))
                .get();
        assertThat(rsp.getHits().getTotalHits(),equalTo(1L));
    }
    
    // #74 test
    @Test
    public void testUUID() throws Exception {
        createIndex("test1");
        ensureGreen("test1");
        
        createIndex("test2");
        ensureGreen("test2");
        
        process(ConsistencyLevel.ONE,"create table test1.pk_uuid (pk_uuid uuid, column_not_uuid text, primary key(pk_uuid));");
        process(ConsistencyLevel.ONE,"create table test2.pk_not_uuid (pk_not_uuid text, column_uuid uuid, primary key(pk_not_uuid));");
        
        assertAcked(client().admin().indices().preparePutMapping("test1").setType("pk_uuid").setSource("{ \"pk_uuid\" : { \"discover\" : \".*\"}}", XContentType.JSON).get());
        assertAcked(client().admin().indices().preparePutMapping("test2").setType("pk_not_uuid").setSource("{ \"pk_not_uuid\" : { \"discover\" : \".*\"}}", XContentType.JSON).get());
        
        assertThat(client().prepareIndex("test1", "pk_uuid", "bacc6c75-91b8-4a86-a408-ff7bafac535d").setSource("{ \"column_not_uuid\": \"a value\" }", XContentType.JSON).get().getResult(), equalTo(DocWriteResponse.Result.CREATED));
        assertThat(client().prepareIndex("test1", "pk_uuid", "bacc6c75-91b8-4a86-a408-ff7bafac535d").setSource("{ \"column_not_uuid\": \"a value\", \"pk_uuid\": \"bacc6c75-91b8-4a86-a408-ff7bafac535d\" }", XContentType.JSON).get().getResult(), equalTo(DocWriteResponse.Result.CREATED));
        assertThat(client().prepareIndex("test2", "pk_not_uuid", "pk2").setSource("{ \"column_uuid\": \"bacc6c75-91b8-4a86-a408-ff7bafac535d\" }", XContentType.JSON).get().getResult(), equalTo(DocWriteResponse.Result.CREATED));
        assertThat(client().prepareIndex("test2", "pk_not_uuid", "pk2").setSource("{ \"column_uuid\": \"bacc6c75-91b8-4a86-a408-ff7bafac535d\", \"pk_not_uuid\":\"pk2\" }", XContentType.JSON).get().getResult(), equalTo(DocWriteResponse.Result.CREATED));
    }
    
    // #91 test
    // mvn test -Pdev -pl com.strapdata.elasticsearch:elasticsearch -Dtests.seed=622A2B0618CE4676 -Dtests.class=org.elassandra.CqlTypesTests -Dtests.method="testMapAsObject" -Des.logger.level=ERROR -Dtests.assertion.disabled=false -Dtests.security.manager=false -Dtests.heap.size=1024m -Dtests.locale=ro-RO -Dtests.timezone=America/Toronto
    @Test
    public void testMapAsObject() throws Exception {
        createIndex("test");
        ensureGreen("test");
        
        process(ConsistencyLevel.ONE,"CREATE TABLE test.event_test (id text, strings map<text, text>, PRIMARY KEY (id));");
        assertAcked(client().admin().indices().preparePutMapping("test").setType("event_test").setSource("{ \"event_test\" : { \"discover\" : \".*\"}}", XContentType.JSON).get());
        
        long N = 10;
        for(int i=0; i < N; i++)
            process(ConsistencyLevel.ONE,String.format(Locale.ROOT, "insert into test.event_test (id,strings) VALUES ('%d',{'key%d':'b%d'})", i, i, i));
        
        assertThat(client().prepareSearch().setIndices("test").setTypes("event_test").setQuery(QueryBuilders.matchAllQuery()).get().getHits().getTotalHits(), equalTo(N));
        assertThat(client().prepareSearch().setIndices("test").setTypes("event_test").setQuery(QueryBuilders.nestedQuery("strings", QueryBuilders.queryStringQuery("strings.key1:b1"), RandomPicks.randomFrom(random(), ScoreMode.values()))).get().getHits().getTotalHits(), equalTo(1L));
    }
    
    // mvn test -Pdev -pl com.strapdata.elasticsearch:elasticsearch -Dtests.seed=622A2B0618CE4676 -Dtests.class=org.elassandra.CqlTypesTests -Dtests.method="testMapAsObjectWithDynamicMapping" -Des.logger.level=ERROR -Dtests.assertion.disabled=false -Dtests.security.manager=false -Dtests.heap.size=1024m -Dtests.locale=ro-RO -Dtests.timezone=America/Toronto
    @Test
    public void testMapAsObjectWithDynamicMapping() throws Exception {
        createIndex("test");
        ensureGreen("test");
        
        process(ConsistencyLevel.ONE,"CREATE TABLE test.event_test (id text, strings map<text, text>, PRIMARY KEY (id));");
        assertAcked(client().admin().indices().preparePutMapping("test").setType("event_test")
                .setSource("{ \"event_test\" : { \"discover\" : \".*\", "+
                        "\"dynamic_templates\": [ "+
                            "{ \"strings_template\": { "+
                                "\"match\": \"strings.*\", "+ 
                                "\"mapping\": { "+
                                    "\"type\": \"text\"" +
                                "}"+
                          "}}" +
                         "]"+
        "}}}", XContentType.JSON).get());
        
        long N = 10;
        for(int i=0; i < N; i++)
            process(ConsistencyLevel.ONE,String.format(Locale.ROOT, "insert into test.event_test (id,strings) VALUES ('%d',{'key%d':'test b%d'})", i, i, i));
        
        assertThat(client().prepareSearch().setIndices("test").setTypes("event_test").setQuery(QueryBuilders.matchAllQuery()).get().getHits().getTotalHits(), equalTo(N));
        assertThat(client().prepareSearch().setIndices("test").setTypes("event_test").setQuery(QueryBuilders.nestedQuery("strings",QueryBuilders.matchQuery("strings.key1", "test b1"), RandomPicks.randomFrom(random(), ScoreMode.values()))).get().getHits().getTotalHits(), equalTo(1L));
    }
    
    // #91 test
    // see https://www.elastic.co/guide/en/elasticsearch/reference/2.4/null-value.html
    // see https://www.datastax.com/dev/blog/cql3_collections (empty list = null)
    // mvn test -Pdev -pl com.strapdata.elasticsearch:elasticsearch -Dtests.seed=622A2B0618CE4676 -Dtests.class=org.elassandra.CqlTypesTests -Dtests.method="testNullValue" -Des.logger.level=ERROR -Dtests.assertion.disabled=false -Dtests.security.manager=false -Dtests.heap.size=1024m -Dtests.locale=ro-RO -Dtests.timezone=America/Toronto
    @Test
    public void testNullValue() throws Exception {
        XContentBuilder mapping = XContentFactory.jsonBuilder()
                .startObject()
                    .startObject("properties")
                        .startObject("id").field("type", "keyword").field("cql_collection", "singleton").field("cql_primary_key_order", 0).field("cql_partition_key", true).endObject()
                        .startObject("status_code")
                            .field("type", "keyword")
                            .field("null_value", "NULL")
                        .endObject()
                    .endObject()
                .endObject();
        assertAcked(client().admin().indices().prepareCreate("test").addMapping("my_type", mapping));
        ensureGreen("test");

        assertThat(client().prepareIndex("test", "my_type", "1").setSource("{\"status_code\": \"OK\" }", XContentType.JSON).get().getResult(), equalTo(DocWriteResponse.Result.CREATED));
        assertThat(client().prepareIndex("test", "my_type", "2").setSource("{\"status_code\": [ \"NOK\", \"OK\" ] }", XContentType.JSON).get().getResult(), equalTo(DocWriteResponse.Result.CREATED));
        assertThat(client().prepareIndex("test", "my_type", "3").setSource("{\"status_code\": null }", XContentType.JSON).get().getResult(), equalTo(DocWriteResponse.Result.CREATED));
        assertThat(client().prepareIndex("test", "my_type", "4").setSource("{\"status_code\": [] }", XContentType.JSON).get().getResult(), equalTo(DocWriteResponse.Result.CREATED));
        assertThat(client().prepareIndex("test", "my_type", "5").setSource("{\"status_code\": \"NULL\" }", XContentType.JSON).get().getResult(), equalTo(DocWriteResponse.Result.CREATED));
       
        assertThat(client().prepareSearch().setIndices("test").setTypes("my_type").setQuery(QueryBuilders.queryStringQuery("status_code:NULL")).get().getHits().getTotalHits(), equalTo(3L));
    }
    
    // #112 test
    // mvn test -Pdev -pl com.strapdata.elasticsearch:elasticsearch -Dtests.seed=622A2B0618CE4676 -Dtests.class=org.elassandra.CqlTypesTests -Dtests.method="testSets" -Des.logger.level=ERROR -Dtests.assertion.disabled=false -Dtests.security.manager=false -Dtests.heap.size=1024m -Dtests.locale=ro-RO -Dtests.timezone=America/Toronto
    @Test
    public void testSets() throws Exception {
        XContentBuilder mapping = XContentFactory.jsonBuilder()
               .startObject()
                   .startObject("properties")
                       .startObject("items")
                           .field("type", "object")
                           .field("cql_collection", "set")
                           .field("cql_udt_name", "item")
                           .startObject("properties")
                               .startObject("name")
                                   .field("type", "keyword")
                                   .field("cql_collection", "singleton")
                               .endObject()
                           .endObject()
                        .endObject()
                        .startObject("item")
                           .field("type", "object")
                           .field("cql_collection", "singleton")
                           .field("cql_udt_name", "item")
                           .startObject("properties")
                               .startObject("name")
                                   .field("type", "keyword")
                                   .field("cql_collection", "singleton")
                                .endObject()
                           .endObject()
                        .endObject()
                        .startObject("attrs")
                            .field("type", "keyword")
                            .field("cql_collection", "set")
                         .endObject()
                  .endObject()
             .endObject();
        assertAcked(client().admin().indices().prepareCreate("test").addMapping("tab_set", mapping));
        ensureGreen("test");
        process(ConsistencyLevel.ONE,"insert into test.tab_set (\"_id\",item,items,attrs) values ('1',{name:'hello'},{{name:'world'},{name:'heaven'}},{'blue','red'})");
        assertThat(client().prepareSearch().setIndices("test").setTypes("tab_set").setQuery(QueryBuilders.queryStringQuery("items.name:heaven")).get().getHits().getTotalHits(), equalTo(1L));
        assertThat(client().prepareSearch().setIndices("test").setTypes("tab_set").setQuery(QueryBuilders.queryStringQuery("items.name:world")).get().getHits().getTotalHits(), equalTo(1L));
        assertThat(client().prepareSearch().setIndices("test").setTypes("tab_set").setQuery(QueryBuilders.queryStringQuery("red")).get().getHits().getTotalHits(), equalTo(1L));
       
        process(ConsistencyLevel.ONE,"insert into test.tab_set (\"_id\",item,items,attrs) values ('1',{name:'hello'},{{name:'heaven'}},{'blue'})");
        assertThat(client().prepareSearch().setIndices("test").setTypes("tab_set").setQuery(QueryBuilders.queryStringQuery("items.name:heaven")).get().getHits().getTotalHits(), equalTo(1L));
        assertThat(client().prepareSearch().setIndices("test").setTypes("tab_set").setQuery(QueryBuilders.queryStringQuery("red")).get().getHits().getTotalHits(), equalTo(0L));
       
        process(ConsistencyLevel.ONE,"update test.tab_set set items = items + {{name:'world'}} where \"_id\" = '1'");
        assertThat(client().prepareSearch().setIndices("test").setTypes("tab_set").setQuery(QueryBuilders.queryStringQuery("items.name:heaven")).get().getHits().getTotalHits(), equalTo(1L));
        assertThat(client().prepareSearch().setIndices("test").setTypes("tab_set").setQuery(QueryBuilders.queryStringQuery("items.name:world")).get().getHits().getTotalHits(), equalTo(1L));
        process(ConsistencyLevel.ONE,"update test.tab_set set attrs = attrs + {'yellow'} where \"_id\" = '1'");
        assertThat(client().prepareSearch().setIndices("test").setTypes("tab_set").setQuery(QueryBuilders.queryStringQuery("yellow")).get().getHits().getTotalHits(), equalTo(1L));
    }
    
    // #161 Search over a nested set returns wrong inner_hits
    public void testNestedSets() throws Exception {
        createIndex("test");
        ensureGreen("test");
        
        process(ConsistencyLevel.ONE,"CREATE TYPE IF NOT EXISTS test.model (name VARCHAR, date TIMESTAMP);");
        process(ConsistencyLevel.ONE,"CREATE TABLE IF NOT EXISTS test.make_models (make VARCHAR, models SET<FROZEN<model>>, PRIMARY KEY (make));");
        
        XContentBuilder mapping = XContentFactory.jsonBuilder()
                .startObject()
                    .startObject("make_models")
                        .field("discover", ".*")
                    .endObject()
                .endObject();
        assertTrue(client().admin().indices().preparePutMapping("test").setType("make_models").setSource(mapping).get().isAcknowledged());
        
        process(ConsistencyLevel.ONE, "UPDATE test.make_models SET models = models + {{name : 'dart', date : '2018-01-29 11:53:00'}} WHERE make='dodge';");
        process(ConsistencyLevel.ONE, "UPDATE test.make_models SET models = models + {{name : 'polara', date : '2018-01-29 11:54:00'}} WHERE make='dodge';");
        process(ConsistencyLevel.ONE, "UPDATE test.make_models SET models = models + {{name : 'mustang', date : '2018-02-01 11:50:00'}} WHERE make='ford';");
        process(ConsistencyLevel.ONE, "UPDATE test.make_models SET models = models + {{name : 'galaxie', date : '2018-02-01 11:51:00'}} WHERE make='ford';");
        process(ConsistencyLevel.ONE, "UPDATE test.make_models SET models = models + {{name : 'camaro', date : '2018-02-01 12:50:00'}} WHERE make='gm';");
        process(ConsistencyLevel.ONE, "UPDATE test.make_models SET models = models + {{name : 'chevelle', date : '2018-02-01 12:52:00'}} WHERE make='gm';");
        
        assertThat(client().prepareSearch().setIndices("test").setTypes("make_models").setQuery(QueryBuilders.nestedQuery("models", QueryBuilders.termQuery("models.name", "galaxie"), RandomPicks.randomFrom(random(), ScoreMode.values()))).get().getHits().getTotalHits(), equalTo(1L));
        process(ConsistencyLevel.ONE, "UPDATE test.make_models SET models = models + {{name : 'mustang', date : '2018-02-01 11:50:00'}} WHERE make='ford';");
        assertThat(client().prepareSearch().setIndices("test").setTypes("make_models").setQuery(QueryBuilders.nestedQuery("models", QueryBuilders.termQuery("models.name", "galaxie"), RandomPicks.randomFrom(random(), ScoreMode.values()))).get().getHits().getTotalHits(), equalTo(1L));
        process(ConsistencyLevel.ONE, "UPDATE test.make_models SET models = models + {{name : 'galaxie', date : '2018-02-01 11:51:00'}} WHERE make='ford';");
        assertThat(client().prepareSearch().setIndices("test").setTypes("make_models").setQuery(QueryBuilders.nestedQuery("models", QueryBuilders.termQuery("models.name", "galaxie"), RandomPicks.randomFrom(random(), ScoreMode.values()))).get().getHits().getTotalHits(), equalTo(1L));
    }
    
    // #197 Deletion of a List element removes the document on ES 
    public void testDeleteInUDTList() throws Exception {
        createIndex("test");
        ensureGreen("test");
        
        process(ConsistencyLevel.ONE, "CREATE TYPE test.type_test (id text);");
        process(ConsistencyLevel.ONE, "CREATE TABLE test.table_test (" + 
                "    id1 text," + 
                "    id2 text," + 
                "    list list<frozen<type_test>>," + 
                "    PRIMARY KEY (id1, id2)" + 
                ");");
        
        XContentBuilder mapping = XContentFactory.jsonBuilder()
                .startObject()
                    .startObject("table_test")
                        .field("discover", ".*")
                    .endObject()
                .endObject();
        assertTrue(client().admin().indices().preparePutMapping("test").setType("table_test").setSource(mapping).get().isAcknowledged());
        process(ConsistencyLevel.ONE, "UPDATE test.table_test SET list = list + [{ id:'foo'}] where id1='1' and id2='2';");
        process(ConsistencyLevel.ONE, "UPDATE test.table_test SET list = list + [{ id:'bar'}] where id1='1' and id2='2';");
        
        SearchResponse resp = client().prepareSearch().setIndices("test").setTypes("table_test").setQuery(QueryBuilders.matchAllQuery()).get();
        assertThat(resp.getHits().getTotalHits(), equalTo(1L));
        List<Object> list = (List<Object>) resp.getHits().getAt(0).getSourceAsMap().get("list");
        assertThat(list.size(), equalTo(2));
        Map<String, Object> map = (Map<String, Object>)list.get(0);
        assertThat(map.size(), equalTo(1));
        
        process(ConsistencyLevel.ONE, "UPDATE test.table_test SET list = list - [{ id:'bar'}] where id1='1' and id2='2';");
        SearchResponse resp2 = client().prepareSearch().setIndices("test").setTypes("table_test").setQuery(QueryBuilders.matchAllQuery()).get();
        assertThat(resp2.getHits().getTotalHits(), equalTo(1L));
        Map<String, Object> map2 = (Map<String, Object>) resp2.getHits().getAt(0).getSourceAsMap().get("list");
        assertThat(map2.size(), equalTo(1));
    }
    
    // test CQL timeuuid, date and time mapping.
    @Test
    public void testTimes() throws Exception {
        createIndex("test");
        ensureGreen("test");
        
        process(ConsistencyLevel.ONE,"CREATE TABLE test.event_test (id text, start timeuuid, end timeuuid, day date, hour time, PRIMARY KEY (id));");
        assertAcked(client().admin().indices().preparePutMapping("test").setType("event_test")
                .setSource("{ \"event_test\" : { \"discover\" : \"^((?!end).*)\", \"properties\":{ \"end\":{\"type\":\"date\",\"cql_collection\":\"singleton\"}}}}", XContentType.JSON).get());
        
        LocalDate localDate = LocalDate.parse("2010-10-10");
        Instant instant = Instant.from(localDate.atStartOfDay(ZoneId.of("GMT")));
        UUID end = UUIDGen.getTimeUUID(instant.toEpochMilli());
        UUID start = UUIDGen.getTimeUUID();
        
        process(ConsistencyLevel.ONE,"INSERT INTO test.event_test (id , start , end, day, hour) VALUES (?,?,?,?,?)", 
                "1", start, end, SimpleDateSerializer.dateStringToDays("2010-10-10"), 10*3600*1000000000L);
        
        SearchResponse resp = client().prepareSearch().setIndices("test").setTypes("event_test").setQuery(QueryBuilders.queryStringQuery("day:2010-10-10")).get();
        assertThat(resp.getHits().getTotalHits(), equalTo(1L));
        assertThat(resp.getHits().getHits()[0].getSourceAsMap().get("day"), equalTo("2010-10-10T00:00:00.000Z"));
        assertThat(resp.getHits().getHits()[0].getSourceAsMap().get("hour"), equalTo(36000000000000L));
        assertThat(resp.getHits().getHits()[0].getSourceAsMap().get("start"), equalTo(start.toString()));
        assertThat(resp.getHits().getHits()[0].getSourceAsMap().get("end"), equalTo("2010-10-10T00:00:00.000Z"));
    }
    
    // see issue #128
    @Test
    public void testFetchMultipleTypes() throws Exception {
        createIndex("test");
        ensureGreen("test");
        
        assertThat(client().prepareIndex("test", "typeA", "1").setSource("{ \"a\":\"1\", \"x\":\"aaa\" }", XContentType.JSON).get().getResult(), equalTo(DocWriteResponse.Result.CREATED));
        assertThat(client().prepareIndex("test", "typeA", "2").setSource("{ \"b\":\"1\", \"x\":\"aaa\" }", XContentType.JSON).get().getResult(), equalTo(DocWriteResponse.Result.CREATED));
        assertThat(client().prepareIndex("test", "typeA", "3").setSource("{ \"c\":\"1\", \"x\":\"aaa\" }", XContentType.JSON).get().getResult(), equalTo(DocWriteResponse.Result.CREATED));
        SearchResponse resp = client().prepareSearch().setIndices("test").setQuery(QueryBuilders.queryStringQuery("q=aaa")).get();
        assertThat(resp.getHits().getTotalHits(), equalTo(3L));
    }
    
    // see issue #142
    @Test
    public void testNestedDate() throws Exception {
        XContentBuilder mapping = XContentFactory.jsonBuilder()
                .startObject()
                    .startObject("properties")
                        .startObject("id").field("type", "keyword").field("cql_collection", "singleton").field("cql_primary_key_order", 0).field("cql_partition_key", true).endObject()
                        .startObject("event_timestamp")
                            .field("type", "date")
                            .field("format", "strict_date_hour_minute_second||epoch_millis")
                            .field("cql_collection", "singleton")
                        .endObject()
                        .startObject("event_info")
                            .field("type", "nested")
                            .field("cql_collection", "singleton")
                            .field("cql_udt_name", "event_info_udt")
                            .field("dynamic", "false")
                            .startObject("properties")
                               .startObject("event_timestamp")
                                .field("type", "date")
                                .field("format", "strict_date_hour_minute_second||epoch_millis")
                                .field("cql_collection", "singleton")
                            .endObject()
                        .endObject()
                    .endObject()
                    .endObject()
                .endObject();
        assertAcked(client().admin().indices().prepareCreate("test").addMapping("my_type", mapping));
        ensureGreen("test");
        
        
        assertThat(client().prepareIndex("test", "my_type", "1")
                .setSource("{\"event_info\": {},\"event_timestamp\": \"2017-11-21T16:30:00\"}", XContentType.JSON)
                .get().getResult(), equalTo(DocWriteResponse.Result.CREATED));
        assertThat(client().prepareIndex("test", "my_type", "2")
                .setSource("{\"event_info\": {\"event_timestamp\": \"2017-11-21T16:30:00\"},\"event_timestamp\": \"2017-11-21T16:30:00\"}", XContentType.JSON)
                .get().getResult(), equalTo(DocWriteResponse.Result.CREATED));
        
        SearchResponse resp = client().prepareSearch().setIndices("test").setQuery(QueryBuilders.matchAllQuery()).get();
        assertThat(resp.getHits().getTotalHits(), equalTo(2L));
        assertThat(resp.getFailedShards(), equalTo(0));
    }
}

