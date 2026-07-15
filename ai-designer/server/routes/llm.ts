/**
 * LLM 代理路由
 *
 * 真实 LLM 调用走 Anthropic Messages API,与 ccswitch / Claude Code 一致。
 * 配置由 server/config/llmConfig.ts 管理(支持运行时通过 /api/config/llm 修改)。
 *
 * 错误传播策略:
 * - 流式接口在尚未写出任何 SSE 帧前,若发现上游失败,直接返回 502 JSON 让前端能正常感知错误;
 * - 一旦已经开始流式输出,出错改用 SSE 帧 {type:'error'} 通知前端;
 * - 客户端关闭连接时,通过 AbortController 取消上游 fetch,避免上游 socket 与配额泄漏。
 */

import { Router, Request, Response } from 'express';
import { buildAuthHeaders, getLlmConfig, isLlmConfigured } from '../config/llmConfig';
import { requireBffAdmin } from '../security/adminGuard';

export const llmRouter = Router();

// LLM calls consume privileged server-side credentials and must never be an open proxy.
llmRouter.use(requireBffAdmin);

const LLM_REQUEST_TIMEOUT_MS = 300_000; // 5 分钟(大项目方案审查/拆分较慢)

interface ModuleSummary { type: string; name: string; icon: string; config?: unknown }

function buildGeneratePlanSystemPrompt(): string {
  return `你是一位资深 BladeX 4.1.0 后端架构师。
任务: 把用户的自然语言需求 + 拖入的模块清单, 转化为一份**完整、可执行**的 BladeX 4.1.0 后端开发方案(Markdown)。

== 处理简短需求的策略 ==
用户的需求可能非常简短(如"做一个员工管理模块"、"我要一个订单系统")。
此时你应当扮演一位有经验的产品+架构师, **主动展开**合理默认设计:
- 根据业务领域常识推断字段集 (员工→工号/姓名/部门/手机/邮箱/职位/状态; 订单→订单号/客户/金额/状态; 商品→编码/名称/分类/价格/库存)
- 自动设计合理的业务状态机 (如订单: 待付款→已付款→已发货→已完成→已取消)
- 默认提供 5 个标准 CRUD 端点 (/detail /list /save /update /remove)
- 默认提供 Excel 导出能力 (业务表常需)
- 默认提供 Feign 远程调用接口 (BladeX 微服务架构默认)
- 表名默认 blade_{模块名}, 实体名 PascalCase, 模块名 lowercase
- 字段默认含唯一索引(如编号/工号)和常用查询字段索引(如状态/部门)

== 处理详细需求的策略 ==
如果用户已经明确给出字段/校验/状态机, 必须严格遵守, 不要自由发挥替换。
拖入的模块配置(表名/字段/路径前缀)是**强约束**, 必须采用。

== 必须输出的章节 ==
1. 需求分析 (业务目标 / 核心能力 / 关键校验)
2. 模块结构 (blade-service-api / blade-service 命名)
3. 数据库 DDL (snake_case, 含 BaseEntity 标准字段: id/create_user/create_time/update_user/update_time/status/is_deleted)
4. Entity 定义 (extends BaseEntity, @TableName, @EqualsAndHashCode(callSuper=true))
5. VO 类型 (QVO/IVO/UVO/VO/EVO 用途和关键字段)
6. Mapper 接口 (extends BaseMapper<Entity>)
7. Service 层 (I*Service extends BaseService / *ServiceImpl extends BaseServiceImpl)
8. Controller 端点 (5 个标准 CRUD + 必要业务接口, 返回 R<T>, extends BladeController)
9. Wrapper 转换类 (必须含 entityVO/entity(IVO)/entity(UVO))
10. Excel 导入导出类 (如适用)
11. Feign 客户端接口 (如适用, 不要引用 fallback 类)
12. 关键业务逻辑 (状态机/事务/校验) 与异常处理
13. 实现顺序 (DDL → Entity/VO → Mapper → Service → Controller → Excel/Feign → 集成测试)

要求:
- 输出 Markdown, 中文表述, 代码片段用 \`\`\`sql / \`\`\`java
- 严格遵循 BladeX 4.1.0 规范, 不要写 @Autowired, 用 @AllArgsConstructor 构造器注入
- VO 全部平铺在 org.springblade.{module}.vo 包 (不要 ivo/qvo/uvo 子包)
- 方案开头**必须**明确写出: 实体名(PascalCase) / 模块名(lowercase) / 表名(blade_xxx) / 包路径
  示例: "**实体名**: Employee  **模块名**: employee  **表名**: blade_employee  **包路径**: org.springblade.employee"
- 不要解释你在做什么, 直接给方案`;
}

function buildSplitPlanSystemPrompt(): string {
  return `你是一位 BladeX 4.1.0 开发任务拆分专家。
任务: 把总方案拆分成 5-7 个相互独立、可顺序执行的子方案。

每个子方案必须包含:
- id        : 字符串, 形如 "sub_1", "sub_2"
- index     : 序号(1 起)
- title     : 简短中文标题, **必须**包含以下模块层关键字之一: DDL / 数据库 / 建表 / Entity / 实体 / VO / 视图 / Mapper / Service / 服务 / Controller / 控制器 / API / Wrapper / 包装 / Excel / 导入导出 / Feign / 远程
  反例(禁止): "改进订单号生成逻辑" / "编译验证" / "确认现状" — 这种标题 Part B 无法识别为模块层任务
- planContent: 子方案 Markdown 内容, **必须**明确写出: 实体名(如"实体名: Order"), 模块名(如"模块名: order"), 包路径(如"org.springblade.order.entity"), 表名(如"blade_order")
- prerequisites: 前置依赖的子方案 id 列表

**强制拆分维度: 按 BladeX 模块层而非开发步骤拆分**
标准模板(可选裁剪):
1. 数据库 DDL — 建表语句
2. Entity 与 VO — Entity 类 + QVO/IVO/UVO/VO/EVO 五类视图对象
3. Mapper 与 Service — Mapper 接口 + IService 接口 + ServiceImpl 实现
4. Wrapper 与 Controller — Wrapper 转换类 + Controller 5 个 CRUD 端点
5. Excel — Excel 导入导出类(如需)
6. Feign — Feign 远程调用接口(如需)

只输出 JSON, 不要 markdown 包裹, 不要解释, 结构:
{
  "subPlans": [
    { "id": "sub_1", "index": 1, "title": "数据库 DDL", "planContent": "实体名: Order, 模块名: order, 表名: blade_order ...", "prerequisites": [] }
  ]
}`;
}

function buildReviewPlanSystemPrompt(stage: string): string {
  return `你是一位 BladeX 4.1.0 代码审查专家。请审查以下 ${stage === 'master' ? '总方案' : '子方案'} 是否符合 BladeX 规范并指出问题。

只输出 JSON, 不要 markdown 包裹, 结构:
{
  "passes": true|false,
  "issues": [
    { "severity": "ERROR"|"WARN", "rule": "...", "message": "..." }
  ],
  "fixes": [
    { "section": "原方案的 ## 章节标题(必须与原方案完全一致)", "newContent": "修复后的完整章节(含 ## 标题行)" }
  ],
  "fixedContent": "(仅当问题太复杂无法片段修复时提供完整方案)",
  "changeLog": [
    { "what": "...", "why": "...", "before": "...", "after": "..." }
  ]
}

要求:
- 发现 ERROR 级问题时 passes=false
- **优先用 fixes(章节替换)修复**: 只返回需要改的章节, section 必须是原方案 ## 标题的精确文本
- 如果问题跨多个章节关联(如 DDL 改字段 -> Entity/VO/Mapper 都要改), 把所有相关章节都放入 fixes, 保持章节间一致
- 只有当问题太复杂无法用片段修复(需整体重组)时, 才提供完整 fixedContent
- fixes 的 newContent 必须是完整章节(含 ## 标题行), 不要省略章节内未改动部分
- 只有 WARN(无 ERROR)时 passes=true, fixes 为空`;
}

// ==================== /generate-plan (流式) ====================

llmRouter.post('/generate-plan', async (req: Request, res: Response) => {
  if (!isLlmConfigured()) {
    await handleMockGeneratePlan(req, res);
    return;
  }
  await handleLiveGeneratePlan(req, res);
});

// ==================== /review-plan (非流式 JSON) ====================

llmRouter.post('/review-plan', async (req: Request, res: Response) => {
  const { planContent, stage } = req.body as { planContent: string; stage: 'master' | 'subplan' };

  // SSE: 审查-修复循环每轮发进度, 前端实时显示(不等最终结果, 无超时)
  res.writeHead(200, {
    'Content-Type': 'text/event-stream',
    'Cache-Control': 'no-cache',
    'Connection': 'keep-alive',
  });
  const sendSSE = (obj: object) => { res.write(`data: ${JSON.stringify(obj)}\n\n`); };

  if (!isLlmConfigured()) {
    sendSSE({ type: 'done', data: { passes: true, issues: [{ severity: 'WARN', rule: 'Controller规范', message: '建议为所有Controller添加@ApiOperationSupport(order=N)' }], fixedContent: planContent, reviewLog: [], changeLog: [] } });
    res.end();
    return;
  }

  try {
    // 审查-修复闭环: 审查 -> 有 ERROR -> 片段修复(前 2 轮) -> 全局重写(第 3 轮) -> 重审查 -> 直到无 ERROR
    type Fix = { section: string; newContent: string };
    type ReviewIssue = { severity?: string; rule?: string; message?: string };
    type ReviewData = { passes?: boolean; issues?: ReviewIssue[]; fixes?: Fix[]; fixedContent?: string; changeLog?: unknown[] };
    type ReviewLogEntry = { round: number; action: string; errorCount: number; message: string };

    const MAX_ROUNDS = 3; // 最多 4 轮(初始审查 + 3 修复)
    const FIX_ONLY_ROUNDS = 2; // 前 2 轮优先片段修复, 第 3 轮可全局重写
    let currentContent = planContent;
    let lastData: ReviewData = { passes: true, issues: [], fixes: [], fixedContent: planContent, changeLog: [] };
    const reviewLog: ReviewLogEntry[] = [];

    for (let attempt = 0; attempt <= MAX_ROUNDS; attempt++) {
      sendSSE({ type: 'progress', message: `第 ${attempt + 1} 轮审查中...` });
      const refSummary = await getReferenceAdaptationSummary();
      const text = await callAnthropicJson(
        withReferenceSummary(buildReviewPlanSystemPrompt(stage || 'master'), refSummary),
        `请审查如下方案:\n\n${currentContent}`
      );
      const data: ReviewData = safeJsonParse(text) ?? lastData;
      lastData = {
        passes: data.passes ?? true,
        issues: data.issues ?? [],
        fixes: data.fixes ?? [],
        fixedContent: data.fixedContent ?? currentContent,
        changeLog: data.changeLog ?? [],
      };

      const errorCount = Array.isArray(lastData.issues)
        ? lastData.issues.filter((i) => i.severity === 'ERROR').length : 0;

      // 无 ERROR -> 通过
      if (lastData.passes || errorCount === 0) {
        reviewLog.push({ round: attempt + 1, action: 'review', errorCount: 0, message: `审查通过(第 ${attempt + 1} 轮)` });
        break;
      }
      // 达上限 -> 停
      if (attempt === MAX_ROUNDS) {
        reviewLog.push({ round: attempt + 1, action: 'review', errorCount, message: `达最大轮次, 剩余 ${errorCount} 个 ERROR` });
        break;
      }

      // 修复: 前 FIX_ONLY_ROUNDS 轮优先片段, 之后可全局
      const fixes = Array.isArray(lastData.fixes) ? lastData.fixes : [];
      const hasFixedContent = lastData.fixedContent && lastData.fixedContent !== currentContent;

      if (fixes.length > 0 && attempt < FIX_ONLY_ROUNDS) {
        // 片段修复(优先)
        const { result, applied, skipped } = applyFixes(currentContent, fixes);
        currentContent = result;
        reviewLog.push({ round: attempt + 1, action: 'fix-sections', errorCount, message: `片段修复 ${applied} 章节(跳过 ${skipped}), 原 ${errorCount} ERROR` });
      } else if (hasFixedContent) {
        // 全局重写(片段修不好 或 超过 FIX_ONLY_ROUNDS)
        currentContent = lastData.fixedContent!;
        reviewLog.push({ round: attempt + 1, action: 'fix-full-rewrite', errorCount, message: `全局重写, 原 ${errorCount} ERROR` });
      } else if (fixes.length > 0) {
        // 超过 FIX_ONLY_ROUNDS 但只有 fixes, 继续片段
        const { result, applied } = applyFixes(currentContent, fixes);
        currentContent = result;
        reviewLog.push({ round: attempt + 1, action: 'fix-sections', errorCount, message: `片段修复 ${applied} 章节, 原 ${errorCount} ERROR` });
      } else {
        // 有 ERROR 但无修复方案 -> 停
        reviewLog.push({ round: attempt + 1, action: 'review', errorCount, message: `${errorCount} ERROR 但无修复方案` });
        break;
      }
      // 修复后发进度(最后一条 reviewLog)
      if (reviewLog.length > 0) {
        sendSSE({ type: 'progress', message: reviewLog[reviewLog.length - 1].message });
      }
    }

    sendSSE({ type: 'done', data: { passes: lastData.passes ?? true, issues: lastData.issues ?? [], fixedContent: currentContent, reviewLog, changeLog: lastData.changeLog ?? [] } });
    res.end();
  } catch (err) {
    const msg = err instanceof Error ? err.message : String(err);
    console.error('[/review-plan] LLM 调用失败:', msg);
    sendSSE({ type: 'error', message: msg });
    res.end();
  }
});

// ==================== /enrich-requirement (非流式 JSON) ====================
// 根据用户当前的(短)需求 + 已配置的模块信息,反向生成一份完整、详细的需求描述。
// 主要供「帮我补齐需求」按钮使用 — 用户写了三个字"做个商品",一键展开成完整字段+校验+状态机。

llmRouter.post('/enrich-requirement', async (req: Request, res: Response) => {
  const { userInput, modules } = req.body as { userInput: string; modules?: ModuleSummary[] };

  if (!isLlmConfigured()) {
    // Mock 降级 — 拼一个简化的样板
    res.json({
      success: true,
      data: {
        enriched: `${userInput || '业务模块'}\n\n业务字段(示例):\n- name: 名称 (String, 必填)\n- code: 编码 (String, 必填, 唯一)\n- status: 状态 (Integer)\n- remark: 备注 (String)\n\n业务规则:\n- 编码全局唯一\n- 删除使用逻辑删除\n- 列表支持按名称/状态分页查询\n\n(LLM 未配置,以上为示例占位文本)`,
      },
    });
    return;
  }

  const moduleSummary = (modules || [])
    .map((m, i) => `${i + 1}. [${m.type}] ${m.name}  config=${JSON.stringify(m.config ?? {})}`)
    .join('\n') || '(无)';

  const sysPrompt = `你是一位资深产品经理 + BladeX 后端架构师。
任务: 根据用户输入的简短需求 + 已在画布上拖入的模块配置, 推断并展开为一份**完整、可执行**的业务需求描述。

输出格式: 纯文本 (不要 markdown 包裹, 不要解释步骤), 应包含:
1. 业务领域简介 (1-2 句)
2. 业务字段清单 (字段名/类型/是否必填/中文注释, 至少 6-10 个)
3. 业务状态机 (如订单状态/审批状态等, 如适用)
4. 关键业务规则 (唯一约束、删除策略、列表筛选条件等)
5. 是否需要 Excel 导入导出 / Feign 远程调用 等辅助功能

要求:
- 字段名用 camelCase, 类型用 Java 类型 (String/Long/Integer/BigDecimal/Boolean/Date/LocalDateTime)
- 严格遵守用户已配置的模块约束 (表名/实体名/模块名), 不要替换
- 若用户配置了 fields, 必须保留且可补充更多字段
- 文本控制在 800 字以内, 简洁专业`;

  const userPrompt = `用户简短需求:\n${userInput || '(空)'}\n\n已配置模块:\n${moduleSummary}\n\n请展开为完整业务需求描述。`;

  try {
    const text = await callAnthropicJson(sysPrompt, userPrompt);
    res.json({ success: true, data: { enriched: text.trim() } });
  } catch (err) {
    const msg = err instanceof Error ? err.message : String(err);
    console.error('[/enrich-requirement] LLM 调用失败:', msg);
    res.status(502).json({ success: false, error: msg });
  }
});

// ==================== /suggest-modules (非流式 JSON) ====================
// 根据需求文本 反向建议要拖入哪些模块类型(ENTITY/API/EXCEL/FEIGN 等),以及推荐的命名。
// 供「推荐拖入模块」按钮使用,识别业务领域后告诉用户该拖什么。

llmRouter.post('/suggest-modules', async (req: Request, res: Response) => {
  const { userInput } = req.body as { userInput: string };

  if (!isLlmConfigured()) {
    res.json({
      success: true,
      data: {
        suggestions: [
          { type: 'ENTITY', name: '数据模型', icon: '📦', config: { tableName: 'blade_xxx', moduleName: 'xxx', entityName: 'Xxx', needVO: true, needExcel: true } },
          { type: 'API', name: 'API接口', icon: '🔌', config: { pathPrefix: 'xxx', needAuth: true } },
        ],
        reasoning: '(LLM 未配置, 返回通用模板建议)',
      },
    });
    return;
  }

  const sysPrompt = `你是一位资深 BladeX 4.1.0 架构师。
任务: 根据用户输入的业务需求,识别业务领域,推断该用户需要拖入哪些 BladeX 模块,以及推荐的命名。

可用模块类型: ENTITY (数据模型) / API (API接口) / EXCEL (Excel导入导出) / FEIGN (远程调用) / PAGE (前端页面) / FLOW (工作流) / JOB (定时任务) / CONFIG (Nacos配置)

只输出 JSON, 不要 markdown 包裹, 不要解释, 结构:
{
  "suggestions": [
    {
      "type": "ENTITY",
      "name": "数据模型",
      "icon": "📦",
      "config": { "tableName": "blade_xxx", "moduleName": "xxx", "entityName": "Xxx", "needVO": true, "needExcel": true }
    }
  ],
  "reasoning": "为什么推荐这些模块的一句话说明"
}

要求:
- type 必须是上述 8 种之一
- ENTITY 必须配 tableName/moduleName/entityName, 命名用业务领域常识 (订单→blade_order/Order/order, 商品→blade_product/Product/product)
- ENTITY 若涉及报表/数据导出,设置 needExcel:true
- 业务核心 CRUD 一般需要 ENTITY+API, 如有跨服务调用建议补 FEIGN
- 简单业务 2-3 个模块即可, 不要过度推荐`;

  const userPrompt = `用户需求: ${userInput || '(空)'}\n\n请返回推荐模块清单 JSON。`;

  try {
    const text = await callAnthropicJson(sysPrompt, userPrompt);
    const parsed = safeJsonParse(text);
    if (!parsed || !Array.isArray(parsed.suggestions)) {
      res.json({
        success: true,
        data: { suggestions: [], reasoning: '未能解析 LLM 响应,请直接手动拖入模块' },
      });
      return;
    }
    res.json({ success: true, data: parsed });
  } catch (err) {
    const msg = err instanceof Error ? err.message : String(err);
    console.error('[/suggest-modules] LLM 调用失败:', msg);
    res.status(502).json({ success: false, error: msg });
  }
});

// ==================== /complete-one-shot (一键完备) ====================
// 串行调用 suggest-modules → enrich-requirement, 解决"先推荐还是先补齐"的不确定性。
// 用户点一个按钮就把"模块推荐 + 需求补齐"两件事按确定顺序做完。

llmRouter.post('/complete-one-shot', async (req: Request, res: Response) => {
  const { userInput, existingModules } = req.body as {
    userInput: string;
    existingModules?: ModuleSummary[];
  };

  if (!userInput || userInput.trim().length < 2) {
    res.status(400).json({ success: false, error: '需求文本太短(至少 2 字)' });
    return;
  }

  if (!isLlmConfigured()) {
    // Mock 降级
    res.json({
      success: true,
      data: {
        suggestions: [
          { type: 'ENTITY', name: '数据模型', icon: '📦', config: { tableName: 'blade_xxx', moduleName: 'xxx', entityName: 'Xxx', needVO: true, needExcel: true } },
        ],
        enriched: `${userInput}\n\n(Mock: LLM 未配置, 一键完备退化为示例)\n\n业务字段:\n- name: 名称\n- code: 编码\n- status: 状态`,
        reasoning: 'Mock 模式',
      },
    });
    return;
  }

  try {
    // ── 第一步: 推荐模块 ─────────────────────────────
    const suggestPrompt = `你是 BladeX 4.1.0 架构师。根据用户需求识别业务领域,推荐应当拖入的模块。
可用模块类型: ENTITY/API/EXCEL/FEIGN/PAGE/FLOW/JOB/CONFIG
只输出 JSON, 不要 markdown 包裹, 不要解释:
{"suggestions":[{"type":"ENTITY","name":"数据模型","icon":"📦","config":{"tableName":"blade_xxx","moduleName":"xxx","entityName":"Xxx","needVO":true,"needExcel":true}}],"reasoning":"简短理由"}
要求:
- ENTITY 必须配 tableName/moduleName/entityName, 用业务领域常识命名 (订单→blade_order/Order/order)
- 涉及导出报表配 needExcel:true
- 2-3 个模块即可, 不要过度推荐
- 排除已有模块类型 (避免重复)`;

    const existingTypes = (existingModules || []).map((m) => m.type);
    const suggestUserPrompt = `用户需求: ${userInput.trim()}\n已有模块类型: ${existingTypes.length ? existingTypes.join(', ') : '(无)'}`;

    const suggestText = await callAnthropicJson(suggestPrompt, suggestUserPrompt);
    const suggestParsed = safeJsonParse(suggestText) as
      | { suggestions?: unknown[]; reasoning?: string }
      | null;
    const suggestions = Array.isArray(suggestParsed?.suggestions) ? suggestParsed.suggestions : [];
    const reasoning = (suggestParsed?.reasoning as string) || '';

    // ── 第二步: 基于"原需求 + 已有模块 + 新推荐模块"展开需求 ──
    const allModules = [...(existingModules || []), ...(suggestions as ModuleSummary[])];
    const moduleSummary = allModules
      .map((m, i) => `${i + 1}. [${m.type}] ${m.name}  config=${JSON.stringify(m.config ?? {})}`)
      .join('\n') || '(无)';

    const enrichPrompt = `你是产品经理 + BladeX 架构师。根据用户简短需求和已配置模块,生成一份完整业务需求描述。
输出: 纯文本(非 markdown), 含:
1. 业务领域简介(1-2 句)
2. 业务字段清单(字段名/类型/必填/注释, 至少 6-10 个)
3. 业务状态机(如适用)
4. 关键业务规则
5. 是否需要 Excel/Feign 等辅助
要求:
- 字段名 camelCase, 类型用 Java 类型
- 严格遵守模块约束 (表名/实体名/模块名), 不要替换
- 800 字以内, 简洁专业`;

    const enrichUserPrompt = `用户简短需求: ${userInput.trim()}\n\n已配置/已推荐模块:\n${moduleSummary}\n\n请展开为完整业务需求描述。`;

    const enrichText = await callAnthropicJson(enrichPrompt, enrichUserPrompt);

    res.json({
      success: true,
      data: {
        suggestions,
        enriched: enrichText.trim(),
        reasoning,
      },
    });
  } catch (err) {
    const msg = err instanceof Error ? err.message : String(err);
    console.error('[/complete-one-shot] LLM 调用失败:', msg);
    res.status(502).json({ success: false, error: msg });
  }
});

// ==================== /split-plan (非流式 JSON) ====================

llmRouter.post('/split-plan', async (req: Request, res: Response) => {
  const { planContent } = req.body as { planContent: string };

  if (!isLlmConfigured()) {
    res.json({ success: true, data: mockSplit(planContent) });
    return;
  }

  try {
    const refSummary = await getReferenceAdaptationSummary();
    const text = await callAnthropicJson(withReferenceSummary(buildSplitPlanSystemPrompt(), refSummary), `总方案:\n\n${planContent}`);
    const parsed = safeJsonParse(text);
    const subPlans = Array.isArray(parsed?.subPlans) ? (parsed.subPlans as unknown[]) : [];
    if (subPlans.length === 0) {
      // LLM 偶尔返回不规范 JSON,降级到 Mock 防 UI 卡死
      res.json({ success: true, data: mockSplit(planContent) });
      return;
    }
    // 补齐字段
    const normalized = subPlans.map((raw, i) => {
      const sp = raw as Record<string, unknown>;
      return {
        id: typeof sp.id === 'string' ? sp.id : `sub_${i + 1}`,
        masterPlanId: 'plan_1',
        index: typeof sp.index === 'number' ? sp.index : i + 1,
        title: typeof sp.title === 'string' ? sp.title : `子方案${i + 1}`,
        planContent: typeof sp.planContent === 'string' ? sp.planContent : '',
        prerequisites: Array.isArray(sp.prerequisites) ? sp.prerequisites : [],
        // 拆分成功 = 全部子方案文本已生成, 统一标记 GENERATED。
        // (原 i===0 仅标记首个, 导致列表里只有第一个状态 Tag 更新, 其余误显"待生成"。)
        status: 'GENERATED',
      };
    });

    // 关键校验: title 必须命中 Part B 的模块层关键字, 否则 parseAtomicTasks 会落到兜底 Code.java
    // 命中率 < 50% 时降级到 Mock, 避免污染 Part B 生成
    const validKeywords = [
      'DDL', '数据库', '建表', 'sql',
      'Entity', '实体',
      'VO', '视图',
      'Mapper',
      'Service', '服务',
      'Controller', '控制器', 'API',
      'Wrapper', '包装',
      'Excel', '导入导出',
      'Feign', '远程',
    ];
    const titleHasKeyword = (t: string) => {
      const lower = t.toLowerCase();
      return validKeywords.some((k) => lower.includes(k.toLowerCase()));
    };
    const hitCount = normalized.filter((sp) => titleHasKeyword(sp.title)).length;
    const hitRate = normalized.length === 0 ? 0 : hitCount / normalized.length;
    if (hitRate < 0.5) {
      console.warn(
        `[/split-plan] LLM 拆分 title 命中率过低(${hitCount}/${normalized.length}=${(hitRate * 100).toFixed(0)}%), 降级到 Mock 模板. 实际 titles: ${normalized.map((s) => s.title).join(' / ')}`
      );
      res.json({ success: true, data: mockSplit(planContent) });
      return;
    }
    res.json({ success: true, data: { subPlans: normalized } });
  } catch (err) {
    const msg = err instanceof Error ? err.message : String(err);
    console.error('[/split-plan] LLM 调用失败:', msg);
    res.status(502).json({ success: false, error: msg });
  }
});

// ==================== Live LLM 实现 ====================

/** 获取 Part B 参考项目适配摘要(版本+项目结构+衔接点)。参考项目未就绪/Part B 不可达返回 null */
async function getReferenceAdaptationSummary(): Promise<string | null> {
  const partBUrl = process.env.PART_B_URL || 'http://localhost:8111';
  try {
    const resp = await fetch(`${partBUrl}/api/project/adaptation-summary`, { signal: AbortSignal.timeout(5000) });
    if (!resp.ok) return null;
    const data = await resp.json();
    return data?.data ?? null;
  } catch {
    return null;
  }
}

/** 拼接参考项目摘要到 systemPrompt(无摘要则原样返回) */
function withReferenceSummary(basePrompt: string, refSummary: string | null): string {
  if (!refSummary) return basePrompt;
  return basePrompt + '\n\n== 参考项目适配(新模块必须遵循, 以接入参考项目编译) ==\n' + refSummary;
}

/** 按 ## 章节标题替换方案内容。标题没匹配上的 fix 跳过(不破坏原方案)。 */
function applyFixes(plan: string, fixes: { section: string; newContent: string }[]): { result: string; applied: number; skipped: number } {
  let result = plan;
  let applied = 0;
  let skipped = 0;
  for (const fix of fixes) {
    const section = fix.section.trim();
    const start = result.indexOf(section);
    if (start < 0) {
      console.warn(`[/review-plan] 章节未匹配, 跳过: ${section.substring(0, 40)}`);
      skipped++;
      continue;
    }
    // 找下一个 ## 标题(章节结束位置)
    const nextSection = result.indexOf('\n## ', start + section.length);
    const end = nextSection >= 0 ? nextSection : result.length;
    result = result.substring(0, start) + fix.newContent + result.substring(end);
    applied++;
  }
  return { result, applied, skipped };
}

/** 调用 Anthropic Messages API(非流式),返回首个 text block */
async function callAnthropicJson(systemPrompt: string, userPrompt: string): Promise<string> {
  const cfg = getLlmConfig();
  const base = cfg.baseUrl.replace(/\/+$/, '');
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), LLM_REQUEST_TIMEOUT_MS);
  try {
    const resp = await fetch(`${base}/v1/messages`, {
      method: 'POST',
      headers: buildAuthHeaders(),
      body: JSON.stringify({
        model: cfg.model,
        max_tokens: cfg.maxTokens,
        system: systemPrompt,
        messages: [{ role: 'user', content: userPrompt }],
      }),
      signal: controller.signal,
    });
    if (!resp.ok) {
      const errText = await resp.text().catch(() => '');
      throw new Error(`Anthropic ${resp.status}: ${errText.slice(0, 500)}`);
    }
    type AnthropicResp = { content?: { type: string; text?: string }[] };
    const data: AnthropicResp = await resp.json();
    const block = data.content?.find((c) => c.type === 'text');
    return block?.text || '';
  } finally {
    clearTimeout(timer);
  }
}

/** 流式调用 — Anthropic SSE → 转译为前端约定的 SSE 帧 */
async function handleLiveGeneratePlan(req: Request, res: Response): Promise<void> {
  const { userInput, modules } = req.body as { userInput: string; modules?: ModuleSummary[] };

  // 从模块配置提取强约束(表名/模块名/实体名/字段),作为权威默认值传给 LLM,
  // 用户没在需求文本里写的, LLM 会从这些约束自动补全, 而不是自由发挥。
  const constraints: string[] = [];
  for (const m of modules || []) {
    const cfg = (m.config ?? {}) as Record<string, unknown>;
    if (m.type === 'ENTITY') {
      if (cfg.tableName) constraints.push(`表名: ${cfg.tableName}`);
      if (cfg.moduleName) constraints.push(`模块名: ${cfg.moduleName}`);
      if (cfg.entityName) constraints.push(`实体名: ${cfg.entityName}`);
      if (cfg.needExcel) constraints.push(`需要 Excel 导入导出`);
      if (cfg.needVO) constraints.push(`需要 VO 类 (QVO/IVO/UVO/VO)`);
      // 字段列表作为强约束传给 LLM
      const fields = cfg.fields as Array<{ name: string; type: string; comment: string; nullable: boolean }> | undefined;
      if (fields && fields.length > 0) {
        constraints.push(`业务字段 (严格使用以下定义, 不要增减):`);
        for (const f of fields) {
          const nullInfo = f.nullable === false ? '非空' : '可空';
          constraints.push(`  - ${f.name}: ${f.type} (${f.comment || '无注释'}, ${nullInfo})`);
        }
      }
    }
    if (m.type === 'API' && cfg.pathPrefix) {
      constraints.push(`API 路径前缀: /${cfg.pathPrefix}`);
    }
    if (m.type === 'FEIGN' && cfg.targetService) {
      constraints.push(`Feign 目标服务: ${cfg.targetService}`);
    }
    if (m.type === 'EXCEL' && cfg.entityName) {
      constraints.push(`Excel 关联实体: ${cfg.entityName}`);
    }
  }
  const constraintsBlock = constraints.length > 0
    ? `\n\n## 强约束(必须采用, 不要替换)\n${constraints.map((c) => '- ' + c).join('\n')}`
    : '';

  const moduleSummary = (modules || [])
    .map((m, i) => `${i + 1}. [${m.type}] ${m.icon} ${m.name}  config=${JSON.stringify(m.config ?? {})}`)
    .join('\n') || '(无)';

  const trimmed = (userInput || '').trim();
  const isShortRequirement = trimmed.length < 30;
  const guidance = isShortRequirement
    ? '\n\n## 推断指南\n用户需求较简短, 请根据业务领域常识合理推断字段集、状态机、辅助功能(Excel/Feign), 并在方案开头明示推断的实体名/模块名/表名/包路径。'
    : '';

  const userPrompt =
    `## 用户需求\n${trimmed || '(未填写)'}` +
    `\n\n## 已拖入的模块\n${moduleSummary}` +
    constraintsBlock +
    guidance +
    `\n\n请生成完整的 BladeX 4.1.0 后端开发方案。`;

  const cfg = getLlmConfig();
  const base = cfg.baseUrl.replace(/\/+$/, '');

  // 用于取消上游请求
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), LLM_REQUEST_TIMEOUT_MS);

  let headersSent = false;
  let finished = false;
  // 客户端断开时取消上游 — 但只在已经开始流式输出 之后才响应,
  // 避免在 fetch 上游 headers 阶段因请求体读完 / keep-alive 误触发 close 而取消。
  req.on('close', () => {
    if (finished) return;
    if (headersSent && !controller.signal.aborted) {
      controller.abort();
      console.log('[/generate-plan] 客户端断开(流式阶段),取消上游 LLM 请求');
    }
    clearTimeout(timer);
  });

  const refSummary = await getReferenceAdaptationSummary();
  let upstream: globalThis.Response;
  try {
    upstream = await fetch(`${base}/v1/messages`, {
      method: 'POST',
      headers: { ...buildAuthHeaders(), accept: 'text/event-stream' },
      body: JSON.stringify({
        model: cfg.model,
        max_tokens: cfg.maxTokens,
        stream: true,
        system: withReferenceSummary(buildGeneratePlanSystemPrompt(), refSummary),
        messages: [{ role: 'user', content: userPrompt }],
      }),
      signal: controller.signal,
    });
  } catch (err) {
    clearTimeout(timer);
    const msg = err instanceof Error ? err.message : String(err);
    res.status(502).json({ success: false, error: `Anthropic 网络异常: ${msg}` });
    return;
  }

  if (!upstream.ok || !upstream.body) {
    clearTimeout(timer);
    const errText = await upstream.text().catch(() => '');
    // 此时尚未写 SSE 头,直接 502 让前端能感知到错误
    res.status(502).json({
      success: false,
      error: `Anthropic ${upstream.status}: ${errText.slice(0, 300)}`,
    });
    return;
  }

  // 进入流式输出阶段
  res.setHeader('Content-Type', 'text/event-stream');
  res.setHeader('Cache-Control', 'no-cache');
  res.setHeader('Connection', 'keep-alive');
  res.setHeader('X-Accel-Buffering', 'no'); // 防止 nginx 缓冲
  headersSent = true;
  // 立即 flush headers 触发前端 reader
  res.flushHeaders?.();

  const send = (data: object) => {
    if (res.writableEnded) return;
    res.write(`data: ${JSON.stringify(data)}\n\n`);
  };

  send({ type: 'progress', stage: 'analyzing', message: '正在分析需求结构...' });
  send({ type: 'progress', stage: 'planning', message: '正在生成开发方案...' });

  const reader = upstream.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  let totalChars = 0;

  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      let sep = buffer.indexOf('\n\n');
      while (sep !== -1) {
        const frame = buffer.slice(0, sep);
        buffer = buffer.slice(sep + 2);
        for (const line of frame.split('\n')) {
          if (!line.startsWith('data:')) continue;
          const payload = line.slice(5).trim();
          if (!payload || payload === '[DONE]') continue;
          try {
            const evt = JSON.parse(payload);
            if (evt.type === 'content_block_delta' && evt.delta?.type === 'text_delta') {
              const chunk = evt.delta.text || '';
              if (chunk) {
                totalChars += chunk.length;
                send({ type: 'content', chunk });
              }
            }
            if (evt.type === 'message_stop') {
              send({ type: 'complete', tokensUsed: totalChars });
            }
          } catch {
            // keepalive / 非 JSON 帧
          }
        }
        sep = buffer.indexOf('\n\n');
      }
    }
    send({ type: 'complete', tokensUsed: totalChars });
  } catch (err) {
    const msg = err instanceof Error ? err.message : String(err);
    console.error('[/generate-plan] 流读取异常:', msg);
    if (headersSent) {
      send({ type: 'error', error: msg });
    }
  } finally {
    finished = true;
    clearTimeout(timer);
    try { reader.cancel(); } catch { /* noop */ }
    if (!res.writableEnded) res.end();
  }
}

// ==================== Mock 实现 ====================

async function handleMockGeneratePlan(req: Request, res: Response): Promise<void> {
  const { userInput, modules } = req.body as { userInput: string; modules?: ModuleSummary[] };

  res.setHeader('Content-Type', 'text/event-stream');
  res.setHeader('Cache-Control', 'no-cache');
  res.setHeader('Connection', 'keep-alive');
  res.setHeader('X-Accel-Buffering', 'no');
  res.flushHeaders?.();

  let clientGone = false;
  req.on('close', () => { clientGone = true; });

  const moduleList = (modules || []).map((m) => `${m.icon} ${m.name}`).join(', ');

  const mockPlan = `# 开发方案

## 1. 需求分析
根据用户输入"${userInput || '(未输入)'}"${moduleList ? `及已拖入模块[${moduleList}]` : ''}，分析如下：

## 2. 模块结构
- **API模块**: \`blade-service-api/blade-order-api\`
- **服务模块**: \`blade-service/blade-order\`

## 3. 数据库设计
\`\`\`sql
CREATE TABLE blade_order (
    id BIGINT PRIMARY KEY COMMENT '主键(雪花算法)',
    order_no VARCHAR(50) NOT NULL COMMENT '订单编号',
    customer_name VARCHAR(100) COMMENT '客户名称',
    amount DECIMAL(18,2) COMMENT '金额',
    status INT DEFAULT 1 COMMENT '状态',
    remark VARCHAR(500) COMMENT '备注',
    create_user BIGINT COMMENT '创建人',
    create_time DATETIME COMMENT '创建时间',
    update_user BIGINT COMMENT '修改人',
    update_time DATETIME COMMENT '修改时间',
    is_deleted INT DEFAULT 0 COMMENT '逻辑删除'
) COMMENT '订单表';
\`\`\`

## 4. Entity定义
- 类名: \`Order\`
- 包路径: \`org.springblade.order.entity\`
- 继承: \`BaseEntity\`
- 注解: \`@Data @TableName("blade_order") @EqualsAndHashCode(callSuper = true)\`

## 5. VO类型
| 类型 | 类名 | 说明 |
|------|------|------|
| QVO | OrderQVO | 查询参数 |
| IVO | OrderIVO | 新增参数 |
| UVO | OrderUVO | 修改参数 |
| VO | OrderVO | 输出视图 |

## 6. Mapper接口
\`OrderMapper extends BaseMapper<Order>\`

## 7. Service层
- 接口: \`IOrderService extends BaseService<Order>\`
- 实现: \`OrderServiceImpl extends BaseServiceImpl<OrderMapper, Order>\`

## 8. Controller端点
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /order/detail | 详情 |
| GET | /order/list | 分页列表 |
| POST | /order/save | 新增 |
| POST | /order/update | 修改 |
| POST | /order/remove | 逻辑删除 |

## 9. 实现顺序
1. 数据库DDL → 2. Entity与VO → 3. Mapper → 4. Service → 5. Controller
`;

  const lines = mockPlan.split('\n');

  const send = (data: object) => {
    if (res.writableEnded) return;
    res.write(`data: ${JSON.stringify(data)}\n\n`);
  };

  send({ type: 'progress', stage: 'analyzing', message: '正在分析需求结构 (Mock)...' });
  await sleep(400);
  send({ type: 'progress', stage: 'planning', message: '正在生成开发方案 (Mock)...' });
  await sleep(300);

  for (let i = 0; i < lines.length; i++) {
    if (clientGone) return;
    send({ type: 'content', chunk: lines[i] + '\n' });
    await sleep(15 + Math.random() * 25);
  }

  await sleep(200);
  send({ type: 'complete', tokensUsed: 3500 });
  res.end();
}

function mockSplit(_planContent: string) {
  return {
    subPlans: [
      { id: 'sub_1', masterPlanId: 'plan_1', index: 1, title: '数据库DDL', planContent: '## 子方案1: 数据库DDL\n\n创建业务表 blade_order...', prerequisites: [], status: 'GENERATED' },
      { id: 'sub_2', masterPlanId: 'plan_1', index: 2, title: 'Entity与VO类', planContent: '## 子方案2: Entity与VO\n\n创建 Order Entity 与 4 个 VO...', prerequisites: ['sub_1'], status: 'GENERATED' },
      { id: 'sub_3', masterPlanId: 'plan_1', index: 3, title: 'Mapper与Service层', planContent: '## 子方案3: Mapper与Service\n\n实现数据访问和业务逻辑...', prerequisites: ['sub_2'], status: 'GENERATED' },
      { id: 'sub_4', masterPlanId: 'plan_1', index: 4, title: 'Controller与Wrapper', planContent: '## 子方案4: Controller\n\n实现 5 个标准 CRUD REST 端点...', prerequisites: ['sub_3'], status: 'GENERATED' },
      { id: 'sub_5', masterPlanId: 'plan_1', index: 5, title: 'Excel导入导出', planContent: '## 子方案5: Excel\n\n实现订单数据 Excel 导出...', prerequisites: ['sub_4'], status: 'GENERATED' },
    ],
  };
}

function safeJsonParse(raw: string): Record<string, unknown> | null {
  if (!raw) return null;
  let body = raw.trim();
  // 去掉 ```json ... ``` 或 ``` ... ``` 包裹
  const fence = body.match(/^```(?:json)?\s*([\s\S]*?)```$/);
  if (fence) body = fence[1].trim();
  try {
    return JSON.parse(body);
  } catch {
    // 尝试从首个 { 到末尾 } 截取
    const start = body.indexOf('{');
    const end = body.lastIndexOf('}');
    if (start >= 0 && end > start) {
      try {
        return JSON.parse(body.slice(start, end + 1));
      } catch {
        return null;
      }
    }
    return null;
  }
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
