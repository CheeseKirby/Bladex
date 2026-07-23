package org.springblade.safetycontrol.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import org.apache.ibatis.annotations.Param;
import org.springblade.safetycontrol.entity.Hotwork;
import org.springblade.safetycontrol.vo.HotworkVO;
import org.springblade.safetycontrol.vo.HotworkQVO;

import java.util.List;

/**
 * 动火作业 Mapper 接口
 *
 * @author BladeX
 * @since 2022-03-16
 */
public interface HotworkMapper extends BaseMapper<Hotwork> {

	/**
	 * 自定义分页查询
	 *
	 * @param page 分页参数
	 * @param qvo  查询参数
	 * @return 分页列表
	 */
	List<HotworkVO> selectHotworkPage(IPage<HotworkVO> page, @Param("qvo") HotworkQVO qvo);

	/**
	 * 导出动火作业列表
	 *
	 * @param qvo 查询参数
	 * @return 动火作业列表
	 */
	List<HotworkVO> exportHotwork(@Param("qvo") HotworkQVO qvo);

}