package org.acme.employeescheduling.rest.exception;

public record ErrorInfo(String sessionId, String message) {
}
