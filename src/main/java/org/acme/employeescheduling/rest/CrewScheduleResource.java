package org.acme.employeescheduling.rest;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import ai.timefold.solver.core.api.solver.SolverManager;

import org.acme.employeescheduling.domain.Crew;
import org.acme.employeescheduling.domain.CrewSchedule;
import org.acme.employeescheduling.domain.CrewShift;
import org.acme.employeescheduling.domain.Line;
import org.acme.employeescheduling.rest.exception.EmployeeScheduleSolverException;
import org.acme.employeescheduling.service.CrewData;
import org.acme.employeescheduling.service.CrewDataCache;
import org.acme.employeescheduling.service.CrewRotationGenerator;
import org.acme.employeescheduling.service.CrewScheduleFactory;
import org.acme.employeescheduling.service.CrewRosterService;
import org.acme.employeescheduling.service.CrewRosterService.Roster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Path("crew-schedules")
public class CrewScheduleResource {

    private static final Logger LOGGER = LoggerFactory.getLogger(CrewScheduleResource.class);

    private final SolverManager<CrewSchedule, String> solverManager;
    private final CrewDataCache crewDataCache;
    private final CrewRotationGenerator rotationGenerator;
    private final CrewScheduleFactory scheduleFactory;
    private final CrewRosterService rosterService;

    private final ConcurrentMap<String, Job> jobIdToJob = new ConcurrentHashMap<>();

    @Inject
    public CrewScheduleResource(SolverManager<CrewSchedule, String> solverManager, CrewDataCache crewDataCache,
            CrewRotationGenerator rotationGenerator, CrewScheduleFactory scheduleFactory,
            CrewRosterService rosterService) {
        this.solverManager = solverManager;
        this.crewDataCache = crewDataCache;
        this.rotationGenerator = rotationGenerator;
        this.scheduleFactory = scheduleFactory;
        this.rosterService = rosterService;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Collection<String> list() {
        return jobIdToJob.keySet();
    }

    /** Takes crew data and lines from the cache, generates the crew schedule and starts solving. Returns the job ID. */
    @POST
    @Path("solve")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.TEXT_PLAIN)
    public String solve(SolveRequest request) {
        validate(request);
        CrewData data = crewDataCache.get(request.department());
        List<CrewShift> crewShifts = generateRotation(() -> rotationGenerator.generate(
                request.from(), request.to(), request.anchorDate(), crewsByLine(request, data)));
        CrewSchedule problem = scheduleFactory.create(data, crewShifts);
        LOGGER.info("Solving {} positions, {} employees, {} crew shifts.",
                problem.getPositions().size(), problem.getEmployees().size(), crewShifts.size());

        String jobId = UUID.randomUUID().toString();
        jobIdToJob.put(jobId, Job.ofSchedule(problem));
        solverManager.solveBuilder()
                .withProblemId(jobId)
                .withProblemFinder(id -> jobIdToJob.get(jobId).schedule())
                .withBestSolutionConsumer(solution -> jobIdToJob.put(jobId, Job.ofSchedule(solution)))
                .withExceptionHandler((id, exception) -> {
                    jobIdToJob.put(jobId, Job.ofException(exception));
                    LOGGER.error("Failed solving jobId ({}).", jobId, exception);
                })
                .run();
        return jobId;
    }

    /** The crew schedule generated at startup or by the last {@code init}. */
    @GET
    @Path("rotation")
    @Produces(MediaType.APPLICATION_JSON)
    public Roster rotation() {
        return rosterService.getRoster();
    }

    /**
     * Reloads data from the DB, gives crews to lines at random and generates the crew schedule
     * for the month of {@code date} (yyyy-MM-dd, default: today).
     */
    @POST
    @Path("init")
    @Produces(MediaType.APPLICATION_JSON)
    public Roster init(@QueryParam("date") String date) {
        try {
            return rosterService.init(date == null || date.isBlank() ? LocalDate.now() : LocalDate.parse(date));
        } catch (DateTimeParseException e) {
            throw new BadRequestException("date must be yyyy-MM-dd, got: " + date);
        }
    }

    @GET
    @Path("{jobId}")
    @Produces(MediaType.APPLICATION_JSON)
    public CrewSchedule get(@PathParam("jobId") String jobId) {
        CrewSchedule schedule = getOrThrow(jobId);
        schedule.setSolverStatus(solverManager.getSolverStatus(jobId));
        return schedule;
    }

    @DELETE
    @Path("{jobId}")
    @Produces(MediaType.APPLICATION_JSON)
    public CrewSchedule stop(@PathParam("jobId") String jobId) {
        solverManager.terminateEarly(jobId);
        return get(jobId);
    }

    private CrewSchedule getOrThrow(String jobId) {
        Job job = jobIdToJob.get(jobId);
        if (job == null) {
            throw new EmployeeScheduleSolverException(jobId, Response.Status.NOT_FOUND, "No schedule found.");
        }
        if (job.exception() != null) {
            throw new EmployeeScheduleSolverException(jobId, job.exception());
        }
        return job.schedule();
    }

    private Map<Line, List<Crew>> crewsByLine(SolveRequest request, CrewData data) {
        Map<Integer, Crew> crewsById = data.crews().stream()
                .collect(Collectors.toMap(Crew::getId, Function.identity()));
        Map<Line, List<Crew>> crewsByLine = new LinkedHashMap<>();
        for (SolveRequest.LineRotation lr : request.lines()) {
            if (lr.crewIds() == null) {
                throw new BadRequestException("crewIds are required for line " + lr.lineId());
            }
            List<Crew> crews = lr.crewIds().stream().map(id -> {
                Crew crew = crewsById.get(id);
                if (crew == null) {
                    throw new BadRequestException("Unknown crew " + id + " in department " + request.department());
                }
                return crew;
            }).toList();
            Line line = crewDataCache.getLine(lr.lineId())
                    .orElseThrow(() -> new BadRequestException("Unknown line " + lr.lineId() + " (PLR_PLINES.KRC)."));
            crewsByLine.put(line, crews);
        }
        return crewsByLine;
    }

    /** Turns a wrong rotation (not 4 crews, the same crew twice) into 400 Bad Request. */
    private static <T> T generateRotation(Supplier<T> generator) {
        try {
            return generator.get();
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(e.getMessage());
        }
    }

    private static void validate(SolveRequest r) {
        if (r == null || r.department() == null || r.from() == null || r.to() == null
                || r.anchorDate() == null || r.lines() == null || r.lines().isEmpty()) {
            throw new BadRequestException("department, from, to, anchorDate and lines are required.");
        }
        if (r.to().isBefore(r.from())) {
            throw new BadRequestException("'to' must not be before 'from'.");
        }
    }

    private record Job(CrewSchedule schedule, Throwable exception) {
        static Job ofSchedule(CrewSchedule schedule) {
            return new Job(schedule, null);
        }

        static Job ofException(Throwable exception) {
            return new Job(null, exception);
        }
    }
}
