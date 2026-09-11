package com.maou.apptemplateapi.module.ai.service;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 轻量 token 估算器。
 *
 * <p>调用前无法拿到真实 token 数，因此按字符类型估算：
 * 中日韩字符按 1 字符 ≈ 1 token，其余字符（英文、数字、符号）按 4 字符 ≈ 1 token。
 * 该估算偏保守（偏大），用于「是否超预算、是否需要降级」的决策足够安全；
 * 真实用量在模型返回 tokenUsage 时以接口返回值为准。
 */
@Service
public class AiTokenEstimator {

    private static final long DEFAULT_IMAGE_TOKENS = 800L;

    public long estimate(String text) {
        if (!StringUtils.hasText(text)) {
            return 0;
        }
        long cjk = 0;
        long other = 0;
        for (int i = 0; i < text.length(); i++) {
            if (isCjk(text.charAt(i))) {
                cjk++;
            } else {
                other++;
            }
        }
        return cjk + (long) Math.ceil(other / 4.0);
    }

    public long estimateImages(int imageCount) {
        return Math.max(imageCount, 0) * DEFAULT_IMAGE_TOKENS;
    }

    private boolean isCjk(char value) {
        return (value >= 0x4E00 && value <= 0x9FFF)
                || (value >= 0x3400 && value <= 0x4DBF)
                || (value >= 0x3000 && value <= 0x303F)
                || (value >= 0xFF00 && value <= 0xFFEF);
    }
}
