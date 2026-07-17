import assert from 'node:assert/strict';
import test from 'node:test';

import { isExecutionTimeline, isGeneratedFileDetail, isGeneratedFileSummaryArray, unwrapPartBRequest, unwrapPartBResponse } from './api';

// 这些测试锁住归一化的语义修复:
// - 以 success 布尔字段为准,而非用 data 存在性反推
// - "业务成功但 data 为空"不再误判失败
// - 失败时带出 msg,不丢错误原因
// - 兼容 BFF 502 形状 { success:false, msg } 与 Part B 透传 { code,success,data,msg }

test('成功且 data 非空 -> 原样带出,success 为 true', () => {
  const r = unwrapPartBResponse(
    { code: 200, success: true, data: [{ fileId: 1 }], msg: null },
    [],
  );
  assert.equal(r.success, true);
  assert.deepEqual(r.data, [{ fileId: 1 }]);
});

test('成功但 data 为 null 不再误判失败(关键修复)', () => {
  // 旧逻辑:raw.data 为 falsy -> 返回 { success: false }(误判)
  // 新逻辑:读 success:true -> 返回 { success: true, data: fallback }
  const r = unwrapPartBResponse(
    { code: 200, success: true, data: null, msg: null },
    [],
  );
  assert.equal(r.success, true);
  assert.deepEqual(r.data, []);
});

test('业务失败 -> success:false 且带出 msg', () => {
  const r = unwrapPartBResponse(
    { code: 500, success: false, data: null, msg: '数据库挂了' },
    [],
  );
  assert.equal(r.success, false);
  assert.deepEqual(r.data, []);
  assert.equal(r.msg, '数据库挂了');
});

test('BFF 502 形状 { success:false, msg } 也正确归一化', () => {
  const r = unwrapPartBResponse({ success: false, msg: 'Part B 通信失败' }, null);
  assert.equal(r.success, false);
  assert.equal(r.data, null);
  assert.equal(r.msg, 'Part B 通信失败');
});

test('无 success 字段的脏数据兜底为失败', () => {
  const r = unwrapPartBResponse({ weird: true }, []);
  assert.equal(r.success, false);
  assert.deepEqual(r.data, []);
});

test('fallback 按调用方需要区分(null 与空数组)', () => {
  assert.equal(unwrapPartBResponse({ success: true, data: null }, null).data, null);
  assert.deepEqual(unwrapPartBResponse({ success: true, data: null }, []).data, []);
});

test('success:true 但 data 形状错误时降级为失败', () => {
  const r = unwrapPartBResponse(
    { success: true, data: { fileId: 1 }, msg: null },
    [] as Array<{ fileId: number }>,
    Array.isArray,
  );
  assert.equal(r.success, false);
  assert.deepEqual(r.data, []);
  assert.match(r.msg ?? '', /shape/);
});

 test('Axios 502 也被归一化为 success:false，而不是向调用方抛异常', async () => {
  const axiosError = {
    isAxiosError: true,
    message: 'Request failed with status code 502',
    response: { data: { success: false, msg: 'Part B 通信失败' } },
  };

  const r = await unwrapPartBRequest(
    Promise.reject(axiosError),
    [] as Array<{ fileId: number }>,
    Array.isArray,
  );

  assert.equal(r.success, false);
  assert.deepEqual(r.data, []);
  assert.equal(r.msg, 'Part B 通信失败');
});


test('Part B DTO validators reject malformed nested data', () => {
  assert.equal(isGeneratedFileSummaryArray([{}]), false);
  assert.equal(isGeneratedFileDetail({ id: 1, subPlanId: 2, filePath: '/a', fileName: 'a' }), false);
  assert.equal(isExecutionTimeline({
    receptionId: 'r', totalSubPlans: 1, completedSubPlans: 0, failedSubPlans: 0,
    subPlanTimelines: [{ subPlanId: 1, fileCount: 0, steps: [{}] }],
  }), false);
});

test('Part B DTO validators accept minimal valid data', () => {
  const summary = { id: 1, subPlanId: 2, filePath: '/a', fileName: 'a.java' };
  assert.equal(isGeneratedFileSummaryArray([summary]), true);
  assert.equal(isGeneratedFileDetail({ ...summary, content: 'class A {}' }), true);
  assert.equal(isExecutionTimeline({
    receptionId: 'r', totalSubPlans: 1, completedSubPlans: 1, failedSubPlans: 0,
    subPlanTimelines: [{
      subPlanId: 1, fileCount: 1,
      steps: [{ id: 1, stage: 'WRITE', status: 'COMPLETED' }],
    }],
  }), true);
});

test('Part B DTO validators accept Java nulls for optional fields', () => {
  assert.equal(isGeneratedFileSummaryArray([{
    id: 1,
    subPlanId: 2,
    partASubPlanId: null,
    subPlanTitle: null,
    fileType: null,
    filePath: '/a',
    fileName: 'a.java',
    fileExtension: null,
    action: null,
    sizeBytes: null,
    lineCount: null,
    createTime: null,
  }]), true);

  assert.equal(isExecutionTimeline({
    receptionId: 'r',
    overallStatus: 'COMPLETED_WITH_ERRORS',
    totalSubPlans: 1,
    completedSubPlans: 0,
    failedSubPlans: 0,
    subPlanTimelines: [{
      subPlanId: 1,
      partASubPlanId: 'sub_1',
      index: 1,
      title: 'Entity',
      status: 'COMPLETED_WITH_ERRORS',
      errorMessage: null,
      fileCount: 1,
      startedAt: null,
      completedAt: null,
      steps: [{
        id: 1,
        stage: 'CROSS_FILE_VALIDATION',
        status: 'FAILED',
        action: null,
        filePath: null,
        reason: null,
        createTime: null,
      }],
    }],
  }), true);
});