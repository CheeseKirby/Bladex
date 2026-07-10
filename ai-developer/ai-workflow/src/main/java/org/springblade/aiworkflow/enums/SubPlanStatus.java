package org.springblade.aiworkflow.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Getter
@AllArgsConstructor
public enum SubPlanStatus {

    QUEUED("QUEUED", "排队中"),
    EXECUTING("EXECUTING", "执行中"),
    COMPLETED("COMPLETED", "已完成"),
    COMPLETED_WITH_ERRORS("COMPLETED_WITH_ERRORS", "完成但有未修复错误"),
    FAILED("FAILED", "失败"),
    SKIPPED("SKIPPED", "已跳过");

    private final String code;
    private final String desc;

    private static final Map<String, SubPlanStatus> CODE_MAP =
            Collections.unmodifiableMap(
                    Arrays.stream(values()).collect(Collectors.toMap(SubPlanStatus::getCode, Function.identity())));

    public static SubPlanStatus fromCode(String code) {
        SubPlanStatus result = CODE_MAP.get(code);
        if (result == null) {
            throw new IllegalArgumentException("Unknown SubPlanStatus code: " + code);
        }
        return result;
    }
}
