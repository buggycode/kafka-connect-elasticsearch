/**
 * Copyright 2016 Confluent Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 **/

package io.confluent.connect.elasticsearch;

import com.google.gson.JsonArray;

import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.sink.SinkRecord;
import org.apache.kafka.connect.sink.SinkTaskContext;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.node.Node;
import org.elasticsearch.test.ESIntegTestCase;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;

import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import io.searchbox.client.JestClient;
import io.searchbox.client.JestClientFactory;
import io.searchbox.client.config.HttpClientConfig;
import io.searchbox.client.http.JestHttpClient;
import io.searchbox.core.Search;
import io.searchbox.core.SearchResult;

public class ElasticsearchSinkTestBase extends ESIntegTestCase {

  protected static Set<TopicPartition> assignment;

  protected static final String TYPE = "kafka-connect";
  protected static final long SLEEP_INTERVAL_MS = 2000;

  protected static final String TOPIC = "topic";
  protected static final int PARTITION = 12;
  protected static final int PARTITION2 = 13;
  protected static final int PARTITION3 = 14;
  protected static final TopicPartition TOPIC_PARTITION = new TopicPartition(TOPIC, PARTITION);
  protected static final TopicPartition TOPIC_PARTITION2 = new TopicPartition(TOPIC, PARTITION2);
  protected static final TopicPartition TOPIC_PARTITION3 = new TopicPartition(TOPIC, PARTITION3);
  protected static SinkTaskContext context;

  protected final JestClientFactory factory = new JestClientFactory();
  protected JestHttpClient client;

  @BeforeClass
  public static void createAssignment() {
    assignment = new HashSet<>();
    assignment.add(TOPIC_PARTITION);
    assignment.add(TOPIC_PARTITION2);
    assignment.add(TOPIC_PARTITION3);
    context = new MockSinkTaskContext();
  }

  @AfterClass
  public static void clearAssignment() {
    assignment.clear();
    context = null;
  }

  @Before
  public void setUp() throws Exception {
    super.setUp();
    factory.setHttpClientConfig(
        new HttpClientConfig
            .Builder("http://localhost:" + getPort())
            .multiThreaded(true).build()
    );
    client = (JestHttpClient) factory.getObject();
  }

  @After
  public void tearDown() throws Exception {
    super.tearDown();
    if (client != null) {
      client.shutdownClient();
    }
    client = null;
  }

  protected int getPort() {
    assertTrue("There should be at least 1 HTTP endpoint exposed in the test cluster",
               cluster().httpAddresses().length > 0);
    return cluster().httpAddresses()[0].getPort();
  }

  protected Struct createRecord(Schema schema) {
    Struct struct = new Struct(schema);
    struct.put("user", "Liquan");
    struct.put("message", "trying out Elastic Search.");
    return struct;
  }

  protected Schema createSchema() {
    return SchemaBuilder.struct().name("record")
        .field("user", Schema.STRING_SCHEMA)
        .field("message", Schema.STRING_SCHEMA)
        .build();
  }

  protected Schema createOtherSchema() {
    return SchemaBuilder.struct().name("record")
        .field("user", Schema.INT32_SCHEMA)
        .build();
  }

  protected Struct createOtherRecord(Schema schema) {
    Struct struct = new Struct(schema);
    struct.put("user", 10);
    return struct;
  }

  protected SearchResult search(JestClient client) throws IOException {
    return client.execute(new Search.Builder("").build());
  }

  protected void verifySearch(Collection<SinkRecord> records, SearchResult result, boolean ignoreKey) {
    JsonArray hits = result.getJsonObject().getAsJsonObject("hits").getAsJsonArray("hits");
    assertEquals(records.size(), hits.size());
    Set<String> hitIds = new HashSet<>();
    for (int i = 0; i < hits.size(); ++i) {
      String id = hits.get(i).getAsJsonObject().get("_id").getAsString();
      hitIds.add(id);
    }

    if (ignoreKey) {
      for (SinkRecord record : records) {
        String topic = record.topic();
        int partition = record.kafkaPartition();
        long offset = record.kafkaOffset();
        String id = topic + "+" + String.valueOf(partition) + "+" + String.valueOf(offset);
        assertTrue(hitIds.contains(id));
      }
    } else {
      for (SinkRecord record : records) {
        String id = DataConverter.convertKey(record.key(), record.keySchema());
        assertTrue(hitIds.contains(id));
      }
    }
  }

  @Override
  protected Settings nodeSettings(int nodeOrdinal) {
    return Settings.settingsBuilder()
        .put(super.nodeSettings(nodeOrdinal))
        .put(IndexMetaData.SETTING_NUMBER_OF_SHARDS, 1)
        .put(IndexMetaData.SETTING_NUMBER_OF_REPLICAS, 1)
        .put(Node.HTTP_ENABLED, true)
        .build();
  }

  protected static class MockSinkTaskContext implements SinkTaskContext {

    private Map<TopicPartition, Long> offsets;
    private long timeoutMs;

    public MockSinkTaskContext() {
      this.offsets = new HashMap<>();
      this.timeoutMs = -1L;
    }

    @Override
    public void offset(Map<TopicPartition, Long> offsets) {
      this.offsets.putAll(offsets);
    }

    @Override
    public void offset(TopicPartition tp, long offset) {
      offsets.put(tp, offset);
    }

    /**
     * Get offsets that the SinkTask has submitted to be reset. Used by the Copycat framework.
     * @return the map of offsets
     */
    public Map<TopicPartition, Long> offsets() {
      return offsets;
    }

    @Override
    public void timeout(long timeoutMs) {
      this.timeoutMs = timeoutMs;
    }

    /**
     * Get the timeout in milliseconds set by SinkTasks. Used by the Copycat framework.
     * @return the backoff timeout in milliseconds.
     */
    public long timeout() {
      return timeoutMs;
    }

    /**
     * Get the timeout in milliseconds set by SinkTasks. Used by the Copycat framework.
     * @return the backoff timeout in milliseconds.
     */

    @Override
    public Set<TopicPartition> assignment() {
      return assignment;
    }

    @Override
    public void pause(TopicPartition... partitions) {
      return;
    }

    @Override
    public void resume(TopicPartition... partitions) {
      return;
    }
  }
}
