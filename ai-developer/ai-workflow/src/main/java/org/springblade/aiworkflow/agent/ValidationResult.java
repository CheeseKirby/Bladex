package org.springblade.aiworkflow.agent;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 规范校验结果
 *
 * @author AI Developer
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ValidationResult {

    /** 是否通过(无 ERROR 级别问题) */
    private boolean passes;

    /** 问题列表 */
    private List<ValidationIssue> issues = new ArrayList<>();

    public static ValidationResult pass() {
        return new ValidationResult(true, new ArrayList<>());
    }

    public static ValidationResult fail(List<ValidationIssue> issues) {
        return new ValidationResult(false, issues);
    }

    /** 是否有 ERROR 级别问题(需要阻塞) */
    public boolean hasErrors() {
        return issues != null && issues.stream().anyMatch(i -> "ERROR".equalsIgnoreCase(i.getSeverity()));
    }

    /** 是否有 WARN 级别问题(仅建议,不阻塞) */
    public boolean hasWarningsOnly() {
        return issues != null && !issues.isEmpty() && !hasErrors();
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ValidationIssue {

        /** 严重级别: ERROR/WARN */
        private String severity;

        /** 违反的规则名称 */
        private String rule;

        /** 行号 */
        private Integer line;

        /** 具体问题描述 */
        private String message;

        public static ValidationIssue error(String rule, String message) {
            return new ValidationIssue("ERROR", rule, null, message);
        }

        public static ValidationIssue warn(String rule, String message) {
            return new ValidationIssue("WARN", rule, null, message);
        }
    }
}
