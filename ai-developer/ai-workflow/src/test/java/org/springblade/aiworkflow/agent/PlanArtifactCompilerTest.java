package org.springblade.aiworkflow.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanArtifactCompilerTest {

    private final GenerationContext context = new GenerationContext(
            GenerationIdentity.of("specialperiod", "SpecialPeriod", "blade_special_period",
                    "org.springblade.specialperiod"),
            ReferenceFrameworkProfile.defaults());

    @Test
    void recognizesBoldDtosAndFeignProviderButNotFrameworkBaseClass() {
        String content = """
                ## 交付物清单
                ### 1. Feign 传输 DTO（2个）
                - **SpecialPeriodCheckDTO** (校验请求) - 包 `org.springblade.specialperiod.dto`
                - **SpecialPeriodCheckResultDTO** (校验结果) - 包 `org.springblade.specialperiod.dto`

                ### 2. Feign 实现类（Service 模块）
                - 类名: `SpecialPeriodClient`
                - 包: `org.springblade.specialperiod.feign`
                - 继承 `BladeController` 基类
                """;

        PlanArtifactCompilation result = new PlanArtifactCompiler()
                .compile(1L, "Feign 远程调用接口与实现", content, context);

        assertTrue(has(result.artifacts(), "SpecialPeriodCheckDTO", ArtifactKind.DTO));
        assertTrue(has(result.artifacts(), "SpecialPeriodCheckResultDTO", ArtifactKind.DTO));
        assertTrue(result.artifacts().stream().filter(item -> "SpecialPeriodCheckDTO".equals(item.name()))
                .allMatch(item -> "org.springblade.specialperiod.dto".equals(item.declaredPackage())));
        assertTrue(has(result.artifacts(), "SpecialPeriodClient", ArtifactKind.FEIGN_PROVIDER));
        assertFalse(result.artifacts().stream().anyMatch(item -> "BladeController".equals(item.name())));
    }

    @Test
    void preservesNegativeExcelScopeAndFindsCrossModuleControllerModification() {
        String content = """
                ## 模块归属
                - 本模块(specialperiod) needExcel=false, 无需自身导出

                ### 1. hotwork 模块 Excel 导出 EVO
                - 类名: `SpecialPeriodHotworkEVO`
                - 包: `org.springblade.hotwork.vo.evo`

                ### 2. hotwork 模块导出 Controller 端点
                - 位置: `HotworkController` 中新增端点
                """;

        PlanArtifactCompilation result = new PlanArtifactCompiler()
                .compile(2L, "Excel 导入导出对接 hotwork 模块", content, context);

        assertTrue(result.artifacts().stream().anyMatch(item -> item.prohibited()
                && item.kind() == ArtifactKind.EXCEL_MODEL
                && "SpecialPeriodExcel".equals(item.name())));
        assertTrue(result.artifacts().stream().anyMatch(item -> item.kind() == ArtifactKind.EXCEL_MODEL
                && "SpecialPeriodHotworkEVO".equals(item.name())
                && "hotwork".equals(item.ownerModule())));
        assertTrue(result.artifacts().stream().anyMatch(item -> item.kind() == ArtifactKind.CONTROLLER
                && item.action() == ArtifactAction.MODIFY
                && "HotworkController".equals(item.name())
                && "hotwork".equals(item.ownerModule())));
    }


    @Test
    void compilesSecondLevelFqcnHeadingsFromReviewedApiPlan() {
        String content = """
                ## \u76ee\u6807\u5c42: API \u6a21\u5757 (blade-specialperiod-api)

                ## 1. Entity: org.springblade.specialperiod.entity.SpecialPeriod
                - extends BaseEntity

                ## 2. VO: org.springblade.specialperiod.vo.SpecialPeriodVO
                ## 3. QVO: org.springblade.specialperiod.vo.qvo.SpecialPeriodQVO
                ## 4. IVO: org.springblade.specialperiod.vo.ivo.SpecialPeriodIVO
                ## 5. UVO: org.springblade.specialperiod.vo.uvo.SpecialPeriodUVO
                ## 6. EVO: org.springblade.specialperiod.vo.evo.SpecialPeriodEVO
                ## 7. DTO: org.springblade.specialperiod.dto.HotworkMatchDTO
                """;

        PlanArtifactCompilation result = new PlanArtifactCompiler()
                .compile(210L, "Entity \u5b9e\u4f53\u4e0e VO \u89c6\u56fe\u5bf9\u8c61\u5b9a\u4e49", content, context);

        assertTrue(has(result.artifacts(), "SpecialPeriod", ArtifactKind.ENTITY));
        assertTrue(has(result.artifacts(), "SpecialPeriodVO", ArtifactKind.VO));
        assertTrue(has(result.artifacts(), "SpecialPeriodQVO", ArtifactKind.VO));
        assertTrue(has(result.artifacts(), "SpecialPeriodIVO", ArtifactKind.VO));
        assertTrue(has(result.artifacts(), "SpecialPeriodUVO", ArtifactKind.VO));
        assertTrue(has(result.artifacts(), "SpecialPeriodEVO", ArtifactKind.VO));
        assertTrue(has(result.artifacts(), "HotworkMatchDTO", ArtifactKind.DTO));
        assertTrue(result.artifacts().stream().allMatch(item -> item.declaredPackage() != null));
    }

    @Test
    void compilesMultipleControllersAndExactExcelUtilityFromFqcnHeadings() {
        String content = """
                ## 1. \u4e3b\u63a7\u5236\u5668: org.springblade.specialperiod.controller.SpecialPeriodController
                - \u7ee7\u627f BladeController

                ## 2. \u7edf\u8ba1\u63a7\u5236\u5668: org.springblade.specialperiod.controller.SpecialPeriodStatController
                - GET /byPeriodType

                ## 3. Excel \u5de5\u5177\u7c7b: org.springblade.specialperiod.excel.SpecialPeriodExcelUtil
                - \u4f7f\u7528 EasyExcel
                """;

        PlanArtifactCompilation result = new PlanArtifactCompiler()
                .compile(212L, "Wrapper and Controller layers", content, context);

        assertTrue(has(result.artifacts(), "SpecialPeriodController", ArtifactKind.CONTROLLER));
        assertTrue(has(result.artifacts(), "SpecialPeriodStatController", ArtifactKind.CONTROLLER));
        assertTrue(has(result.artifacts(), "SpecialPeriodExcelUtil", ArtifactKind.EXCEL_MODEL));
    }

    @Test
    void compilesReviewedServiceMethodSupplementIntoInterfaceAndImplementationContributions() {
        String content = """
                ## 2. Service \u59d4\u6258\u65b9\u6cd5\u8865\u5145

                ### ISpecialPeriodService \u65b0\u589e\u65b9\u6cd5\u58f0\u660e
                boolean importSpecialPeriod(MultipartFile file);
                void exportSpecialPeriod(SpecialPeriodQVO qvo, HttpServletResponse response);

                ### SpecialPeriodServiceImpl \u5b9e\u73b0
                @Override
                public boolean importSpecialPeriod(MultipartFile file) { return true; }
                """;

        PlanArtifactCompilation result = new PlanArtifactCompiler()
                .compile(213L, "Excel \u5bfc\u5165\u5bfc\u51fa\u5de5\u5177\u7c7b\u5b9e\u73b0", content, context);

        assertTrue(result.artifacts().stream().anyMatch(item -> item.kind() == ArtifactKind.SERVICE_INTERFACE
                && item.action() == ArtifactAction.MODIFY
                && "ISpecialPeriodService".equals(item.name())));
        assertTrue(result.artifacts().stream().anyMatch(item -> item.kind() == ArtifactKind.SERVICE_IMPL
                && item.action() == ArtifactAction.MODIFY
                && "SpecialPeriodServiceImpl".equals(item.name())));
    }


    @Test
    void canonicalCompilationUsesDeliverableIdsAndExactContractTypeNames() throws Exception {
        CanonicalPlanContractV2 contract = canonicalFixture();
        GenerationContext canonicalContext = new GenerationContext(contract.generationIdentity(),
                ReferenceFrameworkProfile.defaults(), contract.toDomainContract(), contract);

        PlanArtifactCompilation result = new PlanArtifactCompiler().compileCanonical(300L,
                List.of("deliverable.mapper.4", "deliverable.service.5"), canonicalContext);

        assertTrue(result.issues().isEmpty(), result.issues().toString());
        assertTrue(has(result.artifacts(), "TicketMapper", ArtifactKind.MAPPER));
        assertTrue(has(result.artifacts(), "TicketMapper", ArtifactKind.MAPPER_XML));
        assertTrue(has(result.artifacts(), "ITicketService", ArtifactKind.SERVICE_INTERFACE));
        assertTrue(has(result.artifacts(), "TicketServiceImpl", ArtifactKind.SERVICE_IMPL));
        assertFalse(result.artifacts().stream().anyMatch(item -> item.name().contains("SpecialPeriod")));
    }

    @Test
    void canonicalCompilationRejectsDuplicateOrUnknownDeliverableIds() throws Exception {
        CanonicalPlanContractV2 contract = canonicalFixture();
        GenerationContext canonicalContext = new GenerationContext(contract.generationIdentity(),
                ReferenceFrameworkProfile.defaults(), contract.toDomainContract(), contract);

        PlanArtifactCompilation result = new PlanArtifactCompiler().compileCanonical(301L,
                List.of("deliverable.entity.2", "deliverable.entity.2", "deliverable.unknown"), canonicalContext);

        assertTrue(result.issues().stream().anyMatch(issue -> "CANONICAL-DELIVERABLE-DUPLICATE".equals(issue.rule())));
        assertTrue(result.issues().stream().anyMatch(issue -> "CANONICAL-DELIVERABLE-UNKNOWN".equals(issue.rule())));
    }

    @Test
    void canonicalCompilationEmitsExactCustomMapperAndServiceTypes() throws Exception {
        CanonicalPlanContractV2 source = canonicalFixture();
        List<CanonicalPlanContractV2.Deliverable> deliverables = new ArrayList<>(source.deliverables());
        replaceDeliverable(deliverables, "deliverable.mapper.4", item -> new CanonicalPlanContractV2.Deliverable(
                item.id(), item.kind(), item.name(), item.moduleId(), "CustomTicketMapper", item.moduleSide(),
                item.action(), List.of("CustomTicketMapper"), item.requiresTypes()));
        replaceDeliverable(deliverables, "deliverable.service.5", item -> new CanonicalPlanContractV2.Deliverable(
                item.id(), item.kind(), item.name(), item.moduleId(), "ICustomTicketService", item.moduleSide(),
                item.action(), List.of("ICustomTicketService", "CustomTicketServiceImpl"),
                item.requiresTypes().stream().map(type -> "TicketMapper".equals(type) ? "CustomTicketMapper" : type).toList()));
        CanonicalPlanContractV2 contract = copyContract(source, deliverables);
        GenerationContext canonicalContext = new GenerationContext(contract.generationIdentity(),
                ReferenceFrameworkProfile.defaults(), contract.toDomainContract(), contract);

        PlanArtifactCompilation result = new PlanArtifactCompiler().compileCanonical(302L,
                List.of("deliverable.mapper.4", "deliverable.service.5"), canonicalContext);

        assertTrue(result.issues().isEmpty(), result.issues().toString());
        assertTrue(has(result.artifacts(), "CustomTicketMapper", ArtifactKind.MAPPER));
        assertTrue(has(result.artifacts(), "CustomTicketMapper", ArtifactKind.MAPPER_XML));
        assertTrue(has(result.artifacts(), "ICustomTicketService", ArtifactKind.SERVICE_INTERFACE));
        assertTrue(has(result.artifacts(), "CustomTicketServiceImpl", ArtifactKind.SERVICE_IMPL));
    }

    @Test
    void canonicalCompilationPreservesCrossModuleOwnership() throws Exception {
        CanonicalPlanContractV2 source = canonicalFixture();
        List<Map<String, Object>> modules = new ArrayList<>(source.modules());
        modules.add(Map.of("id", "module.hotwork", "name", "hotwork",
                "basePackage", "org.springblade.hotwork", "kind", "EXISTING"));
        List<CanonicalPlanContractV2.Deliverable> deliverables = new ArrayList<>(source.deliverables());
        replaceDeliverable(deliverables, "deliverable.controller.6", item -> new CanonicalPlanContractV2.Deliverable(
                item.id(), item.kind(), item.name(), "module.hotwork", item.className(), item.moduleSide(),
                "MODIFY", item.providesTypes(), item.requiresTypes()));
        CanonicalPlanContractV2 contract = new CanonicalPlanContractV2(source.contractVersion(), source.sourceHash(),
                source.sourceMode(), source.referenceSnapshotId(), source.referenceProfile(), source.rulesetVersion(), source.identity(),
                source.fields(), source.domains(), modules, source.aggregates(), source.entities(), source.states(),
                source.integrations(), deliverables, source.referenceBindings(), source.architectureDecisions());
        GenerationContext canonicalContext = new GenerationContext(contract.generationIdentity(),
                ReferenceFrameworkProfile.defaults(), contract.toDomainContract(), contract);

        PlanArtifactCompilation result = new PlanArtifactCompiler().compileCanonical(304L,
                List.of("deliverable.controller.6"), canonicalContext);

        assertTrue(result.issues().isEmpty(), result.issues().toString());
        assertTrue(result.artifacts().stream().anyMatch(item -> "TicketController".equals(item.name())
                && "hotwork".equals(item.ownerModule())
                && "org.springblade.hotwork.controller".equals(item.declaredPackage())));
    }

    @Test
    void canonicalCompilationReportsEveryDeclaredTypeThatProducesNoArtifact() throws Exception {
        CanonicalPlanContractV2 source = canonicalFixture();
        List<CanonicalPlanContractV2.Deliverable> deliverables = new ArrayList<>(source.deliverables());
        replaceDeliverable(deliverables, "deliverable.entity.2", item -> new CanonicalPlanContractV2.Deliverable(
                item.id(), item.kind(), item.name(), item.moduleId(), item.className(), item.moduleSide(),
                item.action(), List.of("Ticket", "UndeclaredExtraEntity"), item.requiresTypes()));
        CanonicalPlanContractV2 contract = copyContract(source, deliverables);
        GenerationContext canonicalContext = new GenerationContext(contract.generationIdentity(),
                ReferenceFrameworkProfile.defaults(), contract.toDomainContract(), contract);

        PlanArtifactCompilation result = new PlanArtifactCompiler().compileCanonical(303L,
                List.of("deliverable.entity.2"), canonicalContext);

        assertTrue(result.issues().stream().anyMatch(issue ->
                "CANONICAL-PROVIDED-TYPE-NOT-EMITTED".equals(issue.rule())
                        && "UndeclaredExtraEntity".equals(issue.filePath())));
    }

    private CanonicalPlanContractV2 copyContract(CanonicalPlanContractV2 source,
                                                  List<CanonicalPlanContractV2.Deliverable> deliverables) {
        return new CanonicalPlanContractV2(source.contractVersion(), source.sourceHash(), source.sourceMode(),
                source.referenceSnapshotId(), source.referenceProfile(), source.rulesetVersion(), source.identity(), source.fields(),
                source.domains(), source.modules(), source.aggregates(), source.entities(), source.states(),
                source.integrations(), deliverables, source.referenceBindings(), source.architectureDecisions());
    }

    private void replaceDeliverable(List<CanonicalPlanContractV2.Deliverable> deliverables, String id,
                                    java.util.function.Function<CanonicalPlanContractV2.Deliverable,
                                            CanonicalPlanContractV2.Deliverable> replacement) {
        for (int index = 0; index < deliverables.size(); index++) {
            if (id.equals(deliverables.get(index).id())) {
                deliverables.set(index, replacement.apply(deliverables.get(index)));
                return;
            }
        }
        throw new IllegalArgumentException("Missing fixture deliverable " + id);
    }

    private CanonicalPlanContractV2 canonicalFixture() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/contracts/canonical-plan-contract-v2.json")) {
            return new ObjectMapper().readValue(input, CanonicalPlanContractV2.class);
        }
    }

    private boolean has(List<PlannedArtifact> artifacts, String name, ArtifactKind kind) {
        return artifacts.stream().anyMatch(item -> name.equals(item.name()) && kind == item.kind());
    }
}
