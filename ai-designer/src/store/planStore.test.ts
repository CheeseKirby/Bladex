import assert from 'node:assert/strict';
import { describe, test, beforeEach } from 'node:test';

import { usePlanStore } from './planStore';
import { ORDER_MANAGEMENT_DEMO } from '../demo/orderManagement';
import type { DemoSeed } from '../demo/orderManagement';
import type { DraggedModule, SubPlan } from '../types/plan';

// planStore 用模块级 idCounter + Date.now() 生成 id,测试只断言行为与结构,
// 不断言具体 id 值,避免非确定性。

/** 把 store 恢复到初始状态(merge 模式,保留 actions)。 */
function resetStore() {
  usePlanStore.setState({
    project: null,
    canvasModules: [],
    streamingContent: '',
    isStreaming: false,
    reviewResult: null,
    receptionId: null,
    partBStatuses: {},
    partBOverallStatus: null,
    generatedFiles: [],
    executionTimeline: null,
    writeTarget: 'ISOLATED',
  });
}

let idSeq = 0;
const nextId = (p: string) => `${p}-${++idSeq}`;

function makeModule(overrides: Partial<DraggedModule> = {}): DraggedModule {
  return {
    id: overrides.id ?? nextId('mod'),
    type: overrides.type ?? 'ENTITY',
    name: overrides.name ?? '模块',
    icon: overrides.icon ?? '📦',
    color: overrides.color ?? '#1890ff',
    config: overrides.config ?? { tableName: 'blade_demo', moduleName: 'demo' },
  };
}

function makeSubPlan(overrides: Partial<SubPlan> = {}): SubPlan {
  return {
    id: overrides.id ?? nextId('sp'),
    masterPlanId: 'mp-1',
    index: overrides.index ?? 1,
    title: overrides.title ?? '子方案',
    planContent: overrides.planContent ?? '',
    prerequisites: overrides.prerequisites ?? [],
    status: overrides.status ?? 'PENDING',
    ...overrides,
  };
}

/** createProject 后塞入给定子方案,建立已知起点。 */
function seedProjectWithSubPlans(subPlans: SubPlan[]) {
  usePlanStore.getState().createProject('测试项目');
  usePlanStore.getState().setSubPlans(subPlans);
}

describe('createProject', () => {
  beforeEach(() => resetStore());

  test('创建新项目会清除上一项目的执行时间线', () => {
    usePlanStore.getState().setExecutionTimeline({
      receptionId: 'old', totalSubPlans: 0, completedSubPlans: 0, failedSubPlans: 0, subPlanTimelines: [],
    });

    usePlanStore.getState().createProject('新项目');

    assert.equal(usePlanStore.getState().executionTimeline, null);
  });

  test('创建后状态干净且字段正确', () => {
    usePlanStore.getState().createProject('订单');

    const s = usePlanStore.getState();
    assert.ok(s.project, 'project 应非空');
    assert.equal(s.project!.projectName, '订单');
    assert.equal(s.project!.status, 'DRAFT');
    assert.deepEqual(s.project!.modules, []);
    assert.deepEqual(s.project!.subPlans, []);
    assert.deepEqual(s.canvasModules, []);
    assert.equal(s.receptionId, null);
    assert.deepEqual(s.partBStatuses, {});
    assert.equal(s.partBOverallStatus, null);
    assert.deepEqual(s.generatedFiles, []);
  });
});

describe('模块画布与项目双向同步', () => {
  beforeEach(() => resetStore());

  test('addModuleToCanvas 同时加入画布与项目模块列表,并重写 id', () => {
    usePlanStore.getState().createProject('p');
    usePlanStore.getState().addModuleToCanvas(makeModule({ id: 'raw-id' }));

    const s = usePlanStore.getState();
    assert.equal(s.canvasModules.length, 1);
    assert.equal(s.project!.modules.length, 1);
    // id 被 `${type}_${genId()}` 覆盖,原始 id 不保留
    assert.match(s.canvasModules[0].id, /^ENTITY_/);
    assert.notEqual(s.canvasModules[0].id, 'raw-id');
  });

  test('removeModuleFromCanvas 双向剔除', () => {
    usePlanStore.getState().createProject('p');
    usePlanStore.getState().addModuleToCanvas(makeModule());
    usePlanStore.getState().addModuleToCanvas(makeModule());

    const before = usePlanStore.getState();
    const removeId = before.canvasModules[0].id;
    usePlanStore.getState().removeModuleFromCanvas(removeId);

    const s = usePlanStore.getState();
    assert.equal(s.canvasModules.length, 1);
    assert.equal(s.project!.modules.length, 1);
    assert.ok(!s.canvasModules.find((m) => m.id === removeId));
  });

  test('updateModuleConfig partial merge 且不影响其他模块', () => {
    usePlanStore.getState().createProject('p');
    usePlanStore.getState().addModuleToCanvas(makeModule({ config: { tableName: 't1' } }));
    usePlanStore.getState().addModuleToCanvas(makeModule({ config: { tableName: 't2' } }));

    const state = usePlanStore.getState();
    const targetId = state.canvasModules[0].id;
    usePlanStore.getState().updateModuleConfig(targetId, { moduleName: 'm1' });

    const s = usePlanStore.getState();
    assert.deepEqual(s.canvasModules[0].config, { tableName: 't1', moduleName: 'm1' });
    assert.deepEqual(s.canvasModules[1].config, { tableName: 't2' });
  });
});

describe('removeSubPlan 依赖与状态清理', () => {
  beforeEach(() => resetStore());

  test('删除子方案后,其他子方案的 prerequisites 剔除对它的引用', () => {
    seedProjectWithSubPlans([
      makeSubPlan({ id: 'sp-a', prerequisites: ['sp-b'] }),
      makeSubPlan({ id: 'sp-b', prerequisites: [] }),
    ]);

    usePlanStore.getState().removeSubPlan('sp-b');

    const s = usePlanStore.getState();
    const a = s.project!.subPlans.find((sp) => sp.id === 'sp-a');
    assert.deepEqual(a!.prerequisites, [], '被删子方案的引用应从 prerequisites 剔除');
  });

  test('删除子方案后,partBStatuses 中对应 key 被移除', () => {
    seedProjectWithSubPlans([
      makeSubPlan({ id: 'sp-a' }),
      makeSubPlan({ id: 'sp-b' }),
    ]);
    usePlanStore.getState().setPartBStatus('sp-b', 'EXECUTING');
    usePlanStore.getState().setPartBStatus('sp-a', 'QUEUED');

    usePlanStore.getState().removeSubPlan('sp-b');

    const statuses = usePlanStore.getState().partBStatuses;
    assert.equal(statuses['sp-b'], undefined, '被删子方案的 Part B 状态应清除');
    assert.equal(statuses['sp-a'], 'QUEUED', '其他子方案状态应保留');
  });

  test('删除不存在的 id 不报错且无副作用', () => {
    seedProjectWithSubPlans([makeSubPlan({ id: 'sp-a' })]);

    usePlanStore.getState().removeSubPlan('not-exist');

    assert.equal(usePlanStore.getState().project!.subPlans.length, 1);
  });
});

describe('removeSubPlanDependency', () => {
  beforeEach(() => resetStore());

  test('剔除指定依赖边,保留其他依赖', () => {
    seedProjectWithSubPlans([
      makeSubPlan({ id: 'target', prerequisites: ['src-1', 'src-2'] }),
    ]);

    usePlanStore.getState().removeSubPlanDependency('src-1', 'target');

    const target = usePlanStore.getState().project!.subPlans.find((sp) => sp.id === 'target');
    assert.deepEqual(target!.prerequisites, ['src-2']);
  });
});

describe('状态更新', () => {
  beforeEach(() => resetStore());

  test('updateSubPlanStatus 更新单个子方案状态', () => {
    seedProjectWithSubPlans([
      makeSubPlan({ id: 'sp-1', status: 'PENDING' }),
      makeSubPlan({ id: 'sp-2', status: 'PENDING' }),
    ]);

    usePlanStore.getState().updateSubPlanStatus('sp-1', 'REVIEWED');

    const s = usePlanStore.getState();
    const sp1 = s.project!.subPlans.find((sp) => sp.id === 'sp-1');
    const sp2 = s.project!.subPlans.find((sp) => sp.id === 'sp-2');
    assert.equal(sp1!.status, 'REVIEWED');
    assert.equal(sp2!.status, 'PENDING', '其他子方案不应受影响');
  });

  test('setPartBStatus 设置并覆盖 Part B 状态', () => {
    usePlanStore.getState().setPartBStatus('sp-1', 'QUEUED');
    usePlanStore.getState().setPartBStatus('sp-1', 'COMPLETED');

    assert.equal(usePlanStore.getState().partBStatuses['sp-1'], 'COMPLETED');
  });

  test('setPartBOverallStatus 设置整体状态', () => {
    usePlanStore.getState().setPartBOverallStatus('EXECUTING');
    assert.equal(usePlanStore.getState().partBOverallStatus, 'EXECUTING');
  });
});

describe('loadDemo 占位符替换', () => {
  beforeEach(() => resetStore());

  test('加载真实 demo 后所有占位符被替换为真实 id', () => {
    usePlanStore.getState().loadDemo(ORDER_MANAGEMENT_DEMO);

    const subPlans = usePlanStore.getState().project!.subPlans;
    for (const sp of subPlans) {
      for (const ref of sp.prerequisites) {
        assert.ok(!ref.startsWith('__SUBPLAN_'), `残留占位符: ${ref}`);
      }
    }
  });

  test('每个 prerequisite id 指向实际存在的子方案(无孤儿依赖)', () => {
    usePlanStore.getState().loadDemo(ORDER_MANAGEMENT_DEMO);

    const subPlans = usePlanStore.getState().project!.subPlans;
    const ids = new Set(subPlans.map((sp) => sp.id));
    for (const sp of subPlans) {
      for (const ref of sp.prerequisites) {
        assert.ok(ids.has(ref), `孤儿依赖: ${ref} 不存在于子方案 id 集合`);
      }
    }
  });

  test('加载后项目状态为 SUBPLANS_REVIEWED 且数量匹配', () => {
    usePlanStore.getState().loadDemo(ORDER_MANAGEMENT_DEMO);

    const s = usePlanStore.getState();
    assert.equal(s.project!.status, 'SUBPLANS_REVIEWED');
    assert.equal(s.project!.modules.length, ORDER_MANAGEMENT_DEMO.modules.length);
    assert.equal(s.project!.subPlans.length, ORDER_MANAGEMENT_DEMO.subPlans.length);
    assert.equal(s.canvasModules.length, ORDER_MANAGEMENT_DEMO.modules.length);
  });

  test('占位符按 0-based 索引映射到子方案 id', () => {
    const minimalSeed: DemoSeed = {
      projectName: '最小',
      rawRequirements: 'r',
      modules: [],
      masterPlan: { version: 1, planContent: '', status: 'SUBPLANS_REVIEWED' },
      subPlans: [
        { index: 1, title: 'a', planContent: '', prerequisites: [], status: 'CONFIRMED' },
        { index: 2, title: 'b', planContent: '', prerequisites: ['__SUBPLAN_0__'], status: 'CONFIRMED' },
      ],
    };

    usePlanStore.getState().loadDemo(minimalSeed);

    const subPlans = usePlanStore.getState().project!.subPlans;
    assert.equal(subPlans[1].prerequisites[0], subPlans[0].id, '__SUBPLAN_0__ 应映射到第一个子方案的 id');
  });
});

describe('resetProject', () => {
  beforeEach(() => resetStore());

  test('清空所有运行时状态', () => {
    usePlanStore.getState().createProject('p');
    usePlanStore.getState().addModuleToCanvas(makeModule());
    usePlanStore.getState().setPartBStatus('sp-1', 'EXECUTING');
    usePlanStore.getState().setReceptionId('rec-1');
    usePlanStore.getState().setGeneratedFiles([{ fileId: 1, fileName: 'a', filePath: '/a', action: 'CREATED' } as never]);
    usePlanStore.getState().setExecutionTimeline({
      receptionId: 'rec-1', totalSubPlans: 1, completedSubPlans: 0, failedSubPlans: 0, subPlanTimelines: [],
    });

    usePlanStore.getState().resetProject();

    const s = usePlanStore.getState();
    assert.equal(s.project, null);
    assert.deepEqual(s.canvasModules, []);
    assert.deepEqual(s.partBStatuses, {});
    assert.equal(s.receptionId, null);
    assert.deepEqual(s.generatedFiles, []);
  });
});

describe('流式缓冲', () => {
  beforeEach(() => resetStore());

  test('appendStreamingChunk 累加内容', () => {
    usePlanStore.getState().setStreamingContent('a');
    usePlanStore.getState().appendStreamingChunk('b');
    usePlanStore.getState().appendStreamingChunk('c');

    assert.equal(usePlanStore.getState().streamingContent, 'abc');
  });

  test('setStreamingContent 覆盖', () => {
    usePlanStore.getState().setStreamingContent('a');
    usePlanStore.getState().setStreamingContent('x');

    assert.equal(usePlanStore.getState().streamingContent, 'x');
  });
});

describe('project 为 null 时的安全降级', () => {
  beforeEach(() => resetStore());

  test('未创建项目时 setMasterPlan 不抛错且 project 仍为 null', () => {
    assert.doesNotThrow(() =>
      usePlanStore.getState().setMasterPlan({
        id: 'mp', projectId: 'p', version: 1, planContent: '', status: 'REVIEWED',
      }),
    );
    assert.equal(usePlanStore.getState().project, null);
  });

  test('未创建项目时 addModuleToCanvas 仍更新画布,project 保持 null', () => {
    usePlanStore.getState().addModuleToCanvas(makeModule());

    const s = usePlanStore.getState();
    assert.equal(s.canvasModules.length, 1);
    assert.equal(s.project, null);
  });

  test('未创建项目时 removeSubPlan 不抛错', () => {
    assert.doesNotThrow(() => usePlanStore.getState().removeSubPlan('x'));
  });
});

describe('子方案审查结果', () => {
  beforeEach(() => resetStore());

  test('写入 reviewedContent 和 changeLog，并把单个子方案标记 REVIEWED', () => {
    seedProjectWithSubPlans([makeSubPlan({ id: 'sp-1', status: 'GENERATED' })]);

    usePlanStore.getState().setSubPlanReview('sp-1', 'fixed', [
      { what: '字段', why: '规范', before: 'a', after: 'b' },
    ]);

    const sp = usePlanStore.getState().project!.subPlans[0];
    assert.equal(sp.reviewedContent, 'fixed');
    assert.equal(sp.status, 'REVIEWED');
    assert.equal(sp.reviewChangeLog?.length, 1);
  });

  test('全部子方案审查完成后项目进入 SUBPLANS_REVIEWED', () => {
    seedProjectWithSubPlans([
      makeSubPlan({ id: 'sp-1', status: 'GENERATED' }),
      makeSubPlan({ id: 'sp-2', status: 'REVIEWED' }),
    ]);

    usePlanStore.getState().setSubPlanReview('sp-1', 'fixed');

    assert.equal(usePlanStore.getState().project!.status, 'SUBPLANS_REVIEWED');
  });
});
