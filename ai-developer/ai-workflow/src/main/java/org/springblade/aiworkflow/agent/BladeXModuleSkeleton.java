package org.springblade.aiworkflow.agent;

import org.springblade.aiworkflow.enums.TaskType;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Builds the API/service module skeleton from the detected reference profile. */
public final class BladeXModuleSkeleton {

    private BladeXModuleSkeleton() {
    }

    public static List<GeneratedFile> ensureFor(
            List<GeneratedFile> existing, Set<String> ensuredSkeletonKeys, GenerationContext context) {
        boolean hasApi = existing.stream().anyMatch(f -> "API".equals(BladeXModuleLayout.sideOfPath(f.getFilePath())));
        boolean hasImpl = existing.stream().anyMatch(f -> "IMPL".equals(BladeXModuleLayout.sideOfPath(f.getFilePath())));
        boolean hasExcel = existing.stream().anyMatch(f -> f.getFilePath() != null
                && (f.getFilePath().endsWith("Excel.java") || f.getFilePath().endsWith("EVO.java")
                || f.getFilePath().contains("/excel/")));
        List<GeneratedFile> result = new ArrayList<>();
        String module = context.identity().moduleName();
        if (hasApi && ensuredSkeletonKeys.add(module + ":API")) result.addAll(buildApiSide(context, hasExcel));
        if (hasImpl && ensuredSkeletonKeys.add(module + ":IMPL")) result.addAll(buildImplSide(context));
        return result;
    }

    public static List<GeneratedFile> buildApiSide(GenerationContext context, boolean hasExcel) {
        return List.of(GeneratedFile.create(TaskType.OTHER,
                BladeXModuleLayout.apiPomPath(context), apiPom(context, hasExcel)));
    }

    public static List<GeneratedFile> buildImplSide(GenerationContext context) {
        return List.of(
                GeneratedFile.create(TaskType.OTHER, BladeXModuleLayout.implPomPath(context), implPom(context)),
                GeneratedFile.create(TaskType.OTHER, BladeXModuleLayout.applicationPath(context), applicationClass(context)),
                GeneratedFile.create(TaskType.OTHER, BladeXModuleLayout.bootstrapPath(context), bootstrapYml(context)),
                GeneratedFile.create(TaskType.OTHER, BladeXModuleLayout.appDevPath(context), appDevYml()));
    }

    private static String apiPom(GenerationContext context, boolean hasExcel) {
        GenerationIdentity identity = context.identity();
        ReferenceFrameworkProfile profile = context.referenceProfile();
        String excel = hasExcel ? """
        <dependency>
            <groupId>cn.afterturn</groupId>
            <artifactId>easypoi-annotation</artifactId>
        </dependency>
""" : "";
        String swagger = profile.usesSwaggerV2() ? """
        <dependency>
            <groupId>io.swagger</groupId>
            <artifactId>swagger-annotations</artifactId>
        </dependency>
""" : """
        <dependency>
            <groupId>io.swagger.core.v3</groupId>
            <artifactId>swagger-annotations</artifactId>
        </dependency>
""";
        return """
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>%s</groupId>
        <artifactId>%s</artifactId>
        <version>%s</version>
        <relativePath>../pom.xml</relativePath>
    </parent>
    <artifactId>%s</artifactId>
    <packaging>jar</packaging>
    <dependencies>
%s%s    </dependencies>
</project>
""".formatted(profile.parentGroupId(), profile.apiParentArtifactId(), usableVersion(profile.apiParentVersion()),
                identity.apiModuleName(), swagger, excel);
    }

    private static String implPom(GenerationContext context) {
        GenerationIdentity identity = context.identity();
        ReferenceFrameworkProfile profile = context.referenceProfile();
        String resources = profile.mapperXmlInJava() ? """
    <build>
        <resources>
            <resource>
                <directory>src/main/java</directory>
                <filtering>false</filtering>
                <includes><include>**/*.xml</include></includes>
            </resource>
            <resource><directory>src/main/resources</directory></resource>
        </resources>
    </build>
""" : "";
        return """
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>%s</groupId>
        <artifactId>%s</artifactId>
        <version>%s</version>
        <relativePath>../pom.xml</relativePath>
    </parent>
    <artifactId>%s</artifactId>
    <packaging>jar</packaging>
    <dependencies>
        <dependency>
            <groupId>%s</groupId>
            <artifactId>%s</artifactId>
            <version>%s</version>
        </dependency>
        <dependency>
            <groupId>org.springblade</groupId>
            <artifactId>blade-core-boot</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springblade</groupId>
            <artifactId>blade-starter-swagger</artifactId>
        </dependency>
    </dependencies>
%s</project>
""".formatted(profile.parentGroupId(), profile.serviceParentArtifactId(), usableVersion(profile.serviceParentVersion()),
                identity.serviceModuleName(), profile.parentGroupId(), identity.apiModuleName(),
                usableVersion(profile.internalDependencyVersion()), resources);
    }

    private static String applicationClass(GenerationContext context) {
        GenerationIdentity identity = context.identity();
        String className = BladeXModuleLayout.capitalize(identity.moduleName()) + "Application";
        String annotationImports;
        String annotations;
        switch (context.referenceProfile().applicationStyle()) {
            case "SPRING_CLOUD_APPLICATION" -> {
                annotationImports = "import org.springframework.cloud.client.SpringCloudApplication;\n"
                        + "import org.springframework.context.annotation.ComponentScan;";
                annotations = "@SpringCloudApplication\n@ComponentScan(\"org.springblade.*\")";
            }
            case "SPRING_BOOT_APPLICATION" -> {
                annotationImports = "import org.springframework.boot.autoconfigure.SpringBootApplication;";
                annotations = "@SpringBootApplication";
            }
            default -> {
                annotationImports = "import org.springblade.core.cloud.client.BladeCloudApplication;";
                annotations = "@BladeCloudApplication";
            }
        }
        return """
package %s;

import org.springblade.core.cloud.feign.EnableBladeFeign;
import org.springblade.core.launch.BladeApplication;
%s

@EnableBladeFeign
%s
public class %s {
    public static void main(String[] args) {
        BladeApplication.run("%s", %s.class, args);
    }
}
""".formatted(identity.basePackage(), annotationImports, annotations, className, identity.serviceName(), className);
    }

    private static String bootstrapYml(GenerationContext context) {
        String profileBlock = "SPRING_PROFILES".equals(context.referenceProfile().profileStyle())
                ? "  profiles: dev\n"
                : "  config:\n    activate:\n      on-profile: dev\n";
        return """
spring:
  cloud:
    nacos:
      config:
        namespace: %s
      discovery:
        namespace: %s
%s""".formatted(context.referenceProfile().nacosNamespace(),
                context.referenceProfile().nacosNamespace(), profileBlock);
    }

    private static String appDevYml() {
        return """
server:
  port: ${blade.server.port:0}
spring:
  datasource:
    driver-class-name: com.mysql.cj.jdbc.Driver
    url: ${blade.datasource.dev.url}
    username: ${blade.datasource.dev.username}
    password: ${blade.datasource.dev.password}
""";
    }

    private static String usableVersion(String version) {
        return version == null || version.isBlank() || "UNKNOWN".equalsIgnoreCase(version) ? "${revision}" : version;
    }

    // Compatibility helpers for old unit tests.
    private static GenerationContext legacy(String module) {
        return new GenerationContext(GenerationIdentity.of(module, "Business", "blade_" + module,
                "org.springblade." + module), ReferenceFrameworkProfile.defaults());
    }
    public static List<GeneratedFile> ensureFor(List<GeneratedFile> existing, Set<String> keys) {
        String module = existing.stream().map(GeneratedFile::getFilePath).map(BladeXModuleLayout::moduleOfPath)
                .filter(v -> v != null).findFirst().orElse("business");
        return ensureFor(existing, keys, legacy(module));
    }
    public static List<GeneratedFile> buildApiSide(String module, boolean hasExcel) { return buildApiSide(legacy(module), hasExcel); }
    public static List<GeneratedFile> buildImplSide(String module) { return buildImplSide(legacy(module)); }
}
