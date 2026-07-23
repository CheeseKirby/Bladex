package org.springblade.aiworkflow.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReferenceProjectHealthTest {

    @TempDir
    Path tempDir;

    @Test
    void adaptationSummaryReportsPomModulesWhoseDirectoriesAreMissing() throws Exception {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project><properties><java.version>1.8</java.version></properties></project>
                """);
        Path service = Files.createDirectories(tempDir.resolve("blade-service"));
        Files.writeString(service.resolve("pom.xml"), """
                <project><modules>
                  <module>blade-existing</module>
                  <module>blade-missing</module>
                </modules></project>
                """);
        Files.createDirectories(service.resolve("blade-existing"));

        Path api = Files.createDirectories(tempDir.resolve("blade-service-api"));
        Files.writeString(api.resolve("pom.xml"), """
                <project><modules><module>blade-missing-api</module></modules></project>
                """);

        ReferenceProjectIndex index = new ReferenceProjectIndex();
        index.setPath(tempDir.toString());

        String summary = index.buildAdaptationSummary();

        assertNotNull(summary);
        assertTrue(summary.contains("REF-DANGLING-MODULE"));
        assertTrue(summary.contains("blade-service/blade-missing"));
        assertTrue(summary.contains("blade-service-api/blade-missing-api"));
        assertFalse(summary.contains("blade-service/blade-existing is declared"));
    }
}
