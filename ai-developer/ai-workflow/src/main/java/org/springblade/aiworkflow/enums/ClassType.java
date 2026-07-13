package org.springblade.aiworkflow.enums;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 已有项目扫描的类类型分类。
 *
 * <p>用于阶段1"扫描已有 BladeX 项目"时,把扫到的每个 Java 类归入一个语义类别,
 * 供后续阶段(增/改/删)按类型查询。分类规则见 {@link #fromDeclaration}。
 */
@Getter
@AllArgsConstructor
public enum ClassType {

    ENTITY("ENTITY", "实体类"),
    SERVICE("SERVICE", "Service 接口"),
    SERVICE_IMPL("SERVICE_IMPL", "Service 实现类"),
    CONTROLLER("CONTROLLER", "控制器"),
    MAPPER("MAPPER", "Mapper 接口"),
    WRAPPER("WRAPPER", "Wrapper 转换类"),
    VO("VO", "视图对象"),
    FEIGN("FEIGN", "Feign 客户端"),
    OTHER("OTHER", "其他");

    private final String code;
    private final String desc;

    /**
     * 按类名后缀 + 包路径 + 注解/继承判定类型。后缀优先于包路径。
     *
     * <p>判定顺序(命中即返回,后者不会覆盖前者):
     * <ol>
     *   <li>{@code *Controller} → CONTROLLER</li>
     *   <li>{@code *ServiceImpl} → SERVICE_IMPL</li>
     *   <li>{@code *Mapper} → MAPPER</li>
     *   <li>{@code *Wrapper} → WRAPPER</li>
     *   <li>{@code *Client} → FEIGN(覆盖 I*Client 与 *Client)</li>
     *   <li>接口 {@code I*Service} → SERVICE</li>
     *   <li>{@code *EVO/*IVO/*UVO/*QVO/*VO}(长后缀优先)→ VO</li>
     *   <li>包路径含 {@code .entity} / {@code .pojo.entity},或有 @TableName,
     *       或 extends BaseEntity/TenantEntity/BladeEntity → ENTITY</li>
     *   <li>else → OTHER</li>
     * </ol>
     *
     * @param cid JavaParser 解析出的类/接口声明
     * @param pkg 该类的包路径(如 org.springblade.order.entity),可为空
     */
    public static ClassType fromDeclaration(ClassOrInterfaceDeclaration cid, String pkg) {
        String name = cid.getNameAsString();

        // 1-5. 后缀优先(类名比包路径更可靠,BladeX 约定类名后缀即职责)
        if (name.endsWith("Controller")) return CONTROLLER;
        // ServiceImpl 判定: 名字后缀 + 业务约束(排除 LauncherServiceImpl 等非业务启动器)
        // 业务 ServiceImpl: implements I*Service, 或 extends BaseServiceImpl, 或在 .service.impl 包
        if (name.endsWith("ServiceImpl") && isBusinessServiceImpl(cid, pkg)) return SERVICE_IMPL;
        if (name.endsWith("Mapper")) return MAPPER;
        if (name.endsWith("Wrapper")) return WRAPPER;
        if (name.endsWith("Client")) return FEIGN;

        // 6. Service 接口: IXxxService
        if (cid.isInterface() && name.startsWith("I") && name.endsWith("Service")) return SERVICE;

        // 7. VO: 长后缀优先(EVO/IVO/UVO/QVO 先于 VO,避免 IOrderVO 被当 VO 之外的)
        if (name.endsWith("EVO") || name.endsWith("IVO") || name.endsWith("UVO") || name.endsWith("QVO")) return VO;
        if (name.endsWith("VO")) return VO;

        // 8. Entity: 包路径 / @TableName / extends BaseEntity 系
        if (isEntity(cid, pkg)) return ENTITY;

        return OTHER;
    }

    /** Entity 判定: 包路径含 .entity, 或有 @TableName 注解, 或继承 BaseEntity/TenantEntity/BladeEntity。 */
    private static boolean isEntity(ClassOrInterfaceDeclaration cid, String pkg) {
        if (pkg != null && (pkg.endsWith(".entity") || pkg.contains(".entity.") || pkg.contains(".pojo.entity"))) {
            return true;
        }
        // @TableName 注解(BladeX Entity 标配)
        for (var anno : cid.getAnnotations()) {
            if ("TableName".equals(anno.getNameAsString())) return true;
        }
        // extends BaseEntity / TenantEntity / BladeEntity(简单名比对,不依赖 classpath)
        return cid.getExtendedTypes().stream()
                .anyMatch(t -> {
                    String n = t.getNameAsString();
                    // 取简单名(去包路径)
                    int dot = n.lastIndexOf('.');
                    String simple = dot >= 0 ? n.substring(dot + 1) : n;
                    return "BaseEntity".equals(simple) || "TenantEntity".equals(simple) || "BladeEntity".equals(simple);
                });
    }

    /**
     * 业务 ServiceImpl 判定 — 排除 LauncherServiceImpl 等非业务启动器(SPI 扩展)。
     * 满足任一即视为业务 ServiceImpl: 在 .service.impl 包 / implements I*Service / extends BaseServiceImpl。
     */
    private static boolean isBusinessServiceImpl(ClassOrInterfaceDeclaration cid, String pkg) {
        if (pkg != null && pkg.contains(".service.impl")) return true;
        boolean implementsIService = cid.getImplementedTypes().stream()
                .anyMatch(t -> {
                    String n = t.getNameAsString();
                    int dot = n.lastIndexOf('.');
                    String simple = dot >= 0 ? n.substring(dot + 1) : n;
                    return simple.startsWith("I") && simple.endsWith("Service");
                });
        if (implementsIService) return true;
        return cid.getExtendedTypes().stream()
                .anyMatch(t -> t.getNameAsString().endsWith("BaseServiceImpl"));
    }
}
