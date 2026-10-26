package org.acme.employeescheduling.controller;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;

import org.acme.employeescheduling.config.SessionHeader;
import org.acme.employeescheduling.domain.CrewSchedule;
import org.acme.employeescheduling.domain.Line;
import org.acme.employeescheduling.domain.Role;
import org.acme.employeescheduling.exception.ScheduleException;
import org.acme.employeescheduling.repository.CrewScheduleRepository.ScheduleVersion;
import org.acme.employeescheduling.service.CrewDataCache;
import org.acme.employeescheduling.service.CrewRosterService;
import org.acme.employeescheduling.service.CrewRosterService.CrewDetails;
import org.acme.employeescheduling.service.CrewRosterService.Rotation;
import org.acme.employeescheduling.service.CrewScheduleEditor;
import org.acme.employeescheduling.service.CrewScheduleEditor.CrewShiftRequest;
import org.acme.employeescheduling.service.CrewScheduleSessionService;
import org.acme.employeescheduling.service.LineSchedule;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Every endpoint works with the schedule version of the caller (X-Session-Id
 * header,
 * {@value SessionHeader#DEFAULT_SESSION_ID} when missing or empty), like
 * food-packaging-quarkus. Changes stay in the version (in memory), nothing is
 * written to the DB.
 */
@RestController
@RequestMapping("/crew-schedules")
public class CrewScheduleController {

    private final CrewScheduleSessionService sessionService;
    private final CrewRosterService rosterService;
    private final CrewScheduleEditor editor;
    private final CrewDataCache crewDataCache;
    private final Clock clock;

    public CrewScheduleController(CrewScheduleSessionService sessionService, CrewRosterService rosterService,
            CrewScheduleEditor editor, CrewDataCache crewDataCache, Clock clock) {
        this.sessionService = sessionService;
        this.rosterService = rosterService;
        this.editor = editor;
        this.crewDataCache = crewDataCache;
        this.clock = clock;
    }

    /** The whole version: positions with assigned employees. */
    @GetMapping
    public CrewSchedule get(
            @RequestHeader(name = SessionHeader.HEADER, defaultValue = SessionHeader.DEFAULT_SESSION_ID) String sessionId) {
        return sessionService.requireVersion(sessionId).schedule();
    }

    /**
     * The crew schedule of the version: day shift and night shift for every line
     * and day.
     */
    @GetMapping("/rotation")
    public RotationResponse rotation(
            @RequestHeader(name = SessionHeader.HEADER, defaultValue = SessionHeader.DEFAULT_SESSION_ID) String sessionId) {
        return toResponse(sessionId, sessionService.requireVersion(sessionId));
    }

    /**
     * All active lines (loaded at startup), sorted by name. Also works when there is no version yet,
     * so the frontend can show the lines before the first "New empty month".
     */
    @GetMapping("/lines")
    public List<Line> lines() {
        return crewDataCache.getLines();
    }

    /** Crews of the caller's version with their lines, composition and all their employees. */
    @GetMapping("/crews")
    public List<CrewDetails> crews(
            @RequestHeader(name = SessionHeader.HEADER, defaultValue = SessionHeader.DEFAULT_SESSION_ID) String sessionId) {
        return rosterService.toCrewDetails(sessionService.requireVersion(sessionId).schedule());
    }

    /** All roles of the caller's version, sorted by name (for adding a role to a crew). */
    @GetMapping("/roles")
    public List<Role> roles(
            @RequestHeader(name = SessionHeader.HEADER, defaultValue = SessionHeader.DEFAULT_SESSION_ID) String sessionId) {
        return rosterService.roles(sessionService.requireVersion(sessionId).schedule());
    }

    /**
     * Reloads data from the DB and makes a new version without crew shifts for
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

    /** Puts a crew on a line for one shift (409 if the line shift already has a crew). */
    @PostMapping("/crew-shifts")
    public RotationResponse addCrewShift(
            @RequestHeader(name = SessionHeader.HEADER, defaultValue = SessionHeader.DEFAULT_SESSION_ID) String sessionId,
            @RequestBody CrewShiftRequest request) {
        return toResponse(sessionId, sessionService.edit(sessionId, schedule -> editor.addCrewShift(schedule, request)));
    }

    /** Moves a crew shift or gives it another crew (409 if it is pinned). */
    @PutMapping("/crew-shifts/{crewShiftId}")
    public RotationResponse moveCrewShift(
            @RequestHeader(name = SessionHeader.HEADER, defaultValue = SessionHeader.DEFAULT_SESSION_ID) String sessionId,
            @PathVariable String crewShiftId, @RequestBody CrewShiftRequest request) {
        return toResponse(sessionId,
                sessionService.edit(sessionId, schedule -> editor.moveCrewShift(schedule, crewShiftId, request)));
    }

    /** Removes a crew shift (409 if it is pinned). */
    @DeleteMapping("/crew-shifts/{crewShiftId}")
    public RotationResponse removeCrewShift(
            @RequestHeader(name = SessionHeader.HEADER, defaultValue = SessionHeader.DEFAULT_SESSION_ID) String sessionId,
            @PathVariable String crewShiftId) {
        return toResponse(sessionId,
                sessionService.edit(sessionId, schedule -> editor.removeCrewShift(schedule, crewShiftId)));
    }

    /** Pins or unpins a crew shift: a pinned crew shift cannot be moved or removed. */
    @PutMapping("/crew-shifts/{crewShiftId}/pinned")
    public RotationResponse setPinned(
            @RequestHeader(name = SessionHeader.HEADER, defaultValue = SessionHeader.DEFAULT_SESSION_ID) String sessionId,
            @PathVariable String crewShiftId, @RequestBody PinnedRequest request) {
        return toResponse(sessionId, sessionService.edit(sessionId,
                schedule -> editor.setPinned(schedule, crewShiftId, request.pinned())));
    }

    /**
     * Sets how many people of a role the crew needs per shift from {@code validFrom} (default: the first day of
     * the month) until the next composition version; 0 removes the role. Earlier crew shifts do not change.
     */
    @PutMapping("/crews/{crewId}/roles/{roleId}")
    public List<CrewDetails> setRoleCount(
            @RequestHeader(name = SessionHeader.HEADER, defaultValue = SessionHeader.DEFAULT_SESSION_ID) String sessionId,
            @PathVariable int crewId, @PathVariable int roleId, @RequestBody RoleCountRequest request) {
        ScheduleVersion version = sessionService.edit(sessionId,
                schedule -> editor.setRoleCount(schedule, crewId, roleId, request.count(), request.validFrom()));
        return rosterService.toCrewDetails(version.schedule());
    }

    /** Removes a composition version of the crew: from its date on, the previous version is valid again. */
    @DeleteMapping("/crews/{crewId}/composition/{validFrom}")
    public List<CrewDetails> removeCompositionVersion(
            @RequestHeader(name = SessionHeader.HEADER, defaultValue = SessionHeader.DEFAULT_SESSION_ID) String sessionId,
            @PathVariable int crewId, @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate validFrom) {
        ScheduleVersion version = sessionService.edit(sessionId,
                schedule -> editor.removeCompositionVersion(schedule, crewId, validFrom));
        return rosterService.toCrewDetails(version.schedule());
    }

    /** Moves an employee to another crew. */
    @PutMapping("/employees/{employeeId}/crew")
    public List<CrewDetails> moveEmployee(
            @RequestHeader(name = SessionHeader.HEADER, defaultValue = SessionHeader.DEFAULT_SESSION_ID) String sessionId,
            @PathVariable int employeeId, @RequestBody EmployeeCrewRequest request) {
        if (request.crewId() == null) {
            throw new ScheduleException(sessionId, HttpStatus.BAD_REQUEST, "crewId is required.");
        }
        ScheduleVersion version = sessionService.edit(sessionId,
                schedule -> editor.moveEmployee(schedule, employeeId, request.crewId()));
        return rosterService.toCrewDetails(version.schedule());
    }

    private RotationResponse toResponse(String sessionId, ScheduleVersion version) {
        Rotation rotation = rosterService.toRotation(version.schedule());
        return new RotationResponse(sessionId, version.id(), rotation.month(), rotation.lines());
    }

    public record RotationResponse(String sessionId, String versionId, YearMonth month, List<LineSchedule> lines) {
    }

    public record PinnedRequest(boolean pinned) {
    }

    public record RoleCountRequest(int count, LocalDate validFrom) {
    }

    public record EmployeeCrewRequest(Integer crewId) {
    }
}
