package io.github.foundry4j.quarkforge.cache.annotation;

import io.github.foundry4j.quarkforge.core.exception.BizException;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// ## CacheKeyResolver
/// 解析 [Cacheable] / [CacheEvict] / [CachePut] 的 `key` 表达式。
///
/// 支持的表达式子集：
///
/// | 形式 | 含义 |
/// |------|------|
/// | `'text'` | 字面量字符串 |
/// | `#p0` `#p1` ... | 方法参数 |
/// | `#p0.field` | 参数的 Bean 属性（读 `getField()` 或直接 `field`） |
/// | `#p0.method()` | 参数的零参方法 |
/// | `#root.methodName` | 当前方法名 |
/// | `#result` `#result.id` | 方法返回值及其属性（仅 [CachePut]，方法执行后可用） |
/// | 空字符串 | 默认键 = `类FQN#方法名#参数摘要` |
///
/// 解析失败时包装为 [BizException] 抛出。
public final class CacheKeyResolver {

    private static final Pattern TOKEN = Pattern.compile(
            "#(p\\d+|args\\[\\d+]|root\\.methodName|result)((?:\\.\\w+(?:\\(\\))?)*)");
    private static final Pattern SINGLE_QUOTED = Pattern.compile("^'(.*)'$", Pattern.DOTALL);

    private CacheKeyResolver() {
    }

    /// 解析 key 表达式。
    ///
    /// @param expression 注解里的 `key` 字符串（可为空）
    /// @param method     当前被拦截的方法
    /// @param args       方法实参
    /// @param namespace  前缀（可为空）
    /// @return 最终缓存键
    public static String resolve(String expression, Method method, Object[] args, String namespace) {
        return resolve(expression, method, args, namespace, null);
    }

    /// 解析 key 表达式，支持 `#result` 引用方法返回值（仅 [CachePut] 使用）。
    ///
    /// @param expression 注解里的 `key` 字符串（可为空）
    /// @param method     当前被拦截的方法
    /// @param args       方法实参
    /// @param namespace  前缀（可为空）
    /// @param result     方法返回值，可为 null
    /// @return 最终缓存键
    public static String resolve(String expression, Method method, Object[] args, String namespace, Object result) {
        String value = expression == null ? "" : expression.trim();
        String inner;
        if (value.isEmpty()) {
            inner = defaultKey(method, args);
        } else {
            Matcher quoted = SINGLE_QUOTED.matcher(value);
            if (quoted.matches()) {
                inner = quoted.group(1);
            } else {
                inner = evaluate(value, method, args, result);
            }
        }
        if (namespace != null && !namespace.isBlank()) {
            return "qf:cache:" + namespace + ":" + inner;
        }
        return "qf:cache:" + inner;
    }

    private static String evaluate(String expression, Method method, Object[] args, Object result) {
        // 按 + 拆分（支持字符串拼接），再逐段求值后直接拼接
        String[] parts = expression.split("\\+");
        StringBuilder out = new StringBuilder(128);
        for (String rawPart : parts) {
            String segment = rawPart.trim();
            out.append(evaluateSegment(segment, method, args, result));
        }
        return out.toString();
    }

    private static String evaluateSegment(String segment, Method method, Object[] args, Object result) {
        Matcher quoted = SINGLE_QUOTED.matcher(segment);
        if (quoted.matches()) {
            return quoted.group(1);
        }
        Matcher m = TOKEN.matcher(segment);
        if (m.matches()) {
            String head = m.group(1);
            String tail = m.group(2);
            Object base = resolveHead(head, method, args, result);
            Object resolved = resolveChained(base, tail);
            return resolved == null ? "null" : resolved.toString();
        }
        // 纯字面量（比如用户写的 namespace 片段）
        return segment;
    }

    private static Object resolveHead(String head, Method method, Object[] args, Object result) {
        if (head.startsWith("p")) {
            int idx = Integer.parseInt(head.substring(1));
            return idx < args.length ? args[idx] : null;
        }
        if (head.startsWith("args")) {
            int idxEnd = head.indexOf(']');
            int idx = Integer.parseInt(head.substring(4, idxEnd));
            return idx < args.length ? args[idx] : null;
        }
        if ("root.methodName".equals(head)) {
            return method.getName();
        }
        if ("result".equals(head)) {
            return result;
        }
        throw new BizException(io.github.foundry4j.quarkforge.core.api.CommonErrorCode.PARAM_ERROR,
                "无法解析缓存键表达式: #" + head);
    }

    private static Object resolveChained(Object base, String tail) {
        if (base == null) {
            return null;
        }
        if (tail == null || tail.isEmpty()) {
            return base;
        }
        // tail 形如 .field 或 .method() 或 .field.nested()
        String[] parts = tail.split("\\.");
        for (String part : parts) {
            if (part.isEmpty()) continue;
            boolean invoke = part.endsWith("()");
            String name = invoke ? part.substring(0, part.length() - 2) : part;
            base = invoke ? invokeZeroArg(base, name) : readProperty(base, name);
            if (base == null) {
                return null;
            }
        }
        return base;
    }

    private static Object readProperty(Object target, String property) {
        // 1. 尝试 getXxx / isXxx
        String getter = "get" + Character.toUpperCase(property.charAt(0)) + property.substring(1);
        try {
            return invoke(target.getClass().getMethod(getter), target);
        } catch (NoSuchMethodException _) {
        } catch (Exception e) {
            throw new BizException(io.github.foundry4j.quarkforge.core.api.CommonErrorCode.PARAM_ERROR,
                    "读取缓存键属性失败: " + property);
        }
        String isser = "is" + Character.toUpperCase(property.charAt(0)) + property.substring(1);
        try {
            return invoke(target.getClass().getMethod(isser), target);
        } catch (NoSuchMethodException _) {
        } catch (Exception e) {
            throw new BizException(io.github.foundry4j.quarkforge.core.api.CommonErrorCode.PARAM_ERROR,
                    "读取缓存键属性失败: " + property);
        }
        // 2. 直接读字段
        try {
            var field = target.getClass().getField(property);
            return field.get(target);
        } catch (NoSuchFieldException _) {
        } catch (Exception e) {
            throw new BizException(io.github.foundry4j.quarkforge.core.api.CommonErrorCode.PARAM_ERROR,
                    "读取缓存键字段失败: " + property);
        }
        // 3. record 访问器（可能是包级私有的 DTO）
        try {
            return invoke(target.getClass().getMethod(property), target);
        } catch (NoSuchMethodException _) {
        } catch (Exception e) {
            throw new BizException(io.github.foundry4j.quarkforge.core.api.CommonErrorCode.PARAM_ERROR,
                    "读取缓存键 record 访问器失败: " + property);
        }
        throw new BizException(io.github.foundry4j.quarkforge.core.api.CommonErrorCode.PARAM_ERROR,
                "无法访问属性: " + property + " on " + target.getClass().getName());
    }

    private static Object invokeZeroArg(Object target, String method) {
        try {
            return invoke(target.getClass().getMethod(method), target);
        } catch (NoSuchMethodException e) {
            throw new BizException(io.github.foundry4j.quarkforge.core.api.CommonErrorCode.PARAM_ERROR,
                    "找不到方法: " + method);
        } catch (Exception e) {
            throw new BizException(io.github.foundry4j.quarkforge.core.api.CommonErrorCode.PARAM_ERROR,
                    "调用方法失败: " + method);
        }
    }

    /// 允许访问包级私有类型上的公共方法（例如测试内定义的 record）。
    private static Object invoke(Method method, Object target) throws Exception {
        method.trySetAccessible();
        return method.invoke(target);
    }

    private static String defaultKey(Method method, Object[] args) {
        StringBuilder sb = new StringBuilder();
        sb.append(method.getDeclaringClass().getName()).append('#').append(method.getName());
        if (args != null && args.length > 0) {
            sb.append('(').append(Arrays.deepToString(args)).append(')');
        }
        return sb.toString();
    }
}
