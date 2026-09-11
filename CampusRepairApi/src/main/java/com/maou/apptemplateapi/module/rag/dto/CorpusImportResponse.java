package com.maou.apptemplateapi.module.rag.dto;

import java.util.List;

public record CorpusImportResponse(
        Long documentId,
        String title,
        String standardNo,
        int sectionCount,
        int chunkCount,
        String importBatch,
        List<String> sections
) {
}
