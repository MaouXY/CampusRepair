package com.maou.apptemplateapi.module.rag.dto;

import java.util.List;

public record CorpusBatchImportRequest(
        String batchName,
        List<CorpusSectionItem> documents
) {
}
