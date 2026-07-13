package org.springblade.aiworkflow.vo;

import lombok.Data;
import org.springblade.aiworkflow.agent.IndexedClassInfo;

import java.util.List;
import java.util.Map;

/**
 * 已有项目扫描结果。
 *
 * <p>包含扫描元信息 + 按逻辑模块分组的类清单 + 扁平类列表。
 * 阶段1只读返回;阶段2/3 增删改时作为"现有项目认知"的依据。
 */
@Data
public class ProjectScanVO {

    /** 扫描元信息 */
    private ScanMeta meta;

    /** 按逻辑模块分组的类清单 */
    private List<ModuleGroup> modules;

    /** 扁平类列表(便于按名/类型直接查) */
    private List<IndexedClassInfo> classes;

    /** 扫描元信息 */
    @Data
    public static class ScanMeta {
        /** 解析后的项目根绝对路径 */
        private String projectRoot;
        /** 扫描完成时间(ISO-8601) */
        private String scannedAt;
        /** 扫到的 .java 文件总数 */
        private int totalFiles;
        /** 成功索引的类数量 */
        private int indexedClasses;
        /** 解析失败/超大被跳过的文件数 */
        private int skippedFiles;
        /** 扫描耗时(毫秒) */
        private long durationMillis;
        /** 是否来自缓存(force=false 且有缓存时为 true) */
        private boolean fromCache;
    }

    /** 按逻辑模块分组的类清单 */
    @Data
    public static class ModuleGroup {
        /** 模块名(如 order / education) */
        private String module;
        /** 该模块的类数量 */
        private int classCount;
        /** 各类型数量(ClassType.name() → count) */
        private Map<String, Integer> typeCounts;
        /** 该模块的类清单 */
        private List<IndexedClassInfo> classes;
    }
}
