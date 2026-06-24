import React, { useState, useCallback } from 'react';
import { ConfigProvider, Modal, Input, message, App as AntApp } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import DndProvider from './components/layout/DndProvider';
import MainLayout from './components/layout/MainLayout';
import { usePlanStore } from './store/planStore';
import { saveProject } from './services/api';

const App: React.FC = () => {
  const [newProjectModalOpen, setNewProjectModalOpen] = useState(false);
  const [newProjectName, setNewProjectName] = useState('');

  const createProject = usePlanStore((s) => s.createProject);
  const project = usePlanStore((s) => s.project);

  const handleNewProject = useCallback(() => {
    setNewProjectName('');
    setNewProjectModalOpen(true);
  }, []);

  const handleCreateProject = useCallback(() => {
    if (!newProjectName.trim()) return;
    createProject(newProjectName.trim());
    setNewProjectModalOpen(false);
    message.success(`项目 "${newProjectName}" 已创建`);
  }, [newProjectName, createProject]);

  const handleSave = useCallback(async () => {
    if (!project) return;
    try {
      await saveProject(project);
      message.success('项目已保存');
    } catch {
      message.warning('保存失败（离线模式，数据仅在内存中）');
    }
  }, [project]);

  const handleExport = useCallback(() => {
    if (!project) return;
    const json = JSON.stringify(project, null, 2);
    const blob = new Blob([json], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `${project.projectName}_${new Date().toISOString().slice(0, 10)}.json`;
    a.click();
    URL.revokeObjectURL(url);
    message.success('项目已导出');
  }, [project]);

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
