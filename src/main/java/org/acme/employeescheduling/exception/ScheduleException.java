package org.acme.employeescheduling.exception;

import org.springframework.http.HttpStatus;

/** An error for one session, sent to the frontend as {@link ErrorInfo}. */
public class ScheduleException extends RuntimeException {

    private final String sessionId;

    private final HttpStatus status;

    public ScheduleException(String sessionId, HttpStatus status, String message) {
        super(message);
        this.sessionId = sessionId;
        this.status = status;
    }

    public String getSessionId() {
        return sessionId;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
