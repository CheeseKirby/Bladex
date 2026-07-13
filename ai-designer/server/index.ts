/**
 * Part A BFF (Backend For Frontend)
 *
 * 职责：
 * 1. LLM API 代理（管理 API Key，不暴露给前端）
 * 2. 方案持久化（内存,开发态）
 * 3. Part B 传输代理
 * 4. 运行时配置 (LLM url/token/model)
 *
 * 端口：3004（开发环境；默认值，可由 .env 的 PORT 覆盖）
 *
 * 安全:
 * - CORS 默认仅允许 FRONTEND_ORIGIN(默认 http://localhost:3005),禁止任意来源
 * - 写入类端点(PUT/POST /api/config/llm)要求 X-Admin-Token 与 BFF_ADMIN_TOKEN 匹配,
 *   未设置 token 时只接受本地回环(127.0.0.1)
 */

import express, { type Request, type Response, type NextFunction } from 'express';
import cors from 'cors';
import { llmRouter } from './routes/llm';
import { plansRouter } from './routes/plans';
import { transmissionRouter } from './routes/transmission';
import { configRouter } from './routes/config';
import { isLlmConfigured } from './config/llmConfig';

const app = express();
const PORT = process.env.PORT || 3004;

// 允许的前端源 — 默认 Vite dev server。生产请通过 FRONTEND_ORIGIN 设置具体域名。
// 用逗号分隔可以指定多个源。
const ALLOWED_ORIGINS = (process.env.FRONTEND_ORIGIN || 'http://localhost:3005')
  .split(',')
  .map((s) => s.trim())
  .filter(Boolean);

// 中间件
app.use(
  cors({
    origin: (origin, callback) => {
      // 同源/curl/server-to-server 请求没有 Origin 头 — 允许
      if (!origin) return callback(null, true);
      if (ALLOWED_ORIGINS.includes(origin)) return callback(null, true);
      // 非白名单来源: 不发 Access-Control-Allow-Origin 头,浏览器自动拦截。
      // 用 callback(null, false) 而非 callback(error),避免把请求当成 500 错误。
      console.warn(`[BFF] CORS blocked origin: ${origin}`);
      return callback(null, false);
    },
    credentials: false,
  })
);
app.use(express.json({ limit: '10mb' }));

// 简易请求日志(只打方法+路径,不打 body)
app.use((req, _res, next) => {
  if (!req.path.startsWith('/api/health')) {
    console.log(`[BFF] ${req.method} ${req.path}`);
  }
  next();
});

// 路由
app.use('/api/llm', llmRouter);
app.use('/api/plans', plansRouter);
app.use('/api/transmission', transmissionRouter);
app.use('/api/config', configRouter);

// 根路径 — 提示用户访问前端地址
app.get('/', (_req, res) => {
  res
    .status(200)
    .json({
      service: 'ai-designer-bff',
      port: PORT,
      frontend: ALLOWED_ORIGINS[0] || 'http://localhost:3005',
      endpoints: ['/api/llm', '/api/plans', '/api/transmission', '/api/config', '/api/health'],
    });
});

// 健康检查
app.get('/api/health', (_req, res) => {
  res.json({ status: 'ok', timestamp: new Date().toISOString() });
});

// 兜底错误处理 — 避免 CORS 校验或路由抛错导致连接挂起
app.use((err: Error, _req: Request, res: Response, _next: NextFunction) => {
  console.error('[BFF] 未捕获异常:', err.message);
  if (res.headersSent) return;
  res.status(500).json({ success: false, msg: err.message });
});

app.listen(PORT, () => {
  console.log(`[AI Designer BFF] 服务已启动: http://localhost:${PORT}`);
  console.log(`[AI Designer BFF] CORS 允许来源: ${ALLOWED_ORIGINS.join(', ')}`);
  console.log(`[AI Designer BFF] LLM mode: ${isLlmConfigured() ? 'live' : 'mock'} (访问 /api/config/llm 查看/修改)`);
});
