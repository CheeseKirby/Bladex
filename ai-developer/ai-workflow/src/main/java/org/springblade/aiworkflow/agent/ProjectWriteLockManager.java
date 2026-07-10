package org.springblade.aiworkflow.agent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 项目写锁管理器 - C3: 消除 REAL 模式 TOCTOU 竞态。
 *
 * <p>REAL 模式"查重(detectNameConflicts) + 写盘(fileWriteExecutor.write)"非原子,线程池 max4 并发时,
 * 两个 REAL plan 针对同一目标项目可同时通过查重后写入同名文件,后者静默覆盖前者。
 * 本类对同一 {@code targetProjectRoot} 提供进程级 {@link ReentrantLock},plan 级串行化 REAL 写盘。
 *
 * <p>ISOLATED 模式不受影响(隔离区允许覆盖,无需串行)。不同 targetProjectRoot 的锁互不阻塞。
 *
 * @author AI Developer
 */
@Slf4j
@Component
public class ProjectWriteLockManager {

    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    /**
     * 获取(或创建)某 targetProjectRoot 对应的写锁。
     * 同一 root 的锁串行化 REAL 写盘;不同 root 返回不同锁实例,互不阻塞。
     */
    public ReentrantLock lockFor(String targetProjectRoot) {
        String key = targetProjectRoot == null ? "" : targetProjectRoot;
        return locks.computeIfAbsent(key, k -> new ReentrantLock());
    }
}
