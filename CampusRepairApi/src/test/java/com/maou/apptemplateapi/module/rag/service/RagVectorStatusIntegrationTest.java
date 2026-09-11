package com.maou.apptemplateapi.module.rag.service;

import com.maou.apptemplateapi.common.enums.UserRole;
import com.maou.apptemplateapi.common.security.CurrentUser;
import com.maou.apptemplateapi.common.security.JwtTokenService;
import com.maou.apptemplateapi.common.security.LoginType;
import com.maou.apptemplateapi.module.rag.dto.RagVectorStatusResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 第三阶段 V3：向量检索真实状态核对接口。
 */
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class RagVectorStatusIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenService jwtTokenService;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void shouldExposeVectorStatusForAdmin() throws Exception {
        String body = mockMvc.perform(get("/api/v1/admin/rag/vector-status")
                        .header("Authorization", bearer(token(10003L, "admin01", UserRole.ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.embeddingProvider").isNotEmpty())
                .andExpect(jsonPath("$.data.dimension").isNumber())
                .andExpect(jsonPath("$.data.collectionName").isNotEmpty())
                .andExpect(jsonPath("$.data.rrfK").isNumber())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        RagVectorStatusResponse status = objectMapper.treeToValue(objectMapper.readTree(body).path("data"), RagVectorStatusResponse.class);
        assertThat(status.dimension()).isGreaterThan(0);
        assertThat(status.message()).isNotEmpty();
        assertThat(status.chunkCount()).isGreaterThanOrEqualTo(0);
        assertThat(status.syncedChunkCount() + status.pendingChunkCount() + status.failedChunkCount())
                .isLessThanOrEqualTo(status.chunkCount());
    }

    @Test
    void shouldRejectNonAdmin() throws Exception {
        mockMvc.perform(get("/api/v1/admin/rag/vector-status")
                        .header("Authorization", bearer(token(10005L, "worker02", UserRole.WORKER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403));
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
