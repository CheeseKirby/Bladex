package org.springblade.aiworkflow.agent;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springblade.aiworkflow.enums.TaskType;

/**
 * 原子任务 — 一个不可再分的代码生成单元
 *
 * @author AI Developer
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AtomicTask {

    /** 任务类型 */
    private TaskType type;

    /** 任务描述（用于Prompt构建） */
    private String taskDescription;

    /** 目标文件路径（相对于target-project-root） */
    private String targetPath;

    /** 期望的代码内容（LLM生成前为空，生成后填充） */
    private String expectedContent;

    /** Exact top-level class/interface name expected at targetPath. */
    private String expectedClassName;

    /**
     * 实体名(类名,如 "Order")。由 parseAtomicTasks 推导后填入,
     * 供 PromptBuilder 把 {@code {Entity}} / {@code {Name}} 占位符替换为真实实体名,
     * 防止 LLM 自由发挥导致类名漂移。
     */
    private String entityName;

    /** 模块名(如 "order")。供 PromptBuilder 在路径示例中使用。 */
    private String moduleName;

    /** Immutable plan-wide identity and reference conventions. */
    private GenerationContext generationContext;

    private Long sourceSubPlanId;

    /** Selected reference metadata for timeline/reporting. */
    private String selectedReferenceClass;
    private String selectedReferenceModule;
    private String selectedReferencePath;
    private Integer referenceScore;
    private String referenceReason;

}
