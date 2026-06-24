package org.springblade.aiworkflow.service;

import org.springblade.aiworkflow.vo.ExecutionStatusVO;
import org.springblade.aiworkflow.vo.ExecutionTimelineVO;
import org.springblade.aiworkflow.vo.GeneratedFileDetailVO;
import org.springblade.aiworkflow.vo.GeneratedFileSummaryVO;
import org.springblade.aiworkflow.vo.PlanReceiveRequest;
import org.springblade.aiworkflow.vo.PlanReceiveResponse;
import org.springblade.aiworkflow.vo.SubPlanDetailVO;

import java.util.List;

public interface IPlanExecutionService {

    PlanReceiveResponse receivePlan(PlanReceiveRequest request);

    ExecutionStatusVO getStatus(String receptionId);

    void executeAsync(String receptionId);

    SubPlanDetailVO getSubPlanDetail(Long subPlanId);

    /** 按接收编号列出该方案下所有生成的代码文件(不含内容) */
    List<GeneratedFileSummaryVO> listGeneratedFiles(String receptionId);

    /** 获取单个生成文件的完整内容 */
    GeneratedFileDetailVO getGeneratedFileDetail(Long fileId);

    /** 获取该方案完整执行时间线 */
    ExecutionTimelineVO getTimeline(String receptionId);
}
