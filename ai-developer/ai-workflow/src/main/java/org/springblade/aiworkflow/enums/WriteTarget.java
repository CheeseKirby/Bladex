package org.springblade.aiworkflow.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 写入目标 — 阶段2 控制生成产物落盘位置。
 *
 * <ul>
 *   <li>{@link #ISOLATED} — 落隔离区 outputRoot(ai-generated-modules)。安全,默认值。</li>
 *   <li>{@link #REAL} — 落 target-project-root(默认亦为 ai-generated-modules),需鉴权,
 *       且生成前查重,类名/表名冲突即拒绝,绝不覆盖现有代码。</li>
 * </ul>
 * <p>注:target-project-root 默认隔离区,故 REAL 现亦落隔离区,仅多一道查重;
 * 如需写真实项目,通过 TARGET_PROJECT_ROOT 指定。
 */
@Getter
@AllArgsConstructor
public enum WriteTarget {

    ISOLATED("ISOLATED", "隔离区(ai-generated-modules)"),
    REAL("REAL", "目标项目根(查重写入)");

    private final String code;
    private final String desc;

    /**
     * 安全解析:code 匹配则返回对应枚举;空或非法值统一返回 {@link #ISOLATED}(安全默认)。
     *
     * @param code 写入目标代码(ISOLATED/REAL),可为空
     * @return 对应枚举,空/非法返回 ISOLATED
     */
    public static WriteTarget parse(String code) {
        if (code == null || code.isBlank()) return ISOLATED;
        for (WriteTarget t : values()) {
            if (t.code.equalsIgnoreCase(code.trim())) return t;
        }
        return ISOLATED;
    }

    /** 是否落真实项目(查重 + 鉴权判断用) */
    public boolean isReal() {
        return this == REAL;
    }
}
