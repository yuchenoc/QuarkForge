package io.github.foundry4j.quarkforge.data;

import io.github.foundry4j.quarkforge.core.api.CursorPage;
import io.github.foundry4j.quarkforge.core.exception.BizException;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// ## CursorPaginationTest
/// keyset 分页：完整遍历、方向、同值兜底、非法参数。
@QuarkusTest
class CursorPaginationTest {

    @Inject
    CursorStockRepository repository;

    @Inject
    EntityManager em;

    @BeforeEach
    void clean() {
        QuarkusTransaction.requiringNew().run(() ->
                em.createQuery("delete from VersionedStock").executeUpdate());
    }

    @Test
    void walksAllRowsByIdAscending() {
        List<Long> expected = seed(List.of("a", "b", "c", "d", "e"));

        CursorPage<VersionedStock> page = findPage("id", true, null, 2);
        List<Long> actual = new ArrayList<>(ids(page.records()));
        assertTrue(page.hasMore());

        while (page.hasMore()) {
            page = findPage("id", true, page.nextCursor(), 2);
            actual.addAll(ids(page.records()));
        }

        assertEquals(expected, actual);
        assertFalse(page.hasMore());
        assertNull(page.nextCursor());
    }

    @Test
    void descendingByFieldHandlesDuplicateValues() {
        // 相同 name 时用 id 作为二次排序，翻页不重不漏
        List<Long> expected = seed(List.of("a", "a", "b", "b", "c", "c"));

        CursorPage<VersionedStock> page = findPage("name", false, null, 2);
        List<VersionedStock> all = new ArrayList<>(page.records());
        while (page.hasMore()) {
            page = findPage("name", false, page.nextCursor(), 2);
            all.addAll(page.records());
        }

        Set<Long> distinct = new HashSet<>(ids(all));
        assertEquals(expected.size(), all.size());
        assertEquals(expected.size(), distinct.size());
        for (int i = 1; i < all.size(); i++) {
            String previous = all.get(i - 1).name;
            String current = all.get(i).name;
            assertTrue(previous.compareTo(current) >= 0, "name 应保持倒序: " + previous + " -> " + current);
        }
    }

    @Test
    void rejectsUnsupportedSortField() {
        BizException error = assertThrows(BizException.class,
                () -> findPage("notExists", true, null, 10));

        assertEquals(40000, error.errorCode().code());
    }

    @Test
    void rejectsMalformedCursor() {
        BizException error = assertThrows(BizException.class,
                () -> findPage("id", true, "not-a-cursor", 10));

        assertEquals(40000, error.errorCode().code());
    }

    private CursorPage<VersionedStock> findPage(String field, boolean ascending, String cursor, int size) {
        return QuarkusTransaction.requiringNew()
                .call(() -> repository.findPageAfter(field, ascending, cursor, size));
    }

    private List<Long> seed(List<String> names) {
        return QuarkusTransaction.requiringNew().call(() -> {
            List<Long> ids = new ArrayList<>();
            for (String name : names) {
                VersionedStock stock = new VersionedStock();
                stock.name = name;
                stock.quantity = 1;
                em.persist(stock);
                ids.add(stock.id);
            }
            return ids;
        });
    }

    private static List<Long> ids(List<VersionedStock> stocks) {
        return stocks.stream().map(stock -> stock.id).toList();
    }
}
