package org.springblade.aiworkflow.agent;

import org.junit.jupiter.api.Test;
import org.springblade.aiworkflow.enums.TaskType;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneratedProjectValidatorTest {

    private final GenerationContext context = new GenerationContext(
            GenerationIdentity.of("safeprod", "SpecialPeriod", "blade_special_period", "org.springblade.safeprod"),
            ReferenceFrameworkProfile.defaults());

    @Test
    void rejectsMissingDeliverablePathPackageMismatchAndDuplicateFqcn() {
        String code = "package org.springblade.safeprod.pojo.entity; public class SpecialPeriod {}";
        GeneratedFile wrongPath = GeneratedFile.create(TaskType.STANDARD_CRUD_ENTITY,
                "blade-service-api/blade-other-api/src/main/java/org/springblade/other/SpecialPeriod.java", code);
        GeneratedFile duplicate = GeneratedFile.create(TaskType.STANDARD_CRUD_ENTITY,
                "blade-service-api/blade-safeprod-api/src/main/java/org/springblade/safeprod/pojo/entity/Copy.java", code);
        ExpectedDeliverable expected = new ExpectedDeliverable(1L, TaskType.STANDARD_CRUD_ENTITY,
                BladeXModuleLayout.entityPath(context, "SpecialPeriod"), "SpecialPeriod", "safeprod", true);

        List<GeneratedProjectValidator.Issue> issues = new GeneratedProjectValidator()
                .validate(List.of(wrongPath, duplicate), List.of(expected), context, null);

        assertTrue(hasRule(issues, "DELIVERABLE-MISSING"));
        assertTrue(hasRule(issues, "PATH-PACKAGE-MISMATCH"));
        assertTrue(hasRule(issues, "DUPLICATE-FQCN"));
        assertTrue(hasRule(issues, "MODULE-IDENTITY-MISMATCH"));
    }

    @Test
    void rejectsServiceImportWithoutGeneratedApiDependency() {
        GeneratedFile entity = GeneratedFile.create(TaskType.STANDARD_CRUD_ENTITY,
                BladeXModuleLayout.entityPath(context, "SpecialPeriod"),
                "package org.springblade.safeprod.pojo.entity; public class SpecialPeriod {}");
        GeneratedFile service = GeneratedFile.create(TaskType.STANDARD_CRUD_SERVICE,
                BladeXModuleLayout.serviceInterfacePath(context, "SpecialPeriod"),
                "package org.springblade.safeprod.service; import org.springblade.safeprod.pojo.entity.SpecialPeriod; public interface ISpecialPeriodService {}");
        GeneratedFile pom = GeneratedFile.create(TaskType.OTHER, BladeXModuleLayout.implPomPath(context),
                "<project><artifactId>blade-safeprod</artifactId></project>");
        List<GeneratedFile> files = new java.util.ArrayList<>(List.of(entity, service, pom));
        files.addAll(BladeXModuleSkeleton.buildApiSide(context, false));
        files.addAll(BladeXModuleSkeleton.buildImplSide(context).stream()
                .filter(file -> !file.getFilePath().endsWith("pom.xml")).toList());

        List<GeneratedProjectValidator.Issue> issues = new GeneratedProjectValidator()
                .validate(files, List.of(), context, null);
        assertTrue(hasRule(issues, "MAVEN-INTERNAL-DEPENDENCY-MISSING"));
    }

    @Test
    void rejectsMapperColumnsAndEntityFieldsOutsideGeneratedDdl() {
        GeneratedFile ddl = GeneratedFile.create(TaskType.DDL_STATEMENT, BladeXModuleLayout.ddlPath(context), """
                CREATE TABLE blade_special_period (
                  id BIGINT,
                  period_name VARCHAR(100),
                  is_deleted INT
                );
                """);
        GeneratedFile entity = GeneratedFile.create(TaskType.STANDARD_CRUD_ENTITY,
                BladeXModuleLayout.entityPath(context, "SpecialPeriod"), """
                package org.springblade.safeprod.pojo.entity;
                public class SpecialPeriod { private String periodName; private String remark; }
                """);
        GeneratedFile xml = GeneratedFile.create(TaskType.MAPPER_XML,
                BladeXModuleLayout.mapperXmlPath(context, "SpecialPeriod"), """
                <mapper><select id="list" resultType="SpecialPeriod">
                  SELECT id, period_name, remark FROM blade_special_period
                </select></mapper>
                """);
        List<GeneratedProjectValidator.Issue> issues = new GeneratedProjectValidator()
                .validate(List.of(ddl, entity, xml), List.of(), context, null);
        assertTrue(hasRule(issues, "ENTITY-DDL-COLUMN-MISSING"));
        assertTrue(hasRule(issues, "MAPPER-DDL-COLUMN-MISSING"));
    }

    @Test
    void rejectsBusinessServiceMethodsThatHaveNoControllerCall() {
        GeneratedFile service = GeneratedFile.create(TaskType.STANDARD_CRUD_SERVICE,
                BladeXModuleLayout.serviceInterfacePath(context, "SpecialPeriod"), """
                package org.springblade.safeprod.service;
                public interface ISpecialPeriodService { boolean enable(Long id); boolean matchSpecialPeriod(Long id); }
                """);
        GeneratedFile controller = GeneratedFile.create(TaskType.STANDARD_CRUD_CONTROLLER,
                BladeXModuleLayout.controllerPath(context, "SpecialPeriod"), """
                package org.springblade.safeprod.controller;
                public class SpecialPeriodController { void enable(){ service.enable(1L); } }
                """);
        List<GeneratedProjectValidator.Issue> issues = new GeneratedProjectValidator()
                .validate(List.of(service, controller), List.of(), context, null);
        assertTrue(hasRule(issues, "CONTROLLER-SERVICE-BUSINESS-GAP"));
    }

    @Test
    void acceptsAReferenceAlignedJava8Module() {
        ReferenceFrameworkProfile profile = new ReferenceFrameworkProfile(
                "2.4.0.RELEASE", "1.8", "org.springblade", "blade-service-api", "blade-service",
                "2.4.0.RELEASE", "2.4.0.RELEASE", "${bladex.project.version}", "javax", "v2",
                "entity", java.util.Map.of("VO", "vo", "QVO", "vo.qvo", "IVO", "vo.ivo", "UVO", "vo.uvo", "EVO", "vo.evo"),
                "controller", "service", "service.impl", "mapper", "wrapper", "feign", "excel.support",
                true, "SPRING_CLOUD_APPLICATION", "blade_lxqt", "SPRING_PROFILES", "reference");
        GenerationContext aligned = new GenerationContext(context.identity(), profile);
        List<GeneratedFile> files = new java.util.ArrayList<>();
        files.add(GeneratedFile.create(TaskType.DDL_STATEMENT, BladeXModuleLayout.ddlPath(aligned), """
                CREATE TABLE blade_special_period (
                  id BIGINT,
                  period_name VARCHAR(100),
                  is_deleted INT
                );
                """));
        files.add(GeneratedFile.create(TaskType.STANDARD_CRUD_ENTITY,
                BladeXModuleLayout.entityPath(aligned, "SpecialPeriod"), """
                package org.springblade.safeprod.entity;
                public class SpecialPeriod { private String periodName; }
                """));
        files.add(GeneratedFile.create(TaskType.STANDARD_CRUD_SERVICE,
                BladeXModuleLayout.serviceInterfacePath(aligned, "SpecialPeriod"), """
                package org.springblade.safeprod.service;
                public interface ISpecialPeriodService { boolean enable(Long id); }
                """));
        files.add(GeneratedFile.create(TaskType.STANDARD_CRUD_CONTROLLER,
                BladeXModuleLayout.controllerPath(aligned, "SpecialPeriod"), """
                package org.springblade.safeprod.controller;
                public class SpecialPeriodController { void enable(){ service.enable(1L); } }
                """));
        files.add(GeneratedFile.create(TaskType.MAPPER_XML,
                BladeXModuleLayout.mapperXmlPath(aligned, "SpecialPeriod"), """
                <mapper><resultMap id="result" type="SpecialPeriod"><result property="periodName" column="period_name"/></resultMap>
                <select id="list" resultMap="result">SELECT id, period_name, is_deleted FROM blade_special_period</select></mapper>
                """));
        files.addAll(BladeXModuleSkeleton.buildApiSide(aligned, false));
        files.addAll(BladeXModuleSkeleton.buildImplSide(aligned));

        List<GeneratedProjectValidator.Issue> issues = new GeneratedProjectValidator()
                .validate(files, List.of(), aligned, null);
        assertTrue(issues.stream().noneMatch(GeneratedProjectValidator.Issue::isError), issues.toString());
    }

    @Test
    void acceptsMapperPropertiesInheritedFromBaseEntity() {
        GeneratedFile ddl = GeneratedFile.create(TaskType.DDL_STATEMENT, BladeXModuleLayout.ddlPath(context), """
                CREATE TABLE blade_special_period (
                  id BIGINT,
                  period_name VARCHAR(100),
                  create_user BIGINT,
                  create_dept BIGINT,
                  create_time DATETIME,
                  update_user BIGINT,
                  update_time DATETIME,
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
        GeneratedFile xml = GeneratedFile.create(TaskType.MAPPER_XML,
                BladeXModuleLayout.mapperXmlPath(context, "SpecialPeriod"), """
                <mapper><resultMap id="result" type="SpecialPeriod">
                  <id property="id" column="id"/>
                  <result property="periodName" column="period_name"/>
                  <result property="createUser" column="create_user"/>
                  <result property="createDept" column="create_dept"/>
                  <result property="createTime" column="create_time"/>
                  <result property="updateUser" column="update_user"/>
                  <result property="updateTime" column="update_time"/>
                  <result property="status" column="status"/>
                  <result property="isDeleted" column="is_deleted"/>
                </resultMap></mapper>
                """);

        List<GeneratedProjectValidator.Issue> issues = new GeneratedProjectValidator()
                .validate(List.of(ddl, entity, xml), List.of(), context, null);

        assertTrue(issues.stream().noneMatch(issue -> "MAPPER-RESULT-PROPERTY-MISSING".equals(issue.rule())),
                issues.toString());
    }

    @Test
    void acceptsMapperPropertiesInheritedThroughVoEntityAndBaseEntity() {
        GeneratedFile ddl = GeneratedFile.create(TaskType.DDL_STATEMENT, BladeXModuleLayout.ddlPath(context), """
                CREATE TABLE blade_special_period (
                  id BIGINT,
                  period_name VARCHAR(100),
                  create_user BIGINT,
                  create_time DATETIME,
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
        GeneratedFile vo = GeneratedFile.create(TaskType.OTHER,
                BladeXModuleLayout.voPath(context, "SpecialPeriod", "VO"), """
                package org.springblade.safeprod.pojo.vo;
                import org.springblade.safeprod.pojo.entity.SpecialPeriod;
                public class SpecialPeriodVO extends SpecialPeriod { private String periodNameDesc; }
                """);
        GeneratedFile xml = GeneratedFile.create(TaskType.MAPPER_XML,
                BladeXModuleLayout.mapperXmlPath(context, "SpecialPeriod"), """
                <mapper>
                  <resultMap id="vo" type="org.springblade.safeprod.pojo.vo.SpecialPeriodVO">
                    <id property="id" column="id"/>
                    <result property="periodName" column="period_name"/>
                    <result property="createUser" column="create_user"/>
                    <result property="createTime" column="create_time"/>
                    <result property="status" column="status"/>
                    <result property="isDeleted" column="is_deleted"/>
                    <result property="periodNameDesc" column="period_name_desc"/>
                  </resultMap>
                  <resultMap id="entity" type="org.springblade.safeprod.pojo.entity.SpecialPeriod">
                    <id property="id" column="id"/>
                    <result property="periodName" column="period_name"/>
                  </resultMap>
                </mapper>
                """);

        List<GeneratedProjectValidator.Issue> issues = new GeneratedProjectValidator()
                .validate(List.of(ddl, entity, vo, xml), List.of(), context, null);

        assertTrue(issues.stream().noneMatch(issue -> "MAPPER-RESULT-PROPERTY-MISSING".equals(issue.rule())),
                issues.toString());
    }

    @Test
    void duplicatePathIsReportedOnceWithoutRepeatingDownstreamMapperDiagnostics() {
        GeneratedFile entity = GeneratedFile.create(TaskType.STANDARD_CRUD_ENTITY,
                BladeXModuleLayout.entityPath(context, "SpecialPeriod"), """
                package org.springblade.safeprod.pojo.entity;
                public class SpecialPeriod { private String periodName; }
                """);
        String xmlContent = """
                <mapper><resultMap id="result" type="SpecialPeriod">
                  <result property="missingField" column="missing_field"/>
                </resultMap></mapper>
                """;
        GeneratedFile first = GeneratedFile.create(TaskType.MAPPER_XML,
                BladeXModuleLayout.mapperXmlPath(context, "SpecialPeriod"), xmlContent);
        GeneratedFile duplicate = GeneratedFile.create(TaskType.MAPPER_XML,
                BladeXModuleLayout.mapperXmlPath(context, "SpecialPeriod"), xmlContent);

        List<GeneratedProjectValidator.Issue> issues = new GeneratedProjectValidator()
                .validate(List.of(entity, first, duplicate), List.of(), context, null);

        assertTrue(issues.stream().filter(issue -> "DUPLICATE-PATH".equals(issue.rule())).count() == 1, issues.toString());
        assertTrue(issues.stream().filter(issue -> "MAPPER-RESULT-PROPERTY-MISSING".equals(issue.rule())).count() == 1,
                issues.toString());
    }

    @Test
    void doesNotRequireControllerToCallInternalValidationHelper() {
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
                public class SpecialPeriodController {
                    void importData(){ service.importExcel(); }
                    void export(){ service.exportData(); }
                }
                """);

        List<GeneratedProjectValidator.Issue> issues = new GeneratedProjectValidator()
                .validate(List.of(service, controller), List.of(), context, null);

        assertTrue(issues.stream().noneMatch(issue -> issue.message().contains("checkPeriodNameUnique")), issues.toString());
    }
    private boolean hasRule(List<GeneratedProjectValidator.Issue> issues, String rule) {
        return issues.stream().anyMatch(issue -> rule.equals(issue.rule()));
    }
}
