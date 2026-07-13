package org.springblade.aiworkflow.agent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 冲突检测器(H8 从 BladeXCodeAgent 拆出)- REAL 模式查重。
 *
 * <p>检测本次生成的类名/表名是否与目标项目已有冲突,避免覆盖真实项目代码。
 * 依赖 {@link ExistingProjectIndex}(强制重扫确保最新,降级回退缓存记 WARN)。
 *
 * @author AI Developer
 */
@Slf4j
@Component
public class ConflictDetector {

    private final ExistingProjectIndex existingProjectIndex;

    public ConflictDetector(ExistingProjectIndex existingProjectIndex) {
        this.existingProjectIndex = existingProjectIndex;
    }

    /**
     * REAL 模式查重必须用最新索引 - 目标项目可能在上次扫描后被改动(如并发写、手动改、
     * 或本 plan 前序子方案刚写入新文件)。用 force=true 强制重扫,确保不漏(代价 ~1.5s,
     * REAL 模式低频可接受)。降级策略:扫描失败才用缓存,且记 WARN(有覆盖风险)。
     *
     * @return 冲突描述(无冲突返回 null)
     */
    public String detectNameConflicts(List<GeneratedFile> files) {
        try {
            existingProjectIndex.scan(true);
        } catch (Exception e) {
            log.warn("REAL 模式查重: 强制重扫失败, 回退缓存(有覆盖风险): {}", e.getMessage());
        }
        if (existingProjectIndex.getCachedScan() == null) {
            log.warn("REAL 模式查重: 索引仍为空, 降级不查重(有覆盖风险)");
            return null;
        }

        for (GeneratedFile f : files) {
            if (f.getFilePath() == null || !f.getFilePath().endsWith(".java")) continue;
            // 类名 = 文件名去 .java
            String path = f.getFilePath();
            String simpleName = path.substring(path.lastIndexOf('/') + 1).replace(".java", "");
            if (existingProjectIndex.findBySimpleName(simpleName).isPresent()) {
                return "类名 " + simpleName + " 已存在于目标项目(" + path + ")";
            }
            // 表名: 从 content 提 @TableName("xxx")
            if (f.getContent() != null) {
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("@TableName\\s*\\(\\s*\"([^\"]+)\"").matcher(f.getContent());
                if (m.find()) {
                    String tableName = m.group(1);
                    boolean tableConflict = existingProjectIndex.getCachedClasses().stream()
                            .anyMatch(c -> tableName.equals(c.tableName()));
                    if (tableConflict) {
                        return "表名 " + tableName + " 已存在于目标项目(" + simpleName + ")";
                    }
                }
            }
        }
        return null;
    }
}
