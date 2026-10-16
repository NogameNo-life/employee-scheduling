package org.acme.employeescheduling.rest.exception;

import jakarta.ws.rs.core.Response;

public class EmployeeScheduleSolverException extends RuntimeException {

    private final String sessionId;

    private final Response.Status status;

    public EmployeeScheduleSolverException(String sessionId, Response.Status status, String message) {
        super(message);
        this.sessionId = sessionId;
        this.status = status;
    }

    public EmployeeScheduleSolverException(String sessionId, Throwable cause) {
        super(cause.getMessage(), cause);
        this.sessionId = sessionId;
        this.status = Response.Status.INTERNAL_SERVER_ERROR;
    }

    public String getSessionId() {
        return sessionId;
    }

    public Response.Status getStatus() {
        return status;
    }
}
