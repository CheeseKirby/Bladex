package org.springblade.aiworkflow.service;

import org.springblade.aiworkflow.agent.IndexedClassInfo;
import org.springblade.aiworkflow.enums.ClassType;
import org.springblade.aiworkflow.vo.BrowseResult;
import org.springblade.aiworkflow.vo.ProjectScanVO;
import org.springblade.aiworkflow.vo.ReferenceProjectVO;

import java.util.List;

/**
 * 已有 BladeX 项目扫描服务 — 阶段1。
 *
 * <p>协调 {@link org.springblade.aiworkflow.agent.ExistingProjectIndex} 完成扫描与查询,
 * 对外提供"扫描"和"按条件查索引"两个能力。根路径由 {@code target-project-root} 配置决定,
 * 服务层负责校验根路径有效性,校验失败抛 {@link IllegalArgumentException}(→400)。
 */
public interface IProjectScanService {

    /**
     * 触发扫描。
     *
     * @param force true 强制重扫;false 有缓存返回缓存
     * @return 扫描结果 VO
     * @throws IllegalArgumentException 根不存在/非目录/文件数超限
     */
    ProjectScanVO scan(boolean force);

    /**
     * 按条件查索引(不触发扫描,仅读缓存)。
     *
     * @param module  模块名过滤(null 忽略)
     * @param type    ClassType 名过滤(null 忽略,大小写不敏感)
     * @param nameLike 类名包含子串(null/空 忽略)
     * @return 命中的类列表;未扫描过返回空列表
     */
    List<IndexedClassInfo> queryIndex(String module, String type, String nameLike);

    /** 缓存是否存在(供 Controller 判断 /index 是否该 404) */
    boolean hasCache();

    /**
     * 阶段2增强: 设置参考项目路径并扫描。
     *
     * @param path 参考项目根路径(绝对路径)
     * @return 扫描结果 VO
     * @throws IllegalArgumentException 路径不存在/非目录/文件数超限
     */
    ReferenceProjectVO setReferencePath(String path);

    /**
     * 阶段2增强: 查询参考项目当前状态(路径/是否就绪/文件数)。
     *
     * @return 状态 VO(path=null 表示未设置)
     */
    ReferenceProjectVO getReferenceStatus();

    /**
     * 阶段2增强: 浏览本机目录(只列子目录,供前端选参考项目路径)。
     *
     * @param path 当前目录绝对路径;null/空返回系统根(Windows 盘符)
     * @return 浏览结果(current/parent/dirs/accessible)
     */
    BrowseResult browse(String path);
}
