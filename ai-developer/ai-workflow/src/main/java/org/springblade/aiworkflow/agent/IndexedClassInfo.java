package org.springblade.aiworkflow.agent;

import org.springblade.aiworkflow.enums.ClassType;

import java.util.List;
import java.util.Map;

/**
 * 已有项目扫描的索引条目 — 单个 Java 类的结构摘要(不可变)。
 *
 * <p>由 {@link ExistingProjectIndex} 扫描目标项目时为每个顶层类型生成一份。
 * 阶段2/3(增/改/删)将基于此索引决策:新增时查重、改时定位、删时清理引用。
 *
 * @param simpleName           类简单名(如 Order)
 * @param packageName          包路径(如 org.springblade.order.entity)
 * @param type                 语义类型分类
 * @param interfaze           是否接口
 * @param module               逻辑模块名(如 order / education)
 * @param side                 归属侧:API / IMPL / OPS / PLATFORM / OTHER
 * @param mavenModulePath      Maven 模块相对路径(如 blade-service/blade-order),无则 null
 * @param relativePath         .java 文件相对项目根的路径
 * @param tableName            @TableName 注解的表名,无注解为 null
 * @param publicMethodSignatures public 非静态非构造方法签名(如 "save(Long,Order)")
 * @param fields               非静态字段名→类型(如 {orderNo: String}),已排除 serialVersionUID
 * @param imports              全限定 import 列表
 */
public record IndexedClassInfo(
        String simpleName,
        String packageName,
        ClassType type,
        boolean interfaze,
        String module,
        String side,
        String mavenModulePath,
        String relativePath,
        String tableName,
        List<String> publicMethodSignatures,
        Map<String, String> fields,
        List<String> imports
) {
    public IndexedClassInfo {
        // 不可变防御: 集合字段统一 copyOf(null 兜底为空集合,避免下游 NPE)
        publicMethodSignatures = publicMethodSignatures == null ? List.of() : List.copyOf(publicMethodSignatures);
        fields = fields == null ? Map.of() : Map.copyOf(fields);
        imports = imports == null ? List.of() : List.copyOf(imports);
    }
}
