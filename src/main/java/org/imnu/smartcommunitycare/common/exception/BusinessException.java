package org.imnu.smartcommunitycare.common.exception;

import lombok.Getter;

/**
 * 业务异常。业务状态冲突、参数不合法、无权限等都用它抛出，
 * 由全局异常处理器转成对应 HTTP 状态码与统一响应体。
 */
@Getter
public class BusinessException extends RuntimeException {

    private final int code;

    /** 需要返回给前端的用户提示 */
    private final String userMessage;

    public BusinessException(String userMessage) {
        this(400, userMessage);
    }

    public BusinessException(int code, String userMessage) {
        super(userMessage);
        this.code = code;
        this.userMessage = userMessage;
    }

    /** 400 参数或状态不合法 */
    public static BusinessException badRequest(String message) {
        return new BusinessException(400, message);
    }

    /** 401 未登录或登录失效 */
    public static BusinessException unauthorized(String message) {
        return new BusinessException(401, message);
    }

    /** 403 角色不允许 */
    public static BusinessException forbidden(String message) {
        return new BusinessException(403, message);
    }

    /**
     * 404 对象不存在或不在当前用户可见范围。
     * 越权访问统一按 404 处理，不泄露对象是否存在。
     */
    public static BusinessException notFound(String message) {
        return new BusinessException(404, message);
    }

    /** 409 状态冲突、版本过期、名额不足、防重键内容冲突 */
    public static BusinessException conflict(String message) {
        return new BusinessException(409, message);
    }
}
