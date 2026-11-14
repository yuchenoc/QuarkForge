package io.github.foundry4j.quarkforge.web.exception;

import io.github.foundry4j.quarkforge.core.api.CommonErrorCode;
import io.github.foundry4j.quarkforge.core.api.Result;
import io.github.foundry4j.quarkforge.core.i18n.I18n;
import io.github.foundry4j.quarkforge.web.i18n.Locales;
import io.quarkus.logging.Log;
import jakarta.transaction.RollbackException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.Locale;

/// ## OptimisticLockRollbackExceptionMapper
/// `@Transactional` 方法提交时，Hibernate 的乐观锁冲突可能被 JTA 包装成
/// [RollbackException]；这里检查异常链，命中乐观锁则返回 409，其余回滚仍为 500。
@Provider
public class OptimisticLockRollbackExceptionMapper implements ExceptionMapper<RollbackException> {

    @Context
    HttpHeaders headers;

    @Override
    public Response toResponse(RollbackException exception) {
        Locale locale = requestLocale();
        if (OptimisticLockExceptionMapper.isOptimisticLock(exception)) {
            Log.warnf("事务提交时发生乐观锁冲突: %s", exception.getMessage());
            return OptimisticLockExceptionMapper.conflictResponse(locale);
        }
        Log.warnf(exception, "事务提交失败");
        return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(Result.error(CommonErrorCode.INTERNAL_ERROR,
                        I18n.message(CommonErrorCode.INTERNAL_ERROR, locale)))
                .type(MediaType.APPLICATION_JSON)
                .build();
    }

    /// 当前请求语言，抽成方法便于单元测试覆写。
    protected Locale requestLocale() {
        return Locales.resolve(headers);
    }
}
