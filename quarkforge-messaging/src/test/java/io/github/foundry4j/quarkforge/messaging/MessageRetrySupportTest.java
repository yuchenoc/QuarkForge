package io.github.foundry4j.quarkforge.messaging;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// ## MessageRetrySupportTest
/// 重试次数与耗尽后的异常传递。
class MessageRetrySupportTest {

    private final MessageEnvelope envelope = MessageEnvelope.of("order.created", "{}");

    @Test
    void retriesUntilHandlerSucceeds() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Consumer<MessageEnvelope> handler = _ -> {
            if (calls.incrementAndGet() < 3) {
                throw new IllegalStateException("fail " + calls.get());
            }
        };

        MessageRetrySupport.deliver("order.created", envelope, handler, policy(3));

        assertEquals(3, calls.get());
    }

    @Test
    void throwsLastErrorWhenRetriesExhausted() {
        AtomicInteger calls = new AtomicInteger();
        Consumer<MessageEnvelope> handler = _ -> {
            calls.incrementAndGet();
            throw new IllegalStateException("always fails");
        };

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> MessageRetrySupport.deliver("order.created", envelope, handler, policy(2)));

        assertEquals("always fails", error.getMessage());
        assertEquals(3, calls.get());
    }

    @Test
    void noRetryCallsHandlerOnce() {
        AtomicInteger calls = new AtomicInteger();

        assertThrows(IllegalStateException.class, () -> MessageRetrySupport.deliver(
                "order.created", envelope, _ -> {
                    calls.incrementAndGet();
                    throw new IllegalStateException("boom");
                }, MessageRetryPolicy.noRetry()));

        assertEquals(1, calls.get());
    }

    private static MessageRetryPolicy policy(int retries) {
        return new MessageRetryPolicy(retries, Duration.ofMillis(1), 1.0, Duration.ofMillis(1));
    }
}
