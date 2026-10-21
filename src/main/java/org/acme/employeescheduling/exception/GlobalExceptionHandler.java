package org.acme.employeescheduling.exception;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ScheduleException.class)
    public ResponseEntity<ErrorInfo> handle(ScheduleException exception) {
        return ResponseEntity.status(exception.getStatus())
                .body(new ErrorInfo(exception.getSessionId(), exception.getMessage()));
    }
}
