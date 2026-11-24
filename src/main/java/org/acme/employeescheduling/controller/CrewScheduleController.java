package org.acme.employeescheduling.controller;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;

import org.acme.employeescheduling.config.SessionHeader;
import org.acme.employeescheduling.domain.CrewSchedule;
import org.acme.employeescheduling.domain.Line;
import org.acme.employeescheduling.domain.ShiftTime;
import org.acme.employeescheduling.exception.ScheduleException;
import org.acme.employeescheduling.repository.CrewScheduleRepository.ScheduleVersion;
import org.acme.employeescheduling.service.CrewDataCache;
import org.acme.employeescheduling.service.CrewRosterService;
import org.acme.employeescheduling.service.CrewRosterService.CrewDetails;
import org.acme.employeescheduling.service.CrewRosterService.Rotation;
import org.acme.employeescheduling.service.CrewRotationGenerator;
import org.acme.employeescheduling.service.CrewRotationGenerator.GeneratePreview;
import org.acme.employeescheduling.service.CrewRotationGenerator.GenerateRequest;
import org.acme.employeescheduling.service.CrewRotationGenerator.GenerateResult;
import org.acme.employeescheduling.service.CrewScheduleEditor;
import org.acme.employeescheduling.service.CrewScheduleEditor.AbsenceRequest;
import org.acme.employeescheduling.service.CrewScheduleEditor.ClearResult;
import org.acme.employeescheduling.service.CrewScheduleEditor.CrewShiftRequest;
import org.acme.employeescheduling.service.CrewScheduleEditor.SampleRequest;
import org.acme.employeescheduling.service.CrewScheduleSessionService;
import org.acme.employeescheduling.service.LineSchedule;
import org.acme.employeescheduling.service.ScheduleValidator;
import org.acme.employeescheduling.service.ScheduleValidator.ScheduleWarning;
import com.fasterxml.jackson.annotation.JsonInclude;

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
    private final CrewRotationGenerator generator;
    private final ScheduleValidator validator;
    private final CrewDataCache crewDataCache;
    private final Clock clock;

    public CrewScheduleController(CrewScheduleSessionService sessionService, CrewRosterService rosterService,
            CrewScheduleEditor editor, CrewRotationGenerator generator, ScheduleValidator validator,
            CrewDataCache crewDataCache, Clock clock) {
        this.sessionService = sessionService;
        this.rosterService = rosterService;
        this.editor = editor;
        this.generator = generator;
        this.validator = validator;
        this.crewDataCache = crewDataCache;
        this.clock = clock;
    }

    /** The whole version: lines, crews, employees and crew shifts. */
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
     * Lines that are active in the current month (loaded at startup), sorted by name. Also works when there is
     * no version yet,
     * so the frontend can show the lines before the first "New empty month".
     */
    @GetMapping("/lines")
    public List<Line> lines() {
        return crewDataCache.getLines(YearMonth.now(clock));
    }

    /** Crews of the caller's version with their lines and all their employees. */
    @GetMapping("/crews")
    public List<CrewDetails> crews(
            @RequestHeader(name = SessionHeader.HEADER, defaultValue = SessionHeader.DEFAULT_SESSION_ID) String sessionId) {
        return rosterService.toCrewDetails(sessionService.requireVersion(sessionId).schedule());
    }

    /**
     * Reloads data from the DB and makes a new version without crew shifts for
     * {@code month} (yyyy-MM, default: the current month). Only the caller's
     * version is replaced. {@code generate=true}: then generates the crew schedule of every line from scratch
     * (the response has {@code generation}); default: the version stays empty.
     */
    @PostMapping("/init")
    public RotationResponse init(
            @RequestHeader(name = SessionHeader.HEADER, defaultValue = SessionHeader.DEFAULT_SESSION_ID) String sessionId,
            @RequestParam(required = false) String month,
            @RequestParam(defaultValue = "false") boolean generate) {
        YearMonth yearMonth;
        try {
            yearMonth = month == null || month.isBlank() ? YearMonth.now(clock) : YearMonth.parse(month);
        } catch (DateTimeParseException e) {
            throw new ScheduleException(sessionId, HttpStatus.BAD_REQUEST, "month must be yyyy-MM, got: " + month);
        }
        ScheduleVersion version = sessionService.init(sessionId, yearMonth);
        if (!generate) {
            return toResponse(sessionId, version);
        }
        return generate(sessionId, new GenerateRequest(null, null, null, false));
    }

    /**
     * Fills the crew schedule with the rotation Day - Night - Sleep day - Day off (see
     * {@link CrewRotationGenerator}). 400: invalid request, 404: unknown line. Places that cannot be filled stay
     * empty and are listed in {@code generation.messages}.
     */
    @PostMapping("/generate")
    public RotationResponse generate(
            @RequestHeader(name = SessionHeader.HEADER, defaultValue = SessionHeader.DEFAULT_SESSION_ID) String sessionId,
            @RequestBody(required = false) GenerateRequest request) {
        GenerateResult[] result = new GenerateResult[1];
        ScheduleVersion version = sessionService.edit(sessionId,
                schedule -> result[0] = generator.generate(schedule, request));
        return toResponse(sessionId, version, result[0]);
    }

    /** Pinned, unpinned and empty places from {@code from} to {@code to} (null: end of month), per line and in total. */
    @GetMapping("/generate/preview")
    public GeneratePreview previewGeneration(
            @RequestHeader(name = SessionHeader.HEADER, defaultValue = SessionHeader.DEFAULT_SESSION_ID) String sessionId,
            @RequestParam(required = false) String lineId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        GeneratePreview[] preview = new GeneratePreview[1];
        sessionService.edit(sessionId, schedule -> preview[0] = generator.preview(schedule, lineId, from, to));
        return preview[0];
    }

    /**
     * Sets the sample of a line: the pinned DAY crews of 4 days in a row. Does not generate. 400: invalid sample,
     * 404: unknown line or crew, 409: conflict with a pinned shift or another line (nothing changes).
     */
    @PutMapping("/lines/{lineId}/sample")
    public RotationResponse setLineSample(
            @RequestHeader(name = SessionHeader.HEADER, defaultValue = SessionHeader.DEFAULT_SESSION_ID) String sessionId,
            @PathVariable String lineId, @RequestBody SampleRequest request) {
        return toResponse(sessionId, sessionService.edit(sessionId,
                schedule -> editor.setLineSample(schedule, lineId, request)));
    }

    /**
     * Removes the crew shifts of a line (null: all lines) in a date range, with their people changes; pinned ones
     * only with {@code includePinned}. The response has {@code cleared}.
     */
    @PostMapping("/crew-shifts/clear")
    public RotationResponse clearCrewShifts(
            @RequestHeader(name = SessionHeader.HEADER, defaultValue = SessionHeader.DEFAULT_SESSION_ID) String sessionId,
            @RequestBody ClearRequest request) {
        ClearResult[] result = new ClearResult[1];
        ScheduleVersion version = sessionService.edit(sessionId, schedule -> result[0] = editor.clearCrewShifts(schedule,
                request.lineId(), request.from(), request.to(), request.includePinned()));
        return toResponse(sessionId, version, null, result[0]);
    }

    /** Pins or unpins every crew shift of a line (null: all lines) in a date range. */
    @PostMapping("/crew-shifts/pinned")
    public RotationResponse setPinnedInRange(
            @RequestHeader(name = SessionHeader.HEADER, defaultValue = SessionHeader.DEFAULT_SESSION_ID) String sessionId,
            @RequestBody BulkPinnedRequest request) {
        return toResponse(sessionId, sessionService.edit(sessionId, schedule -> editor.setPinnedInRange(schedule,
                request.lineId(), request.from(), request.to(), request.pinned())));
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

    /** The employee works this crew shift (only this one): back from absence, or as a substitute. */
    @PutMapping("/crew-shifts/{crewShiftId}/employees/{employeeId}")
    public RotationResponse addEmployeeToShift(
            @RequestHeader(name = SessionHeader.HEADER, defaultValue = SessionHeader.DEFAULT_SESSION_ID) String sessionId,
            @PathVariable String crewShiftId, @PathVariable int employeeId) {
        return toResponse(sessionId, sessionService.edit(sessionId,
                schedule -> editor.addEmployeeToShift(schedule, crewShiftId, employeeId)));
    }

    /** The employee does not work this crew shift (only this one). */
    @DeleteMapping("/crew-shifts/{crewShiftId}/employees/{employeeId}")
    public RotationResponse removeEmployeeFromShift(
            @RequestHeader(name = SessionHeader.HEADER, defaultValue = SessionHeader.DEFAULT_SESSION_ID) String sessionId,
            @PathVariable String crewShiftId, @PathVariable int employeeId) {
        return toResponse(sessionId, sessionService.edit(sessionId,
                schedule -> editor.removeEmployeeFromShift(schedule, crewShiftId, employeeId)));
    }

    /** {@code employeeId} does not work this crew shift, {@code substituteId} works it instead (one step). */
    @PostMapping("/crew-shifts/{crewShiftId}/replacements")
    public RotationResponse replaceEmployee(
            @RequestHeader(name = SessionHeader.HEADER, defaultValue = SessionHeader.DEFAULT_SESSION_ID) String sessionId,
            @PathVariable String crewShiftId, @RequestBody ReplacementRequest request) {
        if (request.employeeId() == null || request.substituteId() == null) {
            throw new ScheduleException(sessionId, HttpStatus.BAD_REQUEST, "employeeId and substituteId are required.");
        }
        return toResponse(sessionId, sessionService.edit(sessionId, schedule -> editor.replaceEmployee(schedule,
                crewShiftId, request.employeeId(), request.substituteId())));
    }

    /**
     * Moves an employee to another crew: changes the employees of both crews, from {@code from} on
     * (yyyy-MM-dd, in the version's month; missing: the whole month).
     */
    @PutMapping("/employees/{employeeId}/crew")
    public List<CrewDetails> moveEmployee(
            @RequestHeader(name = SessionHeader.HEADER, defaultValue = SessionHeader.DEFAULT_SESSION_ID) String sessionId,
            @PathVariable int employeeId, @RequestBody EmployeeCrewRequest request) {
        if (request.crewId() == null) {
            throw new ScheduleException(sessionId, HttpStatus.BAD_REQUEST, "crewId is required.");
        }
        ScheduleVersion version = sessionService.edit(sessionId, schedule -> editor.moveEmployee(schedule,
                employeeId, request.crewId(), request.from() == null ? schedule.getMonth().atDay(1) : request.from()));
        return rosterService.toCrewDetails(version.schedule());
    }

    /** Removes the crew change of the employee on {@code from} (yyyy-MM-dd). */
    @DeleteMapping("/employees/{employeeId}/crew-changes/{from}")
    public List<CrewDetails> removeCrewChange(
            @RequestHeader(name = SessionHeader.HEADER, defaultValue = SessionHeader.DEFAULT_SESSION_ID) String sessionId,
            @PathVariable int employeeId, @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from) {
        ScheduleVersion version = sessionService.edit(sessionId,
                schedule -> editor.removeCrewChange(schedule, employeeId, from));
        return rosterService.toCrewDetails(version.schedule());
    }

    /** Vacation, sick leave, dismissal (no end) or other absence: the employee works no shift in that period. */
    @PostMapping("/employees/{employeeId}/absences")
    public RotationResponse addAbsence(
            @RequestHeader(name = SessionHeader.HEADER, defaultValue = SessionHeader.DEFAULT_SESSION_ID) String sessionId,
            @PathVariable int employeeId, @RequestBody AbsenceRequest request) {
        return toResponse(sessionId, sessionService.edit(sessionId,
                schedule -> editor.addAbsence(schedule, employeeId, request)));
    }

    @DeleteMapping("/employees/{employeeId}/absences/{absenceId}")
    public RotationResponse removeAbsence(
            @RequestHeader(name = SessionHeader.HEADER, defaultValue = SessionHeader.DEFAULT_SESSION_ID) String sessionId,
            @PathVariable int employeeId, @PathVariable String absenceId) {
        return toResponse(sessionId, sessionService.edit(sessionId,
                schedule -> editor.removeAbsence(schedule, employeeId, absenceId)));
    }

    private RotationResponse toResponse(String sessionId, ScheduleVersion version) {
        return toResponse(sessionId, version, null);
    }

    private RotationResponse toResponse(String sessionId, ScheduleVersion version, GenerateResult generation) {
        return toResponse(sessionId, version, generation, null);
    }

    private RotationResponse toResponse(String sessionId, ScheduleVersion version, GenerateResult generation,
            ClearResult cleared) {
        // Read the version under its lock: another request of the same session may change it
        synchronized (version.schedule()) {
            Rotation rotation = rosterService.toRotation(version.schedule());
            return new RotationResponse(sessionId, version.id(), rotation.month(), version.schedule().getShifts(),
                    rotation.lines(), validator.validate(version.schedule()), generation, cleared);
        }
    }

    /**
     * @param shifts     times of the shift types (PLR_SM), e.g. for snapping a dragged crew shift
     * @param warnings   rest rule warnings, calculated for every response (nothing is blocked)
     * @param generation only after generating: what the generator did
     * @param cleared    only after clearing: removed and kept pinned crew shifts
     */
    public record RotationResponse(String sessionId, String versionId, YearMonth month, List<ShiftTime> shifts,
            List<LineSchedule> lines, List<ScheduleWarning> warnings,
            @JsonInclude(JsonInclude.Include.NON_NULL) GenerateResult generation,
            @JsonInclude(JsonInclude.Include.NON_NULL) ClearResult cleared) {
    }

    /**
     * @param lineId        null: all lines
     * @param from          null: the 1st of the month
     * @param to            null: the end of the month
     * @param includePinned true: the pinned crew shifts are removed too
     */
    public record ClearRequest(String lineId, LocalDate from, LocalDate to, boolean includePinned) {
    }

    public record PinnedRequest(boolean pinned) {
    }

    /**
     * @param lineId null: all lines
     * @param from   null: the 1st of the month
     * @param to     null: the end of the month
     */
    public record BulkPinnedRequest(String lineId, LocalDate from, LocalDate to, boolean pinned) {
    }

    public record ReplacementRequest(Integer employeeId, Integer substituteId) {
    }

    /** @param from first day in the new crew; null: the whole month */
    public record EmployeeCrewRequest(Integer crewId, LocalDate from) {
    }

}
