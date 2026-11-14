package io.github.foundry4j.quarkforge.cache.annotation;

import jakarta.enterprise.util.Nonbinding;
import jakarta.interceptor.InterceptorBinding;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/// ## CachePut
/// 更新后回填缓存：方法**总是执行**，成功返回后把结果写入缓存，保持缓存与数据库一致。
///
/// ```java
/// @CachePut(key = "#p0", namespace = "user", ttl = "PT10M")
/// public User updateUser(long id, UserPatch patch) { ... }
///
/// // 键来自返回值：更新前并不知道新的业务主键
/// @CachePut(key = "'user:code:' + #result.code")
/// public User updateByCode(String code, UserPatch patch) { ... }
/// ```
///
/// 与 [Cacheable] 的区别：`@Cacheable` 命中缓存时不会执行方法，`@CachePut` 每次都会执行并覆盖缓存。
/// 返回 `null` 时不写缓存；方法抛异常时也不写缓存。
///
/// 可重复标注，一次调用回填多个键。
@Inherited
@Documented
@InterceptorBinding
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(CachePut.List.class)
public @interface CachePut {

    /// 缓存键表达式，语法同 [Cacheable.key]，额外支持 `#result`（方法返回值）及其属性。
    @Nonbinding
    String key() default "";

    /// 缓存 TTL（ISO-8601 Duration）；留空使用 `quarkforge.cache.local.default-ttl`。
    @Nonbinding
    String ttl() default "";

    /// 缓存键的 namespace 前缀。
    @Nonbinding
    String namespace() default "";

    @Documented
    @Inherited
    @Target({ElementType.METHOD, ElementType.TYPE})
    @Retention(RetentionPolicy.RUNTIME)
    @interface List {
        CachePut[] value();
    }
}
