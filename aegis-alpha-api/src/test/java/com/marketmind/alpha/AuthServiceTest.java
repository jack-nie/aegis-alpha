package com.marketmind.alpha;

import com.marketmind.alpha.domain.User;
import com.marketmind.alpha.mapper.UserMapper;
import com.marketmind.alpha.service.AuthService;
import com.marketmind.alpha.service.LangChainGateway;
import com.marketmind.alpha.service.TokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthServiceTest {
    @Autowired
    private AuthService authService;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private TokenService tokenService;
    @Autowired
    private MockMvc mockMvc;
    @MockBean
    private LangChainGateway langChainGateway;

    @BeforeEach
    void seedUser() {
        if (userMapper.count() == 0) {
            User user = new User();
            user.setUserId("u-1");
            user.setUsername("guanghui.nie");
            user.setPasswordHash(AuthService.hash("guanghui.nie"));
            user.setTenantId("tenant-1");
            user.setRoles("portfolio_manager");
            userMapper.insert(user);
        }
    }

    @Test
    void loginIssuesBearerTokenForImportedUser() {
        Map<String, Object> response = authService.login("guanghui.nie", "guanghui.nie");

        assertThat(response.get("token_type")).isEqualTo("bearer");
        assertThat(response.get("access_token")).asString().contains(".");
        assertThat(response.get("tenant_id")).isEqualTo("tenant-1");
        Map<String, Object> tokenPayload = tokenService.verify(String.valueOf(response.get("access_token")));
        assertThat((Iterable<?>) tokenPayload.get("roles")).contains("portfolio_manager");
    }

    @Test
    void loginRejectsWrongPassword() {
        assertThatThrownBy(() -> authService.login("guanghui.nie", "bad"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void profileEndpointDoesNotExposePasswordHashAndKeepsRoles() throws Exception {
        String auth = loginToken();

        mockMvc.perform(get("/_backend/profile").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("guanghui.nie"))
                .andExpect(jsonPath("$.tenant_id").value("tenant-1"))
                .andExpect(jsonPath("$.roles", hasItem("portfolio_manager")))
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.password_hash").doesNotExist());
    }

    @Test
    void meEndpointIncludesRolesFromAuthenticatedPrincipal() throws Exception {
        String auth = loginToken();

        mockMvc.perform(get("/_backend/auth/me").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("guanghui.nie"))
                .andExpect(jsonPath("$.roles", hasItem("portfolio_manager")));
    }

    @Test
    void auditEventsEndpointReturnsLoginEventsForAuthenticatedUser() throws Exception {
        String auth = loginToken();

        mockMvc.perform(get("/_backend/admin/audit-events").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].eventId", notNullValue()))
                .andExpect(jsonPath("$[0].tenantId").value("tenant-1"))
                .andExpect(jsonPath("$[0].userId").value("u-1"))
                .andExpect(jsonPath("$[0].action").value("auth.login.success"));
    }

    private String loginToken() throws Exception {
        String login = mockMvc.perform(post("/_backend/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"guanghui.nie\",\"password\":\"guanghui.nie\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token", notNullValue()))
                .andReturn().getResponse().getContentAsString();
        String token = login.replaceAll(".*\"access_token\":\"([^\"]+)\".*", "$1");
        return "Bearer " + token;
    }
}
