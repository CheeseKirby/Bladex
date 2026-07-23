package org.springblade.aiworkflow.agent;

import org.junit.jupiter.api.Test;
import org.springblade.aiworkflow.enums.TaskType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectQualityRepairerTest {

    private final GenerationContext context = new GenerationContext(
            GenerationIdentity.of("safeprod", "SpecialPeriod", "blade_special_period", "org.springblade.safeprod"),
            ReferenceFrameworkProfile.defaults());

    @Test
    void rewritesWrongGeneratedImportWithoutCallingLlm() {
        GeneratedFile ivo = GeneratedFile.create(TaskType.OTHER,
                "blade-service-api/blade-safeprod-api/src/main/java/org/springblade/safeprod/pojo/vo/ivo/SpecialPeriodIVO.java",
                "package org.springblade.safeprod.pojo.vo.ivo; public class SpecialPeriodIVO {}");
        GeneratedFile controller = GeneratedFile.create(TaskType.STANDARD_CRUD_CONTROLLER,
                BladeXModuleLayout.controllerPath(context, "SpecialPeriod"), """
                package org.springblade.safeprod.controller;
                import org.springblade.safeprod.pojo.vo.SpecialPeriodIVO;
                public class SpecialPeriodController {}
                """);

        GeneratedFile repaired = ProjectQualityRepairer.repairGeneratedImports(controller, List.of(ivo, controller));

        assertTrue(repaired.getContent().contains("import org.springblade.safeprod.pojo.vo.ivo.SpecialPeriodIVO;"));
        assertFalse(repaired.getContent().contains("import org.springblade.safeprod.pojo.vo.SpecialPeriodIVO;"));
        assertEquals("MODIFY", repaired.getAction());
    }

    @Test
    void deterministicImportRepairDoesNotCrashWhenPersistedTypeIsMissing() {
        GeneratedFile ivo = new GeneratedFile(null,
                "blade-service-api/blade-safeprod-api/src/main/java/org/springblade/safeprod/pojo/vo/ivo/SpecialPeriodIVO.java",
                "package org.springblade.safeprod.pojo.vo.ivo; public class SpecialPeriodIVO {}", "CREATE");
        GeneratedFile controller = new GeneratedFile(null,
                BladeXModuleLayout.controllerPath(context, "SpecialPeriod"), """
                package org.springblade.safeprod.controller;
                import org.springblade.safeprod.pojo.vo.SpecialPeriodIVO;
                public class SpecialPeriodController {}
                """, "CREATE");

        ProjectQualityRepairer repairer = new ProjectQualityRepairer(
                new GeneratedProjectValidator(), new CrossFileValidator(), new ConventionValidator());
        ProjectQualityRepairer.RepairResult result = repairer.repair(
                List.of(ivo, controller), List.of(), context, null, Map.of(), 1,
                (source, projectContext, task, issueDescription) -> null,
                file -> true);

        assertTrue(result.files().stream()
                .filter(file -> file.getFilePath().endsWith("SpecialPeriodController.java"))
                .findFirst().orElseThrow().getContent()
                .contains("import org.springblade.safeprod.pojo.vo.ivo.SpecialPeriodIVO;"));
        assertTrue(result.events().stream().anyMatch(event -> "DETERMINISTIC_IMPORT".equals(event.strategy())
                && event.success()));
    }

    @Test
    void repairsControllerAndMapperThenRevalidatesStrictGate() {
        GeneratedFile ddl = GeneratedFile.create(TaskType.DDL_STATEMENT, BladeXModuleLayout.ddlPath(context), """
                CREATE TABLE blade_special_period (
                  id BIGINT,
                  period_name VARCHAR(100),
                  status INT,
                  is_deleted INT
                );
                """);
        GeneratedFile entity = GeneratedFile.create(TaskType.STANDARD_CRUD_ENTITY,
                BladeXModuleLayout.entityPath(context, "SpecialPeriod"), """
                package org.springblade.safeprod.pojo.entity;
                import org.springblade.core.mp.base.BaseEntity;
                public class SpecialPeriod extends BaseEntity { private String periodName; }
                """);
        GeneratedFile service = GeneratedFile.create(TaskType.STANDARD_CRUD_SERVICE,
                BladeXModuleLayout.serviceInterfacePath(context, "SpecialPeriod"), """
                package org.springblade.safeprod.service;
                public interface ISpecialPeriodService {
                    boolean checkPeriodNameUnique(String name);
                    boolean importExcel();
                    java.util.List<String> exportData();
                }
                """);
        GeneratedFile controller = GeneratedFile.create(TaskType.STANDARD_CRUD_CONTROLLER,
                BladeXModuleLayout.controllerPath(context, "SpecialPeriod"), """
                package org.springblade.safeprod.controller;
                public class SpecialPeriodController {}
                """);
        GeneratedFile mapperJava = GeneratedFile.create(TaskType.CUSTOM_MAPPER,
                BladeXModuleLayout.mapperJavaPath(context, "SpecialPeriod"), """
                package org.springblade.safeprod.mapper;
                public interface SpecialPeriodMapper {}
                """);
        GeneratedFile mapperXml = GeneratedFile.create(TaskType.MAPPER_XML,
                BladeXModuleLayout.mapperXmlPath(context, "SpecialPeriod"), """
                <mapper><resultMap id="result" type="SpecialPeriod">
                  <id property="id" column="id"/><result property="periodName" column="period_name"/>
                  <result property="content" column="content"/>
                </resultMap><select id="list" resultMap="result">SELECT id, period_name, content FROM blade_special_period</select></mapper>
                """);
        List<GeneratedFile> files = new ArrayList<>(List.of(ddl, entity, service, controller, mapperJava, mapperXml));
        files.addAll(BladeXModuleSkeleton.buildApiSide(context, false));
        files.addAll(BladeXModuleSkeleton.buildImplSide(context));
        AtomicTask controllerTask = task(TaskType.STANDARD_CRUD_CONTROLLER, controller.getFilePath());
        AtomicTask mapperTask = task(TaskType.MAPPER_XML, mapperXml.getFilePath());
        AtomicInteger fixes = new AtomicInteger();

        ProjectQualityRepairer repairer = new ProjectQualityRepairer(
                new GeneratedProjectValidator(), new CrossFileValidator(), new ConventionValidator() {
                    @Override
                    public ValidationResult validate(GeneratedFile file) {
                        return ValidationResult.pass();
                    }
                });
        ProjectQualityRepairer.RepairResult result = repairer.repair(
                files, List.of(), context, null,
                Map.of(controller.getFilePath(), controllerTask, mapperXml.getFilePath(), mapperTask),
                3,
                (source, projectContext, task, issueDescription) -> {
                    fixes.incrementAndGet();
                    if (source.getFilePath().endsWith("Controller.java")) {
                        return GenerationResult.llmSuccess(List.of(GeneratedFile.modify(source.getType(), source.getFilePath(), """
                                package org.springblade.safeprod.controller;
                                public class SpecialPeriodController {
                                    void importData(){ service.importExcel(); }
                                    void export(){ service.exportData(); }
                                }
                                """)));
                    }
                    return GenerationResult.llmSuccess(List.of(GeneratedFile.modify(source.getType(), source.getFilePath(), """
                            <mapper><resultMap id="result" type="SpecialPeriod">
                              <id property="id" column="id"/><result property="periodName" column="period_name"/>
                            </resultMap><select id="list" resultMap="result">SELECT id, period_name FROM blade_special_period</select></mapper>
                            """)));
                },
                file -> true);

        assertEquals(2, fixes.get());
        assertTrue(result.issues().stream().noneMatch(GeneratedProjectValidator.Issue::isError), result.issues().toString());
        assertTrue(result.events().stream().allMatch(ProjectQualityRepairer.RepairEvent::success));
    }


    @Test
    void persistedRepairIsMarkedFailedWhenTheSameFileStillHasErrorsAfterRevalidation() {
        ProjectQualityRepairer.RepairEvent attempted = ProjectQualityRepairer.RepairEvent.success(
                1, "src/SpecialPeriodController.java", "PROJECT_LLM", "controller repair persisted");
        GeneratedProjectValidator.Issue unresolved = new GeneratedProjectValidator.Issue(
                "ERROR", "UNRESOLVED-PROJECT-IMPORT", "src/SpecialPeriodController.java", "missing VO");

        List<ProjectQualityRepairer.RepairEvent> events = ProjectQualityRepairer.finalizeEventsAfterRevalidation(
                List.of(attempted), List.of(unresolved));

        assertFalse(events.get(0).success());
        assertTrue(events.get(0).detail().startsWith("FAILED_REVALIDATION:"));
    }

    @Test
    void rejectsAndDoesNotPersistLlmCandidateThatIntroducesANewProjectError() {
        String path = BladeXModuleLayout.controllerPath(context, "SpecialPeriod");
        GeneratedFile controller = GeneratedFile.create(TaskType.STANDARD_CRUD_CONTROLLER, path, """
                package org.springblade.safeprod.controller;
                public class SpecialPeriodController { String state = "bad"; }
                """);
        List<GeneratedFile> files = new ArrayList<>();
        files.add(controller);
        files.addAll(BladeXModuleSkeleton.buildImplSide(context));
        AtomicInteger persisted = new AtomicInteger();

        CrossFileValidator controlledValidator = new CrossFileValidator() {
            @Override
            public List<ContractIssue> validate(List<GeneratedFile> candidates, boolean planWide) {
                String content = candidates.stream()
                        .filter(file -> path.equals(file.getFilePath()))
                        .findFirst().orElseThrow().getContent();
                if (content.contains("worse")) {
                    return List.of(
                            new ContractIssue("ERROR", "service mismatch", "CROSS-CONTROLLER-SERVICE-MISMATCH", path, null),
                            new ContractIssue("ERROR", "new missing import", "CROSS-IMPORT-CLOSURE-MISSING", path, null));
                }
                return List.of(new ContractIssue("ERROR", "service mismatch",
                        "CROSS-CONTROLLER-SERVICE-MISMATCH", path, null));
            }
        };
        ProjectQualityRepairer repairer = new ProjectQualityRepairer(
                new GeneratedProjectValidator(), controlledValidator, new ConventionValidator() {
                    @Override
                    public ValidationResult validate(GeneratedFile file) {
                        return ValidationResult.pass();
                    }
                });

        ProjectQualityRepairer.RepairResult result = repairer.repair(
                files, List.of(), context, null, Map.of(path, task(TaskType.STANDARD_CRUD_CONTROLLER, path)), 1,
                (source, projectContext, task, issueDescription) -> GenerationResult.llmSuccess(List.of(
                        GeneratedFile.modify(source.getType(), source.getFilePath(), """
                                package org.springblade.safeprod.controller;
                                public class SpecialPeriodController { String state = "worse"; }
                                """))),
                file -> {
                    persisted.incrementAndGet();
                    return true;
                });

        assertEquals(0, persisted.get(), "globally worse candidate must never reach persistence");
        assertTrue(result.files().stream().filter(file -> path.equals(file.getFilePath()))
                .findFirst().orElseThrow().getContent().contains("state = \"bad\""));
        assertTrue(result.events().stream().anyMatch(event -> !event.success()
                && event.detail().startsWith("FAILED_REVALIDATION:")));
    }

    @Test
    void doesNotRewritePlatformImportsToGeneratedBusinessTypes() {
        GeneratedFile fakeBusinessBase = GeneratedFile.create(TaskType.STANDARD_CRUD_CONTROLLER,
                BladeXModuleLayout.namedControllerPath(context, "BladeController"), """
                package org.springblade.safeprod.controller;
                public class BladeController {}
                """);
        GeneratedFile controller = GeneratedFile.create(TaskType.STANDARD_CRUD_CONTROLLER,
                BladeXModuleLayout.controllerPath(context, "SpecialPeriod"), """
                package org.springblade.safeprod.controller;
                import org.springblade.core.boot.ctrl.BladeController;
                public class SpecialPeriodController extends BladeController {}
                """);

        GeneratedFile repaired = ProjectQualityRepairer.repairGeneratedImports(
                controller, List.of(fakeBusinessBase, controller));

        assertTrue(repaired.getContent().contains("import org.springblade.core.boot.ctrl.BladeController;"));
        assertFalse(repaired.getContent().contains("import org.springblade.safeprod.controller.BladeController;"));
    }

    @Test
    void repairsCanonicalEntityAndDdlAsOneRevalidatedBatch() {
        GenerationIdentity identity = GenerationIdentity.of(
                "safeprod", "SpecialPeriod", "blade_special_period", "org.springblade.safeprod");
        CanonicalDomainContract contract = new CanonicalDomainContract(identity, List.of(
                new CanonicalDomainContract.DomainField("periodName", "period_name", "String", true,
                        CanonicalDomainContract.FieldRole.PERSISTENT, java.util.Set.of(), "test"),
                new CanonicalDomainContract.DomainField("periodType", "period_type", "Integer", true,
                        CanonicalDomainContract.FieldRole.PERSISTENT, java.util.Set.of(), "test")
        ), List.of(), List.of());
        GenerationContext canonicalContext = new GenerationContext(identity, ReferenceFrameworkProfile.defaults(), contract);
        String ddlPath = BladeXModuleLayout.ddlPath(canonicalContext);
        String entityPath = BladeXModuleLayout.entityPath(canonicalContext, "SpecialPeriod");
        GeneratedFile ddl = GeneratedFile.create(TaskType.DDL_STATEMENT, ddlPath, """
                CREATE TABLE `blade_special_period` (
                  `id` BIGINT,
                  `period_name` VARCHAR(64),
                  `period_type_code` VARCHAR(16)
                ) ENGINE=InnoDB;
                """);
        GeneratedFile entity = GeneratedFile.create(TaskType.STANDARD_CRUD_ENTITY, entityPath, """
                package org.springblade.safeprod.pojo.entity;
                public class SpecialPeriod {
                    private String periodName;
                    private String periodTypeCode;
                }
                """);
        List<GeneratedFile> files = new ArrayList<>(List.of(ddl, entity));
        files.addAll(BladeXModuleSkeleton.buildApiSide(canonicalContext, false));
        AtomicTask ddlTask = task(TaskType.DDL_STATEMENT, ddlPath);
        ddlTask.setGenerationContext(canonicalContext);
        AtomicTask entityTask = task(TaskType.STANDARD_CRUD_ENTITY, entityPath);
        entityTask.setGenerationContext(canonicalContext);
        AtomicInteger persisted = new AtomicInteger();
        ProjectQualityRepairer repairer = new ProjectQualityRepairer(
                new GeneratedProjectValidator(), new CrossFileValidator(), new ConventionValidator() {
            @Override public ValidationResult validate(GeneratedFile file) { return ValidationResult.pass(); }
        });

        ProjectQualityRepairer.RepairResult result = repairer.repair(
                files, List.of(), canonicalContext, null,
                Map.of(ddlPath, ddlTask, entityPath, entityTask), 2,
                (source, projectContext, task, issueDescription) -> {
                    if (source.getFilePath().endsWith(".sql")) {
                        return GenerationResult.llmSuccess(List.of(GeneratedFile.modify(source.getType(), source.getFilePath(), """
                                CREATE TABLE `blade_special_period` (
                                  `id` BIGINT,
                                  `period_name` VARCHAR(64),
                                  `period_type` INT
                                ) ENGINE=InnoDB;
                                """)));
                    }
                    return GenerationResult.llmSuccess(List.of(GeneratedFile.modify(source.getType(), source.getFilePath(), """
                            package org.springblade.safeprod.pojo.entity;
                            public class SpecialPeriod {
                                private String periodName;
                                private Integer periodType;
                            }
                            """)));
                },
                file -> { persisted.incrementAndGet(); return true; });

        assertEquals(2, persisted.get());
        assertTrue(result.issues().stream().noneMatch(GeneratedProjectValidator.Issue::isError), result.issues().toString());
        assertTrue(result.events().stream().filter(ProjectQualityRepairer.RepairEvent::success).count() >= 2);
    }

    @Test
    void removesWholeDocumentMarkdownFenceBeforeLlmRepair() {
        String path = "blade-service/blade-safeprod/src/main/java/org/springblade/safeprod/helper/SourceHelper.java";
        GeneratedFile fenced = GeneratedFile.create(TaskType.OTHER, path, """
                ```java
                package org.springblade.safeprod.helper;
                public class SourceHelper {}
                ```
                """);
        List<GeneratedFile> files = new ArrayList<>();
        files.add(fenced);
        files.addAll(BladeXModuleSkeleton.buildImplSide(context));
        AtomicInteger persisted = new AtomicInteger();
        ProjectQualityRepairer repairer = new ProjectQualityRepairer(
                new GeneratedProjectValidator(), new CrossFileValidator(), new ConventionValidator());

        ProjectQualityRepairer.RepairResult result = repairer.repair(
                files, List.of(), context, null, Map.of(), 1,
                (source, projectContext, task, issueDescription) -> null,
                file -> {
                    persisted.incrementAndGet();
                    return true;
                });

        assertEquals(1, persisted.get());
        assertTrue(result.issues().isEmpty(), result.issues().toString());
        assertFalse(result.files().get(0).getContent().contains("```"));
        assertTrue(result.events().stream().anyMatch(event -> event.success()
                && "DETERMINISTIC_SOURCE_CLEANUP".equals(event.strategy())));
    }

    private AtomicTask task(TaskType type, String path) {
        AtomicTask task = new AtomicTask();
        task.setType(type);
        task.setTargetPath(path);
        task.setEntityName("SpecialPeriod");
        task.setModuleName("safeprod");
        task.setGenerationContext(context);
        task.setTaskDescription("repair project quality");
        return task;
    }
}
