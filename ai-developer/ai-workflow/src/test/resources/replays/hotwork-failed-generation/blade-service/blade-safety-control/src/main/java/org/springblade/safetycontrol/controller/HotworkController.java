package org.springblade.safetycontrol.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.github.xiaoymin.knife4j.annotations.ApiOperationSupport;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import lombok.AllArgsConstructor;
import org.springblade.core.boot.ctrl.BladeController;
import org.springblade.core.mp.support.Condition;
import org.springblade.core.mp.support.Query;
import org.springblade.core.tool.api.R;
import org.springblade.core.tool.utils.Func;
import org.springblade.safetycontrol.entity.Hotwork;
import org.springblade.safetycontrol.service.IHotworkService;
import org.springblade.safetycontrol.vo.HotworkVO;
import org.springblade.safetycontrol.vo.HotworkIVO;
import org.springblade.safetycontrol.vo.HotworkUVO;
import org.springblade.safetycontrol.wrapper.HotworkWrapper;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.Map;

@RestController
@AllArgsConstructor
@RequestMapping("/hotwork")
@Api(value = "动火作业", tags = "动火作业接口")
public class HotworkController extends BladeController {

	private IHotworkService hotworkService;

	@GetMapping("/detail")
	@ApiOperationSupport(order = 1)
	@ApiOperation(value = "详情", notes = "传入hotwork")
	public R<HotworkVO> detail(Hotwork hotwork) {
		Hotwork detail = hotworkService.getOne(Condition.getQueryWrapper(hotwork));
		return R.data(HotworkWrapper.build().entityVO(detail));
	}

	@GetMapping("/list")
	@ApiOperationSupport(order = 2)
	@ApiOperation(value = "分页列表", notes = "传入hotwork")
	public R<IPage<HotworkVO>> list(@ApiParam(value = "查询参数", hidden = true) @RequestParam Map<String, Object> hotwork, Query query) {
		IPage<Hotwork> pages = hotworkService.page(Condition.getPage(query), Condition.getQueryWrapper(hotwork, Hotwork.class));
		return R.data(HotworkWrapper.build().pageVO(pages));
	}

	@PostMapping("/save")
	@ApiOperationSupport(order = 3)
	@ApiOperation(value = "新增", notes = "传入hotwork")
	public R save(@Valid @RequestBody HotworkIVO hotworkIVO) {
		return R.status(hotworkService.save(HotworkWrapper.build().entity(hotworkIVO)));
	}

	@PostMapping("/update")
	@ApiOperationSupport(order = 4)
	@ApiOperation(value = "修改", notes = "传入hotwork")
	public R update(@Valid @RequestBody HotworkUVO hotworkUVO) {
		return R.status(hotworkService.updateById(HotworkWrapper.build().entity(hotworkUVO)));
	}

	@PostMapping("/remove")
	@ApiOperationSupport(order = 5)
	@ApiOperation(value = "逻辑删除", notes = "传入ids")
	public R remove(@ApiParam(value = "主键集合", required = true) @RequestParam String ids) {
		return R.status(hotworkService.deleteLogic(Func.toLongList(ids)));
	}

}