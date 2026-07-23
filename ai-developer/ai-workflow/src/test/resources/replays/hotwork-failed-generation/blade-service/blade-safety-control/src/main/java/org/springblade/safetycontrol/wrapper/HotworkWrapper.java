package org.springblade.safetycontrol.wrapper;

import org.springblade.core.mp.support.BaseEntityWrapper;
import org.springblade.core.tool.utils.BeanUtil;
import org.springblade.safetycontrol.entity.Hotwork;
import org.springblade.safetycontrol.vo.HotworkVO;
import org.springblade.safetycontrol.vo.HotworkIVO;
import org.springblade.safetycontrol.vo.HotworkUVO;

import java.util.Objects;

public class HotworkWrapper extends BaseEntityWrapper<Hotwork, HotworkVO> {

    public static HotworkWrapper build() {
        return new HotworkWrapper();
    }

    @Override
    public HotworkVO entityVO(Hotwork entity) {
        if (entity == null) {
            return new HotworkVO();
        }
        return Objects.requireNonNull(BeanUtil.copy(entity, HotworkVO.class));
    }

    public Hotwork entity(HotworkIVO ivo) {
        if (ivo == null) {
            return new Hotwork();
        }
        return Objects.requireNonNull(BeanUtil.copy(ivo, Hotwork.class));
    }

    public Hotwork entity(HotworkUVO uvo) {
        if (uvo == null) {
            return new Hotwork();
        }
        return Objects.requireNonNull(BeanUtil.copy(uvo, Hotwork.class));
    }
}