package com.nfpl.access.exception;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/** One error shape for everything: {error, message, trace?}. A denial always carries its reasoning. */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handle(ApiException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", e.code);
        body.put("message", e.getMessage());
        if (e.decision != null) body.put("trace", e.decision.trace());
        return ResponseEntity.status(e.status).body(body);
    }
}
