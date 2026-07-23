package org.springblade.safetycontrol.vo;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * Hotwork 查询对象
 *
 * @author BladeX
 */
@Data
@ApiModel(value = "Hotwork查询对象", description = "Hotwork查询对象")
public class HotworkQVO implements Serializable {

	private static final long serialVersionUID = 1L;

	@ApiModelProperty(value = "主键")
	private Long hotworkId;

	@ApiModelProperty(value = "申请编号")
	private String applyCode;

	@ApiModelProperty(value = "申请时间")
	private Date applyTime;

	@ApiModelProperty(value = "动火等级")
	private Integer hotworkLevel;

	@ApiModelProperty(value = "作业内容")
	private String workContent;

	@ApiModelProperty(value = "是否特殊时期")
	private Integer isSpecialPeriod;

	@ApiModelProperty(value = "升级标志")
	private Integer upgradeFlag;

	@ApiModelProperty(value = "时期名称")
	private String periodName;

	@ApiModelProperty(value = "时期类型")
	private Integer periodType;

	@ApiModelProperty(value = "开始时间")
	private Date startTime;

	@ApiModelProperty(value = "结束时间")
	private Date endTime;

}