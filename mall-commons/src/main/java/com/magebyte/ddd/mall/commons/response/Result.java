package com.magebyte.ddd.mall.commons.response;

/**
 * 统一响应体：{@code code} + {@code message} + {@code data}。
 *
 * <p>四个限界上下文对外暴露的 REST 接口统一返回本对象，约定：
 * <ul>
 *   <li>{@code code}：状态码。成功为 {@value #CODE_SUCCESS}（对齐 HTTP 200）；
 *       失败沿用 HTTP 状态码语义（如 422 表示"请求没毛病、但业务规则不允许"）。</li>
 *   <li>{@code message}：给人看的结果说明，成功为 "OK"，失败为业务原因（领域语言）。</li>
 *   <li>{@code data}：承载载荷，成功时为返回值，失败时为 {@code null}。</li>
 * </ul>
 *
 * <p>本类是纯数据载体、不含任何业务规则，故放公共模块 mall-commons 供各 BC 复用；
 * 刻意不依赖 Spring / Jackson（序列化由各 BC 的 Web 层完成）。
 *
 * @param <T> 载荷类型
 */
public final class Result<T> {

    /** 成功状态码（对齐 HTTP 200）。 */
    public static final int CODE_SUCCESS = 200;

    private final int code;
    private final String message;
    private final T data;

    private Result(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    /** 成功，无载荷。 */
    public static <T> Result<T> ok() {
        return new Result<>(CODE_SUCCESS, "OK", null);
    }

    /** 成功，携带载荷。 */
    public static <T> Result<T> ok(T data) {
        return new Result<>(CODE_SUCCESS, "OK", data);
    }

    /** 失败：给定状态码与原因，载荷为空。 */
    public static <T> Result<T> error(int code, String message) {
        return new Result<>(code, message, null);
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public T getData() {
        return data;
    }
}
