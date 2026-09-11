package com.maou.apptemplateapi.module.rag.dto;

import java.util.List;

public record CorpusPreviewResponse(
        String title,
        int sectionCount,
        int chunkCount,
        int totalChars,
        int avgChunkChars,
        List<CorpusSectionPreview> sections
) {

    public record CorpusSectionPreview(
            String sectionTitle,
            int sectionLevel,
            int charCount,
            String sample
    ) {
    }
}
