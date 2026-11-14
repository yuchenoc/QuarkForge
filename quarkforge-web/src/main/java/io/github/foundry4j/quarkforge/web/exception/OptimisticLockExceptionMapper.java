package io.github.foundry4j.quarkforge.web.exception;

import io.github.foundry4j.quarkforge.core.api.CommonErrorCode;
import io.github.foundry4j.quarkforge.core.api.Result;
import io.github.foundry4j.quarkforge.core.i18n.I18n;
import io.github.foundry4j.quarkforge.web.i18n.Locales;
import io.quarkus.logging.Log;
import jakarta.persistence.OptimisticLockException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.Locale;

/// ## OptimisticLockExceptionMapper
/// 将 JPA 乐观锁冲突转换为 HTTP 409 + 错误码 `40901`。
///
/// 实体继承 [io.github.foundry4j.quarkforge.data.VersionedEntity]（`@Version`）后，
/// 并发更新同一行会抛出 `OptimisticLockException`，前端据此提示“刷新后重试”。
/// 文案按 `Accept-Language` 本地化，取自 `i18n/messages*.properties` 的 `error.40901`。
@Provider
public class OptimisticLockExceptionMapper implements ExceptionMapper<OptimisticLockException> {

    @Context
    HttpHeaders headers;

    @Override
    public Response toResponse(OptimisticLockException exception) {
        Log.warnf("乐观锁冲突: %s", exception.getMessage());
        return conflictResponse(requestLocale());
    }

    /// 当前请求语言，抽成方法便于单元测试覆写。
    protected Locale requestLocale() {
        return Locales.resolve(headers);
    }

    static Response conflictResponse(Locale locale) {
        return Response.status(Response.Status.CONFLICT)
                .entity(Result.error(CommonErrorCode.OPTIMISTIC_LOCK,
                        I18n.message(CommonErrorCode.OPTIMISTIC_LOCK, locale)))
                .type(MediaType.APPLICATION_JSON)
                .build();
    }

    /// 在异常链中查找乐观锁异常（事务提交时可能被包装为 `RollbackException`）。
    static boolean isOptimisticLock(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof OptimisticLockException) {
                return true;
            }
        }
        return false;
    }
}
