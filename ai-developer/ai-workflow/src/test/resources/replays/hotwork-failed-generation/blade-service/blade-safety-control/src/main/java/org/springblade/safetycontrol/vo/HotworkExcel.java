package org.springblade.safetycontrol.vo;

import com.alibaba.excel.annotation.ExcelIgnore;
import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.ColumnWidth;
import com.alibaba.excel.annotation.write.style.ContentRowHeight;
import com.alibaba.excel.annotation.write.style.HeadRowHeight;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 动火作业 Excel 导出模型
 *
 * @author BladeX
 */
@Data
@ColumnWidth(25)
@HeadRowHeight(20)
@ContentRowHeight(18)
public class HotworkExcel implements Serializable {
	private static final long serialVersionUID = 1L;

	/**
	 * 主键ID
	 */
	@ExcelIgnore
	private Long hotworkId;

	/**
	 * 申请编号
	 */
	@ColumnWidth(20)
	@ExcelProperty("申请编号")
	private String applyCode;

	/**
	 * 申请时间
	 */
	@ColumnWidth(20)
	@ExcelProperty("申请时间")
	private Date applyTime;

	/**
	 * 动火等级
	 */
	@ExcelProperty("动火等级")
	private String hotworkLevel;

	/**
	 * 作业内容
	 */
	@ColumnWidth(30)
	@ExcelProperty("作业内容")
	private String workContent;

	/**
	 * 是否特殊时段
	 */
	@ExcelProperty("是否特殊时段")
	private String isSpecialPeriod;

	/**
	 * 是否升级
	 */
	@ExcelProperty("是否升级")
	private String upgradeFlag;

	/**
	 * 时段名称
	 */
	@ExcelProperty("时段名称")
	private String periodName;

	/**
	 * 时段类型
	 */
	@ExcelProperty("时段类型")
	private String periodType;

	/**
	 * 开始时间
	 */
	@ColumnWidth(20)
	@ExcelProperty("开始时间")
	private Date startTime;

	/**
	 * 结束时间
	 */
	@ColumnWidth(20)
	@ExcelProperty("结束时间")
	private Date endTime;

}