package org.springblade.aiworkflow.agent;

import org.junit.jupiter.api.Test;
import org.springblade.aiworkflow.enums.TaskType;

import java.util.ArrayList;
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
    void acceptsHyphenatedPhysicalModuleNamesBoundByCanonicalIdentity() {
        GenerationIdentity identity = new GenerationIdentity(
                "safetycontrol", "HotWorkUpgrade", "blade_hot_work_upgrade",
                "org.springblade.safetycontrol", "blade-safety-control-api",
                "blade-safety-control", "blade-safety-control");
        GenerationContext aligned = new GenerationContext(identity, ReferenceFrameworkProfile.defaults());
        GeneratedFile entity = GeneratedFile.create(TaskType.STANDARD_CRUD_ENTITY,
                BladeXModuleLayout.entityPath(aligned, "HotWorkUpgrade"), """
                package org.springblade.safetycontrol.pojo.entity;
                public class HotWorkUpgrade {}
                """);

        List<GeneratedProjectValidator.Issue> issues = new GeneratedProjectValidator()
                .validate(List.of(entity), List.of(), aligned, null);

        assertTrue(issues.stream().noneMatch(issue -> "MODULE-IDENTITY-MISMATCH".equals(issue.rule())),
                issues.toString());
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
    @Test
    void canonicalContractRejectsAliasFieldsAndIncompleteInputModels() {
        GenerationIdentity identity = GenerationIdentity.of(
                "safeprod", "SpecialPeriod", "blade_special_period", "org.springblade.safeprod");
        CanonicalDomainContract contract = new CanonicalDomainContract(identity, List.of(
                new CanonicalDomainContract.DomainField("periodName", "period_name", "String", true,
                        CanonicalDomainContract.FieldRole.PERSISTENT, java.util.Set.of(), "test"),
                new CanonicalDomainContract.DomainField("periodType", "period_type", "Integer", true,
                        CanonicalDomainContract.FieldRole.PERSISTENT, java.util.Set.of(), "test"),
                new CanonicalDomainContract.DomainField("startDate", "start_date", "Date", true,
                        CanonicalDomainContract.FieldRole.PERSISTENT, java.util.Set.of(), "test")
        ), List.of(
                new CanonicalDomainContract.DomainField("periodTypeDesc", null, "String", false,
                        CanonicalDomainContract.FieldRole.DERIVED, java.util.Set.of(), "test")
        ), List.of());
        GenerationContext canonicalContext = new GenerationContext(identity, ReferenceFrameworkProfile.defaults(), contract);
        List<GeneratedFile> files = List.of(
                GeneratedFile.create(TaskType.DDL_STATEMENT, BladeXModuleLayout.ddlPath(canonicalContext), """
                        CREATE TABLE `blade_special_period` (
                          `id` BIGINT,
                          `period_name` VARCHAR(64),
                          `period_type_code` VARCHAR(16)
                        ) ENGINE=InnoDB;
                        """),
                GeneratedFile.create(TaskType.STANDARD_CRUD_ENTITY,
                        BladeXModuleLayout.entityPath(canonicalContext, "SpecialPeriod"), """
                        package org.springblade.safeprod.pojo.entity;
                        public class SpecialPeriod {
                            private String periodName;
                            private String periodTypeCode;
                        }
                        """),
                GeneratedFile.create(TaskType.OTHER,
                        BladeXModuleLayout.voPath(canonicalContext, "SpecialPeriod", "IVO"), """
                        package org.springblade.safeprod.pojo.vo;
                        public class SpecialPeriodIVO { private String periodName; }
                        """),
                GeneratedFile.create(TaskType.OTHER,
                        BladeXModuleLayout.voPath(canonicalContext, "SpecialPeriod", "UVO"), """
                        package org.springblade.safeprod.pojo.vo;
                        public class SpecialPeriodUVO { private Long id; }
                        """),
                GeneratedFile.create(TaskType.OTHER,
                        BladeXModuleLayout.voPath(canonicalContext, "SpecialPeriod", "VO"), """
                        package org.springblade.safeprod.pojo.vo;
                        public class SpecialPeriodVO extends SpecialPeriod { private Long id; private String periodName; private String inventedName; }
                        """));

        List<GeneratedProjectValidator.Issue> issues = new GeneratedProjectValidator()
                .validate(files, List.of(), canonicalContext, null);

        assertTrue(hasRule(issues, "CANONICAL-ENTITY-FIELD-MISSING"), issues.toString());
        assertTrue(hasRule(issues, "CANONICAL-ENTITY-FIELD-UNEXPECTED"), issues.toString());
        assertTrue(hasRule(issues, "CANONICAL-DDL-COLUMN-MISSING"), issues.toString());
        assertTrue(hasRule(issues, "CANONICAL-INPUT-FIELD-MISSING"), issues.toString());
        assertTrue(hasRule(issues, "CANONICAL-INPUT-VALIDATION-MISSING"), issues.toString());
        assertTrue(hasRule(issues, "CANONICAL-UVO-INHERITANCE"), issues.toString());
        assertTrue(hasRule(issues, "CANONICAL-VO-DERIVED-FIELD-MISSING"), issues.toString());
        assertTrue(hasRule(issues, "CANONICAL-VO-FIELD-UNEXPECTED"), issues.toString());
        assertTrue(hasRule(issues, "CANONICAL-VO-FIELD-SHADOW"), issues.toString());
    }

    @Test
    void canonicalInputValidationRecognizesFieldAnnotations() {
        GenerationIdentity identity = GenerationIdentity.of(
                "visit", "Visit", "blade_visit", "org.springblade.visit");
        CanonicalDomainContract contract = new CanonicalDomainContract(identity, List.of(
                new CanonicalDomainContract.DomainField("name", "name", "String", true,
                        CanonicalDomainContract.FieldRole.PERSISTENT, java.util.Set.of(), "test")
        ), List.of(), List.of());
        GenerationContext context = new GenerationContext(identity, ReferenceFrameworkProfile.defaults(), contract);
        List<GeneratedFile> files = List.of(
                GeneratedFile.create(TaskType.OTHER, BladeXModuleLayout.voPath(context, "Visit", "IVO"), """
                        import javax.validation.constraints.NotBlank;
                        class VisitIVO { @NotBlank private String name; }
                        """),
                GeneratedFile.create(TaskType.OTHER, BladeXModuleLayout.voPath(context, "Visit", "UVO"), """
                        class VisitUVO extends VisitIVO { private Long id; }
                        """));

        List<GeneratedProjectValidator.Issue> issues = new GeneratedProjectValidator()
                .validate(files, List.of(), context, null);
        assertTrue(issues.stream().noneMatch(issue ->
                "CANONICAL-INPUT-VALIDATION-MISSING".equals(issue.rule())), issues::toString);
    }


    @Test
    void mapperBaseColumnsAndQvoReferencesMustCloseAgainstDdlAndQvo() {
        GenerationContext context = new GenerationContext(
                GenerationIdentity.of("safeprod", "SpecialPeriod", "blade_special_period", "org.springblade.safeprod"),
                ReferenceFrameworkProfile.defaults());
        List<GeneratedFile> files = new ArrayList<>(List.of(
                GeneratedFile.create(TaskType.DDL_STATEMENT, BladeXModuleLayout.ddlPath(context), """
                        CREATE TABLE blade_special_period (
                          id BIGINT,
                          period_name VARCHAR(64)
                        ) ENGINE=InnoDB;
                        """),
                GeneratedFile.create(TaskType.STANDARD_CRUD_ENTITY,
                        BladeXModuleLayout.entityPath(context, "SpecialPeriod"), """
                        package org.springblade.safeprod.pojo.entity;
                        public class SpecialPeriod { private String periodName; }
                        """),
                GeneratedFile.create(TaskType.OTHER,
                        BladeXModuleLayout.voPath(context, "SpecialPeriod", "QVO"), """
                        package org.springblade.safeprod.pojo.vo;
                        public class SpecialPeriodQVO { private String periodName; }
                        """),
                GeneratedFile.create(TaskType.MAPPER_XML,
                        BladeXModuleLayout.mapperXmlPath(context, "SpecialPeriod"), """
                        <mapper namespace="org.springblade.safeprod.mapper.SpecialPeriodMapper">
                          <resultMap id="base" type="org.springblade.safeprod.pojo.entity.SpecialPeriod">
                            <id column="id" property="id"/>
                            <result column="tenant_id" property="tenantId"/>
                            <result column="period_name" property="periodName"/>
                          </resultMap>
                          <select id="list" resultMap="base">
                            select id, tenant_id, period_name from blade_special_period
                            <if test="qvo.status != null">and status = #{qvo.status}</if>
                          </select>
                        </mapper>
                        """)));
        files.addAll(BladeXModuleSkeleton.buildApiSide(context, false));
        files.addAll(BladeXModuleSkeleton.buildImplSide(context));

        List<GeneratedProjectValidator.Issue> issues = new GeneratedProjectValidator()
                .validate(files, List.of(), context, null);

        assertTrue(hasRule(issues, "MAPPER-DDL-COLUMN-MISSING"), issues.toString());
        assertTrue(hasRule(issues, "MAPPER-PARAM-PROPERTY-MISSING"), issues.toString());
    }

    private boolean hasRule(List<GeneratedProjectValidator.Issue> issues, String rule) {
        return issues.stream().anyMatch(issue -> rule.equals(issue.rule()));
    }
}
