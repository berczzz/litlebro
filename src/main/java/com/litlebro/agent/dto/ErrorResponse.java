package com.litlebro.agent.dto;

/**
 * 统一错误响应 DTO，所有异常处理均返回此格式。
 *
 * <p>设计目的：
 * <ul>
 *   <li>统一前端错误处理逻辑，客户端只需解析一种错误结构</li>
 *   <li>timestamp 字段帮助排查问题，定位错误发生的时间点</li>
 *   <li>code 字段对应 HTTP 状态码，便于前端做分类处理</li>
 * </ul>
 *
 * @param code      HTTP 状态码，如 400、500
 * @param message   人类可读的错误描述信息
 * @param timestamp 错误发生的时间戳（毫秒）
 */
public record ErrorResponse(
        int code,
        String message,
        long timestamp
) {
    /**
     * 静态工厂方法，自动填充当前时间戳。
     *
     * @param code    HTTP 状态码
     * @param message 错误消息
     * @return 包含当前时间戳的 ErrorResponse 实例
     */
    public static ErrorResponse of(int code, String message) {
        return new ErrorResponse(code, message, System.currentTimeMillis());
    }
}