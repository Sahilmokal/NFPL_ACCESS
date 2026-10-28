package com.nfpl.access.security;

import com.nfpl.access.model.Models.AuthUser;
import com.nfpl.access.model.Models.User;
import com.nfpl.access.exception.ApiException;
import com.nfpl.access.repository.Repo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthUnitTest {
    private Repo repo;
    private Auth auth;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @BeforeEach
    void setUp() {
        repo = mock(Repo.class);
        auth = new Auth(repo, passwordEncoder);
    }

    @Test
    void loginAuthenticatesAgainstTheStoredPasswordHashAndCreatesASession() {
        User asha = new User(1, "asha", "Asha Menon", "STAFF", "ACTIVE", null, null);
        when(repo.userByUsername("asha")).thenReturn(Optional.of(asha));
        when(repo.passwordHash(1)).thenReturn(Optional.of(passwordEncoder.encode("nfpl-demo")));
        MockHttpServletRequest request = new MockHttpServletRequest();

        AuthUser result = auth.login(request, " asha ", "nfpl-demo");

        assertEquals(1, result.id());
        assertEquals("asha", result.username());
        assertNotNull(request.getSession(false));
        assertThrows(NoSuchMethodException.class, () -> result.getClass().getMethod("apiKey"));
    }

    @Test
    void aSessionCannotClaimAnotherHumanIdentity() {
        User asha = new User(1, "asha", "Asha Menon", "STAFF", "ACTIVE", null, null);
        when(repo.userByUsername("asha")).thenReturn(Optional.of(asha));
        when(repo.passwordHash(1)).thenReturn(Optional.of(passwordEncoder.encode("nfpl-demo")));
        MockHttpServletRequest request = new MockHttpServletRequest();
        auth.login(request, "asha", "nfpl-demo");

        ApiException error = assertThrows(ApiException.class, () -> auth.caller(request.getSession(false), 4L, null));

        assertEquals(403, error.status);
        assertEquals("CALLER_MISMATCH", error.code);
    }

    @Test
    void systemCallersStillRequireTheirApiKey() {
        User parser = new User(10, "overnight-parser", "Overnight Parser", "SYSTEM", "ACTIVE", null, "parser-key");
        when(repo.user(10)).thenReturn(Optional.of(parser));

        assertEquals(10, auth.caller(null, 10L, "parser-key"));
        ApiException error = assertThrows(ApiException.class, () -> auth.caller(null, 10L, "wrong"));
        assertEquals("BAD_API_KEY", error.code);
    }

    @Test
    void missingHumanSessionIsRejectedEvenWithAUserIdHeader() {
        ApiException error = assertThrows(ApiException.class, () -> auth.caller(null, 1L, null));

        assertEquals(401, error.status);
        assertEquals("UNAUTHENTICATED", error.code);
    }
}
