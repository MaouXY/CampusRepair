package com.maou.apptemplateapi.module.rag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maou.apptemplateapi.module.rag.entity.RagKnowledgeChunk;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Delete;

@Mapper
public interface RagKnowledgeChunkMapper extends BaseMapper<RagKnowledgeChunk> {

    @Delete("DELETE FROM rag_knowledge_chunk WHERE document_id = #{documentId}")
    int physicalDeleteByDocumentId(@Param("documentId") Long documentId);
}
