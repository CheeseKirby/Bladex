package org.springblade.aiworkflow.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springblade.aiworkflow.entity.AiSubPlan;
import org.springblade.aiworkflow.enums.TaskType;

import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BladeXCodeAgentPlanCompilationTest {

    private final GenerationContext context = new GenerationContext(
            GenerationIdentity.of("specialperiod", "SpecialPeriod", "blade_special_period",
                    "org.springblade.specialperiod"),
            ReferenceFrameworkProfile.defaults());

    @Test
    void structuredArtifactsSuppressForbiddenDefaultsAndCompileExactProviderAndDtos() {
        AiSubPlan subPlan = new AiSubPlan();
        subPlan.setId(7L);
        subPlan.setTitle("Feign and Excel delivery");
        subPlan.setPlanContent("""
                ## Module configuration
                needExcel=false; do not generate local Excel

                ### Feign transport DTO
                - **SpecialPeriodCheckDTO** - package: `org.springblade.specialperiod.dto`
                - **SpecialPeriodCheckResultDTO** - package: `org.springblade.specialperiod.dto`

                ### Feign provider
                - class: `SpecialPeriodClient`
                - package: `org.springblade.specialperiod.feign`
                - extends `BladeController`
                """);

        BladeXCodeAgent agent = new BladeXCodeAgent(
                null, null, null, null, null, null, null, null, null, null,
                1, false, null, null, null, null, null, null);
        BladeXCodeAgent.SubPlanTaskCompilation compilation = agent.parseAtomicTasks(subPlan, context);

        assertTrue(compilation.tasks().stream().anyMatch(task -> task.getType() == TaskType.OTHER
                && task.getTargetPath().endsWith("/org/springblade/specialperiod/dto/SpecialPeriodCheckDTO.java")));
        assertTrue(compilation.tasks().stream().anyMatch(task -> task.getType() == TaskType.OTHER
                && task.getTargetPath().endsWith("/org/springblade/specialperiod/dto/SpecialPeriodCheckResultDTO.java")));
        assertTrue(compilation.tasks().stream().anyMatch(task -> task.getType() == TaskType.FEIGN_PROVIDER
                && task.getTargetPath().endsWith("/org/springblade/specialperiod/feign/SpecialPeriodClient.java")));
        assertFalse(compilation.tasks().stream().anyMatch(task -> task.getTargetPath().endsWith("SpecialPeriodExcel.java")));
        assertFalse(compilation.tasks().stream().anyMatch(task -> task.getTargetPath().endsWith("BladeController.java")));
        assertFalse(compilation.tasks().stream().anyMatch(task -> task.getTargetPath().endsWith("/Feign.java")));
    }


    @Test
    void legacyFeignPlanKeepsClientAndProviderTaskCount() {
        AiSubPlan subPlan = new AiSubPlan();
        subPlan.setId(9L);
        subPlan.setTitle("Feign remote client and service provider");
        subPlan.setPlanContent("""
                Entity: HotworkUpgrade

                ### 1. HotworkUpgradeClient (Feign interface)
                - endpoints follow the reviewed contract

                ### 4. HotworkUpgradeClientClientImpl (Feign \u5b9e\u73b0, Service module)
                - @RestController
                - implements HotworkUpgradeClient
                """);

        GenerationContext hotwork = new GenerationContext(
                GenerationIdentity.of("hotwork", "HotworkUpgrade", "blade_hotwork_upgrade",
                        "org.springblade.hotwork"),
                ReferenceFrameworkProfile.defaults());
        BladeXCodeAgent agent = new BladeXCodeAgent(
                null, null, null, null, null, null, null, null, null, null,
                1, false, null, null, null, null, null, null);
        BladeXCodeAgent.SubPlanTaskCompilation compilation = agent.parseAtomicTasks(subPlan, hotwork);

        assertTrue(compilation.tasks().stream().filter(task -> task.getType() == TaskType.FEIGN_CLIENT
                || task.getType() == TaskType.FEIGN_PROVIDER).count() == 2);
        assertTrue(compilation.tasks().stream().anyMatch(task -> task.getType() == TaskType.FEIGN_PROVIDER
                && task.getTargetPath().endsWith("HotworkUpgradeClientClientImpl.java")));
    }


    @Test
    void realApiPlanCompilesExactApiTasksInsteadOfCodeJavaFallback() {
        AiSubPlan subPlan = new AiSubPlan();
        subPlan.setId(210L);
        subPlan.setTitle("Entity \u5b9e\u4f53\u4e0e VO \u89c6\u56fe\u5bf9\u8c61\u5b9a\u4e49");
        subPlan.setPlanContent("""
                ## \u76ee\u6807\u5c42: API \u6a21\u5757 (blade-specialperiod-api)
                ## 1. Entity: org.springblade.specialperiod.entity.SpecialPeriod
                ## 2. VO: org.springblade.specialperiod.vo.SpecialPeriodVO
                ## 3. QVO: org.springblade.specialperiod.vo.qvo.SpecialPeriodQVO
                ## 4. IVO: org.springblade.specialperiod.vo.ivo.SpecialPeriodIVO
                ## 5. UVO: org.springblade.specialperiod.vo.uvo.SpecialPeriodUVO
                ## 6. EVO: org.springblade.specialperiod.vo.evo.SpecialPeriodEVO
                ## 7. DTO: org.springblade.specialperiod.dto.HotworkMatchDTO
                """);

        BladeXCodeAgent agent = new BladeXCodeAgent(
                null, null, null, null, null, null, null, null, null, null,
                1, false, null, null, null, null, null, null);
        BladeXCodeAgent.SubPlanTaskCompilation compilation = agent.parseAtomicTasks(subPlan, context);

        assertTrue(compilation.tasks().stream().anyMatch(task -> task.getTargetPath().endsWith("/entity/SpecialPeriod.java")));
        assertTrue(compilation.tasks().stream().anyMatch(task -> task.getTargetPath().endsWith("/vo/SpecialPeriodVO.java")));
        assertTrue(compilation.tasks().stream().anyMatch(task -> task.getTargetPath().endsWith("/vo/qvo/SpecialPeriodQVO.java")));
        assertTrue(compilation.tasks().stream().anyMatch(task -> task.getTargetPath().endsWith("/dto/HotworkMatchDTO.java")));
        assertFalse(compilation.tasks().stream().anyMatch(task -> task.getTargetPath().contains("/pojo/")));
        assertFalse(compilation.tasks().stream().anyMatch(task -> task.getTargetPath().contains("ai-generated/subplan-")));
        assertTrue(compilation.tasks().size() == 7);
    }

    @Test
    void exactControllerAndExcelHeadingsDoNotCreateGenericExcelDuplicate() {
        AiSubPlan subPlan = new AiSubPlan();
        subPlan.setId(212L);
        subPlan.setTitle("Wrapper and Controller with Excel utility");
        subPlan.setPlanContent("""
                ## \u76ee\u6807\u5c42: Service \u6a21\u5757 - Wrapper + Controller + Excel
                ## 1. Controller: org.springblade.specialperiod.controller.SpecialPeriodController
                ## 2. Controller: org.springblade.specialperiod.controller.SpecialPeriodStatController
                ## 3. Excel: org.springblade.specialperiod.excel.SpecialPeriodExcelUtil
                """);

        BladeXCodeAgent agent = new BladeXCodeAgent(
                null, null, null, null, null, null, null, null, null, null,
                1, false, null, null, null, null, null, null);
        BladeXCodeAgent.SubPlanTaskCompilation compilation = agent.parseAtomicTasks(subPlan, context);

        assertTrue(compilation.tasks().stream().anyMatch(task -> task.getTargetPath().endsWith("SpecialPeriodController.java")));
        assertTrue(compilation.tasks().stream().anyMatch(task -> task.getTargetPath().endsWith("SpecialPeriodStatController.java")));
        assertTrue(compilation.tasks().stream().anyMatch(task -> task.getTargetPath().endsWith("SpecialPeriodExcelUtil.java")));
        assertFalse(compilation.tasks().stream().anyMatch(task -> task.getTargetPath().endsWith("SpecialPeriodExcel.java")));
        assertFalse(compilation.tasks().stream().anyMatch(task -> task.getTargetPath().contains("/mapper/")));
        assertFalse(compilation.tasks().stream().anyMatch(task -> task.getTargetPath().contains("/service/")));
    }

    @Test
    void excelSubPlanCompilesServiceMethodContributionsForLaterTaskAggregation() {
        AiSubPlan subPlan = new AiSubPlan();
        subPlan.setId(213L);
        subPlan.setTitle("Excel \u5bfc\u5165\u5bfc\u51fa\u5de5\u5177\u7c7b\u5b9e\u73b0");
        subPlan.setPlanContent("""
                ## 1. Excel \u5de5\u5177\u7c7b: org.springblade.specialperiod.excel.SpecialPeriodExcelUtil
                - \u4f7f\u7528 EasyExcel

                ## 2. Service \u59d4\u6258\u65b9\u6cd5\u8865\u5145
                ### ISpecialPeriodService \u65b0\u589e\u65b9\u6cd5\u58f0\u660e
                boolean importSpecialPeriod(MultipartFile file);
                void exportSpecialPeriod(SpecialPeriodQVO qvo, HttpServletResponse response);

                ### SpecialPeriodServiceImpl \u5b9e\u73b0
                @Override
                public boolean importSpecialPeriod(MultipartFile file) { return true; }
                """);

        BladeXCodeAgent agent = new BladeXCodeAgent(
                null, null, null, null, null, null, null, null, null, null,
                1, false, null, null, null, null, null, null);
        BladeXCodeAgent.SubPlanTaskCompilation compilation = agent.parseAtomicTasks(subPlan, context);

        assertTrue(compilation.issues().isEmpty(), compilation.issues().toString());
        assertTrue(compilation.tasks().stream().anyMatch(task -> task.getTargetPath()
                .equals(BladeXModuleLayout.serviceInterfacePath(context, "SpecialPeriod"))));
        assertTrue(compilation.tasks().stream().anyMatch(task -> task.getTargetPath()
                .equals(BladeXModuleLayout.serviceImplPath(context, "SpecialPeriod"))));
        assertTrue(compilation.tasks().stream().anyMatch(task -> task.getTargetPath()
                .endsWith("SpecialPeriodExcelUtil.java")));
    }

    @Test
    void unresolvedCrossModuleModificationProducesNoFallbackOrGuessedController() {
        AiSubPlan subPlan = new AiSubPlan();
        subPlan.setId(8L);
        subPlan.setTitle("Cross-module Controller extension");
        subPlan.setPlanContent("""
                ### hotwork module Controller extension
                - location: `HotworkController`
                - package: `org.springblade.hotwork.controller`
                - add one reviewed endpoint
                """);

        BladeXCodeAgent agent = new BladeXCodeAgent(
                null, null, null, null, null, null, null, null, null, null,
                1, false, null, null, null, null, null, null);
        BladeXCodeAgent.SubPlanTaskCompilation compilation = agent.parseAtomicTasks(subPlan, context);

        assertTrue(compilation.issues().stream()
                .anyMatch(issue -> "PLAN-REFERENCE-UNAVAILABLE".equals(issue.rule())));
        assertFalse(compilation.tasks().stream().anyMatch(task -> task.getTargetPath().contains("HotworkController")));
        assertFalse(compilation.tasks().stream().anyMatch(task -> task.getTargetPath().contains("ai-generated/subplan-")));
    }
    @Test
    void canonicalV2TasksIgnoreMisleadingMarkdownAndUseOnlyAssignedDeliverables() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        CanonicalPlanContractV2 contract;
        try (InputStream input = getClass().getResourceAsStream("/contracts/canonical-plan-contract-v2.json")) {
            contract = mapper.readValue(input, CanonicalPlanContractV2.class);
        }
        GenerationContext canonicalContext = new GenerationContext(contract.generationIdentity(),
                ReferenceFrameworkProfile.defaults(), contract.toDomainContract(), contract);
        AiSubPlan subPlan = new AiSubPlan();
        subPlan.setId(302L);
        subPlan.setTitle("Generate FakeExcel and WrongController");
        subPlan.setPlanContent("Create FakeExcel, WrongController and a completely different Markdown entity.");
        subPlan.setDeliverableIdsJson(mapper.writeValueAsString(
                List.of("deliverable.entity.2", "deliverable.service.5")));

        BladeXCodeAgent agent = new BladeXCodeAgent(
                null, null, null, null, null, null, null, null, null, mapper,
                1, false, null, null, null, null, null, null);
        BladeXCodeAgent.SubPlanTaskCompilation compilation = agent.parseAtomicTasks(subPlan, canonicalContext);

        assertTrue(compilation.issues().isEmpty(), compilation.issues().toString());
        assertTrue(compilation.tasks().stream().anyMatch(task -> task.getTargetPath().endsWith("/entity/Ticket.java")));
        assertTrue(compilation.tasks().stream().anyMatch(task -> task.getTargetPath().endsWith("/service/ITicketService.java")));
        assertTrue(compilation.tasks().stream().anyMatch(task -> task.getTargetPath().endsWith("/service/impl/TicketServiceImpl.java")));
        assertFalse(compilation.tasks().stream().anyMatch(task -> task.getTargetPath().contains("FakeExcel")
                || task.getTargetPath().contains("WrongController") || task.getTargetPath().contains("ai-generated/subplan-")));
    }


}
