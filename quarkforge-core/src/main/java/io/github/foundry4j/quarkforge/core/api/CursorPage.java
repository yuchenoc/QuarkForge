package io.github.foundry4j.quarkforge.core.api;

import java.io.Serializable;
import java.util.List;
import java.util.function.Function;

/// ## CursorPage
/// 游标分页结果，用于深分页场景（`limit/offset` 越翻越慢，游标保持稳定开销）。
///
/// 客户端把 `nextCursor` 原样回传即可继续向后翻页；为 `null` 表示没有更多数据。
///
/// ```json
/// { "records": [...], "nextCursor": "MTc2MDAwMDAwMDAwMHwxMDAx", "hasMore": true }
/// ```
///
/// @param records    当前页数据
/// @param nextCursor 下一页游标，null 表示已到末尾
/// @param hasMore    是否还有下一页
/// @param <T>        记录类型
public record CursorPage<T>(List<T> records, String nextCursor, boolean hasMore) implements Serializable {

    public static <T> CursorPage<T> of(List<T> records, String nextCursor, boolean hasMore) {
        return new CursorPage<>(records, nextCursor, hasMore);
    }

    /// 空页。
    public static <T> CursorPage<T> empty() {
        return new CursorPage<>(List.of(), null, false);
    }

    /// 对当前页数据做类型转换，游标信息保持不变。
    public <R> CursorPage<R> map(Function<T, R> mapper) {
        return new CursorPage<>(records.stream().map(mapper).toList(), nextCursor, hasMore);
    }
}
