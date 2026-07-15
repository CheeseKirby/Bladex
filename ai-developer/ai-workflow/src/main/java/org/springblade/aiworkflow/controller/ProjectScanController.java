package org.springblade.aiworkflow.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springblade.aiworkflow.agent.IndexedClassInfo;
import org.springblade.aiworkflow.common.ApiResponse;
import org.springblade.aiworkflow.controller.ConfigController.AdminTokenGuard;
import org.springblade.aiworkflow.service.IProjectScanService;
import org.springblade.aiworkflow.vo.BrowseResult;
import org.springblade.aiworkflow.vo.ProjectScanVO;
import org.springblade.aiworkflow.vo.ReferenceProjectVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 已有 BladeX 项目扫描 — 阶段1(只读)+ 阶段2增强(参考项目)。
 *
 * <p>端点:
 * <ul>
 *   <li>{@code GET /api/project/scan?force=false} — 扫描写入目标(blade_hgsjy),需鉴权;</li>
 *   <li>{@code GET /api/project/index?module=&type=&name=} — 读缓存(过滤),无鉴权;</li>
 *   <li>{@code POST /api/project/reference} — 设置参考项目路径并扫描,需鉴权;</li>
 *   <li>{@code GET /api/project/reference} — 查参考项目状态,无鉴权。</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/project")
@Tag(name = "项目扫描", description = "扫描已有 BladeX 项目结构(只读,阶段1)")
@AllArgsConstructor
public class ProjectScanController {

    private final IProjectScanService projectScanService;
    private final AdminTokenGuard guard;
    private final org.springblade.aiworkflow.agent.ReferenceProjectIndex referenceProjectIndex;

    @GetMapping("/scan")
    @Operation(summary = "扫描已有项目结构(只读)", description = "递归扫 .java 建立索引并缓存。force=false 有缓存则返回缓存。需 X-Admin-Token")
    public ApiResponse<ProjectScanVO> scan(
            @Parameter(description = "true 强制重扫") @RequestParam(defaultValue = "false") boolean force,
            HttpServletRequest req) {
        // 扫描有成本(遍历+解析),需鉴权防止随意触发
        guard.requireAdmin(req);
        return ApiResponse.ok(projectScanService.scan(force));
    }

    @GetMapping("/index")
    @Operation(summary = "查询项目索引(读缓存)", description = "不触发扫描,仅读上次扫描缓存。未扫描返回 404。module/type/name 任意组合过滤")
    public ApiResponse<List<IndexedClassInfo>> index(
            @Parameter(description = "模块名过滤(如 education)") @RequestParam(required = false) String module,
            @Parameter(description = "类型过滤(ENTITY/SERVICE/CONTROLLER...)") @RequestParam(required = false) String type,
            @Parameter(description = "类名包含子串") @RequestParam(required = false) String name) {
        if (!projectScanService.hasCache()) {
            return ApiResponse.fail(404, "尚未扫描,请先 GET /api/project/scan?force=true");
        }
        return ApiResponse.ok(projectScanService.queryIndex(module, type, name));
    }

    @PostMapping("/reference")
    @Operation(summary = "设置参考项目路径并扫描", description = "REAL 模式生成时参考该项目的同类代码风格。需 X-Admin-Token。body: {path:'绝对路径'}")
    public ApiResponse<ReferenceProjectVO> setReference(
            @RequestBody ReferencePathRequest body,
            HttpServletRequest req) {
        guard.requireAdmin(req);
        String path = (body == null) ? null : body.getPath();
        return ApiResponse.ok(projectScanService.setReferencePath(path));
    }

    @GetMapping("/reference")
    @Operation(summary = "查询参考项目状态", description = "返回当前参考项目路径/是否就绪/文件数。path=null 表示未设置")
    public ApiResponse<ReferenceProjectVO> getReference() {
        return ApiResponse.ok(projectScanService.getReferenceStatus());
    }

    @GetMapping("/adaptation-summary")
    @Operation(summary = "获取参考项目适配摘要", description = "返回版本约束+项目结构分析,供 Part A 生成方案时参考。无鉴权(只读摘要)")
    public ApiResponse<String> getAdaptationSummary() {
        String summary = referenceProjectIndex.buildAdaptationSummary();
        if (summary == null) {
            return ApiResponse.fail(404, "参考项目未就绪,请先 POST /api/project/reference 设置路径");
        }
        return ApiResponse.ok(summary);
    }

    @GetMapping("/browse")
    @Operation(summary = "浏览本机目录(选参考项目路径)", description = "列子目录(只目录)。path 为空返回系统根(盘符)。需 X-Admin-Token")
    public ApiResponse<BrowseResult> browse(
            @Parameter(description = "当前目录绝对路径,空返回盘符") @RequestParam(required = false) String path,
            HttpServletRequest req) {
        // 目录浏览有信息泄露风险,需鉴权
        guard.requireAdmin(req);
        return ApiResponse.ok(projectScanService.browse(path));
    }

    /** 设置参考项目路径的请求体 */
    public static class ReferencePathRequest {
        private String path;
        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }
    }
}
