package org.springblade.aiworkflow.agent;

import org.springblade.aiworkflow.enums.TaskType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * BladeX 模块骨架生成器。
 *
 * <p>根据已生成的业务文件，为其所属的 BladeX 多模块（API / IMPL）补齐骨架文件：
 * pom.xml、Application 启动类、bootstrap.yml、application-dev.yml。
 * 模板 1:1 对齐参考 BladeX 项目（blade-user / blade-desk）。
 *
 * <p>骨架文件 type=OTHER（不触发业务规范校验重试），复用既有的写盘/落库流程。
 *
 * @author AI Developer
 */
public final class BladeXModuleSkeleton {

    private BladeXModuleSkeleton() {}

    /**
     * 扫描已生成文件，为每个 (module, side) 组合补齐尚未生成的骨架。
     *
     * @param existing        本次/本 plan 已生成的业务文件
     * @param ensuredSkeletonKeys 跨 subPlan 去重集合（存 "module:side"）；调用方 per-plan 持有
     * @return 新生成的骨架文件列表
     */
    public static List<GeneratedFile> ensureFor(List<GeneratedFile> existing, Set<String> ensuredSkeletonKeys) {
        List<GeneratedFile> skeletons = new ArrayList<>();
        // module -> 出现过的侧集合；module -> 是否含 Excel/EVO
        Map<String, Set<String>> moduleSides = new LinkedHashMap<>();
        Map<String, Boolean> moduleHasExcel = new LinkedHashMap<>();
        for (GeneratedFile f : existing) {
            String path = f.getFilePath();
            if (path == null) continue;
            String module = BladeXModuleLayout.moduleOfPath(path);
            if (module == null) continue;
            String side = BladeXModuleLayout.sideOfPath(path);
            moduleSides.computeIfAbsent(module, k -> new LinkedHashSet<>()).add(side);
            boolean excel = path.endsWith("Excel.java") || path.contains("EVO.java") || path.contains("/excel/")
                    || (f.getContent() != null && f.getContent().contains("@ExcelProperty"));
            if (excel) moduleHasExcel.merge(module, true, (a, b) -> a || b);
        }
        for (var entry : moduleSides.entrySet()) {
            String module = entry.getKey();
            boolean hasExcel = moduleHasExcel.getOrDefault(module, false);
            for (String side : entry.getValue()) {
                String key = module + ":" + side;
                if (ensuredSkeletonKeys.contains(key)) continue;
                ensuredSkeletonKeys.add(key);
                switch (side) {
                    case "API" -> skeletons.addAll(buildApiSide(module, hasExcel));
                    case "IMPL" -> skeletons.addAll(buildImplSide(module));
                    case "DOC" -> { /* DDL 无骨架 */ }
                    default -> { }
                }
            }
        }
        return skeletons;
    }

    /** API 模块骨架：pom.xml */
    public static List<GeneratedFile> buildApiSide(String module, boolean hasExcel) {
        List<GeneratedFile> files = new ArrayList<>();
        files.add(GeneratedFile.create(TaskType.OTHER,
                BladeXModuleLayout.apiPomPath(module), apiPom(module, hasExcel)));
        return files;
    }

    /** IMPL 模块骨架：pom.xml + Application + bootstrap.yml + application-dev.yml */
    public static List<GeneratedFile> buildImplSide(String module) {
        List<GeneratedFile> files = new ArrayList<>();
        files.add(GeneratedFile.create(TaskType.OTHER,
                BladeXModuleLayout.implPomPath(module), implPom(module)));
        files.add(GeneratedFile.create(TaskType.OTHER,
                BladeXModuleLayout.applicationPath(module), applicationClass(module)));
        files.add(GeneratedFile.create(TaskType.OTHER,
                BladeXModuleLayout.bootstrapPath(module), bootstrapYml()));
        files.add(GeneratedFile.create(TaskType.OTHER,
                BladeXModuleLayout.appDevPath(module), appDevYml()));
        return files;
    }

    // ─── 模板 ───

    private static String apiPom(String module, boolean hasExcel) {
        String easypoi = hasExcel ? """
                <dependency>
                    <groupId>cn.afterturn</groupId>
                    <artifactId>easypoi-annotation</artifactId>
                    <version>4.1.0</version>
                    <scope>compile</scope>
                </dependency>
""" : "";
        return ("""
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>org.springblade</groupId>
        <artifactId>blade-service-api</artifactId>
        <version>${revision}</version>
    </parent>
    <artifactId>blade-%s-api</artifactId>
    <name>%s-api</name>
    <packaging>jar</packaging>
    <dependencies>
        <dependency>
            <groupId>org.springblade</groupId>
            <artifactId>blade-starter-cache</artifactId>
            <version>${bladex.project.version}</version>
        </dependency>
        <dependency>
            <groupId>io.swagger</groupId>
            <artifactId>swagger-annotations</artifactId>
            <version>1.6.6</version>
            <scope>compile</scope>
        </dependency>
%s    </dependencies>
</project>
""").formatted(module, module, easypoi);
    }

    private static String implPom(String module) {
        return ("""
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>org.springblade</groupId>
        <artifactId>blade-service</artifactId>
        <version>${revision}</version>
    </parent>
    <artifactId>blade-%s</artifactId>
    <name>%s</name>
    <packaging>jar</packaging>
    <dependencies>
        <dependency>
            <groupId>org.springblade</groupId>
            <artifactId>blade-core-boot</artifactId>
            <version>${bladex.project.version}</version>
        </dependency>
        <dependency>
            <groupId>org.springblade</groupId>
            <artifactId>blade-starter-swagger</artifactId>
            <version>${bladex.project.version}</version>
        </dependency>
        <dependency>
            <groupId>org.springblade</groupId>
            <artifactId>blade-%s-api</artifactId>
            <version>${bladex.project.version}</version>
        </dependency>
    </dependencies>
</project>
""").formatted(module, module, module);
    }

    private static String applicationClass(String module) {
        String cls = BladeXModuleLayout.capitalize(module) + "Application";
        return """
package org.springblade.%s;

import org.springblade.core.cloud.feign.EnableBladeFeign;
import org.springblade.core.cloud.client.BladeCloudApplication;
import org.springblade.core.launch.BladeApplication;

@EnableBladeFeign
@BladeCloudApplication
public class %s {
    public static void main(String[] args) {
        BladeApplication.run("blade-%s-service", %s.class, args);
    }
}
""".formatted(module, cls, module, cls);
    }

    private static String bootstrapYml() {
        return """
# BladeX 启动配置 — 由代码生成器生成
# Nacos 命名空间默认 blade(纯净 BladeX);如目标环境不同,部署时调整。
spring:
  cloud:
    nacos:
      config:
        namespace: blade
      discovery:
        namespace: blade
  config:
    activate:
      on-profile: dev
""";
    }

    private static String appDevYml() {
        return """
# BladeX 开发环境配置 — 由代码生成器生成（端口/数据源占位符，从 Nacos 配置中心取值）
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
}
