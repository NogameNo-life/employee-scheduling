package org.acme.employeescheduling.controller;

import java.time.Clock;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;

import org.acme.employeescheduling.config.SessionHeader;
import org.acme.employeescheduling.domain.CrewSchedule;
import org.acme.employeescheduling.exception.ScheduleException;
import org.acme.employeescheduling.repository.CrewScheduleRepository.ScheduleVersion;
import org.acme.employeescheduling.service.CrewRosterService;
import org.acme.employeescheduling.service.CrewRosterService.Rotation;
import org.acme.employeescheduling.service.CrewScheduleSessionService;
import org.acme.employeescheduling.service.LineSchedule;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Every endpoint works with the schedule version of the caller (X-Session-Id
 * header,
 * {@value SessionHeader#DEFAULT_SESSION_ID} when missing or empty), like
 * food-packaging-quarkus.
 */
@RestController
@RequestMapping("/crew-schedules")
public class CrewScheduleController {

    private final CrewScheduleSessionService sessionService;
    private final CrewRosterService rosterService;
    private final Clock clock;

    public CrewScheduleController(CrewScheduleSessionService sessionService, CrewRosterService rosterService,
            Clock clock) {
        this.sessionService = sessionService;
        this.rosterService = rosterService;
        this.clock = clock;
    }

    /** The whole version: positions with assigned employees. */
    @GetMapping
    public CrewSchedule get(
            @RequestHeader(name = SessionHeader.HEADER, defaultValue = SessionHeader.DEFAULT_SESSION_ID) String sessionId) {
        return sessionService.requireVersion(sessionId).schedule();
    }

    /**
     * The crew schedule of the version: day crew and night crew for every line and
     * day.
     */
    @GetMapping("/rotation")
    public RotationResponse rotation(
            @RequestHeader(name = SessionHeader.HEADER, defaultValue = SessionHeader.DEFAULT_SESSION_ID) String sessionId) {
        return toResponse(sessionId, sessionService.requireVersion(sessionId));
    }

    /**
     * Reloads data from the DB, gives crews to lines at random and makes a new
     * version for
     * {@code month} (yyyy-MM, default: the current month). Only the caller's
     * version is replaced.
     */
    @PostMapping("/init")
    public RotationResponse init(
            @RequestHeader(name = SessionHeader.HEADER, defaultValue = SessionHeader.DEFAULT_SESSION_ID) String sessionId,
            @RequestParam(required = false) String month) {
        YearMonth yearMonth;
        try {
            yearMonth = month == null || month.isBlank() ? YearMonth.now(clock) : YearMonth.parse(month);
        } catch (DateTimeParseException e) {
            throw new ScheduleException(sessionId, HttpStatus.BAD_REQUEST, "month must be yyyy-MM, got: " + month);
        }
        return toResponse(sessionId, sessionService.init(sessionId, yearMonth));
    }

    private RotationResponse toResponse(String sessionId, ScheduleVersion version) {
        Rotation rotation = rosterService.toRotation(version.schedule());
        return new RotationResponse(sessionId, version.id(), rotation.month(), rotation.lines());
    }

    public record RotationResponse(String sessionId, String versionId, YearMonth month, List<LineSchedule> lines) {
    }
}
