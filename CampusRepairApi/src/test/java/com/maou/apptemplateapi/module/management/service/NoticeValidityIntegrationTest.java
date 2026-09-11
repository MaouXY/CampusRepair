package com.maou.apptemplateapi.module.management.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maou.apptemplateapi.common.enums.UserRole;
import com.maou.apptemplateapi.common.security.CurrentUser;
import com.maou.apptemplateapi.common.security.JwtTokenService;
import com.maou.apptemplateapi.common.security.LoginType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 公告有效期：未生效/已过期不下发学生端，管理端始终可见并返回状态，过期时间早于生效时间被拒绝。
 */
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class NoticeValidityIntegrationTest {

    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenService jwtTokenService;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void shouldOnlyDeliverNoticesInsideValidityWindow() throws Exception {
        String active = createNotice("有效期内的公告", LocalDateTime.now().minusHours(1), LocalDateTime.now().plusDays(1));
        createNotice("尚未生效的公告", LocalDateTime.now().plusDays(3), LocalDateTime.now().plusDays(10));
        createNotice("已过期的公告", LocalDateTime.now().minusDays(10), LocalDateTime.now().minusDays(1));
        createNotice("不限有效期的公告", null, null);

        List<String> visible = publishedNoticeTitles();

        assertThat(visible).contains("有效期内的公告", "不限有效期的公告");
        assertThat(visible).doesNotContain("尚未生效的公告", "已过期的公告");
        assertThat(active).isNotEmpty();
    }

    @Test
    void shouldExposeStatusForAdminList() throws Exception {
        createNotice("尚未生效的公告", LocalDateTime.now().plusDays(3), LocalDateTime.now().plusDays(10));
        createNotice("已过期的公告", LocalDateTime.now().minusDays(10), LocalDateTime.now().minusDays(1));
        createNotice("有效中的公告", LocalDateTime.now().minusHours(1), LocalDateTime.now().plusDays(1));

        String body = mockMvc.perform(get("/api/v1/admin/notices")
                        .header("Authorization", bearer(adminToken()))
                        .param("page", "1")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        JsonNode items = objectMapper.readTree(body).path("data").path("items");
        List<String> statuses = new ArrayList<>();
        for (JsonNode item : items) {
            statuses.add(item.path("title").asText() + "=" + item.path("status").asText());
        }
        assertThat(statuses).contains("尚未生效的公告=NOT_STARTED", "已过期的公告=EXPIRED", "有效中的公告=ACTIVE");
    }

    @Test
    void shouldRejectExpireBeforeEffective() throws Exception {
        LocalDateTime effective = LocalDateTime.now().plusDays(2);
        LocalDateTime expire = LocalDateTime.now().plusDays(1);

        mockMvc.perform(post("/api/v1/admin/notices")
                        .header("Authorization", bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(noticePayload("时间倒置的公告", effective, expire)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(9702));
    }

    private String createNotice(String title, LocalDateTime effectiveAt, LocalDateTime expireAt) throws Exception {
        String body = mockMvc.perform(post("/api/v1/admin/notices")
                        .header("Authorization", bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(noticePayload(title, effectiveAt, expireAt)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(body).path("data").path("id").asText();
    }

    private String noticePayload(String title, LocalDateTime effectiveAt, LocalDateTime expireAt) throws Exception {
        java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("title", title);
        payload.put("content", "公告正文：" + title);
        payload.put("targetRole", "ALL");
        payload.put("published", 1);
        payload.put("sortOrder", 10);
        payload.put("effectiveAt", effectiveAt == null ? null : effectiveAt.format(FORMAT));
        payload.put("expireAt", expireAt == null ? null : expireAt.format(FORMAT));
        return objectMapper.writeValueAsString(payload);
    }

    private List<String> publishedNoticeTitles() throws Exception {
        String body = mockMvc.perform(get("/api/v1/notices")
                        .header("Authorization", bearer(token(10001L, "student01", UserRole.STUDENT))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        List<String> titles = new ArrayList<>();
        for (JsonNode item : objectMapper.readTree(body).path("data")) {
            titles.add(item.path("title").asText());
        }
        return titles;
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
