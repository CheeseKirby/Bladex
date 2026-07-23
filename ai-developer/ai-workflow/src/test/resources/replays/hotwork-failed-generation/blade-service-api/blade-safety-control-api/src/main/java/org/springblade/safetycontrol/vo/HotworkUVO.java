package org.springblade.safetycontrol.vo;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.NotNull;
import java.io.Serializable;
import java.util.Date;

/**
 * Hotwork UVO (修改对象)
 */
@Data
@ApiModel(value = "HotworkUVO对象", description = "动火作业修改对象")
public class HotworkUVO implements Serializable {

	private static final long serialVersionUID = 1L;

	@NotNull(message = "主键不能为空")
	@ApiModelProperty(value = "主键")
	private Long id;

	@NotNull(message = "申请编号不能为空")
	@ApiModelProperty(value = "申请编号")
	private String applyCode;

	@NotNull(message = "申请时间不能为空")
	@ApiModelProperty(value = "申请时间")
	private Date applyTime;

	@NotNull(message = "动火等级不能为空")
	@ApiModelProperty(value = "动火等级")
	private Integer hotworkLevel;

	@NotNull(message = "作业内容不能为空")
	@ApiModelProperty(value = "作业内容")
	private String workContent;

	@NotNull(message = "是否特殊时期不能为空")
	@ApiModelProperty(value = "是否特殊时期")
	private Integer isSpecialPeriod;

	@NotNull(message = "是否提级不能为空")
	@ApiModelProperty(value = "是否提级")
	private Integer upgradeFlag;

	@NotNull(message = "时期名称不能为空")
	@ApiModelProperty(value = "时期名称")
	private String periodName;

	@NotNull(message = "时期类型不能为空")
	@ApiModelProperty(value = "时期类型")
	private Integer periodType;

	@NotNull(message = "开始时间不能为空")
	@ApiModelProperty(value = "开始时间")
	private Date startTime;

	@NotNull(message = "结束时间不能为空")
	@ApiModelProperty(value = "结束时间")
	private Date endTime;

}