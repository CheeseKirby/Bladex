package org.springblade.aiworkflow.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springblade.aiworkflow.entity.AiSubPlan;
import org.springblade.aiworkflow.mapper.AiSubPlanMapper;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 拓扑排序器(H8 从 BladeXCodeAgent 拆出)- 子方案 DAG 拓扑排序 + 依赖解析。
 *
 * <p>处理 Part A 字符串子方案 ID -> Part B 内部 Long ID 映射;prerequisites 可能是 Part A 字符串 ID
 * 或内部 Long ID(兜底 Long.parseLong)。Kahn 算法拓扑排序,检测循环依赖。
 *
 * @author AI Developer
 */
@Slf4j
@Component
public class TopologySorter {

    private final ObjectMapper objectMapper;
    private final AiSubPlanMapper subPlanMapper;

    public TopologySorter(ObjectMapper objectMapper, AiSubPlanMapper subPlanMapper) {
        this.objectMapper = objectMapper;
        this.subPlanMapper = subPlanMapper;
    }

    /**
     * 使用 Kahn 算法对子方案进行拓扑排序。
     *
     * <p>prereqId 可能是 Part A 字符串 ID,也可能是 Part B 内部 Long ID(兜底 Long.parseLong)。
     * 若解析失败会被静默忽略(整个 DAG 退化成按插入顺序串行的伪拓扑序)。
     *
     * @return 拓扑序列表;存在循环依赖时返回 null
     */
    public List<AiSubPlan> buildExecutionOrder(List<AiSubPlan> subPlans) {
        Map<Long, AiSubPlan> subPlanMap = new LinkedHashMap<>();
        Map<Long, List<Long>> adjList = new HashMap<>();
        Map<Long, Integer> inDegree = new HashMap<>();
        // Part A 子方案 ID -> Part B 内部 ID 映射
        Map<String, Long> partAIdToInternalId = new HashMap<>();

        for (AiSubPlan sp : subPlans) {
            subPlanMap.put(sp.getId(), sp);
            adjList.put(sp.getId(), new ArrayList<>());
            inDegree.put(sp.getId(), 0);
            if (sp.getPartASubPlanId() != null) {
                partAIdToInternalId.put(sp.getPartASubPlanId(), sp.getId());
            }
        }

        // 解析依赖关系 - prereqId 可能是 Part A 字符串 ID,也可能是 Part B 内部 Long ID
        for (AiSubPlan sp : subPlans) {
            List<String> prereqs = parsePrerequisites(sp.getPrerequisitesJson());
            for (String prereqId : prereqs) {
                Long pid = partAIdToInternalId.get(prereqId);
                if (pid == null) {
                    // 兜底: 尝试当成 Long ID 解析(以防未来直接传内部 ID)
                    try {
                        pid = Long.parseLong(prereqId);
                    } catch (NumberFormatException ignored) {
                        log.warn("未知前置依赖 ID,已忽略: subPlanId={}, prereq={}", sp.getId(), prereqId);
                        continue;
                    }
                }
                if (adjList.containsKey(pid)) {
                    adjList.get(pid).add(sp.getId());
                    inDegree.merge(sp.getId(), 1, Integer::sum);
                }
            }
        }

        // Kahn算法
        Queue<Long> queue = new LinkedList<>();
        for (Map.Entry<Long, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.offer(entry.getKey());
            }
        }

        List<AiSubPlan> result = new ArrayList<>();
        while (!queue.isEmpty()) {
            Long current = queue.poll();
            result.add(subPlanMap.get(current));
            for (Long next : adjList.get(current)) {
                int degree = inDegree.get(next) - 1;
                inDegree.put(next, degree);
                if (degree == 0) {
                    queue.offer(next);
                }
            }
        }

        // 检查是否存在循环
        if (result.size() != subPlans.size()) {
            log.error("DAG中存在循环依赖!排序结果: {}, 原始: {}", result.size(), subPlans.size());
            return null;
        }

        return result;
    }

    @SuppressWarnings("unchecked")
    public List<String> parsePrerequisites(String prerequisitesJson) {
        if (prerequisitesJson == null || prerequisitesJson.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(prerequisitesJson, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException e) {
            log.warn("解析前置依赖 JSON 失败,按无依赖处理: {}", prerequisitesJson);
            return Collections.emptyList();
        }
    }

    /**
     * 判断给定子方案是否依赖于任何一个已失败/已跳过的子方案 id。
     * 注意 prerequisitesJson 中存的是 Part A 子方案字符串 ID,这里需要先转换为 Part B 内部 Long ID。
     */
    public boolean dependsOnFailed(AiSubPlan sp, Set<Long> failedIds) {
        if (failedIds.isEmpty()) return false;
        List<String> prereqs = parsePrerequisites(sp.getPrerequisitesJson());
        if (prereqs.isEmpty()) return false;
        // 通过 plan_id 把所有子方案再拉一次,建立 partA-id -> internal-id 映射
        // 数据量是单 plan 的子方案数,通常 <= 10
        List<AiSubPlan> siblings = subPlanMapper.selectByPlanId(sp.getPlanId());
        Map<String, Long> partAIdToInternalId = new HashMap<>();
        for (AiSubPlan s : siblings) {
            if (s.getPartASubPlanId() != null) {
                partAIdToInternalId.put(s.getPartASubPlanId(), s.getId());
            }
        }
        for (String prereqId : prereqs) {
            Long pid = partAIdToInternalId.get(prereqId);
            if (pid == null) {
                try {
                    pid = Long.parseLong(prereqId);
                } catch (NumberFormatException ignored) {
                    continue;
                }
            }
            if (failedIds.contains(pid)) return true;
        }
        return false;
    }

    /**
     * 构建反向依赖: 子方案 id -> 直接依赖它的下游子方案 id 列表。
     * 当前主要供日志/未来优化使用,主流程通过 {@link #dependsOnFailed} 在线性遍历中按需查询。
     */
    public Map<Long, List<String>> buildReverseDependencies(List<AiSubPlan> subPlans) {
        // 建立 partA-id -> internal-id 映射
        Map<String, Long> partAIdToInternalId = new HashMap<>();
        for (AiSubPlan sp : subPlans) {
            if (sp.getPartASubPlanId() != null) {
                partAIdToInternalId.put(sp.getPartASubPlanId(), sp.getId());
            }
        }
        Map<Long, List<String>> reverse = new HashMap<>();
        for (AiSubPlan sp : subPlans) {
            List<String> prereqs = parsePrerequisites(sp.getPrerequisitesJson());
            for (String p : prereqs) {
                Long pid = partAIdToInternalId.get(p);
                if (pid == null) {
                    try {
                        pid = Long.parseLong(p);
                    } catch (NumberFormatException ignored) {
                        continue;
                    }
                }
                reverse.computeIfAbsent(pid, k -> new ArrayList<>()).add(String.valueOf(sp.getId()));
            }
        }
        return reverse;
    }
}
