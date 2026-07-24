package org.springblade.aiworkflow.vo;

import lombok.Data;

/**
 * 参考项目状态 VO — 阶段2增强。
 *
 * <p>The selected BladeX project is a read-only source of style and framework conventions.
 * 从该项目的同类代码提取结构化摘要注入 prompt,让生成的新模块贴合现有风格。
 * 参考项目只读,不写入(写入目标仍是 blade_hgsjy)。
 */
@Data
public class ReferenceProjectVO {

    /** 参考项目根路径(绝对路径),null 表示未设置 */
    private String path;

    /** 是否已扫描就绪(可作参考) */
    private boolean ready;

    /** 扫到的 .java 文件总数 */
    private int totalFiles;

    /** 成功索引的类数量 */
    private int indexedClasses;

    /** 逻辑模块数量 */
    private int moduleCount;

    /** 扫描完成时间(ISO-8601) */
    private String scannedAt;

    /** 扫描耗时(毫秒) */
    private long durationMillis;

    /**
     * 项目适配摘要 — 扫描时提取的版本/结构信息(Java版本/SpringBoot/jakarta-javax/pom配置等),
     * 供前端"扫描并应用"后展示,让用户确认参考项目的依赖版本。
     */
    private String adaptationSummary;
}
