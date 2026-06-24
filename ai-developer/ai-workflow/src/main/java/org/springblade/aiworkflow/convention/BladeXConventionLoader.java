package org.springblade.aiworkflow.convention;

import lombok.extern.slf4j.Slf4j;
import org.springblade.aiworkflow.config.AiWorkflowProperties;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BladeX规范文档加载器
 *
 * <p>在应用启动时加载所有BladeX规范文档,缓存在内存中供 PromptBuilder 使用。
 *
 * <p>加载顺序:
 * <ol>
 *   <li>当 {@code convention-docs-path} 以 {@code classpath:} 开头 → 走 classpath 扫描</li>
 *   <li>当配置的文件系统目录存在 → 走文件系统扫描</li>
 *   <li>两者都不可用 → 回退到 {@code classpath:bladex-docs/} (jar 包内置)</li>
 * </ol>
 *
 * @author AI Developer
 */
@Slf4j
public class BladeXConventionLoader {

    /** classpath 内置规范文档目录(打包进 jar 的兜底) */
    private static final String CLASSPATH_FALLBACK = "classpath:bladex-docs/*.md";
    private static final String CLASSPATH_PREFIX = "classpath:";

    private final AiWorkflowProperties properties;
    private final Map<String, String> conventionCache = new ConcurrentHashMap<>();

    public BladeXConventionLoader(AiWorkflowProperties properties) {
        this.properties = properties;
    }

    /**
     * 加载所有规范文档。
     */
    public void loadAll() {
        String configured = properties.getConventionDocsPath();
        log.info("正在加载 BladeX 规范文档,配置路径: {}", configured);

        // 1) 显式 classpath: 前缀
        if (configured != null && configured.startsWith(CLASSPATH_PREFIX)) {
            loadFromClasspath(configured.substring(CLASSPATH_PREFIX.length()));
        } else {
            // 2) 文件系统目录(支持开发期外部目录)
            boolean loaded = false;
            if (configured != null && !configured.isBlank()) {
                Path docsDir = Paths.get(configured);
                if (Files.isDirectory(docsDir)) {
                    loadFromFilesystem(docsDir);
                    loaded = true;
                } else {
                    log.warn("文件系统规范目录不存在,回退到 classpath: {}", docsDir.toAbsolutePath());
                }
            }
            // 3) 兜底: classpath:bladex-docs/
            if (!loaded) {
                loadFromClasspath("bladex-docs/");
            }
        }

        if (conventionCache.isEmpty()) {
            throw new IllegalStateException(
                    "未加载到任何 BladeX 规范文档,请检查 ai-workflow.convention-docs-path 配置 (当前=" + configured + ")");
        }
        log.info("BladeX 规范文档加载完成,共 {} 份", conventionCache.size());
    }

    private void loadFromFilesystem(Path docsDir) {
        try (var stream = Files.list(docsDir)) {
            stream.filter(p -> p.toString().endsWith(".md"))
                  .forEach(path -> {
                      String docName = path.getFileName().toString().replace(".md", "");
                      try {
                          String content = Files.readString(path, StandardCharsets.UTF_8);
                          conventionCache.put(docName, content);
                          log.info("[fs] 已加载规范文档: {} ({} 字符)", docName, content.length());
                      } catch (IOException e) {
                          log.error("[fs] 加载规范文档失败: {}", docName, e);
                      }
                  });
        } catch (IOException e) {
            log.error("[fs] 扫描规范文档目录失败: {}", docsDir, e);
        }
    }

    private void loadFromClasspath(String pattern) {
        // 标准化匹配模式
        String locationPattern = pattern.endsWith("/")
                ? "classpath*:" + pattern + "*.md"
                : (pattern.contains("*") ? "classpath*:" + pattern : "classpath*:" + pattern + "/*.md");
        ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        try {
            Resource[] resources = resolver.getResources(locationPattern);
            for (Resource resource : resources) {
                String filename = resource.getFilename();
                if (filename == null || !filename.endsWith(".md")) {
                    continue;
                }
                String docName = filename.replace(".md", "");
                try (InputStream in = resource.getInputStream()) {
                    String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    conventionCache.put(docName, content);
                    log.info("[classpath] 已加载规范文档: {} ({} 字符)", docName, content.length());
                }
            }
        } catch (IOException e) {
            log.error("[classpath] 扫描规范文档失败: pattern={}", locationPattern, e);
            // 如果还是兜底失败,继续抛在 loadAll 末尾
            if (CLASSPATH_FALLBACK.endsWith(pattern)) {
                throw new IllegalStateException("classpath 兜底规范目录扫描失败: " + locationPattern, e);
            }
        }
    }

    /** 获取指定规范文档内容 */
    public String getContent(String docName) {
        return conventionCache.getOrDefault(docName, "");
    }

    /** 获取所有已加载的文档名称 */
    public Map<String, String> getAllContent() {
        return Map.copyOf(conventionCache);
    }

    public String getDataLayerConvention() {
        return getContent("bladex-data-layer");
    }

    public String getBusinessLayerConvention() {
        return getContent("bladex-business-layer");
    }

    public String getFeignConvention() {
        return getContent("bladex-feign");
    }

    public String getExcelConvention() {
        return getContent("bladex-excel");
    }

    public String getArchitectureConvention() {
        return getContent("bladex-architecture");
    }

    public String getConfigConvention() {
        return getContent("bladex-config");
    }
}
