package io.github.foundry4j.quarkforge.web.exception;

import io.github.foundry4j.quarkforge.core.api.Result;
import jakarta.persistence.OptimisticLockException;
import jakarta.transaction.RollbackException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// ## OptimisticLockExceptionMapperTest
/// 乐观锁冲突的响应状态、错误码与文案本地化。
class OptimisticLockExceptionMapperTest {

    @Test
    void mapsOptimisticLockToConflict() {
        Response response = mapper(Locale.SIMPLIFIED_CHINESE).toResponse(new OptimisticLockException("stale"));

        assertEquals(409, response.getStatus());
        Result<?> result = (Result<?>) response.getEntity();
        assertEquals(40901, result.code());
        assertTrue(result.message().contains("刷新"));
    }

    @Test
    void mapsOptimisticLockWithEnglishLocale() {
        Response response = mapper(Locale.ENGLISH).toResponse(new OptimisticLockException("stale"));

        Result<?> result = (Result<?>) response.getEntity();
        assertTrue(result.message().contains("refresh"));
    }

    @Test
    void mapsWrappedOptimisticLockFromRollback() {
        RollbackException rollback = new RollbackException("tx failed");
        rollback.initCause(new IllegalStateException("tx failed", new OptimisticLockException("stale")));

        Response response = rollbackMapper(Locale.SIMPLIFIED_CHINESE).toResponse(rollback);

        assertEquals(409, response.getStatus());
        assertEquals(40901, ((Result<?>) response.getEntity()).code());
    }

    @Test
    void keepsOtherRollbacksAsServerError() {
        RollbackException rollback = new RollbackException("boom");
        rollback.initCause(new IllegalStateException("boom"));
        Response response = rollbackMapper(Locale.SIMPLIFIED_CHINESE).toResponse(rollback);

        assertEquals(500, response.getStatus());
        assertEquals(50000, ((Result<?>) response.getEntity()).code());
    }

    private OptimisticLockExceptionMapper mapper(Locale locale) {
        return new OptimisticLockExceptionMapper() {
            @Override
            protected Locale requestLocale() {
                return locale;
            }
        };
    }

    private OptimisticLockRollbackExceptionMapper rollbackMapper(Locale locale) {
        return new OptimisticLockRollbackExceptionMapper() {
            @Override
            protected Locale requestLocale() {
                return locale;
            }
        };
    }
}
