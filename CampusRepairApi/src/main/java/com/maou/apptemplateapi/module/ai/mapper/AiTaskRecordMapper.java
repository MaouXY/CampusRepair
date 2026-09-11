package com.maou.apptemplateapi.module.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maou.apptemplateapi.module.ai.entity.AiTaskRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;

@Mapper
public interface AiTaskRecordMapper extends BaseMapper<AiTaskRecord> {

    @Select("SELECT COALESCE(SUM(COALESCE(input_tokens, 0) + COALESCE(output_tokens, 0)), 0) FROM ai_task_record "
            + "WHERE deleted = 0 AND created_at >= #{since}")
    long sumTotalTokensSince(@Param("since") LocalDateTime since);

    @Select("SELECT COUNT(1) FROM ai_task_record WHERE deleted = 0 AND created_at >= #{since}")
    long countSince(@Param("since") LocalDateTime since);

    @Select("SELECT COUNT(1) FROM ai_task_record WHERE deleted = 0 AND created_at >= #{since} "
            + "AND degrade_level IS NOT NULL AND degrade_level <> 'NORMAL'")
    long countDegradedSince(@Param("since") LocalDateTime since);
}
