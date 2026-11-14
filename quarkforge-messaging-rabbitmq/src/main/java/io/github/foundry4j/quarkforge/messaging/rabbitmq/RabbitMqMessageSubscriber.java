package io.github.foundry4j.quarkforge.messaging.rabbitmq;

import io.github.foundry4j.quarkforge.messaging.MessageEnvelope;
import io.github.foundry4j.quarkforge.messaging.MessageRetryPolicy;
import io.github.foundry4j.quarkforge.messaging.MessageRetrySupport;
import io.github.foundry4j.quarkforge.messaging.MessageSubscriber;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.rabbitmq.QueueOptions;
import io.vertx.rabbitmq.RabbitMQClient;
import io.vertx.rabbitmq.RabbitMQMessage;
import io.vertx.rabbitmq.RabbitMQOptions;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/// ## RabbitMqMessageSubscriber
/// RabbitMQ 消费端，[MessageSubscriber] 的生产实现（替换本地默认实现）：
///
/// 1. 声明持久化队列 `queue-prefix + topic` 与死信队列 `队列名 + dlq-suffix`；
/// 2. 配置了 exchange 时按 `topic` 作为 routing key 绑定（与发布侧对称）；
/// 3. 手动确认：处理成功 `basicAck`；失败按统一的 [MessageRetryPolicy] 退避重试，
///    重试耗尽后发布到死信队列并 `basicAck`，不再原地重投；
/// 4. 死信发布失败时 `basicNack(requeue=true)`，避免消息丢失；
/// 5. 通过 `concurrency` 个消费者并行消费同一队列，`prefetch` 控制单消费者预取量。
///
/// 配置：
///
/// | 配置 | 默认值 | 说明 |
/// |------|--------|------|
/// | `quarkforge.messaging.rabbitmq.uri` | `amqp://guest:guest@localhost:5672` | 连接串 |
/// | `quarkforge.messaging.rabbitmq.exchange` | 空 | 交换机，空则使用默认交换机直发队列 |
/// | `quarkforge.messaging.rabbitmq.queue-prefix` | `quarkforge.` | 消费队列名前缀 |
/// | `quarkforge.messaging.rabbitmq.concurrency` | `1` | 每个队列的消费者数量 |
/// | `quarkforge.messaging.rabbitmq.prefetch` | `20` | 单消费者未确认消息上限 |
/// | `quarkforge.messaging.retry.max-retries` | `3` | 最大重试次数 |
/// | `quarkforge.messaging.retry.initial-backoff` | `500ms` | 首次退避 |
/// | `quarkforge.messaging.retry.multiplier` | `2.0` | 退避倍数 |
/// | `quarkforge.messaging.retry.max-backoff` | `10s` | 退避上限 |
/// | `quarkforge.messaging.dlq-suffix` | `.dlq` | 死信队列后缀 |
@ApplicationScoped
public class RabbitMqMessageSubscriber implements MessageSubscriber {

    private static final Logger LOG = Logger.getLogger(RabbitMqMessageSubscriber.class);

    @ConfigProperty(name = "quarkforge.messaging.rabbitmq.uri", defaultValue = "amqp://guest:guest@localhost:5672")
    String uri;

    @ConfigProperty(name = "quarkforge.messaging.rabbitmq.exchange", defaultValue = "")
    String exchange;

    @ConfigProperty(name = "quarkforge.messaging.rabbitmq.queue-prefix", defaultValue = "quarkforge.")
    String queuePrefix;

    @ConfigProperty(name = "quarkforge.messaging.rabbitmq.concurrency", defaultValue = "1")
    int concurrency;

    @ConfigProperty(name = "quarkforge.messaging.rabbitmq.prefetch", defaultValue = "20")
    int prefetch;

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

    private RabbitMQClient client;

    private MessageRetryPolicy retryPolicy;

    @PostConstruct
    void init() {
        retryPolicy = new MessageRetryPolicy(maxRetries, initialBackoff, backoffMultiplier, maxBackoff);
        RabbitMQOptions options = new RabbitMQOptions()
                .setUri(uri)
                .setAutomaticRecoveryEnabled(true);
        client = RabbitMQClient.create(vertx, options);
    }

    /// 建立订阅：声明队列与死信队列 → 按需绑定 → 设置预取 → 启动消费者。
    @Override
    public CompletionStage<Void> subscribe(String topic, Consumer<MessageEnvelope> handler) {
        String queue = queuePrefix + topic;
        String dlq = queue + dlqSuffix;
        Future<Void> setup = client.queueDeclare(queue, true, false, false)
                .compose(_ -> client.queueDeclare(dlq, true, false, false))
                .compose(_ -> bindIfNeeded(queue, topic))
                .compose(_ -> client.basicQos(0, prefetch, false))
                .compose(_ -> startConsumers(queue, topic, handler));
        return setup.toCompletionStage();
    }

    private Future<Void> bindIfNeeded(String queue, String topic) {
        if (exchange == null || exchange.isBlank()) {
            return Future.succeededFuture();
        }
        return client.queueBind(queue, exchange, topic);
    }

    @SuppressWarnings("rawtypes")
    private Future<Void> startConsumers(String queue, String topic, Consumer<MessageEnvelope> handler) {
        List<Future> started = new ArrayList<>();
        for (int i = 0; i < Math.max(1, concurrency); i++) {
            started.add(client.basicConsumer(queue, new QueueOptions().setAutoAck(false))
                    .map(consumer -> {
                        consumer.handler(delivery -> handle(topic, queue, delivery, handler));
                        return null;
                    }));
        }
        return CompositeFuture.all(started).mapEmpty();
    }

    private void handle(String topic, String queue, RabbitMQMessage delivery, Consumer<MessageEnvelope> handler) {
        long deliveryTag = delivery.envelope().getDeliveryTag();
        byte[] body = delivery.body() == null ? new byte[0] : delivery.body().getBytes();
        MessageEnvelope envelope = RabbitMqMessages.toEnvelope(topic, body, delivery.properties());

        vertx.executeBlocking(() -> {
            try {
                MessageRetrySupport.deliver(topic, envelope, handler, retryPolicy);
                return Outcome.SUCCESS;
            } catch (Exception e) {
                return deadLetter(topic, queue, envelope, e);
            }
        }, true).onComplete(result -> {
            Outcome outcome = result.succeeded() ? result.result() : Outcome.DEAD_LETTER_FAILED;
            if (outcome == Outcome.DEAD_LETTER_FAILED) {
                LOG.errorf(result.cause(), "RabbitMQ 死信发布失败，消息重新入队 topic=%s", topic);
                client.basicNack(deliveryTag, false, true);
            } else {
                client.basicAck(deliveryTag, false);
            }
        });
    }

    /// 重试耗尽的消发布到死信队列，保留原始消息头与失败原因。
    private Outcome deadLetter(String topic, String queue, MessageEnvelope envelope, Exception error) {
        String dlq = queue + dlqSuffix;
        LOG.errorf(error, "RabbitMQ 消息重试耗尽，转入死信 queue=%s messageId=%s", queue, envelope.messageId());
        Map<String, String> headers = new HashMap<>(envelope.headers());
        headers.put("x-original-topic", topic);
        headers.put("x-error", error.getMessage() == null ? error.getClass().getName() : error.getMessage());
        headers.put("x-failed-at", Instant.now().toString());
        MessageEnvelope dead = new MessageEnvelope(
                envelope.messageId(), dlq, envelope.payload(), headers, Instant.now());
        try {
            client.basicPublish("", dlq, RabbitMqMessages.toProperties(dead), Buffer.buffer(dead.payload()))
                    .toCompletionStage()
                    .toCompletableFuture()
                    .join();
            return Outcome.DEAD_LETTERED;
        } catch (Exception e) {
            LOG.errorf(e, "RabbitMQ 死信发布失败 dlq=%s", dlq);
            return Outcome.DEAD_LETTER_FAILED;
        }
    }

    @PreDestroy
    void close() {
        if (client != null) {
            client.stop();
        }
    }

    private enum Outcome {
        SUCCESS,
        DEAD_LETTERED,
        DEAD_LETTER_FAILED
    }
}
