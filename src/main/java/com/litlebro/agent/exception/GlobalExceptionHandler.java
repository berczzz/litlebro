package com.litlebro.agent.exception;

import com.litlebro.agent.dto.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理器，拦截所有 Controller 层抛出的异常，
 * 统一转换为 {@link ErrorResponse} 格式返回给客户端。
 *
 * <p>分三层异常处理策略：
 * <ol>
 *   <li>业务参数异常（IllegalArgumentException）— 返回 400</li>
 *   <li>Bean Validation 校验失败（MethodArgumentNotValidException）— 返回 400，含字段级错误</li>
 *   <li>兜底未知异常（Exception）— 返回 500，记录完整堆栈</li>
 * </ol>
 *
 * <p>使用 @RestControllerAdvice 而非 @ControllerAdvice 的原因是
 * 响应体直接序列化为 JSON，无需额外 @ResponseBody 注解。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 单文件上传大小上限（与 application.yml 的 spring.servlet.multipart.max-file-size 保持一致） */
    private final String maxFileSize;

    public GlobalExceptionHandler(@org.springframework.beans.factory.annotation.Value(
            "${spring.servlet.multipart.max-file-size:50MB}") String maxFileSize) {
        this.maxFileSize = maxFileSize;
    }

    /**
     * 处理业务逻辑中主动抛出的参数校验异常。
     * 记录 warn 级别日志（非 error），因为这是用户输入问题而非系统故障。
     *
     * @param ex 非法参数异常
     * @return 400 错误响应，包含异常消息
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("参数校验失败: {}", ex.getMessage());
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of(400, ex.getMessage()));
    }

    /**
     * 处理 Jakarta Validation 框架的校验失败异常。
     * 将所有字段级别的错误信息拼接为分号分隔的字符串，便于前端展示。
     *
     * @param ex 方法参数校验异常
     * @return 400 错误响应，包含所有字段错误的汇总信息
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        // 遍历所有字段校验错误，拼接为 "fieldName: errorMessage" 格式
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("参数校验失败");
        log.warn("参数校验失败: {}", message);
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of(400, message));
    }

    /**
     * 处理文件上传超限异常（MaxUploadSizeExceededException）。
     * 返回 400 提示用户文件过大，而非默认的 500 内部错误。
     *
     * @param ex 文件上传大小超限异常
     * @return 400 错误响应，含文件大小限制说明
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleMaxUploadSize(MaxUploadSizeExceededException ex) {
        log.warn("文件上传超限: {}", ex.getMessage());
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of(400, "文件过大，单文件最大 " + maxFileSize + "，请压缩后重试"));
    }

    /**
     * 处理未知路径（无匹配 Controller 映射且非静态资源）异常。
     * 返回 404 而非默认的 500 内部错误，避免误导为系统故障。
     *
     * @param ex 路径未找到异常
     * @return 404 错误响应
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResourceFound(NoResourceFoundException ex) {
        log.warn("请求路径不存在: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of(404, "请求路径不存在: " + ex.getResourcePath()));
    }

    /**
     * 兜底异常处理器，捕获所有未被上述处理器处理的异常。
     * 记录 error 级别日志并输出完整堆栈，便于排查未预期的系统问题。
     *
     * @param ex 任意未捕获的异常
     * @return 500 错误响应，包含简要错误信息
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneral(Exception ex) {
        log.error("系统异常", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of(500, "系统内部错误: " + ex.getMessage()));
    }
}