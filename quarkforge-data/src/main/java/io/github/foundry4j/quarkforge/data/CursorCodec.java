package io.github.foundry4j.quarkforge.data;

import io.github.foundry4j.quarkforge.core.api.CommonErrorCode;
import io.github.foundry4j.quarkforge.core.exception.BizException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.UUID;

/// ## CursorCodec
/// 游标编解码：`base64url(排序字段值) + "|" + 主键`。
///
/// 游标对客户端不透明，只用于服务端定位“上一页最后一条”的位置；
/// 解码失败统一抛出参数错误，避免非法游标进入 SQL。
final class CursorCodec {

    private CursorCodec() {
    }

    static String encode(Object value, Object id) {
        String raw = String.valueOf(value) + "|" + id;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /// 解码并按排序字段类型还原比较值。
    static Cursor decode(String cursor, Class<?> valueType) {
        String raw;
        try {
            raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new BizException(CommonErrorCode.PARAM_ERROR, "无效的分页游标");
        }
        int split = raw.lastIndexOf('|');
        if (split <= 0) {
            throw new BizException(CommonErrorCode.PARAM_ERROR, "无效的分页游标");
        }
        try {
            return new Cursor(parse(raw.substring(0, split), valueType), Long.parseLong(raw.substring(split + 1)));
        } catch (RuntimeException e) {
            throw new BizException(CommonErrorCode.PARAM_ERROR, "无效的分页游标");
        }
    }

    private static Object parse(String raw, Class<?> type) {
        if (type == String.class || type == CharSequence.class) {
            return raw;
        }
        if (type == Long.class || type == long.class) {
            return Long.valueOf(raw);
        }
        if (type == Integer.class || type == int.class) {
            return Integer.valueOf(raw);
        }
        if (type == Short.class || type == short.class) {
            return Short.valueOf(raw);
        }
        if (type == Double.class || type == double.class) {
            return Double.valueOf(raw);
        }
        if (type == Float.class || type == float.class) {
            return Float.valueOf(raw);
        }
        if (type == Boolean.class || type == boolean.class) {
            return Boolean.valueOf(raw);
        }
        if (type == BigDecimal.class) {
            return new BigDecimal(raw);
        }
        if (type == UUID.class) {
            return UUID.fromString(raw);
        }
        if (type == Instant.class) {
            return Instant.parse(raw);
        }
        if (type == LocalDate.class) {
            return LocalDate.parse(raw);
        }
        if (type == LocalDateTime.class) {
            return LocalDateTime.parse(raw);
        }
        if (type == OffsetDateTime.class) {
            return OffsetDateTime.parse(raw);
        }
        throw new BizException(CommonErrorCode.PARAM_ERROR, "不支持的游标排序字段类型: " + type.getName());
    }

    record Cursor(Object value, long id) {
    }
}
