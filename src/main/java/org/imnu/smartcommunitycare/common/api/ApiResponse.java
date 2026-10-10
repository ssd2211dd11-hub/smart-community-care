package org.imnu.smartcommunitycare.common.api;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 统一响应体。
 *
 * <p>成功业务码固定为 {@code 200}，与旧项目保持一致，不因习惯差异做全量改造。
 * HTTP 状态码仍按真实语义返回（400/401/403/404/409/500），不把失败包装成 200。
 *
 * @param <T> 业务数据类型
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApiResponse<T> {

    /** 业务码：200 成功，非 0 为业务错误 */
    private Integer code;

    /** 提示信息 */
    private String message;

    /** 业务数据 */
    private T data;

    /** 字段级校验错误，仅参数校验失败时返回 */
    private Object fieldErrors;

    /** 服务端时间戳 */
    private Long timestamp;

    /**
     * 成功并返回业务数据，自定义提示文案。
     *
     * <p>刻意<b>不提供</b> {@code success(T data)} 单参数重载：配合
     * {@link #success(String)} 会出现 {@code success("文字")} 的歧义——
     * 编译器会把字符串解析成"只有提示没有数据"的版本，返回类型对不上。
     * 两种语义都用双参数版本表达，编译器不会猜错。
     */
    public static <T> ApiResponse<T> success(String message, T data) {
        return ApiResponse.<T>builder()
                .code(200)
                .message(message)
                .data(data)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    /** 只有提示、没有业务数据 */
    public static ApiResponse<Void> success(String message) {
        return ApiResponse.<Void>builder()
                .code(200)
                .message(message)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    public static <T> ApiResponse<T> error(Integer code, String message) {
        return ApiResponse.<T>builder()
                .code(code)
                .message(message)
                .timestamp(System.currentTimeMillis())
                .build();
    }
}
