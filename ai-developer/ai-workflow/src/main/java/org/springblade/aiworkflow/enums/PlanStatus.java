package org.springblade.aiworkflow.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 方案状态枚举
 *
 * @author AI Developer
 */
@Getter
@AllArgsConstructor
public enum PlanStatus {

    RECEIVED("RECEIVED", "已接收"),
    EXECUTING("EXECUTING", "执行中"),
    COMPLETED("COMPLETED", "已完成"),
    COMPLETED_WITH_ERRORS("COMPLETED_WITH_ERRORS", "完成但有未修复错误"),
    FAILED("FAILED", "失败");

    private final String code;
    private final String desc;

    private static final Map<String, PlanStatus> CODE_MAP =
            Collections.unmodifiableMap(
                    Arrays.stream(values()).collect(Collectors.toMap(PlanStatus::getCode, Function.identity())));

    public static PlanStatus fromCode(String code) {
        PlanStatus result = CODE_MAP.get(code);
        if (result == null) {
            throw new IllegalArgumentException("Unknown PlanStatus code: " + code);
        }
        return result;
    }
}
