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

    private boolean hasRule(List<GeneratedProjectValidator.Issue> issues, String rule) {
        return issues.stream().anyMatch(issue -> rule.equals(issue.rule()));
    }
}
