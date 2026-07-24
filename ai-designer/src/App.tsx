import React, { useState, useCallback, useEffect, useRef } from 'react';
import { ConfigProvider, Modal, Input, message, App as AntApp } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import DndProvider from './components/layout/DndProvider';
import MainLayout from './components/layout/MainLayout';
import { usePlanStore } from './store/planStore';
import { saveProject, loadProject, listProjects } from './services/api';
import type { Project } from './types/plan';
import type { ProjectOption } from './components/layout/TopBar';

const App: React.FC = () => {
  const [newProjectModalOpen, setNewProjectModalOpen] = useState(false);
  const [newProjectName, setNewProjectName] = useState('');
  const [projects, setProjects] = useState<ProjectOption[]>([]);
  const [projectsLoading, setProjectsLoading] = useState(true);
  const restoreRequestRef = useRef(0);

  const createProject = usePlanStore((s) => s.createProject);
  const hydrateProject = usePlanStore((s) => s.hydrateProject);
  const getPersistableProject = usePlanStore((s) => s.getPersistableProject);
  const project = usePlanStore((s) => s.project);

  const handleNewProject = useCallback(() => {
    setNewProjectName('');
    setNewProjectModalOpen(true);
  }, []);

  const refreshProjects = useCallback(async (): Promise<ProjectOption[]> => {
    const response = await listProjects();
    const items = Array.isArray(response?.data) ? response.data as ProjectOption[] : [];
    const sorted = [...items].sort((left, right) =>
      String(right.updatedAt ?? '').localeCompare(String(left.updatedAt ?? '')),
    );
    setProjects(sorted);
    return sorted;
  }, []);

  const restoreProject = useCallback(async (projectId: string, notify: boolean) => {
    const requestId = ++restoreRequestRef.current;
    setProjectsLoading(true);
    try {
      const response = await loadProject(projectId);
      if (requestId !== restoreRequestRef.current) return;
      const restored = response?.data as Project | undefined;
      if (!restored?.id || !Array.isArray(restored.modules) || !Array.isArray(restored.subPlans)) {
        throw new Error('\u9879\u76ee\u6570\u636e\u7ed3\u6784\u65e0\u6548');
      }
      hydrateProject(restored);
      if (notify) message.success(`\u5df2\u6062\u590d\u9879\u76ee "${restored.projectName}"`);
    } catch (error) {
      if (requestId === restoreRequestRef.current && notify) {
        message.error(error instanceof Error ? `\u52a0\u8f7d\u9879\u76ee\u5931\u8d25\uff1a${error.message}` : '\u52a0\u8f7d\u9879\u76ee\u5931\u8d25');
      }
    } finally {
      if (requestId === restoreRequestRef.current) setProjectsLoading(false);
    }
  }, [hydrateProject]);

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      try {
        // Load the durable project catalogue, but do not silently make the newest historical project current.
        // Review/execution state remains recoverable through the selector; a fresh browser session starts neutral.
        await refreshProjects();
      } catch {
        // The page remains usable for a new in-memory project when the BFF catalogue is temporarily unavailable.
      } finally {
        if (!cancelled) setProjectsLoading(false);
      }
    })();
    return () => { cancelled = true; };
  }, [refreshProjects]);

  const handleCreateProject = useCallback(() => {
    if (!newProjectName.trim()) return;
    restoreRequestRef.current += 1;
    createProject(newProjectName.trim());
    setNewProjectModalOpen(false);
    message.success(`项目 "${newProjectName}" 已创建`);
  }, [newProjectName, createProject]);

  const handleSave = useCallback(async () => {
    const snapshot = getPersistableProject();
    if (!snapshot) return;
    try {
      await saveProject(snapshot);
      await refreshProjects();
      message.success('项目已保存');
    } catch {
      message.warning('保存失败（离线模式，数据仅在内存中）');
    }
  }, [getPersistableProject, refreshProjects]);

  const handleSelectProject = useCallback((projectId: string) => {
    if (projectId === project?.id) return;
    void restoreProject(projectId, true);
  }, [project?.id, restoreProject]);

  const handleExport = useCallback(() => {
    const snapshot = getPersistableProject();
    if (!snapshot) return;
    const json = JSON.stringify(snapshot, null, 2);
    const blob = new Blob([json], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `${snapshot.projectName}_${new Date().toISOString().slice(0, 10)}.json`;
    a.click();
    URL.revokeObjectURL(url);
    message.success('项目已导出');
  }, [getPersistableProject]);

  return (
    <ConfigProvider
      locale={zhCN}
      theme={{
        token: {
          colorPrimary: '#1677ff',
          borderRadius: 6,
        },
      }}
    >
      <AntApp>
        <DndProvider>
          <MainLayout
            onNewProject={handleNewProject}
            onSave={handleSave}
            onExport={handleExport}
            projects={projects}
            projectsLoading={projectsLoading}
            onSelectProject={handleSelectProject}
          />
        </DndProvider>

        {/* 新建项目对话框 */}
        <Modal
          title="新建项目"
          open={newProjectModalOpen}
          onOk={handleCreateProject}
          onCancel={() => setNewProjectModalOpen(false)}
          okText="创建"
          cancelText="取消"
        >
          <Input
            placeholder="请输入项目名称..."
            value={newProjectName}
            onChange={(e) => setNewProjectName(e.target.value)}
            onPressEnter={handleCreateProject}
            autoFocus
          />
        </Modal>
      </AntApp>
    </ConfigProvider>
  );
};

export default App;
