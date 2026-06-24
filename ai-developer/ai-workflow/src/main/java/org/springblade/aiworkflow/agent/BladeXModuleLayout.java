package org.springblade.aiworkflow.agent;

/**
 * BladeX 多模块路径布局工具。
 *
 * <p>把 TaskType + module + entity (+ voSuffix) 映射为相对输出根（outputRoot）的 BladeX 多模块路径，
 * 统一收口 parseAtomicTasks 的路径生成，避免硬编码 {@code src/main/java/...} 落到游离根 src。
 *
 * <p>产物布局（对齐参考 BladeX：blade-user / blade-desk）：
 * <ul>
 *   <li><b>API 模块</b> {@code blade-service-api/blade-{module}-api/src/main/java/org/springblade/{module}/}：
 *       {@code pojo/entity/{Entity}.java}、{@code pojo/vo/{Entity}{Suffix}.java}、{@code feign/I{Entity}Client.java}</li>
 *   <li><b>IMPL 模块</b> {@code blade-service/blade-{module}/src/main/java/org/springblade/{module}/}：
 *       controller / mapper(.java+.xml) / service(+impl) / wrapper / excel + {Module}Application.java</li>
 *   <li><b>DDL</b> {@code doc/sql/{module}/migration.sql}</li>
 * </ul>
 *
 * @author AI Developer
 */
public final class BladeXModuleLayout {

    private static final String API_BASE =
            "blade-service-api/blade-%s-api/src/main/java/org/springblade/%s";
    private static final String IMPL_BASE =
            "blade-service/blade-%s/src/main/java/org/springblade/%s";
    private static final String IMPL_RESOURCES = "blade-service/blade-%s/src/main/resources";

    private BladeXModuleLayout() {}

    // ─── DDL ───
    public static String ddlPath(String module) {
        return "doc/sql/" + module + "/migration.sql";
    }

    // ─── API 模块业务文件 ───
    public static String entityPath(String module, String entity) {
        return apiBase(module) + "/pojo/entity/" + entity + ".java";
    }

    public static String voPath(String module, String entity, String suffix) {
        return apiBase(module) + "/pojo/vo/" + entity + suffix + ".java";
    }

    public static String feignPath(String module, String entity) {
        return apiBase(module) + "/feign/I" + entity + "Client.java";
    }

    public static String apiPomPath(String module) {
        return "blade-service-api/blade-" + module + "-api/pom.xml";
    }

    // ─── IMPL 模块业务文件 ───
    public static String controllerPath(String module, String entity) {
        return implBase(module) + "/controller/" + entity + "Controller.java";
    }

    public static String serviceInterfacePath(String module, String entity) {
        return implBase(module) + "/service/I" + entity + "Service.java";
    }

    public static String serviceImplPath(String module, String entity) {
        return implBase(module) + "/service/impl/" + entity + "ServiceImpl.java";
    }

    public static String mapperJavaPath(String module, String entity) {
        return implBase(module) + "/mapper/" + entity + "Mapper.java";
    }

    public static String mapperXmlPath(String module, String entity) {
        return implBase(module) + "/mapper/" + entity + "Mapper.xml";
    }

    public static String wrapperPath(String module, String entity) {
        return implBase(module) + "/wrapper/" + entity + "Wrapper.java";
    }

    public static String excelPath(String module, String entity) {
        return implBase(module) + "/excel/" + entity + "Excel.java";
    }

    public static String applicationPath(String module) {
        return implBase(module) + "/" + capitalize(module) + "Application.java";
    }

    public static String implPomPath(String module) {
        return "blade-service/blade-" + module + "/pom.xml";
    }

    public static String bootstrapPath(String module) {
        return String.format(IMPL_RESOURCES, module) + "/bootstrap.yml";
    }

    public static String appDevPath(String module) {
        return String.format(IMPL_RESOURCES, module) + "/application-dev.yml";
    }

    // ─── 辅助：路径反推（骨架去重/对齐用） ───
    /** 从生成文件相对路径反推 module 名；无法识别返回 null。 */
    public static String moduleOfPath(String relPath) {
        if (relPath == null) return null;
        if (relPath.startsWith("blade-service-api/blade-")) {
            return extractBetween(relPath, "blade-service-api/blade-", "-api/");
        }
        if (relPath.startsWith("blade-service/blade-")) {
            return extractBetween(relPath, "blade-service/blade-", "/");
        }
        if (relPath.startsWith("doc/sql/")) {
            return extractBetween(relPath, "doc/sql/", "/");
        }
        return null;
    }

    /** 路径属于哪一侧："API" / "IMPL" / "DOC" / "OTHER"。 */
    public static String sideOfPath(String relPath) {
        if (relPath == null) return "OTHER";
        if (relPath.startsWith("blade-service-api/")) return "API";
        if (relPath.startsWith("blade-service/")) return "IMPL";
        if (relPath.startsWith("doc/sql/")) return "DOC";
        return "OTHER";
    }

    public static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ─── 内部 ───
    private static String apiBase(String module) {
        return String.format(API_BASE, module, module);
    }

    private static String implBase(String module) {
        return String.format(IMPL_BASE, module, module);
    }

    private static String extractBetween(String src, String prefix, String suffix) {
        int start = prefix.length();
        int end = src.indexOf(suffix, start);
        return end > start ? src.substring(start, end) : null;
    }
}
