package org.springblade.aiworkflow.common;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 统一API响应封装
 *
 * <p>格式与Part A通信契约一致：{ code, success, data, msg }
 *
 * @author AI Developer
 */
@Data
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Schema(description = "统一API响应")
public class ApiResponse<T> implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "状态码: 200成功, 400参数错误, 500服务器错误")
    private int code;

    @Schema(description = "是否成功")
    private boolean success;

    @Schema(description = "响应数据")
    private T data;

    @Schema(description = "消息（成功或错误描述）")
    private String msg;

    // ─── 工厂方法 ───

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(200, true, data, null);
    }

    public static <T> ApiResponse<T> okMessage(String message) {
        return new ApiResponse<>(200, true, null, message);
    }

    public static <T> ApiResponse<T> internalError(String error) {
        return new ApiResponse<>(500, false, null, error);
    }

    public static <T> ApiResponse<T> fail(int code, String error) {
        return new ApiResponse<>(code, false, null, error);
    }

    public static <T> ApiResponse<T> fail(ResultCode rc) {
        return new ApiResponse<>(rc.getCode(), false, null, rc.getMessage());
    }
}
