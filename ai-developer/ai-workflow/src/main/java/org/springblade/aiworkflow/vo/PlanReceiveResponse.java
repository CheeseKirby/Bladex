package org.springblade.aiworkflow.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.Map;

@Data
@Schema(description = "方案接收响应")
public class PlanReceiveResponse {

    @Schema(description = "接收编号")
    private String receptionId;

    @Schema(description = "状态")
    private String status;

    @Schema(description = "子方案状态: subPlanId -> status")
    private Map<String, String> subPlanStatuses;
}
