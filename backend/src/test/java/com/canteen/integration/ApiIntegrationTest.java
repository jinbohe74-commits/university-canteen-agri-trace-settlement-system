package com.canteen.integration;

import com.canteen.common.UserContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 集成测试：MockMvc 走完整 Web 层（拦截器 + Controller + Service + 真实 DB）。
 * 修复后用例断言期望行为；上传产生的临时文件在 @AfterEach 清理。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ApiIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper om;

    private final List<File> createdFiles = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (File f : createdFiles) {
            if (f.exists()) {
                f.delete();
                File parent = f.getParentFile();
                if (parent != null && parent.exists()) {
                    parent.delete(); // 仅删除空目录
                }
            }
        }
        createdFiles.clear();
        UserContext.clear();
    }

    private String login(String username) throws Exception {
        String body = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"123456\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode r = om.readTree(body);
        assertEquals(200, r.path("code").asInt(), "登录应成功：" + body);
        String token = r.path("data").path("token").asText();
        assertFalse(token.isBlank());
        return token;
    }

    private JsonNode callGet(String url, String token) throws Exception {
        var builder = get(url);
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return om.readTree(mvc.perform(builder).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8));
    }

    private MockMultipartFile pngProbe() {
        byte[] png = new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4};
        return new MockMultipartFile("file", "probe.png", "image/png", png);
    }

    @Test
    @DisplayName("健康检查无需鉴权且返回 UP")
    void healthIsPublic() throws Exception {
        JsonNode r = callGet("/api/common/health", null);
        assertEquals(200, r.path("code").asInt());
        assertEquals("UP", r.path("data").path("status").asText());
    }

    @Test
    @DisplayName("[修复验证] 未登录请求返回真实 HTTP 401（网关/WAF 可识别）")
    void unauthenticatedReturnsHttp401() throws Exception {
        var response = mvc.perform(get("/api/admin/dashboard")).andReturn().getResponse();
        assertEquals(401, response.getStatus());
        JsonNode r = om.readTree(response.getContentAsString(StandardCharsets.UTF_8));
        assertEquals(401, r.path("code").asInt());
    }

    @Test
    @DisplayName("[修复验证] 农户 token 访问管理员看板被 403 拦截")
    void farmerTokenBlockedFromAdminDashboard() throws Exception {
        String token = login("farmer24");
        var response = mvc.perform(get("/api/admin/dashboard")
                .header("Authorization", "Bearer " + token)).andReturn().getResponse();
        assertEquals(403, response.getStatus());
        assertEquals(403, om.readTree(response.getContentAsString(StandardCharsets.UTF_8))
                .path("code").asInt());
    }

    @Test
    @DisplayName("[修复验证] 3 个非管理员角色 × 4 个管理员接口 = 12/12 返回 403")
    void nonAdminRolesBlockedFromAdminApi() throws Exception {
        for (String u : List.of("farmer24", "purchaser01", "inspector01")) {
            String token = login(u);
            for (String url : List.of("/api/admin/dashboard", "/api/admin/users",
                    "/api/admin/config", "/api/admin/settlements")) {
                assertEquals(403, mvc.perform(get(url).header("Authorization", "Bearer " + token))
                        .andReturn().getResponse().getStatus(), u + " -> " + url);
            }
        }
    }

    @Test
    @DisplayName("[修复验证] /api/auth/roles 不再返回演示账号")
    void rolesEndpointDoesNotLeakDemoAccounts() throws Exception {
        JsonNode r = callGet("/api/auth/roles", null);
        assertEquals(200, r.path("code").asInt());
        JsonNode data = r.path("data");
        assertTrue(data.isArray() && data.size() >= 4);
        for (JsonNode role : data) {
            assertTrue(role.path("demoAccount").asText().isBlank(), "demoAccount 不应明文返回");
        }
    }

    @Test
    @DisplayName("[修复验证] 无 token 上传被 401 拒绝")
    void unauthenticatedUploadBlocked() throws Exception {
        var response = mvc.perform(multipart("/api/files/upload").file(pngProbe())
                .param("type", "review_probe")).andReturn().getResponse();
        assertEquals(401, response.getStatus());
        assertEquals(401, om.readTree(response.getContentAsString(StandardCharsets.UTF_8))
                .path("code").asInt());
    }

    @Test
    @DisplayName("[修复验证] 已登录上传成功，路径穿越 type 被 400 拒绝")
    void authenticatedUploadAndTypeWhitelist() throws Exception {
        String token = login("admin");
        String body = mvc.perform(multipart("/api/files/upload").file(pngProbe())
                        .param("type", "review_probe")
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode r = om.readTree(body);
        assertEquals(200, r.path("code").asInt(), body);
        String url = r.path("data").path("url").asText();
        assertFalse(url.isBlank());
        File saved = new File("uploads", url.replace("/api/files/", ""));
        createdFiles.add(saved);
        assertTrue(saved.exists(), "文件应落在 uploads 目录内");

        String bad = mvc.perform(multipart("/api/files/upload").file(pngProbe())
                        .param("type", "../../review_escape")
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertEquals(400, om.readTree(bad).path("code").asInt(), "路径穿越必须被拒绝");
        assertFalse(new File("review_escape").exists(), "不应在应用根写出目录");
    }

    @Test
    @DisplayName("管理员看板返回真实业务数据")
    void adminDashboardReturnsRealData() throws Exception {
        String token = login("admin");
        JsonNode r = callGet("/api/admin/dashboard", token);
        assertEquals(200, r.path("code").asInt());
        assertTrue(r.path("data").size() > 0, "看板应包含统计字段");
    }

    @Test
    @DisplayName("订单分页契约：total>0、records 不超过 size")
    void ordersPaginationContract() throws Exception {
        String token = login("admin");
        JsonNode r = callGet("/api/admin/orders?page=1&size=10", token);
        assertEquals(200, r.path("code").asInt());
        JsonNode page = r.path("data").path("page");
        assertTrue(page.path("total").asLong() > 0, "演示库应有订单");
        assertTrue(page.path("records").size() <= 10);
        assertTrue(r.path("data").path("stats").path("total").asLong() > 0);
    }

    @Test
    @DisplayName("[修复验证] 空质检请求返回 400 校验错误而非 404/500")
    void emptyQualityRequestReturns400() throws Exception {
        String token = login("inspector01");
        String body = mvc.perform(post("/api/inspector/quality")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertEquals(400, om.readTree(body).path("code").asInt(), body);
    }
}
