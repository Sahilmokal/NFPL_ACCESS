package com.nfpl.access.controller;

import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletRequest;
import com.nfpl.access.security.Auth;
import com.nfpl.access.service.ExplainService;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class AccessController {
    private final Auth auth;
    private final ExplainService explain;

    public AccessController(Auth auth, ExplainService explain) {
        this.auth = auth;
        this.explain = explain;
    }

    /** May this person do this action on this job? Returns the answer and the step-by-step reasoning. */
    @GetMapping("/access/check")
    public Map<String, Object> check(HttpServletRequest request,
                                     @RequestHeader(value = "X-User-Id", required = false) Long uid,
                                     @RequestHeader(value = "X-Api-Key", required = false) String key,
                                     @RequestParam long userId, @RequestParam String action, @RequestParam long jobId) {
        return explain.check(auth.caller(request.getSession(false), uid, key), userId, action, jobId);
    }

    /** "Why can she do this and he can't?" */
    @GetMapping("/access/compare")
    public Map<String, Object> compare(HttpServletRequest request,
                                       @RequestHeader(value = "X-User-Id", required = false) Long uid,
                                       @RequestHeader(value = "X-Api-Key", required = false) String key,
                                       @RequestParam long userA, @RequestParam long userB,
                                       @RequestParam String action, @RequestParam long jobId) {
        return explain.compare(auth.caller(request.getSession(false), uid, key), userA, userB, action, jobId);
    }

    /** A person's access in plain words. */
    @GetMapping("/users/{id}/access")
    public Map<String, Object> access(HttpServletRequest request,
                                      @RequestHeader(value = "X-User-Id", required = false) Long uid,
                                      @RequestHeader(value = "X-Api-Key", required = false) String key,
                                      @PathVariable long id, @RequestParam(defaultValue = "false") boolean all) {
        return explain.describeAccess(auth.caller(request.getSession(false), uid, key), id, all);
    }

    /** Everyone's access in plain words (the README list is generated from this). */
    @GetMapping("/access/report")
    public List<String> report(HttpServletRequest request,
                               @RequestHeader(value = "X-User-Id", required = false) Long uid,
                               @RequestHeader(value = "X-Api-Key", required = false) String key) {
        return explain.report(auth.caller(request.getSession(false), uid, key));
    }
}
