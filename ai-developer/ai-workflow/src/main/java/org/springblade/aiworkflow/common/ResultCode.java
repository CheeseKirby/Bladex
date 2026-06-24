package org.springblade.aiworkflow.common;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 响应码常量
 *
 * @author AI Developer
 */
@Getter
@AllArgsConstructor
public enum ResultCode {

    /** 成功 */
    SUCCESS(200, "操作成功"),

    /** 参数错误 */
    BAD_REQUEST(400, "参数错误"),

    /** 资源不存在 */
    NOT_FOUND(404, "资源不存在"),

    /** 服务器内部错误 */
    INTERNAL_ERROR(500, "服务器内部错误"),

    /** 方案不存在 */
    PLAN_NOT_FOUND(1001, "方案不存在"),

    /** 方案已存在 */
    PLAN_ALREADY_EXISTS(1002, "方案已存在"),

    /** 执行失败 */
    EXECUTION_FAILED(1003, "执行失败"),

    /** 子方案执行失败 */
    SUB_PLAN_FAILED(1004, "子方案执行失败"),

    /** 编译验证失败 */
    BUILD_FAILED(1005, "编译验证失败"),

    /** 规范校验失败 */
    VALIDATION_FAILED(1006, "规范校验失败"),

    /** LLM调用失败 */
    LLM_CALL_FAILED(1007, "LLM调用失败");

    private final int code;
    private final String message;
}
