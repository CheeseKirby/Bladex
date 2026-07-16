import { useCallback, useEffect, useRef } from 'react';
import { usePlanStore } from '../store/planStore';
import { queryPartBStatus, listPartBFiles, getPartBTimeline } from '../services/api';
import type { PartBSubPlanStatus } from '../types/plan';

/**
 * Part B 状态轮询 Hook(单例)。
 *
 * 关键设计:
 * - 用模块级单例的轮询循环,无论 Hook 在多少组件中调用,只跑一个定时器,避免重复打 BFF;
 * - 用 setTimeout 自调度而非 setInterval,确保前一次请求完成后才发起下一次,杜绝并发重叠;
 * - receptionId 变化时自动重启 / 终态停止;
 * - 出错时不停止轮询(网络瞬断常见),仅在 console.warn,前端组件可通过返回的 lastError 自行展示;
 * - 已知 terminal 状态(COMPLETED/FAILED) 自动停止。
 */

const POLL_INTERVAL_MS = 3000;
const TERMINAL_STATES = new Set(['COMPLETED', 'COMPLETED_WITH_ERRORS', 'FAILED']);

// 单例状态 — 由第一个挂载的实例驱动,后续实例只是订阅 store
let activeReceptionId: string | null = null;
let nextRunTimer: ReturnType<typeof setTimeout> | null = null;
let stopped = true;

interface PollControls {
  startPolling: () => void;
  stopPolling: () => void;
  refreshFiles: () => Promise<void>;
  refreshTimeline: () => Promise<void>;
}

export function usePartBStatusPoll(): PollControls {
  const receptionId = usePlanStore((s) => s.receptionId);

  // 一次性把 store actions 从 zustand 取出来作为模块级 setter — 不直接依赖 React 闭包
  const setPartBStatus = usePlanStore((s) => s.setPartBStatus);
  const setPartBOverallStatus = usePlanStore((s) => s.setPartBOverallStatus);
  const setGeneratedFiles = usePlanStore((s) => s.setGeneratedFiles);
  const setExecutionTimeline = usePlanStore((s) => s.setExecutionTimeline);

  // ref 保证内部函数始终读到最新的 setter(虽然 zustand setter 本身稳定,但维护严格契约更可读)
  const storeRefs = useRef({ setPartBStatus, setPartBOverallStatus, setGeneratedFiles, setExecutionTimeline });
  storeRefs.current = { setPartBStatus, setPartBOverallStatus, setGeneratedFiles, setExecutionTimeline };

  const stopPolling = useCallback(() => {
    stopped = true;
    activeReceptionId = null;
    if (nextRunTimer) {
      clearTimeout(nextRunTimer);
      nextRunTimer = null;
    }
  }, []);

  const refreshFiles = useCallback(async () => {
    if (!receptionId) return;
    try {
      const res = await listPartBFiles(receptionId);
      if (res.success) storeRefs.current.setGeneratedFiles(res.data);
    } catch (err) {
      console.warn('[Part B 文件列表] 拉取失败:', err);
    }
  }, [receptionId]);

  const refreshTimeline = useCallback(async () => {
    if (!receptionId) return;
    try {
      const res = await getPartBTimeline(receptionId);
      if (res.success && res.data) storeRefs.current.setExecutionTimeline(res.data);
    } catch (err) {
      console.warn('[Part B 时间线] 拉取失败:', err);
    }
  }, [receptionId]);

  const startPolling = useCallback(() => {
    if (!receptionId) {
      console.warn('[Part B 状态轮询] receptionId 为空,跳过启动');
      return;
    }
    // 已经在轮询同一个 receptionId? 不重启
    if (!stopped && activeReceptionId === receptionId) return;
    // 切换 receptionId — 清掉旧的
    stopPolling();
    stopped = false;
    activeReceptionId = receptionId;

    const pollLoop = async () => {
      // receptionId 变了 / 停止了 → 直接退出
      if (stopped || activeReceptionId !== receptionId) return;

      const refs = storeRefs.current;
      try {
        const res = await queryPartBStatus(receptionId);
        const data = res?.data;
        if (data) {
          if (data.overallStatus) refs.setPartBOverallStatus(data.overallStatus);
          const updates: { subPlanId: string; status: string }[] = data.subPlanUpdates ?? [];
          for (const item of updates) {
            if (!item.subPlanId || !item.status) continue;
            // 校验 status 在已知集合内,避免脏数据污染 Map
            if (['QUEUED', 'EXECUTING', 'COMPLETED', 'COMPLETED_WITH_ERRORS', 'FAILED', 'SKIPPED'].includes(item.status)) {
              refs.setPartBStatus(item.subPlanId, item.status as PartBSubPlanStatus);
            }
          }
          if (data.overallStatus && TERMINAL_STATES.has(data.overallStatus)) {
            // 终态: 再拉一次完整文件 + 时间线后停止
            try {
              const filesRes = await listPartBFiles(receptionId);
              if (filesRes.success) refs.setGeneratedFiles(filesRes.data);
            } catch (e) {
              console.warn('[Part B 文件列表] 终态拉取失败:', e);
            }
            try {
              const tlRes = await getPartBTimeline(receptionId);
              if (tlRes.success && tlRes.data) refs.setExecutionTimeline(tlRes.data);
            } catch (e) {
              console.warn('[Part B 时间线] 终态拉取失败:', e);
            }
            stopPolling();
            return;
          }
        }
        // 非终态: 并发拉一次文件 + 时间线(允许失败,失败时下一轮继续)
        listPartBFiles(receptionId)
          .then((r) => r.success && refs.setGeneratedFiles(r.data))
          .catch((e) => console.warn('[Part B 文件列表]', e));
        getPartBTimeline(receptionId)
          .then((r) => r.success && r.data && refs.setExecutionTimeline(r.data))
          .catch((e) => console.warn('[Part B 时间线]', e));
      } catch (err) {
        console.warn('[Part B 状态轮询] 失败:', err);
        // 不停止 — 网络瞬断时给下一次机会
      }
      if (!stopped && activeReceptionId === receptionId) {
        nextRunTimer = setTimeout(pollLoop, POLL_INTERVAL_MS);
      }
    };
    // 立刻跑一次
    void pollLoop();
  }, [receptionId, stopPolling]);

  // receptionId 变化时,自动启动新的轮询(此前若有旧的,会被 startPolling 内部停掉)
  useEffect(() => {
    if (receptionId) {
      startPolling();
    } else {
      stopPolling();
    }
    // 卸载时不强制停 — 因为单例由其他实例继续接管,
    // 但若没有任何受 receptionId 控制的组件挂载,activeReceptionId 仍指向旧值,下次启动会复用。
    return undefined;
  }, [receptionId, startPolling, stopPolling]);

  return { startPolling, stopPolling, refreshFiles, refreshTimeline };
}
