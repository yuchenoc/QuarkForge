package io.github.foundry4j.quarkforge.cache.annotation;

import io.github.foundry4j.quarkforge.cache.CacheService;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.lang.reflect.Method;
import java.time.Duration;

/// ## CachePutInterceptor
/// [CachePut] 注解拦截器：先执行方法，成功返回后用返回值回填缓存。
///
/// 键表达式在方法执行后解析，因此支持 `#result`；返回 `null` 或抛异常时不写缓存。
/// 优先级位于 [CacheInterceptor]（+250）与 [CacheEvictInterceptor]（+260）之间，
/// 与 `@CacheEvict` 同时使用时，失效发生在回填之后。
@CachePut
@Interceptor
@Priority(Interceptor.Priority.PLATFORM_BEFORE + 255)
public class CachePutInterceptor {

    @Inject
    CacheService cache;

    @Inject
    @ConfigProperty(name = "quarkforge.cache.local.default-ttl", defaultValue = "PT5M")
    Duration defaultTtl;

    @AroundInvoke
    Object aroundInvoke(InvocationContext context) throws Exception {
        CachePut[] bindings = resolveBindings(context);
        Object result;
        try {
            result = context.proceed();
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        if (result == null) {
            return null;
        }
        for (CachePut put : bindings) {
            String key = CacheKeyResolver.resolve(put.key(), context.getMethod(), context.getParameters(),
                    put.namespace(), result);
            cache.put(key, result, parseTtl(put.ttl()));
        }
        return result;
    }

    private CachePut[] resolveBindings(InvocationContext context) {
        CachePut.List list = context.getInterceptorBinding(CachePut.List.class);
        if (list != null) {
            return list.value();
        }
        CachePut binding = context.getInterceptorBinding(CachePut.class);
        if (binding != null) {
            return new CachePut[]{binding};
        }
        Method method = context.getMethod();
        CachePut[] onMethod = method.getAnnotationsByType(CachePut.class);
        if (onMethod.length > 0) {
            return onMethod;
        }
        return method.getDeclaringClass().getAnnotationsByType(CachePut.class);
    }

    private Duration parseTtl(String ttl) {
        if (ttl == null || ttl.isBlank()) {
            return defaultTtl;
        }
        try {
            return Duration.parse(ttl.trim());
        } catch (Exception e) {
            throw new IllegalArgumentException("无效的 ttl 值: " + ttl, e);
        }
    }
}
