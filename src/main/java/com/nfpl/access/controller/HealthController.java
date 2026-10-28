package com.nfpl.access.controller;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@RestController
public class HealthController {
    private final AtomicBoolean ready = new AtomicBoolean();

    @EventListener(ApplicationReadyEvent.class)
    public void markReady() {
        ready.set(true);
    }

    @GetMapping("/api/health")
    public ResponseEntity<Map<String, String>> health() {
        if (!ready.get()) return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("status", "STARTING"));
        return ResponseEntity.ok(Map.of("status", "UP"));
    }
}
