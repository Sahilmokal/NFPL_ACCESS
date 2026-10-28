package com.nfpl.access.controller;

import com.nfpl.access.model.Models.AuthUser;
import com.nfpl.access.model.Models.LoginRequest;
import com.nfpl.access.security.Auth;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final Auth auth;

    public AuthController(Auth auth) {
        this.auth = auth;
    }

    @PostMapping("/login")
    public AuthUser login(@RequestBody LoginRequest body, HttpServletRequest request) {
        return auth.login(request, body.username(), body.password());
    }

    @GetMapping("/me")
    public AuthUser current(HttpServletRequest request) {
        return auth.current(request.getSession(false));
    }

    @PostMapping("/logout")
    public void logout(HttpServletRequest request) {
        auth.logout(request.getSession(false));
    }
}
