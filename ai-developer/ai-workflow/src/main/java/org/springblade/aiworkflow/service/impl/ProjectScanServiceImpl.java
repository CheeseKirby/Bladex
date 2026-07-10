package org.springblade.aiworkflow.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springblade.aiworkflow.agent.ExistingProjectIndex;
import org.springblade.aiworkflow.agent.IndexedClassInfo;
import org.springblade.aiworkflow.agent.ReferenceProjectIndex;
import org.springblade.aiworkflow.enums.ClassType;
import org.springblade.aiworkflow.service.IProjectScanService;
import org.springblade.aiworkflow.vo.BrowseResult;
import org.springblade.aiworkflow.vo.ProjectScanVO;
import org.springblade.aiworkflow.vo.ReferenceProjectVO;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * 已有项目扫描服务实现 — 阶段1。
 *
 * <p>薄封装:校验 + 委托 {@link ExistingProjectIndex}。扫描/缓存/解析全在 index 内,
 * 此处只做"根路径有效性"前置校验(实际 index.scan 内部也会校验,这里提前抛 400 更清晰)
 * 与 ClassType 字符串→枚举的安全解析(非法 type 返回空而非 500)。
 */
@Slf4j
@Service
public class ProjectScanServiceImpl implements IProjectScanService {

    private final ExistingProjectIndex index;
    private final ReferenceProjectIndex referenceIndex;

    public ProjectScanServiceImpl(ExistingProjectIndex index, ReferenceProjectIndex referenceIndex) {
        this.index = index;
        this.referenceIndex = referenceIndex;
    }

    @Override
    public ProjectScanVO scan(boolean force) {
        // index.scan 内部已校验根路径(不存在→IllegalArgumentException),直接委托
        return index.scan(force);
    }

    @Override
    public List<IndexedClassInfo> queryIndex(String module, String type, String nameLike) {
        // type 字符串安全解析为枚举:非法值返回空列表(而非抛 500)
        ClassType typeEnum = parseType(type);
        return index.filter(module, typeEnum, nameLike);
    }

    @Override
    public boolean hasCache() {
        return index.getCachedScan() != null;
    }

    /** ClassType 名→枚举,非法/null 返回 null(filter 接受 null 表示不过滤) */
    private ClassType parseType(String type) {
        if (type == null || type.isBlank()) return null;
        try {
            return ClassType.valueOf(type.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("非法的 type 参数: {} (忽略, 不过滤)", type);
            return null;
        }
    }

    @Override
    public ReferenceProjectVO setReferencePath(String path) {
        // setPath 校验路径,scan 真正扫描(可能抛 IllegalArgumentException → 400)
        referenceIndex.setPath(path);
        // path 为空表示取消参考,直接返回未就绪状态
        if (path == null || path.isBlank()) {
            return buildReferenceVO(null, false, null);
        }
        ProjectScanVO scanVo = referenceIndex.scan(true);
        // 扫描后提取适配摘要(含版本信息),供前端展示
        String adaptation = referenceIndex.buildAdaptationSummary();
        return buildReferenceVO(scanVo, true, adaptation);
    }

    @Override
    public ReferenceProjectVO getReferenceStatus() {
        ProjectScanVO cached = referenceIndex.getCachedScan();
        // getReferenceStatus 也返回适配摘要(从缓存取,已提取过)
        String adaptation = referenceIndex.isReady() ? referenceIndex.buildAdaptationSummary() : null;
        return buildReferenceVO(cached, referenceIndex.isReady(), adaptation);
    }

    /** 从扫描 VO 构建参考项目状态 VO */
    private ReferenceProjectVO buildReferenceVO(ProjectScanVO scanVo, boolean ready, String adaptationSummary) {
        ReferenceProjectVO vo = new ReferenceProjectVO();
        vo.setPath(referenceIndex.getPath());
        vo.setReady(ready);
        if (scanVo != null && scanVo.getMeta() != null) {
            vo.setTotalFiles(scanVo.getMeta().getTotalFiles());
            vo.setIndexedClasses(scanVo.getMeta().getIndexedClasses());
            vo.setModuleCount(scanVo.getModules() != null ? scanVo.getModules().size() : 0);
            vo.setScannedAt(scanVo.getMeta().getScannedAt());
            vo.setDurationMillis(scanVo.getMeta().getDurationMillis());
        }
        vo.setAdaptationSummary(adaptationSummary);
        return vo;
    }

    /**
     * 浏览本机目录(只列子目录)。
     *
     * <p>path 为空返回系统根(Windows 盘符);非空列该目录下的子目录(隐藏目录不列)。
     * 安全:拒绝相对路径与 .. 片段(必须绝对路径),防止路径遍历。
     */
    @Override
    public BrowseResult browse(String path) {
        BrowseResult result = new BrowseResult();
        // path 为空 → 系统根(Windows 盘符列表)
        if (path == null || path.isBlank()) {
            File[] roots = File.listRoots();
            List<String> rootDirs = new ArrayList<>();
            if (roots != null) {
                for (File root : roots) {
                    // 盘符如 "C:\" — 直接列根路径
                    rootDirs.add(root.getAbsolutePath().replace('\\', '/'));
                }
            }
            result.setCurrent(null);
            result.setParent(null);
            result.setDirs(rootDirs);
            result.setAccessible(true);
            return result;
        }

        // 安全:必须绝对路径 + 拒绝 ..
        String normalized = path.trim().replace('\\', '/');
        if (normalized.contains("..") || !new File(normalized).isAbsolute()) {
            throw new IllegalArgumentException("仅支持绝对路径且不允许 .. : " + path);
        }
        File dir = new File(normalized);
        result.setCurrent(dir.getAbsolutePath().replace('\\', '/'));
        result.setAccessible(dir.isDirectory() && dir.canRead());
        if (!result.isAccessible()) {
            result.setDirs(List.of());
            result.setParent(null);
            return result;
        }
        // 上一级
        File parent = dir.getParentFile();
        result.setParent(parent != null ? parent.getAbsolutePath().replace('\\', '/') : null);
        // 子目录(只目录,隐藏目录不列,排序)
        File[] children = dir.listFiles(File::isDirectory);
        List<String> dirs = new ArrayList<>();
        if (children != null) {
            Arrays.sort(children, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
            for (File c : children) {
                String name = c.getName();
                if (name.startsWith(".") || name.startsWith("$")) continue; // 跳过隐藏/系统
                dirs.add(name);
            }
        }
        result.setDirs(dirs);
        return result;
    }
}
