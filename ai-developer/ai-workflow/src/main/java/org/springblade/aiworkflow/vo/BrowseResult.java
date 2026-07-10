package org.springblade.aiworkflow.vo;

import lombok.Data;

import java.util.List;

/**
 * 目录浏览结果 — 阶段2增强"参考项目路径浏览"功能。
 *
 * <p>前端点"浏览"按钮,逐级列出本机目录(只目录不文件),用户选目录后填入路径输入框。
 * 本地开发工具(localhost),经 AdminTokenGuard 鉴权,风险可控。
 */
@Data
public class BrowseResult {

    /** 当前目录绝对路径,null 表示系统根(Windows 盘符列表) */
    private String current;

    /** 上一级目录绝对路径,null 表示已到根(无上一级) */
    private String parent;

    /** 子目录名列表(只目录,按名称排序;隐藏目录不列) */
    private List<String> dirs;

    /** 当前目录是否可访问(false=无权限/不存在,前端提示) */
    private boolean accessible;
}
