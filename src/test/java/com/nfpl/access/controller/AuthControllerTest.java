package com.nfpl.access.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nfpl.access.model.Models.LoginRequest;
import com.nfpl.access.service.SeedLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthControllerTest {
    @Autowired MockMvc mvc;
    @Autowired SeedLoader seed;
    @Autowired ObjectMapper objectMapper;

    @BeforeEach
    void freshData() {
        seed.load("sample");
    }

    @Test
    void loginCreatesSessionAndReturnsOnlyPublicUserFields() throws Exception {
        MockHttpSession session = (MockHttpSession) mvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new LoginRequest("asha", "nfpl-demo"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("asha"))
                .andExpect(jsonPath("$.apiKey").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andReturn().getRequest().getSession(false);

        mvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1));
    }

    @Test
    void protectedEndpointsRejectMissingSessionsAndUserIdSpoofing() throws Exception {
        mvc.perform(get("/api/jobs").header("X-User-Id", "4"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHENTICATED"));

        MockHttpSession session = (MockHttpSession) mvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new LoginRequest("asha", "nfpl-demo"))))
                .andReturn().getRequest().getSession(false);

        mvc.perform(get("/api/jobs").session(session).header("X-User-Id", "4"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("CALLER_MISMATCH"));
    }

    @Test
    void invalidCredentialsAreRejected() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new LoginRequest("asha", "wrong"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("INVALID_CREDENTIALS"));
    }
}
