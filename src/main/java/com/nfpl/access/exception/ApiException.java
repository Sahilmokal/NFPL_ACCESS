package com.nfpl.access.exception;

import com.nfpl.access.model.Models;

public class ApiException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public final int status;
    public final String code;
    public final transient Models.Decision decision;

    public ApiException(int status, String code, String message) {
        this(status, code, message, null);
    }

    public ApiException(int status, String code, String message, Models.Decision decision) {
        super(message);
        this.status = status;
        this.code = code;
        this.decision = decision;
    }
}
