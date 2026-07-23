/**
 * 方案管理路由
 *
 * 方案 CRUD 操作。设计态数据由本地原子 JSON 存储持久化，避免 BFF 重启后方案丢失。
 */

import { Router, type NextFunction, type Request, type Response } from 'express';
import { requireBffAdmin } from '../security/adminGuard';
import { ProjectStore, projectStore } from '../services/projectStore';

export function createPlansRouter(store: ProjectStore = projectStore): Router {
  const router = Router();
  router.use(requireBffAdmin);

  /** 保存项目 */
  router.post('/save', async (req: Request, res: Response, next: NextFunction) => {
    try {
      if (!req.body?.id) {
        res.status(400).json({ error: '缺少项目ID' });
        return;
      }
      const saved = await store.save(req.body);
      res.json({ success: true, id: saved.id, updatedAt: saved.updatedAt });
    } catch (error) {
      next(error);
    }
  });

  /** 加载项目 */
  router.get('/:id', (req: Request, res: Response) => {
    const project = store.get(req.params.id);
    if (!project) {
      res.status(404).json({ error: '项目不存在' });
      return;
    }
    res.json({ success: true, data: project });
  });

  /** 列出所有项目 */
  router.get('/', (_req: Request, res: Response) => {
    const list = store.list().map((project) => ({
      id: project.id,
      projectName: project.projectName,
      status: project.status,
      updatedAt: project.updatedAt,
    }));
    res.json({ success: true, data: list });
  });

  return router;
}

export const plansRouter = createPlansRouter();
