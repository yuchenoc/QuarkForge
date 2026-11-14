package io.github.foundry4j.quarkforge.data;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// ## VersionedEntityTest
/// `@Version` 在每次更新后自增；携带过期版本号提交会被拒绝。
@QuarkusTest
class VersionedEntityTest {

    @Inject
    EntityManager em;

    @BeforeEach
    void clean() {
        QuarkusTransaction.requiringNew().run(() ->
                em.createQuery("delete from VersionedStock").executeUpdate());
    }

    @Test
    void incrementsVersionOnEachUpdate() {
        Long id = seed();

        updateQuantity(id, 2);
        updateQuantity(id, 3);

        VersionedStock reloaded = QuarkusTransaction.requiringNew()
                .call(() -> em.find(VersionedStock.class, id));
        assertEquals(3, reloaded.quantity);
        assertEquals(2, reloaded.version);
    }

    @Test
    void rejectsStaleVersion() {
        Long id = seed();
        VersionedStock stale = QuarkusTransaction.requiringNew()
                .call(() -> em.find(VersionedStock.class, id));

        // 另一个请求先把版本推进到 1
        updateQuantity(id, 5);

        // 旧版本实体再提交，版本号不匹配
        stale.quantity = 99;
        Exception error = assertThrows(Exception.class,
                () -> QuarkusTransaction.requiringNew().run(() -> em.merge(stale)));

        assertTrue(isOptimisticLock(error), "应抛出乐观锁异常，实际: " + error);
    }

    private Long seed() {
        return QuarkusTransaction.requiringNew().call(() -> {
            VersionedStock stock = new VersionedStock();
            stock.name = "apple";
            stock.quantity = 1;
            em.persist(stock);
            return stock.id;
        });
    }

    private void updateQuantity(Long id, int quantity) {
        QuarkusTransaction.requiringNew().run(() -> {
            VersionedStock stock = em.find(VersionedStock.class, id);
            stock.quantity = quantity;
        });
    }

    private static boolean isOptimisticLock(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            String type = current.getClass().getName();
            if (type.contains("OptimisticLock") || type.contains("StaleObject")) {
                return true;
            }
        }
        return false;
    }
}
