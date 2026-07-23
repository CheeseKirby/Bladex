package org.springblade.aiworkflow.agent;

import org.junit.jupiter.api.Test;
import org.springblade.aiworkflow.entity.AiPlan;
import org.springblade.aiworkflow.entity.AiSubPlan;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Plan55ContractReplayTest {

    @Test
    void productionPlan55CompilesCanonicalFieldsAndCompleteDeliverables() throws Exception {
        GenerationIdentity identity = GenerationIdentity.of(
                "specialperiod", "SpecialPeriod", "blade_special_period", "org.springblade.specialperiod");
        AiPlan plan = new AiPlan();
        plan.setId(55L);
        plan.setMasterPlanContent(resource("replays/plan55/master-plan.md"));

        Map<Long, String> titles = new LinkedHashMap<>();
        titles.put(214L, "数据库 DDL - 建表语句");
        titles.put(215L, "Entity 与 VO - 实体及视图对象");
        titles.put(216L, "Mapper 与 Service - 数据访问与业务服务");
        titles.put(217L, "Wrapper 与 Controller - 包装类与接口控制器");
        titles.put(218L, "Excel 导入导出 - 批量数据处理");
        titles.put(219L, "Feign 远程 - 跨服务调用接口");
        List<AiSubPlan> subPlans = new ArrayList<>();
        for (Map.Entry<Long, String> entry : titles.entrySet()) {
            AiSubPlan subPlan = new AiSubPlan();
            subPlan.setId(entry.getKey());
            subPlan.setPlanId(55L);
            subPlan.setTitle(entry.getValue());
            subPlan.setPlanContent(resource("replays/plan55/subplan-" + entry.getKey() + ".md"));
            subPlans.add(subPlan);
        }

        CanonicalDomainContractCompiler.Compilation domain =
                new CanonicalDomainContractCompiler().compile(plan, subPlans, identity);
        assertFalse(domain.issues().stream().anyMatch(issue -> "ERROR".equals(issue.severity())),
                () -> domain.issues().toString());
        assertEquals(java.util.Set.of("periodName", "periodType", "startDate", "endDate", "startTime",
                        "endTime", "upgradeLevel", "isEnable", "remark"),
                domain.contract().persistentNames());
        for (String field : List.of("periodName", "periodType", "startDate", "endDate", "startTime",
                "endTime", "upgradeLevel", "isEnable", "remark")) {
            assertTrue(domain.contract().persistentNames().contains(field),
                    () -> "missing canonical field " + field + ": " + domain.contract().persistentNames());
        }
        assertTrue(domain.contract().derivedNames().contains("periodTypeDesc"));
        assertTrue(domain.contract().derivedNames().contains("statusDesc"));

        GenerationContext context = new GenerationContext(identity, ReferenceFrameworkProfile.defaults(), domain.contract());
        BladeXCodeAgent agent = new BladeXCodeAgent(
                null, null, null, null, null, null, null, null, null, null,
                1, false, null, null, null, null, null, null);
        PlannedTaskRegistry registry = new PlannedTaskRegistry();
        List<AtomicTask> tasks = new ArrayList<>();
        List<PlannedArtifact> artifacts = new ArrayList<>();
        List<PlanCompilationIssue> issues = new ArrayList<>(domain.issues());
        for (AiSubPlan subPlan : subPlans) {
            BladeXCodeAgent.SubPlanTaskCompilation compilation = agent.parseAtomicTasks(subPlan, context);
            artifacts.addAll(compilation.artifacts());
            issues.addAll(compilation.issues());
            for (AtomicTask task : compilation.tasks()) {
                task.setSourceSubPlanId(subPlan.getId());
                PlannedTaskRegistry.Registration registration = registry.claim(subPlan.getId(), task);
                if (!registration.accepted()) {
                    issues.add(PlanCompilationIssue.error(subPlan.getId(), registration.rule(), task.getTargetPath(),
                            "plan55 replay conflict"));
                } else if (registration.scheduled()) {
                    tasks.add(registration.canonicalTask());
                }
            }
        }
        issues.addAll(new PlanArtifactTaskValidator().validate(artifacts, tasks));
        assertFalse(PlanPreflightGate.evaluate(issues).blocking(), () -> issues.toString());

        for (String expected : List.of(
                "SpecialPeriodHitVO.java", "SpecialPeriodStatVO.java", "SpecialPeriodMatchDTO.java",
                "SpecialPeriodStatController.java", "ExcelUtil.java", "SpecialPeriodExcelReadListener.java",
                "SpecialPeriodClient.java")) {
            assertTrue(tasks.stream().anyMatch(task -> task.getTargetPath().endsWith(expected)),
                    () -> "missing plan55 deliverable " + expected + ": "
                            + tasks.stream().map(AtomicTask::getTargetPath).sorted().toList());
        }
        assertFalse(tasks.stream().anyMatch(task -> task.getTargetPath().contains("ai-generated/subplan-")));
        assertTrue(tasks.stream().allMatch(task -> task.getGenerationContext().domainContract()
                .persistentNames().contains("periodType")));
        AtomicTask controller = tasks.stream().filter(task -> task.getTargetPath().endsWith("SpecialPeriodController.java"))
                .findFirst().orElseThrow();
        for (String endpoint : List.of("check-conflict", "/export", "/import", "/template")) {
            assertTrue(controller.getTaskDescription().contains(endpoint), controller::getTaskDescription);
        }
        AtomicTask service = tasks.stream().filter(task -> task.getTargetPath().endsWith("ISpecialPeriodService.java"))
                .findFirst().orElseThrow();
        assertTrue(service.getTaskDescription().contains("exportData"), service::getTaskDescription);
        assertTrue(service.getTaskDescription().contains("importData"), service::getTaskDescription);
    }

    private String resource(String path) throws IOException {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(path)) {
            if (stream == null) throw new IOException("Missing replay resource: " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
