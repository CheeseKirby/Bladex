import React, { useState, useCallback, useRef, useEffect } from 'react';
import { Layout } from 'antd';
import TopBar, { type ProjectOption } from './TopBar';
import ModulePalette from '../palette/ModulePalette';
import PlanCanvas from '../canvas/PlanCanvas';
import ContextPanel from '../context/ContextPanel';
import ChatInput from './ChatInput';
import { usePlanStore } from '../../store/planStore';

const { Content, Sider } = Layout;

interface MainLayoutProps {
  onNewProject: () => void;
  onSave: () => void;
  onExport: () => void;
  projects: ProjectOption[];
  projectsLoading: boolean;
  onSelectProject: (projectId: string) => void;
}

/**
 * 三栏布局: 左模块面板 / 中画布+底部输入 / 右上下文面板。
 *
 * - 右侧上下文面板 400 (审查/子方案/生成文件内容多)
 * - 底部输入区可拖拽改高 (拖顶部分割条), 内部内容可上下滚动查看
 * - 整体配色: 柔和蓝灰主题, 降低饱和度
 */
const MainLayout: React.FC<MainLayoutProps> = ({ onNewProject, onSave, onExport, projects, projectsLoading, onSelectProject }) => {
  const project = usePlanStore((s) => s.project);

  // 底部输入区高度 — 可拖拽调整, 范围 [100, 60% 视口]
  const [bottomHeight, setBottomHeight] = useState(180);
  const draggingRef = useRef(false);
  const startYRef = useRef(0);
  const startHRef = useRef(0);

  const onDragStart = useCallback((e: React.MouseEvent) => {
    e.preventDefault();
    draggingRef.current = true;
    startYRef.current = e.clientY;
    startHRef.current = bottomHeight;
    document.body.style.cursor = 'row-resize';
    document.body.style.userSelect = 'none';
  }, [bottomHeight]);

  useEffect(() => {
    const onMove = (e: MouseEvent) => {
      if (!draggingRef.current) return;
      // 向上拖 = 增高 (dy 为负)
      const dy = e.clientY - startYRef.current;
      const next = Math.max(100, Math.min(window.innerHeight * 0.6, startHRef.current - dy));
      setBottomHeight(next);
    };
    const onUp = () => {
      if (draggingRef.current) {
        draggingRef.current = false;
        document.body.style.cursor = '';
        document.body.style.userSelect = '';
      }
    };
    window.addEventListener('mousemove', onMove);
    window.addEventListener('mouseup', onUp);
    return () => {
      window.removeEventListener('mousemove', onMove);
      window.removeEventListener('mouseup', onUp);
    };
  }, []);

  return (
    <Layout style={{ height: '100vh', background: '#fff' }}>
      {/* 顶部栏 */}
      <TopBar onNewProject={onNewProject} onSave={onSave} onExport={onExport} projects={projects} projectsLoading={projectsLoading} onSelectProject={onSelectProject} />

      <Layout style={{ flex: 1, overflow: 'hidden' }}>
        {/* 左侧：模块面板 */}
        <Sider
          width={210}
          style={{ background: '#fff', borderRight: '1px solid #eef0f4', overflow: 'auto' }}
        >
          <ModulePalette />
        </Sider>

        {/* 中央：方案画布 + 底部输入区 */}
        <Content style={{ display: 'flex', flexDirection: 'column', overflow: 'hidden', background: '#fff' }}>
          <div style={{ flex: 1, overflow: 'hidden', background: '#fafbfc' }}>
            <PlanCanvas />
          </div>

          {/* 拖拽分割条 — 鼠标悬停变 row-resize */}
          <div
            onMouseDown={onDragStart}
            style={{
              height: 6,
              cursor: 'row-resize',
              background: 'transparent',
              borderTop: '1px solid #eef0f4',
              transition: 'background 0.15s',
            }}
            onMouseEnter={(e) => (e.currentTarget.style.background = '#e6f4ff')}
            onMouseLeave={(e) => (e.currentTarget.style.background = 'transparent')}
            title="拖动调整高度"
          />

          {/* 底部输入区 — 固定高度, 内部可滚动 */}
          <div
            style={{
              height: bottomHeight,
              background: '#fff',
              overflowY: 'auto',
              padding: '14px 20px 16px',
            }}
          >
            <ChatInput disabled={!project} />
          </div>
        </Content>

        {/* 右侧：上下文面板 */}
        <Sider
          width={400}
          style={{ background: '#fff', borderLeft: '1px solid #eef0f4', overflow: 'auto' }}
        >
          <ContextPanel />
        </Sider>
      </Layout>
    </Layout>
  );
};

export default MainLayout;
