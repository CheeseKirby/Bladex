package org.springblade.aiworkflow.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springblade.aiworkflow.entity.AiPlan;
import org.springblade.aiworkflow.enums.TaskType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationReportWriterTest {

    @TempDir
    Path tempDir;

    @Test
    void writesCanonicalDomainContractBesideManifest() throws Exception {
        GenerationIdentity identity = GenerationIdentity.of(
                "specialperiod", "SpecialPeriod", "blade_special_period", "org.springblade.specialperiod");
        CanonicalDomainContract contract = new CanonicalDomainContract(identity, List.of(
                new CanonicalDomainContract.DomainField("periodType", "period_type", "Integer", true,
                        CanonicalDomainContract.FieldRole.PERSISTENT, Set.of(214L), "plan55")
        ), List.of(), List.of());
        GenerationContext context = new GenerationContext(identity, ReferenceFrameworkProfile.defaults(), contract);
        AtomicTask task = new AtomicTask();
        task.setType(TaskType.STANDARD_CRUD_ENTITY);
        task.setTargetPath(BladeXModuleLayout.entityPath(context, "SpecialPeriod"));
        task.setGenerationContext(context);
        AiPlan plan = new AiPlan();
        plan.setReceptionId("rec-report-test");
        plan.setWriteTarget("ISOLATED");
        plan.setOutputDirectory(tempDir.toString());
        plan.setCompileVerificationStatus("PASSED_SOURCE_GATE_DEPENDENCIES_UNVERIFIED");
        ObjectMapper mapper = new ObjectMapper();

        List<GeneratedProjectValidator.Issue> projectIssues = List.of(
                new GeneratedProjectValidator.Issue("ERROR", "PROJECT-RULE", "A.java", "project issue"));
        List<CrossFileValidator.ContractIssue> contractIssues = List.of(
                new CrossFileValidator.ContractIssue("WARN", "cross issue", "CROSS-RULE", "B.java", "A.java"));
        GenerationReportWriter.write(plan, List.of(), List.of(), List.of(task),
                projectIssues, contractIssues, mapper);

        Path contractFile = tempDir.resolve("domain-contract.json");
        assertTrue(Files.exists(contractFile));
        JsonNode contractJson = mapper.readTree(contractFile.toFile());
        assertEquals("periodType", contractJson.path("persistentFields").get(0).path("name").asText());
        JsonNode manifest = mapper.readTree(tempDir.resolve("manifest.json").toFile());
        assertEquals("blade_special_period", manifest.path("domainContract").path("identity").path("tableName").asText());
        JsonNode validation = mapper.readTree(tempDir.resolve("validation-report.json").toFile());
        assertEquals(2, validation.size());
        assertEquals("PROJECT-RULE", validation.get(0).path("rule").asText());
        assertEquals("CROSS-RULE", validation.get(1).path("rule").asText());
    }
}
