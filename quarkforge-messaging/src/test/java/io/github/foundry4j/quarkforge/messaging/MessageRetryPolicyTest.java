package io.github.foundry4j.quarkforge.messaging;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// ## MessageRetryPolicyTest
/// 退避时间计算与参数校验。
class MessageRetryPolicyTest {

    @Test
    void backoffGrowsExponentiallyAndCaps() {
        MessageRetryPolicy policy = new MessageRetryPolicy(3, Duration.ofMillis(500), 2.0, Duration.ofMillis(1500));

        assertEquals(Duration.ofMillis(500), policy.backoff(1));
        assertEquals(Duration.ofMillis(1000), policy.backoff(2));
        assertEquals(Duration.ofMillis(1500), policy.backoff(3));
    }

    @Test
    void noRetryHasNoBackoff() {
        MessageRetryPolicy policy = MessageRetryPolicy.noRetry();

        assertEquals(0, policy.maxRetries());
        assertEquals(Duration.ZERO, policy.backoff(1));
    }

    @Test
    void rejectsInvalidConfiguration() {
        assertThrows(IllegalArgumentException.class,
                () -> new MessageRetryPolicy(-1, Duration.ofMillis(1), 2.0, Duration.ofMillis(10)));
        assertThrows(IllegalArgumentException.class,
                () -> new MessageRetryPolicy(1, Duration.ofMillis(-1), 2.0, Duration.ofMillis(10)));
        assertThrows(IllegalArgumentException.class,
                () -> new MessageRetryPolicy(1, Duration.ofMillis(1), 0.5, Duration.ofMillis(10)));
    }
}
