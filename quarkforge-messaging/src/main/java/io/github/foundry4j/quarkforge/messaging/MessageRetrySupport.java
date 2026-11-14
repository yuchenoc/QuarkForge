package io.github.foundry4j.quarkforge.messaging;

import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.function.Consumer;

/// ## MessageRetrySupport
/// 按 [MessageRetryPolicy] 执行带退避的消费重试，各传输层共用。
///
/// 本方法通过 `Thread.sleep` 等待退避时间，**必须在工作线程中调用**
/// （Kafka / RabbitMQ 消费者均通过 `Vertx.executeBlocking` 执行，避免阻塞事件循环）。
public final class MessageRetrySupport {

    private static final Logger LOG = Logger.getLogger(MessageRetrySupport.class);

    private MessageRetrySupport() {
    }

    /// 处理消息，失败时按策略重试；重试耗尽后抛出最后一次异常，由调用方转入死信。
    public static void deliver(String topic, MessageEnvelope envelope, Consumer<MessageEnvelope> handler,
                               MessageRetryPolicy policy) throws Exception {
        int attempt = 0;
        while (true) {
            try {
                handler.accept(envelope);
                return;
            } catch (Exception e) {
                if (attempt >= policy.maxRetries()) {
                    throw e;
                }
                attempt++;
                Duration backoff = policy.backoff(attempt);
                LOG.warnf("消息处理失败，%dms 后第 %d/%d 次重试 topic=%s messageId=%s: %s",
                        backoff.toMillis(), attempt, policy.maxRetries(), topic, envelope.messageId(), e.getMessage());
                if (!backoff.isZero()) {
                    Thread.sleep(backoff.toMillis());
                }
            }
        }
    }
}
