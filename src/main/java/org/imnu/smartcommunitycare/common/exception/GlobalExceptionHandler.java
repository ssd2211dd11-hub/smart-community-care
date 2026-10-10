package org.imnu.smartcommunitycare.common.exception;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import org.imnu.smartcommunitycare.common.api.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.HashMap;
import java.util.Map;

/**
 * 全局异常处理。
 *
 * <p>三类分开处理：业务异常、参数校验异常、未预期异常。
 * 未预期异常只向服务器日志写完整堆栈，响应体不暴露 SQL、堆栈和内部路径。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 业务异常：HTTP 状态码与业务码保持一致，不把失败包装成 200。
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException e) {
        HttpStatus status = switch (e.getCode()) {
            case 401 -> HttpStatus.UNAUTHORIZED;
            case 403 -> HttpStatus.FORBIDDEN;
            case 404 -> HttpStatus.NOT_FOUND;
            case 409 -> HttpStatus.CONFLICT;
            case 503 -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status)
                .body(ApiResponse.error(e.getCode(), e.getUserMessage()));
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleInvalidParameter(Exception ex) {
        return ApiResponse.error(400, "请求参数缺失或格式不正确");
    }

    /** 缺少请求体、类型错误或损坏的 JSON 是参数错误。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleUnreadableRequest(HttpMessageNotReadableException ex) {
        return ApiResponse.error(400, "请求内容缺失或格式不正确");
    }

    /** 参数校验失败：字段级错误返回给前端定位到输入框 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Map<String, String>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            errors.put(fieldError.getField(), fieldError.getDefaultMessage());
        }
        return ApiResponse.<Map<String, String>>builder()
                .code(400)
                .message("参数校验失败")
                .fieldErrors(errors)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    /**
     * Sa-Token 的未登录异常。
     *
     * <p>必须单独处理：如果不写这个方法，{@code NotLoginException} 会被下面的兜底
     * 分支捕获并返回 500 —— 未登录被当成服务器故障，是安全上的错误信号，
     * 客户端也会因此不重新登录而是盲目重试。
     */
    @ExceptionHandler(NotLoginException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotLogin(NotLoginException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.error(401, "未登录或登录已失效"));
    }

    /** Sa-Token 的权限不足异常 */
    @ExceptionHandler(NotPermissionException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotPermission(NotPermissionException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error(403, "当前角色无权执行此操作"));
    }

    /** Sa-Token 的角色不足异常 */
    @ExceptionHandler(NotRoleException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotRole(NotRoleException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error(403, "当前角色无权执行此操作"));
    }

    /**
     * 请求的路径不存在。
     *
     * <p>Spring Boot 4 中，未匹配到任何 Controller 映射的请求会抛
     * {@link NoResourceFoundException}。如果不在这里拦下，它会落进兜底分支
     * 返回 500 —— "接口没写"被当成"服务器坏了"，调用方无法区分，
     * 监控也会被无意义的 5xx 污染。所以单独映射为 404。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResource(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error(404, "接口不存在"));
    }

    /** 请求方法不被支持，例如用 GET 访问只接受 POST 的接口 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ApiResponse.error(405, "请求方法不被支持"));
    }

    /**
     * 未预期异常兜底。
     *
     * <p>注意：这里必须给响应设置 500 状态，不能沿用业务码。
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> handleUnexpected(Exception e) {
        // 完整堆栈只进服务器日志，不回传前端
        log.error("未预期异常", e);
        return ApiResponse.error(500, "服务器内部错误，请稍后重试");
    }
}
