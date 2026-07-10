import React, { useCallback, useMemo, useEffect } from 'react';
import ReactFlow, {
  Background,
  Controls,
  MiniMap,
  useNodesState,
  useEdgesState,
  addEdge,
  Connection,
  Node,
  Edge,
  EdgeChange,
  MarkerType,
} from 'reactflow';
import 'reactflow/dist/style.css';
import { useDroppable } from '@dnd-kit/core';
import { usePlanStore } from '../../store/planStore';
import PlanNodeComponent from './PlanNode';
import SubPlanNodeComponent from './SubPlanNode';
import PlanEdge from './PlanEdge';
import type { ModuleType } from '../../types/plan';

const nodeTypes = {
  planNode: PlanNodeComponent,
  subPlanNode: SubPlanNodeComponent,
};

/** 注册自定义连线 — 让所有依赖连线带上删除按钮和选中删除能力 */
const edgeTypes = {
  planEdge: PlanEdge,
};

const NODE_COLORS: Record<ModuleType, string> = {
  ENTITY: '#1890ff',
  API: '#52c41a',
  PAGE: '#fa8c16',
  FLOW: '#722ed1',
  JOB: '#eb2f96',
  FEIGN: '#13c2c2',
  EXCEL: '#2f54eb',
  CONFIG: '#595959',
};

const PlanCanvas: React.FC = () => {
  const canvasModules = usePlanStore((s) => s.canvasModules);
  const project = usePlanStore((s) => s.project);
  const subPlans = usePlanStore((s) => s.project?.subPlans || []);

  // @dnd-kit droppable 区域
  const { setNodeRef, isOver } = useDroppable({ id: 'plan-canvas-drop' });

  // 画布节点：模块节点 + 方案节点 + 子方案节点
  const initialNodes: Node[] = useMemo(() => {
    // 模块节点 — 简单网格排列 (模块是并列的输入素材, 无需分组连线)
    const moduleNodes: Node[] = canvasModules.map((mod, i) => ({
      id: mod.id,
      type: 'planNode',
      position: { x: 50 + (i % 3) * 280, y: 50 + Math.floor(i / 3) * 160 },
      data: {
        label: mod.name,
        icon: mod.icon,
        type: mod.type,
        color: NODE_COLORS[mod.type],
        module: mod,
      },
    }));

    // 主方案节点 — 居中
    if (project?.masterPlan && project.status !== 'DRAFT' && project.status !== 'ANALYZING') {
      moduleNodes.push({
        id: 'master-plan-node',
        type: 'planNode',
        position: { x: 360, y: 260 },
        data: {
          label: '📋 总方案',
          icon: '📋',
          type: 'ENTITY' as ModuleType,
          color: '#1677ff',
          module: null,
        },
      });
    }

    // 子方案节点 — 底部按序排列
    const subPlanNodes: Node[] = subPlans.map((sp, i) => ({
      id: sp.id,
      type: 'subPlanNode',
      position: { x: 80 + i * 240, y: 480 },
      data: {
        label: sp.title,
        index: sp.index,
        status: sp.status,
        prerequisites: sp.prerequisites,
      },
    }));

    return [...moduleNodes, ...subPlanNodes];
  }, [canvasModules, project, subPlans]);

  // 画布连线 — 只画有真实语义的 DAG 依赖, 不在模块间强行连线:
  // 模块是并列的"输入素材", 互相无依赖, 不连线 (避免误导用户以为有顺序约束)。
  // 总方案 → 子方案的派生虚线已移除(无实际用途,徒增视觉噪音)。
  // 子方案 → 子方案 (实线 planEdge, 蓝, 带箭头): DAG 依赖, 可选中删除
  const initialEdges: Edge[] = useMemo(() => {
    const edges: Edge[] = [];

    // 子方案 → 子方案 (实线 planEdge, 可选中删除)
    for (const sp of subPlans) {
      for (const prereqId of sp.prerequisites) {
        edges.push({
          id: `${prereqId}->${sp.id}`,
          source: prereqId,
          target: sp.id,
          type: 'planEdge',
          animated: true,
          style: { stroke: '#1677ff' },
          markerEnd: { type: MarkerType.ArrowClosed },
        });
      }
    }
    return edges;
  }, [project, subPlans]);

  const [nodes, setNodes, onNodesChange] = useNodesState(initialNodes);
  const [edges, setEdges, onEdgesChange] = useEdgesState(initialEdges);
  const removeSubPlanDependency = usePlanStore((s) => s.removeSubPlanDependency);

  // 当模块/方案/子方案变化时，同步更新节点和连线
  useEffect(() => {
    setNodes(initialNodes);
  }, [initialNodes, setNodes]);

  useEffect(() => {
    setEdges(initialEdges);
  }, [initialEdges, setEdges]);

  /**
   * 包装 onEdgesChange:
   * - 用户按 Delete/Backspace 选中边后,ReactFlow 抛 `remove` 类型 change
   * - 仅对子方案依赖边 (type === 'planEdge') 同步删除到 store.prerequisites
   * - 虚线派生边 (link-mod2plan-* / link-plan2sub-*) 不可选中 (selectable:false),
   *   即使混入 remove 也会因 type 守卫被跳过, 下次 useMemo 重算时自动恢复
   */
  const handleEdgesChange = useCallback(
    (changes: EdgeChange[]) => {
      for (const c of changes) {
        if (c.type === 'remove') {
          const edgeId = (c as { id: string }).id;
          const edge = edges.find((e) => e.id === edgeId);
          if (edge && edge.type === 'planEdge') {
            removeSubPlanDependency(edge.source, edge.target);
          }
        }
      }
      onEdgesChange(changes);
    },
    [edges, onEdgesChange, removeSubPlanDependency]
  );

  const onConnect = useCallback(
    (params: Connection) => setEdges((eds) => addEdge({ ...params, type: 'planEdge' }, eds)),
    [setEdges]
  );

  if (!project) {
    return (
      <div
        style={{
          height: '100%',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          color: '#9aa3ad',
          fontSize: 17,
          flexDirection: 'column',
          gap: 10,
        }}
      >
        <div style={{ fontSize: 52 }}>🎨</div>
        <div>创建新项目或选择已有项目开始设计</div>
      </div>
    );
  }

  return (
    <div
      ref={setNodeRef}
      style={{
        height: '100%',
        width: '100%',
        background: isOver ? '#e6f4ff' : '#f5f5f5',
        transition: 'background 0.2s',
      }}
    >
      {canvasModules.length === 0 && subPlans.length === 0 && !project.masterPlan ? (
        <div
          style={{
            height: '100%',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            color: '#aab2bc',
            fontSize: 15,
            flexDirection: 'column',
            gap: 10,
          }}
        >
          <div style={{ fontSize: 40 }}>📥</div>
          <div>从左侧拖拽模块到此处，或在下方向LLM描述需求</div>
        </div>
      ) : (
        <ReactFlow
          nodes={nodes}
          edges={edges}
          onNodesChange={onNodesChange}
          onEdgesChange={handleEdgesChange}
          onConnect={onConnect}
          nodeTypes={nodeTypes}
          edgeTypes={edgeTypes}
          fitView
          attributionPosition="bottom-left"
          deleteKeyCode={['Delete', 'Backspace']}
        >
          <Background />
          <Controls />
          <MiniMap
            nodeColor={(n) => n.data?.color || '#ddd'}
            style={{ background: '#fafafa' }}
          />
        </ReactFlow>
      )}
    </div>
  );
};

export default PlanCanvas;
