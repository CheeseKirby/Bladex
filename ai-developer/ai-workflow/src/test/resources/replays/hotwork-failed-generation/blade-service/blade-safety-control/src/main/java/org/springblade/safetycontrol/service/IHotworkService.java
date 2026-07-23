package org.springblade.safetycontrol.service;

import org.springblade.core.mp.base.BaseService;
import org.springblade.safetycontrol.entity.Hotwork;
import org.springblade.safetycontrol.vo.HotworkIVO;

/**
 * 动火作业 服务类
 *
 * @author BladeX
 * @since 2022-03-16
 */
public interface IHotworkService extends BaseService<Hotwork> {

	/**
	 * 新增动火作业（含特殊时段匹配、升级标记等业务校验）
	 *
	 * @param ivo 动火作业输入对象
	 * @return 是否成功
	 */
	boolean submit(HotworkIVO ivo);

	/**
	 * 修改动火作业（含特殊时段匹配、升级标记等业务校验）
	 *
	 * @param ivo 动火作业输入对象
	 * @return 是否成功
	 */
	boolean modify(HotworkIVO ivo);

	/**
	 * 升级动火作业等级
	 *
	 * @param id 动火作业主键
	 * @return 是否成功
	 */
	boolean upgrade(Long id);

}