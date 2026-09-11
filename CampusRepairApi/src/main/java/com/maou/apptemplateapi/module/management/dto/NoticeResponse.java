package com.maou.apptemplateapi.module.management.dto;

import java.time.LocalDateTime;

/**
 * 公告响应。status 由服务端按「是否发布 + 生效时间 + 过期时间」实时计算：
 * DISABLED 已下架 / NOT_STARTED 未生效 / ACTIVE 有效中 / EXPIRED 已过期。
 */
public record NoticeResponse(
        Long id,
        String title,
        String content,
        String targetRole,
        Integer published,
        Integer sortOrder,
        LocalDateTime effectiveAt,
        LocalDateTime expireAt,
        String status,
        Long createdBy,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
