package com.maou.apptemplateapi.module.ai.enums;

/**
 * AI 调用降级级别，按严重程度递增。
 */
public enum DegradeLevel {

    /** 正常调用：完整提示词 + 图片 + 知识片段。 */
    NORMAL,
    /** 轻度降级：超出提示词预算时先舍弃图片输入，保留知识片段与规则候选。 */
    DROP_IMAGES,
    /** 中度降级：压缩知识片段条数，只保留最相关的前 N 条。 */
    MINIMAL_CONTEXT,
    /** 重度降级：不调用模型，直接使用规则评分结果兜底。 */
    RULE_ONLY;

    public boolean isNormal() {
        return this == NORMAL;
    }

    public boolean isRuleOnly() {
        return this == RULE_ONLY;
    }

    public DegradeLevel max(DegradeLevel other) {
        return other == null || other.ordinal() <= this.ordinal() ? this : other;
    }
}
