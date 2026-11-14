package io.github.foundry4j.quarkforge.cache;

import io.github.foundry4j.quarkforge.cache.annotation.CachePut;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// ## CachePutInterceptorTest
/// `@CachePut` 的端到端行为：方法总是执行、返回值回填缓存、`#result` 取键、异常/null 不写缓存。
@QuarkusTest
class CachePutInterceptorTest {

    private static final Duration TTL = Duration.ofMinutes(10);

    @Inject
    LocalCacheService cache;

    @Inject
    AccountService service;

    @BeforeEach
    void tearDown() {
        service.reset();
        cache.evict("qf:cache:account:1");
        cache.evict("qf:cache:account:2");
        cache.evict("qf:cache:account:3");
        cache.evict("qf:cache:account:code:A001");
    }

    @Test
    void cachesReturnValueAfterEveryCall() {
        service.update(1, "alice");
        service.update(1, "bob");

        // 每次调用都执行方法，并把最新结果回填缓存
        assertEquals(2, service.invocations());
        assertEquals("bob", cache.get("qf:cache:account:1", Account.class, TTL).name());
    }

    @Test
    void resolvesKeyFromResult() {
        assertNotNull(service.updateByCode("A001", "carol"));

        Account cached = cache.get("qf:cache:account:code:A001", Account.class, TTL);
        assertEquals("A001", cached.code());
        assertEquals("carol", cached.name());
    }

    @Test
    void doesNotCacheWhenMethodThrows() {
        assertThrows(IllegalStateException.class, () -> service.fail(2));

        assertNull(cache.get("qf:cache:account:2", Account.class, TTL));
    }

    @Test
    void doesNotCacheNullResult() {
        assertNull(service.nullResult(3));

        assertNull(cache.get("qf:cache:account:3", Account.class, TTL));
    }

    @Test
    void updatesExistingCachedValue() {
        service.update(1, "alice");
        assertEquals("alice", cache.get("qf:cache:account:1", Account.class, TTL).name());

        service.update(1, "dave");

        assertEquals("dave", cache.get("qf:cache:account:1", Account.class, TTL).name());
    }

    record Account(long id, String code, String name) {
    }

    @ApplicationScoped
    static class AccountService {

        private final AtomicInteger invocations = new AtomicInteger();

        @CachePut(key = "#p0", namespace = "account", ttl = "PT10M")
        public Account update(long id, String name) {
            invocations.incrementAndGet();
            return new Account(id, "A" + id, name);
        }

        @CachePut(key = "'account:code:' + #result.code", ttl = "PT10M")
        public Account updateByCode(String code, String name) {
            invocations.incrementAndGet();
            return new Account(9, code, name);
        }

        @CachePut(key = "#p0", namespace = "account")
        public Account fail(long id) {
            invocations.incrementAndGet();
            throw new IllegalStateException("boom");
        }

        @CachePut(key = "#p0", namespace = "account")
        public Account nullResult(long id) {
            invocations.incrementAndGet();
            return null;
        }

        public int invocations() {
            return invocations.get();
        }

        public void reset() {
            invocations.set(0);
        }
    }
}
