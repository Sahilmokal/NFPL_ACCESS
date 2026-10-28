package com.nfpl.access.controller;

import com.nfpl.access.model.Models.*;
import com.nfpl.access.exception.ApiException;
import com.nfpl.access.security.Auth;
import com.nfpl.access.service.AccessEvaluator;
import com.nfpl.access.service.ExplainService;
import com.nfpl.access.service.JobService;
import com.nfpl.access.service.Permission;
import com.nfpl.access.service.QueueService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletRequest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class JobController {
    private final Auth auth;
    private final AccessEvaluator eval;
    private final JobService jobs;
    private final QueueService queues;
    private final ExplainService explain;

    public JobController(Auth auth, AccessEvaluator eval, JobService jobs, QueueService queues, ExplainService explain) {
        this.auth = auth;
        this.eval = eval;
        this.jobs = jobs;
        this.queues = queues;
        this.explain = explain;
    }

    private static ResponseEntity<ActionOutcome> respond(ActionOutcome o) {
        return ResponseEntity.status("PENDING_APPROVAL".equals(o.status()) ? 202 : 200).body(o);
    }

    /** Create a job, or update an unprocessed one. Idempotent by job number: this is what the overnight parser calls. */
    @PutMapping("/jobs/{jobNo}")
    public ResponseEntity<ActionOutcome> upsert(HttpServletRequest request,
                                                @RequestHeader(value = "X-User-Id", required = false) Long uid,
                                                @RequestHeader(value = "X-Api-Key", required = false) String key,
                                                @PathVariable String jobNo, @RequestBody JobSpec spec) {
        return respond(jobs.upsert(auth.caller(request.getSession(false), uid, key), jobNo, spec));
    }

    @PostMapping("/jobs/{id}/actions/{action}")
    public ResponseEntity<ActionOutcome> act(HttpServletRequest request,
                                             @RequestHeader(value = "X-User-Id", required = false) Long uid,
                                             @RequestHeader(value = "X-Api-Key", required = false) String key,
                                             @PathVariable long id, @PathVariable String action,
                                             @RequestBody(required = false) ActionRequest body) {
        return respond(jobs.perform(auth.caller(request.getSession(false), uid, key), action, id, body));
    }

    @GetMapping("/jobs/{id}")
    public Job get(HttpServletRequest request,
                   @RequestHeader(value = "X-User-Id", required = false) Long uid,
                   @RequestHeader(value = "X-Api-Key", required = false) String key, @PathVariable long id) {
        return jobs.get(auth.caller(request.getSession(false), uid, key), id);
    }

    /** Jobs I may SEE. Seeing and acting are different: see the design document. */
    @GetMapping("/jobs")
    public List<QueueItem> visible(HttpServletRequest request,
                                   @RequestHeader(value = "X-User-Id", required = false) Long uid,
                                   @RequestHeader(value = "X-Api-Key", required = false) String key,
                                   @RequestParam(required = false) String stage,
                                   @RequestParam(defaultValue = "50") int limit) {
        return queues.visible(auth.caller(request.getSession(false), uid, key), stage, limit);
    }

    @GetMapping("/jobs/{id}/history")
    public Map<String, Object> history(HttpServletRequest request,
                                       @RequestHeader(value = "X-User-Id", required = false) Long uid,
                                       @RequestHeader(value = "X-Api-Key", required = false) String key, @PathVariable long id) {
        return explain.history(auth.caller(request.getSession(false), uid, key), id);
    }

    @GetMapping("/jobs/{id}/who-could")
    public Map<String, Object> whoCould(HttpServletRequest request,
                                        @RequestHeader(value = "X-User-Id", required = false) Long uid,
                                        @RequestHeader(value = "X-Api-Key", required = false) String key,
                                        @PathVariable long id, @RequestParam String action,
                                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime at) {
        return explain.whoCould(auth.caller(request.getSession(false), uid, key), id, action, at);
    }

    /** "Jobs waiting for me". count and items come from the same rules as the yes/no check. */
    @GetMapping("/users/{id}/queue")
    public QueuePage queue(HttpServletRequest request,
                           @RequestHeader(value = "X-User-Id", required = false) Long uid,
                           @RequestHeader(value = "X-Api-Key", required = false) String key, @PathVariable long id,
                           @RequestParam(required = false) String filter, @RequestParam(defaultValue = "50") int limit,
                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime afterUpdatedAt,
                           @RequestParam(required = false) Long afterId) {
        long caller = auth.caller(request.getSession(false), uid, key);
        if (caller != id && !eval.hasAnywhere(caller, Permission.ACCESS_MANAGE)) {
            throw new ApiException(403, "NOT_ALLOWED", "You can see your own queue, or anyone's if you hold ACCESS_MANAGE");
        }
        return queues.queue(id, filter, limit, afterUpdatedAt, afterId);
    }
}
