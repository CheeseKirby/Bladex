package org.springblade.aiworkflow.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReferenceFrameworkProfileTest {

    @TempDir
    Path root;

    @Test
    void defaultsToBladeX410JakartaAndOpenApiV3() {
        ReferenceFrameworkProfile profile = ReferenceFrameworkProfile.defaults();
        assertEquals("4.1.0.RELEASE", profile.bladeXVersion());
        assertEquals("17", profile.javaVersion());
        assertEquals("jakarta", profile.validationNamespace());
        assertEquals("v3", profile.swaggerGeneration());
    }

    @Test
    void snapshotLeaseBlocksReferenceSwitchAndRejectsDrift() throws Exception {
        ReferenceProjectIndex index = new ReferenceProjectIndex();
        ReferenceProjectIndex.SnapshotLease lease = index.acquireSnapshot(null, false);
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            java.util.concurrent.Future<?> change = executor.submit(() -> index.setPath(root.toString()));
            Thread.sleep(100);
            assertFalse(change.isDone());
            lease.close();
            change.get(2, java.util.concurrent.TimeUnit.SECONDS);
            index.scan(true);
            assertThrows(IllegalStateException.class, () -> index.acquireSnapshot("ref-stale", true));
        } finally {
            lease.close();
            executor.shutdownNow();
        }
    }

    @Test
    void structuredPlanWithoutReferenceProjectFallsBackToDefaults() {
        ReferenceProjectIndex index = new ReferenceProjectIndex();
        // No reference project configured; a structured plan carries no snapshotId and must fall back to defaults.
        ReferenceProjectIndex.SnapshotLease lease = index.acquireSnapshot(null, true);
        try {
            ReferenceFrameworkProfile profile = lease.profile();
            assertEquals("4.1.0.RELEASE", profile.bladeXVersion());
            assertEquals("17", profile.javaVersion());
            assertEquals("jakarta", profile.validationNamespace());
            assertEquals("v3", profile.swaggerGeneration());
        } finally {
            lease.close();
        }
    }

    @Test
    void detectsVersionPackagesAndApplicationStyleFromReferenceSource() throws Exception {
        write("pom.xml", """
                <project><modelVersion>4.0.0</modelVersion><groupId>org.springblade</groupId>
                <artifactId>blade</artifactId><version>2.4.0.RELEASE</version>
                <properties><java.version>1.8</java.version></properties></project>
                """);
        write("blade-service-api/pom.xml", """
                <project><modelVersion>4.0.0</modelVersion><artifactId>blade-service-api</artifactId>
                <version>2.4.0.RELEASE</version></project>
                """);
        write("blade-service/pom.xml", """
                <project><modelVersion>4.0.0</modelVersion><artifactId>blade-service</artifactId>
                <version>2.4.0.RELEASE</version></project>
                """);
        write("blade-service-api/blade-demo-api/src/main/java/org/springblade/demo/entity/Demo.java", """
                package org.springblade.demo.entity;
                import javax.validation.constraints.NotNull;
                import io.swagger.annotations.ApiModel;
                public class Demo extends BaseEntity { private String name; }
                """);
        write("blade-service-api/blade-demo-api/src/main/java/org/springblade/demo/vo/ivo/DemoIVO.java", """
                package org.springblade.demo.vo.ivo;
                public class DemoIVO { private String name; }
                """);
        write("blade-service/blade-demo/src/main/java/org/springblade/demo/controller/DemoController.java", """
                package org.springblade.demo.controller;
                public class DemoController { }
                """);
        write("blade-service/blade-demo/src/main/java/org/springblade/demo/DemoApplication.java", """
                package org.springblade.demo;
                import org.springframework.cloud.client.SpringCloudApplication;
                @SpringCloudApplication public class DemoApplication { }
                """);
        write("blade-service/blade-demo/src/main/resources/bootstrap.yml", """
                spring:
                  profiles: dev
                  cloud:
                    nacos:
                      config:
                        namespace: blade_lxqt
                """);

        ReferenceProjectIndex index = new ReferenceProjectIndex();
        index.setPath(root.toString());
        index.scan(true);
        ReferenceFrameworkProfile profile = index.getFrameworkProfile();

        assertEquals("2.4.0.RELEASE", profile.bladeXVersion());
        assertEquals("1.8", profile.javaVersion());
        assertEquals("javax", profile.validationNamespace());
        assertEquals("v2", profile.swaggerGeneration());
        assertEquals("entity", profile.entityPackageSuffix());
        assertEquals("vo.ivo", profile.voPackageSuffix("DemoIVO"));
        assertEquals("SPRING_CLOUD_APPLICATION", profile.applicationStyle());
        assertEquals("blade_lxqt", profile.nacosNamespace());
        assertEquals("SPRING_PROFILES", profile.profileStyle());
        assertTrue(profile.describeForPrompt().contains("2.4.0.RELEASE"));
        String registered = index.buildParentPomWithModule("blade-service-api/pom.xml", "blade-safeprod-api");
        assertTrue(registered.contains("<module>blade-safeprod-api</module>"));
    }

    private void write(String relative, String content) throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
