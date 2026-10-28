package com.nfpl.access.security;

import com.nfpl.access.model.Models.AuthUser;
import com.nfpl.access.model.Models.User;
import com.nfpl.access.exception.ApiException;
import com.nfpl.access.repository.Repo;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class Auth {
    private static final String SESSION_USER_ID = Auth.class.getName() + ".userId";

    private final Repo repo;
    private final PasswordEncoder passwordEncoder;

    public Auth(Repo repo, PasswordEncoder passwordEncoder) {
        this.repo = repo;
        this.passwordEncoder = passwordEncoder;
    }

    public AuthUser login(HttpServletRequest request, String username, String password) {
        User user = username == null ? null : repo.userByUsername(username.trim()).orElse(null);
        String hash = user == null ? null : repo.passwordHash(user.id()).orElse(null);
        if (user == null || hash == null || password == null || !passwordEncoder.matches(password, hash)
                || "SYSTEM".equals(user.userType())) {
            throw new ApiException(401, "INVALID_CREDENTIALS", "Username or password is incorrect");
        }
        if ("DISABLED".equals(user.status())) {
            throw new ApiException(401, "USER_DISABLED", "This user is disabled");
        }

        HttpSession previous = request.getSession(false);
        if (previous != null) previous.invalidate();
        HttpSession session = request.getSession(true);
        session.setAttribute(SESSION_USER_ID, user.id());
        return publicUser(user);
    }

    public AuthUser current(HttpSession session) {
        long userId = sessionUserId(session);
        User user = repo.user(userId).orElseThrow(() -> new ApiException(401, "UNKNOWN_CALLER", "No such user"));
        if ("DISABLED".equals(user.status())) {
            session.invalidate();
            throw new ApiException(401, "USER_DISABLED", "This user is disabled");
        }
        return publicUser(user);
    }

    public void logout(HttpSession session) {
        if (session != null) session.invalidate();
    }

    public long caller(HttpSession session, Long requestedUserId, String apiKey) {
        if (requestedUserId != null) {
            User requested = repo.user(requestedUserId).orElse(null);
            if (requested != null && "SYSTEM".equals(requested.userType())) {
                if ("DISABLED".equals(requested.status())) {
                    throw new ApiException(401, "USER_DISABLED", "This user is disabled");
                }
                if (apiKey == null || !apiKey.equals(requested.apiKey())) {
                    throw new ApiException(401, "BAD_API_KEY", "System users must send their X-Api-Key");
                }
                return requested.id();
            }
        }

        long authenticatedUserId = sessionUserId(session);
        if (requestedUserId != null && requestedUserId != authenticatedUserId) {
            throw new ApiException(403, "CALLER_MISMATCH", "X-User-Id cannot differ from the signed-in user");
        }
        User user = repo.user(authenticatedUserId)
                .orElseThrow(() -> new ApiException(401, "UNKNOWN_CALLER", "No such user"));
        if ("DISABLED".equals(user.status())) {
            session.invalidate();
            throw new ApiException(401, "USER_DISABLED", "This user is disabled");
        }
        return user.id();
    }

    private static long sessionUserId(HttpSession session) {
        if (session == null) throw new ApiException(401, "UNAUTHENTICATED", "Sign in to continue");
        Object value = session.getAttribute(SESSION_USER_ID);
        if (!(value instanceof Long userId)) {
            throw new ApiException(401, "UNAUTHENTICATED", "Sign in to continue");
        }
        return userId;
    }

    private static AuthUser publicUser(User user) {
        return new AuthUser(user.id(), user.username(), user.fullName(), user.userType(), user.status(), user.clientId());
    }
}
