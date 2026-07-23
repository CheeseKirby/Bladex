package org.springblade.safetycontrol.vo;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.io.Serializable;
import java.util.Date;

/**
 * HotworkIVO
 *
 * @author BladeX
 */
@Data
@ApiModel(value = "HotworkIVO", description = "动火作业新增对象")
public class HotworkIVO implements Serializable {

	private static final long serialVersionUID = 1L;

	@ApiModelProperty(value = "申请单号")
	@NotBlank(message = "申请单号不能为空")
	private String applyCode;

	@ApiModelProperty(value = "申请时间")
	@NotNull(message = "申请时间不能为空")
	private Date applyTime;

	@ApiModelProperty(value = "动火等级")
	@NotNull(message = "动火等级不能为空")
	private Integer hotworkLevel;

	@ApiModelProperty(value = "作业内容")
	@NotBlank(message = "作业内容不能为空")
	private String workContent;

	@ApiModelProperty(value = "是否特殊时期 0否 1是")
	@NotNull(message = "是否特殊时期不能为空")
	private Integer isSpecialPeriod;

	@ApiModelProperty(value = "升级标识 0否 1是")
	@NotNull(message = "升级标识不能为空")
	private Integer upgradeFlag;

	@ApiModelProperty(value = "时期名称")
	@NotBlank(message = "时期名称不能为空")
	private String periodName;

	@ApiModelProperty(value = "时期类型")
	@NotNull(message = "时期类型不能为空")
	private Integer periodType;

	@ApiModelProperty(value = "开始时间")
	@NotNull(message = "开始时间不能为空")
	private Date startTime;

	@ApiModelProperty(value = "结束时间")
	@NotNull(message = "结束时间不能为空")
	private Date endTime;

}