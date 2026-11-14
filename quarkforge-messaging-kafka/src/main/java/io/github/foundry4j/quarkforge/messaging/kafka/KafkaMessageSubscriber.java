package io.github.foundry4j.quarkforge.messaging.kafka;

import io.github.foundry4j.quarkforge.messaging.MessageEnvelope;
import io.github.foundry4j.quarkforge.messaging.MessagePublisher;
import io.github.foundry4j.quarkforge.messaging.MessageRetryPolicy;
import io.github.foundry4j.quarkforge.messaging.MessageRetrySupport;
import io.github.foundry4j.quarkforge.messaging.MessageSubscriber;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.kafka.client.consumer.KafkaConsumer;
import io.vertx.kafka.client.consumer.KafkaConsumerRecord;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/// ## KafkaMessageSubscriber
/// Kafka 消费端，[MessageSubscriber] 的生产实现（覆盖本地默认实现）：
///
/// 1. 每个 topic 按 `concurrency` 创建多个消费者，同组并行消费不同分区；
/// 2. 关闭自动提交，消息在处理完成后手动 `commit`；
/// 3. 处理失败按统一的 [MessageRetryPolicy] 退避重试，重试耗尽后转入死信
///    topic `topic + dlq-suffix`，再提交位点，避免毒消息卡住分区；
/// 4. 消费逻辑通过 `executeBlocking` 串行执行，不阻塞事件循环。
///
/// 配置项：
///
/// | 配置 | 默认值 | 说明 |
/// |------|--------|------|
/// | `quarkforge.messaging.kafka.bootstrap-servers` | `localhost:9092` | broker 地址 |
/// | `quarkforge.messaging.kafka.group-id` | `quarkforge` | 消费组 |
/// | `quarkforge.messaging.kafka.auto-offset-reset` | `latest` | 无位点时的起始位置 |
/// | `quarkforge.messaging.kafka.concurrency` | `1` | 每个 topic 的消费者数量 |
/// | `quarkforge.messaging.kafka.max-poll-records` | `500` | 单次拉取的最大消息数 |
/// | `quarkforge.messaging.retry.max-retries` | `3` | 最大重试次数 |
/// | `quarkforge.messaging.retry.initial-backoff` | `500ms` | 首次退避 |
/// | `quarkforge.messaging.retry.multiplier` | `2.0` | 退避倍数 |
/// | `quarkforge.messaging.retry.max-backoff` | `10s` | 退避上限 |
/// | `quarkforge.messaging.dlq-suffix` | `.dlq` | 死信 topic 后缀 |
@ApplicationScoped
public class KafkaMessageSubscriber implements MessageSubscriber {

    private static final Logger LOG = Logger.getLogger(KafkaMessageSubscriber.class);

    @ConfigProperty(name = "quarkforge.messaging.kafka.bootstrap-servers", defaultValue = "localhost:9092")
    String bootstrapServers;

    @ConfigProperty(name = "quarkforge.messaging.kafka.group-id", defaultValue = "quarkforge")
    String groupId;

    @ConfigProperty(name = "quarkforge.messaging.kafka.auto-offset-reset", defaultValue = "latest")
    String autoOffsetReset;

    @ConfigProperty(name = "quarkforge.messaging.kafka.concurrency", defaultValue = "1")
    int concurrency;

    @ConfigProperty(name = "quarkforge.messaging.kafka.max-poll-records", defaultValue = "500")
    int maxPollRecords;

    @ConfigProperty(name = "quarkforge.messaging.retry.max-retries", defaultValue = "3")
    int maxRetries;

    @ConfigProperty(name = "quarkforge.messaging.retry.initial-backoff", defaultValue = "500ms")
    Duration initialBackoff;

    @ConfigProperty(name = "quarkforge.messaging.retry.multiplier", defaultValue = "2.0")
    double backoffMultiplier;

    @ConfigProperty(name = "quarkforge.messaging.retry.max-backoff", defaultValue = "10s")
    Duration maxBackoff;

    @ConfigProperty(name = "quarkforge.messaging.dlq-suffix", defaultValue = ".dlq")
    String dlqSuffix;

    @Inject
    Vertx vertx;

    @Inject
    MessagePublisher publisher;

    private final List<KafkaConsumer<String, String>> consumers = new CopyOnWriteArrayList<>();

    private MessageRetryPolicy retryPolicy;

    @PostConstruct
    void init() {
        retryPolicy = new MessageRetryPolicy(maxRetries, initialBackoff, backoffMultiplier, maxBackoff);
    }

    @Override
    @SuppressWarnings("rawtypes")
    public CompletionStage<Void> subscribe(String topic, Consumer<MessageEnvelope> handler) {
        List<Future> subscriptions = new ArrayList<>();
        for (int i = 0; i < Math.max(1, concurrency); i++) {
            KafkaConsumer<String, String> consumer = KafkaConsumer.create(vertx, consumerConfig());
            consumers.add(consumer);
            consumer.handler(record -> handle(topic, record, handler, consumer));
            subscriptions.add(consumer.subscribe(topic));
        }
        return CompositeFuture.all(subscriptions).<Void>mapEmpty().toCompletionStage();
    }

    private Map<String, String> consumerConfig() {
        Map<String, String> config = new HashMap<>();
        config.put("bootstrap.servers", bootstrapServers);
        config.put("group.id", groupId);
        config.put("key.deserializer", StringDeserializer.class.getName());
        config.put("value.deserializer", StringDeserializer.class.getName());
        config.put("enable.auto.commit", "false");
        config.put("auto.offset.reset", autoOffsetReset);
        config.put("max.poll.records", String.valueOf(maxPollRecords));
        return config;
    }

    private void handle(String topic, KafkaConsumerRecord<String, String> record,
                        Consumer<MessageEnvelope> handler, KafkaConsumer<String, String> consumer) {
        MessageEnvelope envelope = KafkaMessages.toEnvelope(topic, record.record());
        // ordered=true：同一消费者内按位点顺序串行处理，避免乱序
        vertx.executeBlocking(() -> {
            try {
                MessageRetrySupport.deliver(topic, envelope, handler, retryPolicy);
            } catch (Exception e) {
                deadLetter(topic, envelope, e);
            }
            return null;
        }, true).onComplete(_ -> consumer.commit().onFailure(error ->
                LOG.errorf(error, "Kafka 位点提交失败 topic=%s offset=%d", topic, record.offset())));
    }

    /// 重试耗尽的消转入死信 topic，并保留原始消息头与失败原因。
    private void deadLetter(String topic, MessageEnvelope envelope, Exception error) {
        String dlqTopic = topic + dlqSuffix;
        LOG.errorf(error, "Kafka 消息重试耗尽，转入死信 topic=%s dlq=%s messageId=%s",
                topic, dlqTopic, envelope.messageId());
        Map<String, String> headers = new HashMap<>(envelope.headers());
        headers.put("x-original-topic", topic);
        headers.put("x-error", error.getMessage() == null ? error.getClass().getName() : error.getMessage());
        headers.put("x-failed-at", Instant.now().toString());
        publisher.publish(new MessageEnvelope(envelope.messageId(), dlqTopic, envelope.payload(), headers, Instant.now()))
                .toCompletableFuture()
                .join();
    }

    @PreDestroy
    void close() {
        consumers.forEach(KafkaConsumer::close);
    }
}
