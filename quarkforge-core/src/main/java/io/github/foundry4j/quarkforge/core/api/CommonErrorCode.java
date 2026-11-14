package io.github.foundry4j.quarkforge.core.api;

/// ## CommonErrorCode
/// 框架通用错误码，段位与 HTTP 状态码语义对齐：
///
/// | 段位     | 含义         |
/// |----------|--------------|
/// | 200      | 成功         |
/// | 40000    | 参数错误     |
/// | 40100    | 未认证       |
/// | 40300    | 无权限       |
/// | 40400    | 资源不存在   |
/// | 42900    | 触发限流     |
/// | 50000    | 服务内部错误 |
public enum CommonErrorCode implements ErrorCode {

    SUCCESS(200, "success"),
    PARAM_ERROR(40000, "请求参数错误"),
    API_VERSION_UNSUPPORTED(40001, "不支持的 API 版本"),
    UNAUTHORIZED(40100, "未登录或登录已过期"),
    FORBIDDEN(40300, "没有访问权限"),
    NOT_FOUND(40400, "请求的资源不存在"),
    CONFLICT(40900, "相同请求正在处理中，请稍后重试"),
    OPTIMISTIC_LOCK(40901, "数据已被其他请求修改，请刷新后重试"),
    FILE_TOO_LARGE(41300, "上传文件超过大小限制"),
    UNSUPPORTED_MEDIA_TYPE(41500, "不支持的媒体类型"),
    TOO_MANY_REQUESTS(42900, "请求过于频繁，请稍后重试"),
    INTERNAL_ERROR(50000, "服务内部错误"),
    SERVICE_UNAVAILABLE(50300, "服务暂不可用"),
    GATEWAY_TIMEOUT(50400, "依赖服务调用超时");

    private final int code;
    private final String message;

    CommonErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    @Override
    public int code() {
        return code;
    }

    @Override
    public String message() {
        return message;
    }
}
