package com.nfpl.access.controller;

import com.nfpl.access.model.Models.*;
import com.nfpl.access.security.Auth;
import com.nfpl.access.service.ApprovalService;
import com.nfpl.access.service.GrantService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletRequest;

import java.util.List;
import java.util.Map;

/** Managing who has what: grants, roles, user status, approvals. */
@RestController
@RequestMapping("/api")
public class AdminController {
    private final Auth auth;
    private final GrantService grants;
    private final ApprovalService approvals;

    public AdminController(Auth auth, GrantService grants, ApprovalService approvals) {
        this.auth = auth;
        this.grants = grants;
        this.approvals = approvals;
    }

    @PostMapping("/grants")
    public ResponseEntity<GrantOutcome> grant(HttpServletRequest request,
                                              @RequestHeader(value = "X-User-Id", required = false) Long uid,
                                              @RequestHeader(value = "X-Api-Key", required = false) String key,
                                              @RequestBody GrantRequest body) {
        GrantOutcome o = grants.grant(auth.caller(request.getSession(false), uid, key), body);
        return ResponseEntity.status("PENDING_APPROVAL".equals(o.status()) ? 202 : 201).body(o);
    }

    @PostMapping("/grants/{id}/revoke")
    public Map<String, Object> revoke(HttpServletRequest request,
                                      @RequestHeader(value = "X-User-Id", required = false) Long uid,
                                      @RequestHeader(value = "X-Api-Key", required = false) String key,
                                      @PathVariable long id, @RequestBody(required = false) Map<String, String> body) {
        return grants.revoke(auth.caller(request.getSession(false), uid, key), id, body == null ? null : body.get("reason"));
    }

    @GetMapping("/users/directory")
    public List<Map<String, Object>> users(HttpServletRequest request,
                                           @RequestHeader(value = "X-User-Id", required = false) Long uid,
                                           @RequestHeader(value = "X-Api-Key", required = false) String key) {
        return grants.listUsers(auth.caller(request.getSession(false), uid, key));
    }

    @GetMapping("/grants")
    public List<Map<String, Object>> grants(HttpServletRequest request,
                                            @RequestHeader(value = "X-User-Id", required = false) Long uid,
                                            @RequestHeader(value = "X-Api-Key", required = false) String key,
                                            @RequestParam long userId) {
        return grants.listGrants(auth.caller(request.getSession(false), uid, key), userId);
    }

    @GetMapping("/roles")
    public List<Map<String, Object>> roles(HttpServletRequest request,
                                           @RequestHeader(value = "X-User-Id", required = false) Long uid,
                                           @RequestHeader(value = "X-Api-Key", required = false) String key) {
        return grants.listRoles(auth.caller(request.getSession(false), uid, key));
    }

    @PostMapping("/roles")
    public Map<String, Object> createRole(HttpServletRequest request,
                                          @RequestHeader(value = "X-User-Id", required = false) Long uid,
                                          @RequestHeader(value = "X-Api-Key", required = false) String key,
                                          @RequestBody RoleRequest body) {
        return grants.createRole(auth.caller(request.getSession(false), uid, key), body);
    }

    @PutMapping("/roles/{id}/permissions")
    public Map<String, Object> setPermissions(HttpServletRequest request,
                                              @RequestHeader(value = "X-User-Id", required = false) Long uid,
                                              @RequestHeader(value = "X-Api-Key", required = false) String key,
                                              @PathVariable long id, @RequestBody List<String> permissions) {
        return grants.setRolePermissions(auth.caller(request.getSession(false), uid, key), id, permissions);
    }

    @PutMapping("/users/{id}/status")
    public Map<String, Object> status(HttpServletRequest request,
                                      @RequestHeader(value = "X-User-Id", required = false) Long uid,
                                      @RequestHeader(value = "X-Api-Key", required = false) String key,
                                      @PathVariable long id, @RequestBody Map<String, String> body) {
        return grants.changeStatus(auth.caller(request.getSession(false), uid, key), id, body.get("status"), body.get("reason"));
    }

    @GetMapping("/approvals")
    public List<Map<String, Object>> pending(HttpServletRequest request,
                                             @RequestHeader(value = "X-User-Id", required = false) Long uid,
                                             @RequestHeader(value = "X-Api-Key", required = false) String key) {
        return approvals.pending(auth.caller(request.getSession(false), uid, key));
    }

    @PostMapping("/approvals/{id}/approve")
    public Map<String, Object> approve(HttpServletRequest request,
                                       @RequestHeader(value = "X-User-Id", required = false) Long uid,
                                       @RequestHeader(value = "X-Api-Key", required = false) String key, @PathVariable long id) {
        return approvals.approve(auth.caller(request.getSession(false), uid, key), id);
    }

    @PostMapping("/approvals/{id}/reject")
    public Map<String, Object> reject(HttpServletRequest request,
                                      @RequestHeader(value = "X-User-Id", required = false) Long uid,
                                      @RequestHeader(value = "X-Api-Key", required = false) String key, @PathVariable long id,
                                      @RequestBody(required = false) Map<String, String> body) {
        return approvals.reject(auth.caller(request.getSession(false), uid, key), id, body == null ? null : body.get("note"));
    }
}
