package org.springblade.aiworkflow.agent;

import org.junit.jupiter.api.Test;
import org.springblade.aiworkflow.enums.TaskType;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HotworkFailedGenerationReplayTest {

    @Test
    void deterministicallyClosesObservedHotworkGenerationAsOneContractGroup() throws Exception {
        GenerationContext context = hotworkContext();
        List<GeneratedFile> files = loadReplay();
        files.addAll(BladeXModuleSkeleton.buildApiSide(context, false));
        files.addAll(BladeXModuleSkeleton.buildImplSide(context));
        GeneratedProjectValidator validator = new GeneratedProjectValidator();
        CrossFileValidator crossFileValidator = new CrossFileValidator();
        List<GeneratedProjectValidator.Issue> before = validator.validate(files, List.of(), context, null);
        List<CrossFileValidator.ContractIssue> crossBefore = crossFileValidator.validate(files, true);

        List<GeneratedProjectValidator.Issue> sourceBefore = new GeneratedSourceGate().validate(files);
        assertEquals(11, before.stream().filter(GeneratedProjectValidator.Issue::isError).count(), before.toString());
        assertEquals(3, sourceBefore.stream().filter(GeneratedProjectValidator.Issue::isError).count(), sourceBefore.toString());
        assertEquals(1, crossBefore.stream().filter(CrossFileValidator.ContractIssue::isError).count(), crossBefore.toString());
        assertEquals(1, crossBefore.stream().filter(issue -> !issue.isError()).count(), crossBefore.toString());

        ProjectQualityRepairer repairer = new ProjectQualityRepairer(
                validator, crossFileValidator, new ConventionValidator());
        ProjectQualityRepairer.RepairResult result = repairer.repair(
                files, List.of(), context, null, Map.of(), 2,
                (source, projectContext, task, issueDescription) -> null,
                file -> true);

        List<GeneratedProjectValidator.Issue> after = validator.validate(result.files(), List.of(), context, null);
        List<GeneratedProjectValidator.Issue> sourceAfter = new GeneratedSourceGate().validate(result.files());
        List<CrossFileValidator.ContractIssue> crossAfter = crossFileValidator.validate(result.files(), true);
        assertTrue(after.stream().noneMatch(GeneratedProjectValidator.Issue::isError), after.toString());
        assertTrue(sourceAfter.stream().noneMatch(GeneratedProjectValidator.Issue::isError), sourceAfter.toString());
        assertTrue(crossAfter.isEmpty(), crossAfter.toString());
        assertTrue(result.events().stream().anyMatch(event -> event.success()
                && "DETERMINISTIC_CONTRACT_GROUP".equals(event.strategy())), result.events().toString());

        String ivo = content(result.files(), "HotworkIVO.java");
        String uvo = content(result.files(), "HotworkUVO.java");
        String vo = content(result.files(), "HotworkVO.java");
        String controller = content(result.files(), "HotworkController.java");
        String serviceImpl = content(result.files(), "HotworkServiceImpl.java");
        String ddl = content(result.files(), "migration.sql");
        assertTrue(ivo.contains("private Long hotworkId;"));
        assertTrue(uvo.contains("extends HotworkIVO"));
        assertFalse(vo.contains("implements INode"));
        assertFalse(vo.contains("parentId"));
        assertTrue(controller.contains("hotworkService.submit(value)"));
        assertTrue(controller.contains("hotworkService.modify(value)"));
        assertTrue(controller.contains("hotworkService.upgrade(id)"));
        assertFalse(serviceImpl.contains("setHotworkId(null)"));
        assertTrue(ddl.contains("`tenant_id`"));
    }

    @Test
    void authoritativeTaskPathsWinWhenReferenceProfileUsesSplitVoPackages() throws Exception {
        GenerationContext context = hotworkContext(Map.of(
                "VO", "vo", "QVO", "vo.qvo", "IVO", "vo.ivo", "UVO", "vo.uvo", "EVO", "vo.evo"));
        List<GeneratedFile> files = loadReplay();
        BladeXModuleSkeleton.buildApiSide(context, false).stream()
                .filter(file -> !file.getFilePath().contains("/vo/"))
                .forEach(files::add);
        files.addAll(BladeXModuleSkeleton.buildImplSide(context));
        GeneratedProjectValidator validator = new GeneratedProjectValidator();
        CrossFileValidator crossFileValidator = new CrossFileValidator();
        ProjectQualityRepairer repairer = new ProjectQualityRepairer(
                validator, crossFileValidator, new ConventionValidator());

        ProjectQualityRepairer.RepairResult result = repairer.repair(
                files, List.of(), context, null, Map.of(), 2,
                (source, projectContext, task, issueDescription) -> null,
                file -> true);

        List<GeneratedProjectValidator.Issue> projectIssues = validator.validate(result.files(), List.of(), context, null);
        List<GeneratedProjectValidator.Issue> sourceIssues = new GeneratedSourceGate().validate(result.files());
        List<CrossFileValidator.ContractIssue> crossIssues = crossFileValidator.validate(result.files(), true);
        assertTrue(projectIssues.stream().noneMatch(GeneratedProjectValidator.Issue::isError), projectIssues.toString());
        assertTrue(sourceIssues.stream().noneMatch(GeneratedProjectValidator.Issue::isError), sourceIssues.toString());
        assertTrue(crossIssues.isEmpty(), crossIssues.toString());

        String uvoPath = "blade-service-api/blade-safety-control-api/src/main/java/"
                + "org/springblade/safetycontrol/vo/HotworkUVO.java";
        String uvo = result.files().stream()
                .filter(file -> uvoPath.equals(file.getFilePath()))
                .findFirst().orElseThrow().getContent();
        String service = content(result.files(), "IHotworkService.java");
        String controller = content(result.files(), "HotworkController.java");
        assertTrue(uvo.contains("package org.springblade.safetycontrol.vo;"), uvo);
        assertTrue(uvo.contains("extends HotworkIVO"), uvo);
        assertTrue(service.contains("import org.springblade.safetycontrol.vo.HotworkUVO;"), service);
        assertFalse(service.contains("org.springblade.safetycontrol.vo.uvo"), service);
        assertTrue(controller.contains("import org.springblade.safetycontrol.vo.HotworkIVO;"), controller);
        assertTrue(controller.contains("import org.springblade.safetycontrol.vo.HotworkUVO;"), controller);
        assertFalse(controller.contains("org.springblade.safetycontrol.vo.ivo"), controller);
        assertFalse(controller.contains("org.springblade.safetycontrol.vo.uvo"), controller);
    }

    @Test
    void repairsBladeX2SelectCountIntegerContractBeforeSourceGenerationCompletes() throws Exception {
        GenerationContext context = hotworkContext();
        List<GeneratedFile> files = loadReplay();
        for (int i = 0; i < files.size(); i++) {
            GeneratedFile file = files.get(i);
            if (!file.getFilePath().endsWith("HotworkServiceImpl.java")) continue;
            String content = file.getContent();
            int closingBrace = content.lastIndexOf('}');
            String incompatible = "\n\tprivate boolean hasDuplicate() {\n"
                    + "\t\tLong count = baseMapper.selectCount(null);\n"
                    + "\t\treturn count != null && count > 0L;\n\t}\n";
            files.set(i, GeneratedFile.modify(file.getType(), file.getFilePath(),
                    content.substring(0, closingBrace) + incompatible + content.substring(closingBrace)));
            break;
        }
        files.addAll(BladeXModuleSkeleton.buildApiSide(context, false));
        files.addAll(BladeXModuleSkeleton.buildImplSide(context));
        GeneratedProjectValidator validator = new GeneratedProjectValidator();
        List<GeneratedProjectValidator.Issue> before = validator.validate(files, List.of(), context, null);
        assertTrue(before.stream().anyMatch(issue ->
                "FRAMEWORK-SELECTCOUNT-TYPE-MISMATCH".equals(issue.rule())), before.toString());

        ProjectQualityRepairer.RepairResult result = new ProjectQualityRepairer(
                validator, new CrossFileValidator(), new ConventionValidator()).repair(
                files, List.of(), context, null, Map.of(), 2,
                (source, projectContext, task, issueDescription) -> null,
                file -> true);

        List<GeneratedProjectValidator.Issue> after = validator.validate(result.files(), List.of(), context, null);
        assertTrue(after.stream().noneMatch(GeneratedProjectValidator.Issue::isError), after.toString());
        String serviceImpl = content(result.files(), "HotworkServiceImpl.java");
        assertTrue(serviceImpl.contains("Integer count = baseMapper.selectCount(null);"), serviceImpl);
        assertFalse(serviceImpl.contains("Long count = baseMapper.selectCount(null);"), serviceImpl);
    }

    @Test
    void repairsCanonicalColumnsInCreateTableIfNotExistsDdl() throws Exception {
        GenerationContext context = hotworkContext();
        List<GeneratedFile> files = loadReplay();
        for (int i = 0; i < files.size(); i++) {
            GeneratedFile file = files.get(i);
            if (!file.getFilePath().endsWith("migration.sql")) continue;
            files.set(i, GeneratedFile.modify(file.getType(), file.getFilePath(), """
                    CREATE TABLE IF NOT EXISTS `blade_hotwork` (
                      `id` BIGINT(20) NOT NULL,
                      PRIMARY KEY (`id`)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                    """));
            break;
        }
        files.addAll(BladeXModuleSkeleton.buildApiSide(context, false));
        files.addAll(BladeXModuleSkeleton.buildImplSide(context));
        GeneratedProjectValidator validator = new GeneratedProjectValidator();
        List<GeneratedProjectValidator.Issue> before = validator.validate(files, List.of(), context, null);
        assertTrue(before.stream().anyMatch(issue ->
                "CANONICAL-DDL-COLUMN-MISSING".equals(issue.rule())), before.toString());

        ProjectQualityRepairer.RepairResult result = new ProjectQualityRepairer(
                validator, new CrossFileValidator(), new ConventionValidator()).repair(
                files, List.of(), context, null, Map.of(), 2,
                (source, projectContext, task, issueDescription) -> null,
                file -> true);

        List<GeneratedProjectValidator.Issue> after = validator.validate(result.files(), List.of(), context, null);
        assertTrue(after.stream().noneMatch(GeneratedProjectValidator.Issue::isError), after.toString());
        String ddl = content(result.files(), "migration.sql");
        assertTrue(ddl.contains("CREATE TABLE IF NOT EXISTS `blade_hotwork`"), ddl);
        assertTrue(ddl.contains("`tenant_id`"), ddl);
        for (CanonicalDomainContract.DomainField field : context.domainContract().persistentFields()) {
            assertTrue(ddl.contains("`" + field.columnName() + "`"), field.columnName() + ": " + ddl);
        }
    }

    private List<GeneratedFile> loadReplay() throws Exception {
        URI rootUri = getClass().getClassLoader().getResource("replays/hotwork-failed-generation").toURI();
        Path root = Path.of(rootUri);
        List<GeneratedFile> files = new ArrayList<>();
        try (var stream = Files.walk(root)) {
            for (Path file : stream.filter(Files::isRegularFile).toList()) {
                String relative = root.relativize(file).toString().replace('\\', '/');
                files.add(GeneratedFile.create(taskType(relative), relative,
                        Files.readString(file, StandardCharsets.UTF_8)));
            }
        }
        return files;
    }

    private TaskType taskType(String path) {
        if (path.endsWith(".sql")) return TaskType.DDL_STATEMENT;
        if (path.endsWith("Controller.java")) return TaskType.STANDARD_CRUD_CONTROLLER;
        if (path.endsWith("Service.java") || path.endsWith("ServiceImpl.java")) return TaskType.STANDARD_CRUD_SERVICE;
        if (path.endsWith("Mapper.java")) return TaskType.CUSTOM_MAPPER;
        if (path.endsWith("Mapper.xml")) return TaskType.MAPPER_XML;
        if (path.endsWith("Wrapper.java")) return TaskType.WRAPPER;
        if (path.endsWith("/entity/Hotwork.java")) return TaskType.STANDARD_CRUD_ENTITY;
        return TaskType.OTHER;
    }

    private GenerationContext hotworkContext() {
        return hotworkContext(Map.of("VO", "vo", "QVO", "vo", "IVO", "vo", "UVO", "vo", "EVO", "vo"));
    }

    private GenerationContext hotworkContext(Map<String, String> voPackages) {
        GenerationIdentity identity = new GenerationIdentity(
                "safetycontrol", "Hotwork", "blade_hotwork", "org.springblade.safetycontrol",
                "blade-safety-control-api", "blade-safety-control", "blade-safety-control");
        ReferenceFrameworkProfile profile = new ReferenceFrameworkProfile(
                "2.4.0.RELEASE", "1.8", "org.springblade", "blade-service-api", "blade-service",
                "2.4.0.RELEASE", "2.4.0.RELEASE", "2.4.0.RELEASE", "javax", "v2",
                "entity", voPackages,
                "controller", "service", "service.impl", "mapper", "wrapper", "feign", "vo",
                true, "BLADE_CLOUD_APPLICATION", "blade", "SPRING_PROFILES", null);
        List<CanonicalDomainContract.DomainField> fields = List.of(
                field("hotworkId", "hotwork_id", "Long", "动火作业主键ID"),
                field("applyCode", "apply_code", "String", "动火作业申请编号"),
                field("applyTime", "apply_time", "Date", "动火作业申请时间"),
                field("hotworkLevel", "hotwork_level", "Integer", "动火作业级别"),
                field("workContent", "work_content", "String", "动火作业内容"),
                field("isSpecialPeriod", "is_special_period", "Integer", "是否特殊时段"),
                field("upgradeFlag", "upgrade_flag", "Integer", "是否升级"),
                field("periodName", "period_name", "String", "特殊时段名称"),
                field("periodType", "period_type", "Integer", "特殊时段类型"),
                field("startTime", "start_time", "Date", "开始时间"),
                field("endTime", "end_time", "Date", "结束时间"));
        CanonicalDomainContract domain = new CanonicalDomainContract(identity, fields, List.of(), List.of());
        return new GenerationContext(identity, profile, domain);
    }

    private CanonicalDomainContract.DomainField field(String name, String column, String type, String evidence) {
        return new CanonicalDomainContract.DomainField(name, column, type, true,
                CanonicalDomainContract.FieldRole.PERSISTENT, Set.of(), evidence);
    }

    private String content(List<GeneratedFile> files, String suffix) {
        return files.stream().filter(file -> file.getFilePath().endsWith(suffix))
                .findFirst().orElseThrow().getContent();
    }
}