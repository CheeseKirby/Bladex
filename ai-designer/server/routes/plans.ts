/**
 * 方案管理路由
 *
 * 方案 CRUD 操作。当前使用内存存储（开发阶段）。
 * 生产环境应替换为 MySQL 持久化。
 */

import { Router, Request, Response } from 'express';
import { requireBffAdmin } from '../security/adminGuard';

export const plansRouter = Router();
plansRouter.use(requireBffAdmin);

// 内存存储
// eslint-disable-next-line @typescript-eslint/no-explicit-any
const projectsStore: Map<string, Record<string, unknown>> = new Map();

/** 保存项目 */
plansRouter.post('/save', (req: Request, res: Response) => {
  const project = req.body;
  if (!project?.id) {
    return res.status(400).json({ error: '缺少项目ID' });
  }
  projectsStore.set(project.id, { ...project, updatedAt: new Date().toISOString() });
  res.json({ success: true, id: project.id });
});

/** 加载项目 */
plansRouter.get('/:id', (req: Request, res: Response) => {
  const project = projectsStore.get(req.params.id);
  if (!project) {
    return res.status(404).json({ error: '项目不存在' });
  }
  res.json({ success: true, data: project });
});

/** 列出所有项目 */
plansRouter.get('/', (_req: Request, res: Response) => {
  const list = Array.from(projectsStore.values()).map((p) => ({
    id: p.id,
    projectName: p.projectName,
    status: p.status,
    updatedAt: p.updatedAt,
  }));
  res.json({ success: true, data: list });
});
