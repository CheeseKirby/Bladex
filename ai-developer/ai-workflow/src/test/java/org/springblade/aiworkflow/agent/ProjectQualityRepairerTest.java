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
