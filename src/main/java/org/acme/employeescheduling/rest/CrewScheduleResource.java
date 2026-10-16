package org.acme.employeescheduling.rest;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;

import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import ai.timefold.solver.core.api.score.analysis.ScoreAnalysis;
import ai.timefold.solver.core.api.score.buildin.hardmediumsoft.HardMediumSoftScore;
import ai.timefold.solver.core.api.solver.SolutionManager;
import ai.timefold.solver.core.api.solver.SolverManager;
import ai.timefold.solver.core.api.solver.SolverStatus;

import org.acme.employeescheduling.config.SessionHeaderFilter;
import org.acme.employeescheduling.domain.CrewSchedule;
import org.acme.employeescheduling.repository.CrewScheduleRepository;
import org.acme.employeescheduling.repository.CrewScheduleRepository.ScheduleVersion;
import org.acme.employeescheduling.rest.exception.EmployeeScheduleSolverException;
import org.acme.employeescheduling.service.CrewRosterService;
import org.acme.employeescheduling.service.CrewRosterService.Rotation;
import org.acme.employeescheduling.service.LineSchedule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Every endpoint works with the schedule version of the caller (X-Session-Id header,
 * {@value SessionHeaderFilter#DEFAULT_SESSION_ID} when missing), like food-packaging-quarkus.
 */
@Path("crew-schedules")
public class CrewScheduleResource {

    private static final Logger LOGGER = LoggerFactory.getLogger(CrewScheduleResource.class);

    private final SolverManager<CrewSchedule, String> solverManager;
    private final SolutionManager<CrewSchedule, HardMediumSoftScore> solutionManager;
    private final CrewScheduleRepository repository;
    private final CrewScheduleSessionService sessionService;
    private final CrewRosterService rosterService;

    @Inject
    public CrewScheduleResource(SolverManager<CrewSchedule, String> solverManager,
            SolutionManager<CrewSchedule, HardMediumSoftScore> solutionManager, CrewScheduleRepository repository,
            CrewScheduleSessionService sessionService, CrewRosterService rosterService) {
        this.solverManager = solverManager;
        this.solutionManager = solutionManager;
        this.repository = repository;
        this.sessionService = sessionService;
        this.rosterService = rosterService;
    }

    /** The whole version: positions with assigned employees, score and solver status. */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public CrewSchedule get(@HeaderParam(SessionHeaderFilter.HEADER) String sessionId) {
        CrewSchedule schedule = sessionService.requireVersion(sessionId).schedule();
        schedule.setSolverStatus(solverManager.getSolverStatus(sessionService.getProblemId(sessionId)));
        return schedule;
    }

    /** The crew schedule of the version: day crew and night crew for every line and day. */
    @GET
    @Path("rotation")
    @Produces(MediaType.APPLICATION_JSON)
    public RotationResponse rotation(@HeaderParam(SessionHeaderFilter.HEADER) String sessionId) {
        return toResponse(sessionId, sessionService.requireVersion(sessionId));
    }

    /**
     * Reloads data from the DB, gives crews to lines at random and makes a new version for the month of
     * {@code date} (yyyy-MM-dd, default: today). Only the caller's version is replaced.
     */
    @POST
    @Path("init")
    @Produces(MediaType.APPLICATION_JSON)
    public RotationResponse init(@HeaderParam(SessionHeaderFilter.HEADER) String sessionId,
            @QueryParam("date") String date) {
        LocalDate day;
        try {
            day = date == null || date.isBlank() ? LocalDate.now() : LocalDate.parse(date);
        } catch (DateTimeParseException e) {
            throw new BadRequestException("date must be yyyy-MM-dd, got: " + date);
        }
        solverManager.terminateEarly(sessionService.getProblemId(sessionId));
        return toResponse(sessionId, sessionService.init(sessionId, day));
    }

    /** Assigns employees in the caller's version. The best solution is stored in the same version. */
    @POST
    @Path("solve")
    @Produces(MediaType.APPLICATION_JSON)
    public SolveResponse solve(@HeaderParam(SessionHeaderFilter.HEADER) String sessionId) {
        String problemId = sessionService.getProblemId(sessionId);
        if (solverManager.getSolverStatus(problemId) != SolverStatus.NOT_SOLVING) {
            throw new EmployeeScheduleSolverException(sessionId, Response.Status.CONFLICT,
                    "This schedule version is already being solved.");
        }
        ScheduleVersion version = sessionService.requireVersion(sessionId);
        if (version.schedule().getPositions().isEmpty()) {
            throw new BadRequestException("There is nothing to solve. Generate the crew schedule first.");
        }
        LOGGER.info("Session {}: solving version {} ({} positions, {} employees).", sessionId, version.id(),
                version.schedule().getPositions().size(), version.schedule().getEmployees().size());

        solverManager.solveBuilder()
                .withProblemId(problemId)
                .withProblemFinder(id -> version.schedule())
                .withBestSolutionConsumer(solution -> repository.update(sessionId, version.id(), solution))
                .withExceptionHandler((id, exception) -> LOGGER.error("Session {}: solving failed.", sessionId,
                        exception))
                .run();
        return new SolveResponse(sessionId, version.id(), "Solving started");
    }

    @POST
    @Path("stopSolving")
    @Produces(MediaType.APPLICATION_JSON)
    public CrewSchedule stopSolving(@HeaderParam(SessionHeaderFilter.HEADER) String sessionId) {
        solverManager.terminateEarly(sessionService.getProblemId(sessionId));
        return get(sessionId);
    }

    /** Which constraints are broken in the caller's version, and by which positions. */
    @GET
    @Path("analyze")
    @Produces(MediaType.APPLICATION_JSON)
    public ScoreAnalysis<HardMediumSoftScore> analyze(@HeaderParam(SessionHeaderFilter.HEADER) String sessionId) {
        return solutionManager.analyze(sessionService.requireVersion(sessionId).schedule());
    }

    private RotationResponse toResponse(String sessionId, ScheduleVersion version) {
        Rotation rotation = rosterService.toRotation(version.schedule());
        return new RotationResponse(sessionId, version.id(), rotation.month(), rotation.lines());
    }

    public record RotationResponse(String sessionId, String versionId, YearMonth month, List<LineSchedule> lines) {
    }

    public record SolveResponse(String sessionId, String versionId, String message) {
    }
}
