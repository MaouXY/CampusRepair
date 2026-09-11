package com.maou.apptemplateapi.module.rag.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maou.apptemplateapi.common.enums.UserRole;
import com.maou.apptemplateapi.common.security.CurrentUser;
import com.maou.apptemplateapi.common.security.JwtTokenService;
import com.maou.apptemplateapi.common.security.LoginType;
import com.maou.apptemplateapi.module.rag.dto.CorpusImportRequest;
import com.maou.apptemplateapi.module.rag.dto.CorpusImportResponse;
import com.maou.apptemplateapi.module.rag.dto.CorpusPreviewResponse;
import com.maou.apptemplateapi.module.rag.entity.RagKnowledgeChunk;
import com.maou.apptemplateapi.module.rag.entity.RagKnowledgeDocument;
import com.maou.apptemplateapi.module.rag.mapper.RagKnowledgeChunkMapper;
import com.maou.apptemplateapi.module.rag.mapper.RagKnowledgeDocumentMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 语料导入：预览（不落库）、单篇导入（含元数据与章节切片）、批量导入（与转换脚本 JSONL 对齐）。
 */
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class RagCorpusImportServiceIntegrationTest {

    private static final String SAMPLE_CORPUS = """
            校园设备维保规范
            第一章 总则
            1
            第一条 为规范校园设备维护工作，明确各部门职责分工与响应时限，特制定本规范。
            校园设备维保规范
            第二章 空调设备维护
            - 2 -
            2.1 滤网清洗
            断电后取下滤网，用清水冲洗并晾干后装回，建议每季度执行一次。
            2.2 冷媒检查
            使用压力表检测冷媒压力，低于标准值时联系专业单位补充。
            校园设备维保规范
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenService jwtTokenService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RagKnowledgeDocumentMapper documentMapper;

    @Autowired
    private RagKnowledgeChunkMapper chunkMapper;

    @Test
    void shouldPreviewSectionsWithoutPersisting() throws Exception {
        long before = documentMapper.selectCount(null);

        String body = mockMvc.perform(post("/api/v1/admin/rag/corpus/preview")
                        .header("Authorization", bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CorpusImportRequest("校园设备维保规范", 20001L,
                                "示例规范", "GB-TEST-2026", "2026", null, "standard", SAMPLE_CORPUS, 800, 120, 1, null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.sectionCount").isNumber())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        CorpusPreviewResponse preview = objectMapper.treeToValue(objectMapper.readTree(body).path("data"), CorpusPreviewResponse.class);
        assertThat(preview.sectionCount()).isGreaterThanOrEqualTo(3);
        assertThat(preview.chunkCount()).isGreaterThanOrEqualTo(3);
        assertThat(preview.totalChars()).isGreaterThan(0);
        assertThat(preview.sections()).isNotEmpty();
        assertThat(preview.sections().get(0).sectionTitle()).as("子章节标题会带上上级章名").startsWith("第一章 总则");
        assertThat(documentMapper.selectCount(null)).as("预览不应落库").isEqualTo(before);
    }

    @Test
    void shouldImportDocumentWithMetadataAndSections() throws Exception {
        String body = mockMvc.perform(post("/api/v1/admin/rag/corpus/import")
                        .header("Authorization", bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CorpusImportRequest("校园设备维保规范", 20001L,
                                "示例规范", "GB-TEST-2026", "2026", null, "standard", SAMPLE_CORPUS, 800, 120, 1, "batch-test"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        CorpusImportResponse response = objectMapper.treeToValue(objectMapper.readTree(body).path("data"), CorpusImportResponse.class);
        assertThat(response.documentId()).isNotNull();
        assertThat(response.sectionCount()).isGreaterThanOrEqualTo(3);
        assertThat(response.importBatch()).isEqualTo("batch-test");

        RagKnowledgeDocument document = documentMapper.selectById(response.documentId());
        assertThat(document.getSource()).isEqualTo("示例规范");
        assertThat(document.getStandardNo()).isEqualTo("GB-TEST-2026");
        assertThat(document.getDocType()).isEqualTo("standard");
        assertThat(document.getImportBatch()).isEqualTo("batch-test");
        assertThat(document.getEnabled()).isEqualTo(1);

        List<RagKnowledgeChunk> chunks = chunkMapper.selectList(new LambdaQueryWrapper<RagKnowledgeChunk>()
                .eq(RagKnowledgeChunk::getDocumentId, response.documentId())
                .orderByAsc(RagKnowledgeChunk::getChunkIndex));
        assertThat(chunks).isNotEmpty();
        assertThat(chunks).extracting(RagKnowledgeChunk::getSectionTitle).doesNotContainNull();
        assertThat(chunks.get(0).getContent()).startsWith("【章节】");
        assertThat(chunks).anyMatch(chunk -> chunk.getContent().contains("滤网"));
    }

    @Test
    void shouldImportBatchItemsAlignedWithConverterScript() throws Exception {
        String payload = """
                {
                  "batchName": "batch-from-script",
                  "documents": [
                    {"title": "既有建筑维护与改造通用规范", "source": "GB 55022-2021", "standardNo": "GB 55022-2021",
                     "docVersion": "2021", "effectiveDate": "2021-04-09", "docType": "standard", "categoryId": 20001,
                     "sectionTitle": "3.4 设施设备检查", "sectionLevel": 2,
                     "content": "【章节】3.4 设施设备检查\\n设施设备应定期检查，发现隐患应及时处理并记录。"},
                    {"title": "既有建筑维护与改造通用规范", "source": "GB 55022-2021", "standardNo": "GB 55022-2021",
                     "docVersion": "2021", "effectiveDate": "2021-04-09", "docType": "standard", "categoryId": 20001,
                     "sectionTitle": "5.1 空调通风设备", "sectionLevel": 2,
                     "content": "【章节】5.1 空调通风设备\\n空调通风设备应按周期清洗滤网与换热器，保证送风质量。"}
                  ]
                }
                """;

        String body = mockMvc.perform(post("/api/v1/admin/rag/corpus/import-batch")
                        .header("Authorization", bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        CorpusImportResponse response = objectMapper.treeToValue(
                objectMapper.readTree(body).path("data").get(0), CorpusImportResponse.class);
        assertThat(response.standardNo()).isEqualTo("GB 55022-2021");
        assertThat(response.sectionCount()).isEqualTo(2);
        assertThat(response.chunkCount()).isEqualTo(2);
        assertThat(response.importBatch()).isEqualTo("batch-from-script");
        assertThat(response.sections()).containsExactly("3.4 设施设备检查", "5.1 空调通风设备");

        RagKnowledgeDocument document = documentMapper.selectById(response.documentId());
        assertThat(document.getTitle()).isEqualTo("既有建筑维护与改造通用规范");
        assertThat(document.getStandardNo()).isEqualTo("GB 55022-2021");
    }

    @Test
    void shouldRejectBlankContentAndNonAdmin() throws Exception {
        mockMvc.perform(post("/api/v1/admin/rag/corpus/import")
                        .header("Authorization", bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"空语料\",\"content\":\"   \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(31004));

        mockMvc.perform(post("/api/v1/admin/rag/corpus/import")
                        .header("Authorization", bearer(token(10001L, "student01", UserRole.STUDENT)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CorpusImportRequest("x", null, null, null,
                                null, null, null, SAMPLE_CORPUS, null, null, 1, null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403));
    }

    private String adminToken() {
        return token(10003L, "admin01", UserRole.ADMIN);
    }

    private String token(Long userId, String subject, UserRole role) {
        return jwtTokenService.generateToken(CurrentUser.builder()
                .principalId(userId)
                .subject(subject)
                .loginType(LoginType.PASSWORD)
                .roleCode(role.name())
                .organizationId(null)
                .build());
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
