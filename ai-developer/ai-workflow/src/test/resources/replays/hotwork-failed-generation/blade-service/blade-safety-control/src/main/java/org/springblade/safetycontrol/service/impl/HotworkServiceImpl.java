package org.springblade.safetycontrol.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springblade.core.log.exception.ServiceException;
import org.springblade.core.mp.base.BaseServiceImpl;
import org.springblade.core.tool.utils.BeanUtil;
import org.springblade.core.tool.utils.Func;
import org.springblade.safetycontrol.entity.Hotwork;
import org.springblade.safetycontrol.mapper.HotworkMapper;
import org.springblade.safetycontrol.service.IHotworkService;
import org.springblade.safetycontrol.vo.HotworkIVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.Objects;

/**
 * 动火作业 服务实现类
 *
 * @author BladeX
 */
@Service
public class HotworkServiceImpl extends BaseServiceImpl<HotworkMapper, Hotwork> implements IHotworkService {

	@Override
	@Transactional(rollbackFor = Exception.class)
	public boolean submit(HotworkIVO ivo) {
		if (Func.isEmpty(ivo)) {
			throw new ServiceException("动火作业输入对象不能为空!");
		}
		Hotwork hotwork = this.convertToEntity(ivo);
		this.validateBusiness(hotwork);
		hotwork.setHotworkId(null);
		return this.save(hotwork);
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public boolean modify(HotworkIVO ivo) {
		if (Func.isEmpty(ivo)) {
			throw new ServiceException("动火作业输入对象不能为空!");
		}
		if (Func.isEmpty(ivo.getHotworkId())) {
			throw new ServiceException("动火作业主键不能为空!");
		}
		Hotwork existHotwork = this.getById(ivo.getHotworkId());
		if (Func.isEmpty(existHotwork)) {
			throw new ServiceException("动火作业记录不存在!");
		}
		Hotwork hotwork = this.convertToEntity(ivo);
		this.validateBusiness(hotwork);
		return this.updateById(hotwork);
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public boolean upgrade(Long id) {
		if (Func.isEmpty(id)) {
			throw new ServiceException("动火作业主键不能为空!");
		}
		Hotwork hotwork = this.getById(id);
		if (Func.isEmpty(hotwork)) {
			throw new ServiceException("动火作业记录不存在!");
		}
		hotwork.setUpgradeFlag(1);
		Integer currentLevel = hotwork.getHotworkLevel();
		if (Func.isNotEmpty(currentLevel) && currentLevel > 1) {
			hotwork.setHotworkLevel(currentLevel - 1);
		}
		return this.updateById(hotwork);
	}

	/**
	 * 业务校验：申请编号唯一性、特殊时段匹配、升级标记
	 *
	 * @param hotwork 动火作业实体
	 */
	private void validateBusiness(Hotwork hotwork) {
		if (Func.isEmpty(hotwork.getApplyCode())) {
			throw new ServiceException("动火作业申请编号不能为空!");
		}
		if (this.checkApplyCodeExist(hotwork.getApplyCode(), hotwork.getHotworkId())) {
			throw new ServiceException("动火作业申请编号已存在!");
		}
		if (Func.isEmpty(hotwork.getStartTime()) || Func.isEmpty(hotwork.getEndTime())) {
			throw new ServiceException("动火作业开始时间或结束时间不能为空!");
		}
		if (hotwork.getEndTime().before(hotwork.getStartTime())) {
			throw new ServiceException("动火作业结束时间不能早于开始时间!");
		}
		if (this.checkTimeConflict(hotwork.getHotworkId(), hotwork.getStartTime(), hotwork.getEndTime())) {
			throw new ServiceException("动火作业时间与已有记录冲突!");
		}
		this.matchSpecialPeriod(hotwork);
	}

	/**
	 * IVO 转 Entity
	 *
	 * @param ivo 动火作业输入对象
	 * @return 动火作业实体
	 */
	private Hotwork convertToEntity(HotworkIVO ivo) {
		return Objects.requireNonNull(BeanUtil.copy(ivo, Hotwork.class));
	}

	/**
	 * 校验申请编号是否存在
	 *
	 * @param applyCode  申请编号
	 * @param excludeId  排除的主键（新增时传 null，修改时传当前主键）
	 * @return 是否存在
	 */
	private boolean checkApplyCodeExist(String applyCode, Long excludeId) {
		if (Func.isEmpty(applyCode)) {
			return false;
		}
		LambdaQueryWrapper<Hotwork> lqw = Wrappers.<Hotwork>lambdaQuery()
			.eq(Hotwork::getApplyCode, applyCode);
		if (Func.isNotEmpty(excludeId)) {
			lqw.ne(Hotwork::getHotworkId, excludeId);
		}
		long count = this.count(lqw);
		return count > 0L;
	}

	/**
	 * 校验动火作业时间是否冲突
	 *
	 * @param excludeId 排除的主键
	 * @param startTime 开始时间
	 * @param endTime   结束时间
	 * @return 是否冲突
	 */
	private boolean checkTimeConflict(Long excludeId, Date startTime, Date endTime) {
		if (Func.isEmpty(startTime) || Func.isEmpty(endTime)) {
			return false;
		}
		LambdaQueryWrapper<Hotwork> lqw = Wrappers.<Hotwork>lambdaQuery()
			.lt(Hotwork::getStartTime, endTime)
			.gt(Hotwork::getEndTime, startTime);
		if (Func.isNotEmpty(excludeId)) {
			lqw.ne(Hotwork::getHotworkId, excludeId);
		}
		long count = this.count(lqw);
		return count > 0L;
	}

	/**
	 * 匹配特殊时段，若匹配则设置 isSpecialPeriod 和 upgradeFlag
	 *
	 * @param hotwork 动火作业实体
	 * @return 是否匹配到特殊时段
	 */
	private boolean matchSpecialPeriod(Hotwork hotwork) {
		if (Func.isEmpty(hotwork) || Func.isEmpty(hotwork.getApplyTime())) {
			return false;
		}
		Date applyTime = hotwork.getApplyTime();
		LambdaQueryWrapper<Hotwork> lqw = Wrappers.<Hotwork>lambdaQuery()
			.eq(Hotwork::getIsSpecialPeriod, 1)
			.le(Hotwork::getStartTime, applyTime)
			.ge(Hotwork::getEndTime, applyTime);
		if (Func.isNotEmpty(hotwork.getHotworkId())) {
			lqw.ne(Hotwork::getHotworkId, hotwork.getHotworkId());
		}
		long count = this.count(lqw);
		if (count > 0L) {
			hotwork.setIsSpecialPeriod(1);
			hotwork.setUpgradeFlag(1);
			return true;
		}
		return false;
	}

}