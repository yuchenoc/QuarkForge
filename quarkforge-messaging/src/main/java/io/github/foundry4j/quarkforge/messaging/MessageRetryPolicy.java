package io.github.foundry4j.quarkforge.messaging;

import java.time.Duration;

/// ## MessageRetryPolicy
/// 统一的消息消费重试策略：最大重试次数 + 指数退避，供各传输层共用。
///
/// 默认配置（Kafka / RabbitMQ 共用）：
///
/// ```properties
/// quarkforge.messaging.retry.max-retries=3
/// quarkforge.messaging.retry.initial-backoff=500ms
/// quarkforge.messaging.retry.multiplier=2.0
/// quarkforge.messaging.retry.max-backoff=10s
/// quarkforge.messaging.dlq-suffix=.dlq
/// ```
///
/// 重试耗尽后消息转入死信（`destination + dlq-suffix`），不会静默丢弃。
///
/// @param maxRetries     最大重试次数（首次处理不算一次重试）
/// @param initialBackoff 第一次重试的等待时间
/// @param multiplier     退避倍数
/// @param maxBackoff     单次退避上限
public record MessageRetryPolicy(int maxRetries, Duration initialBackoff, double multiplier, Duration maxBackoff) {

    public MessageRetryPolicy {
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries 不能为负");
        }
        if (initialBackoff == null || initialBackoff.isNegative()) {
            throw new IllegalArgumentException("initialBackoff 不能为空或负数");
        }
        if (multiplier < 1) {
            throw new IllegalArgumentException("multiplier 不能小于 1");
        }
        if (maxBackoff == null || maxBackoff.isNegative()) {
            throw new IllegalArgumentException("maxBackoff 不能为空或负数");
        }
    }

    /// 不重试，首次失败即进入死信。
    public static MessageRetryPolicy noRetry() {
        return new MessageRetryPolicy(0, Duration.ZERO, 1.0, Duration.ZERO);
    }

    /// 第 `retry` 次重试（从 1 开始）的等待时间。
    public Duration backoff(int retry) {
        if (retry <= 0 || initialBackoff.isZero()) {
            return Duration.ZERO;
        }
        double millis = initialBackoff.toMillis() * Math.pow(multiplier, retry - 1);
        return Duration.ofMillis(Math.min((long) millis, maxBackoff.toMillis()));
    }
}
