package org.springblade.safetycontrol.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springblade.core.mp.base.BaseEntity;
import org.springframework.format.annotation.DateTimeFormat;

import java.util.Date;

@Data
@TableName("blade_hotwork")
@EqualsAndHashCode(callSuper = true)
@ApiModel(value = "Hotwork对象", description = "动火作业")
public class Hotwork extends BaseEntity {

	private static final long serialVersionUID = 1L;

	@JsonSerialize(using = ToStringSerializer.class)
	@ApiModelProperty(value = "动火作业ID")
	private Long hotworkId;

	@ApiModelProperty(value = "申请编号")
	private String applyCode;

	@DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
	@JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
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

	@DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
	@JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
	@ApiModelProperty(value = "开始时间")
	private Date startTime;

	@DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
	@JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
	@ApiModelProperty(value = "结束时间")
	private Date endTime;

}