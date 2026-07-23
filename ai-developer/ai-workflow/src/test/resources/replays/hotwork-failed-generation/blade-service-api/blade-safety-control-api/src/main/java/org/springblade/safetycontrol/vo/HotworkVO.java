package org.springblade.safetycontrol.vo;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springblade.safetycontrol.entity.Hotwork;
import org.springblade.core.tool.node.INode;

import java.io.Serializable;
import java.util.Date;

/**
 * 动火作业表 VO
 *
 * @author BladeX
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ApiModel(value = "HotworkVO", description = "动火作业表 VO")
public class HotworkVO extends Hotwork implements INode, Serializable {
	private static final long serialVersionUID = 1L;

	@ApiModelProperty(value = "主键ID")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long id;

	@ApiModelProperty(value = "父主键ID")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long parentId;

	@Override
	public Long getId() {
		return this.hotworkId;
	}

	@Override
	public void setId(Long id) {
		this.hotworkId = id;
	}

	@Override
	public Long getParentId() {
		return parentId;
	}

	public void setParentId(Long parentId) {
		this.parentId = parentId;
	}
}