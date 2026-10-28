package com.nfpl.access.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nfpl.access.model.Models.*;
import com.nfpl.access.repository.Repo;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.nfpl.access.repository.Repo.p;

/**
 * Records every attempted action, allowed or denied. REQUIRES_NEW on purpose: a denied attempt throws and rolls the
 * caller's transaction back, but the record of the attempt must survive.
 */
@Component
public class DecisionLog {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Repo repo;

    public DecisionLog(Repo repo) {
        this.repo = repo;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long record(long uid, String action, Long jobId, Decision d) {
        String grantIds = d.grantIds().stream().map(String::valueOf).collect(Collectors.joining(","));
        String msg = d.message() == null ? null : (d.message().length() > 1000 ? d.message().substring(0, 1000) : d.message());
        return repo.insert("INSERT INTO access_decision_log (user_id, action, job_id, allowed, code, message, grant_ids, trace, "
                        + "created_at) VALUES (:u, :a, :j, :al, :c, :m, :g, CAST(:t AS JSON), :at)",
                p("u", uid, "a", action, "j", jobId, "al", d.allowed(), "c", d.code(), "m", msg,
                        "g", grantIds.isEmpty() ? null : grantIds, "t", traceJson(d), "at", Repo.now()));
    }

    static String traceJson(Decision d) {
        List<Map<String, Object>> steps = new ArrayList<>();
        for (Step s : d.trace()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("check", s.check());
            m.put("passed", s.passed());
            m.put("detail", s.detail());
            steps.add(m);
        }
        try {
            return JSON.writeValueAsString(steps);
        } catch (Exception e) {
            return "[]";
        }
    }
}
