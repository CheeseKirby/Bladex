package org.springblade.aiworkflow.agent;

import org.junit.jupiter.api.Test;
import org.springblade.aiworkflow.enums.TaskType;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneratedSourceGateTest {

    private final GeneratedSourceGate gate = new GeneratedSourceGate();

    @Test
    void validJavaPassesWithoutResolvingPrivateDependencies() {
        GeneratedFile file = GeneratedFile.create(TaskType.STANDARD_CRUD_SERVICE,
                "blade-service/blade-safe/src/main/java/org/springblade/safe/service/ISafeService.java", """
                package org.springblade.safe.service;
                import org.springblade.core.mp.base.BaseService;
                public interface ISafeService extends BaseService<MissingPlatformEntity> {
                }
                """);

        assertTrue(gate.validate(List.of(file)).isEmpty());
    }

    @Test
    void malformedJavaAndMarkdownResidueAreBlockingErrors() {
        GeneratedFile file = GeneratedFile.create(TaskType.OTHER,
                "blade-service/blade-safe/src/main/java/org/springblade/safe/service/Broken.java", """
                ```java
                package org.springblade.safe.service;
                public class Broken {
                ```
                """);

        List<GeneratedProjectValidator.Issue> issues = gate.validate(List.of(file));

        assertTrue(hasRule(issues, "SOURCE-MARKDOWN-FENCE"), issues.toString());
        assertTrue(hasRule(issues, "JAVA-SYNTAX-INVALID"), issues.toString());
    }

    @Test
    void packageAndTopLevelTypeMustMatchPhysicalPath() {
        GeneratedFile file = GeneratedFile.create(TaskType.OTHER,
                "blade-service/blade-safe/src/main/java/org/springblade/safe/service/Expected.java", """
                package org.springblade.wrong.service;
                public class Actual {}
                """);

        List<GeneratedProjectValidator.Issue> issues = gate.validate(List.of(file));

        assertTrue(hasRule(issues, "JAVA-PACKAGE-PATH-MISMATCH"), issues.toString());
        assertTrue(hasRule(issues, "JAVA-TOPLEVEL-TYPE-MISMATCH"), issues.toString());
    }

    @Test
    void javaSyntaxNewerThanJava8IsRejected() {
        GeneratedFile file = GeneratedFile.create(TaskType.OTHER,
                "blade-service/blade-safe/src/main/java/org/springblade/safe/service/NewerSyntax.java", """
                package org.springblade.safe.service;
                public record NewerSyntax(String value) {}
                """);

        List<GeneratedProjectValidator.Issue> issues = gate.validate(List.of(file));

        assertTrue(hasRule(issues, "JAVA-SYNTAX-INVALID"), issues.toString());
    }

    @Test
    void knownJacksonAnnotationTypesRequireExplicitImports() {
        GeneratedFile file = GeneratedFile.create(TaskType.OTHER,
                "blade-service-api/blade-safe-api/src/main/java/org/springblade/safe/vo/SafeVO.java", """
                package org.springblade.safe.vo;
                public class SafeVO {
                    @JsonSerialize(using = ToStringSerializer.class)
                    private Long id;
                }
                """);

        List<GeneratedProjectValidator.Issue> issues = gate.validate(List.of(file));

        assertTrue(hasRule(issues, "JAVA-IMPORT-MISSING"), issues.toString());
        assertTrue(GeneratedSourceGate.isSourceGateRule("JAVA-IMPORT-MISSING"));
    }

    @Test
    void todoAndFixmeCommentsAreBlockingButStringLiteralsAreAllowed() {
        GeneratedFile incomplete = GeneratedFile.create(TaskType.OTHER,
                "blade-service/blade-safe/src/main/java/org/springblade/safe/service/Incomplete.java", """
                package org.springblade.safe.service;
                public class Incomplete {
                    // TODO implement the canonical status transition
                    public String marker() { return "TODO is user-visible text"; }
                }
                """);
        GeneratedFile complete = GeneratedFile.create(TaskType.OTHER,
                "blade-service/blade-safe/src/main/java/org/springblade/safe/service/Complete.java", """
                package org.springblade.safe.service;
                public class Complete {
                    public String marker() { return "FIXME is user-visible text"; }
                }
                """);

        List<GeneratedProjectValidator.Issue> incompleteIssues = gate.validate(List.of(incomplete));
        List<GeneratedProjectValidator.Issue> completeIssues = gate.validate(List.of(complete));

        assertTrue(hasRule(incompleteIssues, "SOURCE-INCOMPLETE-PLACEHOLDER"), incompleteIssues.toString());
        assertTrue(GeneratedSourceGate.isSourceGateRule("SOURCE-INCOMPLETE-PLACEHOLDER"));
        assertFalse(hasRule(completeIssues, "SOURCE-INCOMPLETE-PLACEHOLDER"), completeIssues.toString());
    }

    @Test
    void standardMybatisDoctypeParsesWithoutNetworkAccess() {
        GeneratedFile file = GeneratedFile.create(TaskType.MAPPER_XML,
                "blade-service/blade-safe/src/main/resources/mapper/SafeMapper.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="org.springblade.safe.mapper.SafeMapper">
                  <select id="count" resultType="long">select count(1)</select>
                </mapper>
                """);

        assertTrue(gate.validate(List.of(file)).isEmpty());
    }

    @Test
    void malformedOrEntityExpandingXmlIsRejected() {
        GeneratedFile malformed = GeneratedFile.create(TaskType.MAPPER_XML,
                "src/main/resources/mapper/BrokenMapper.xml", "<mapper><select></mapper>");
        GeneratedFile unsafe = GeneratedFile.create(TaskType.MAPPER_XML,
                "src/main/resources/mapper/UnsafeMapper.xml", """
                <!DOCTYPE mapper [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <mapper namespace="x">&xxe;</mapper>
                """);

        List<GeneratedProjectValidator.Issue> issues = gate.validate(List.of(malformed, unsafe));

        assertTrue(hasRule(issues, "XML-SYNTAX-INVALID"), issues.toString());
        assertTrue(hasRule(issues, "XML-UNSAFE-DOCTYPE"), issues.toString());
        assertFalse(issues.isEmpty());
    }

    private boolean hasRule(List<GeneratedProjectValidator.Issue> issues, String rule) {
        return issues.stream().anyMatch(issue -> rule.equals(issue.rule()));
    }
}
