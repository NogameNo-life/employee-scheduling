package org.acme.employeescheduling.rest;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
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
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import ai.timefold.solver.core.api.solver.SolverManager;

import org.acme.employeescheduling.domain.Crew;
import org.acme.employeescheduling.domain.CrewSchedule;
import org.acme.employeescheduling.domain.CrewShift;
import org.acme.employeescheduling.domain.Line;
import org.acme.employeescheduling.rest.exception.EmployeeScheduleSolverException;
import org.acme.employeescheduling.service.CrewData;
import org.acme.employeescheduling.service.CrewDataLoader;
import org.acme.employeescheduling.service.CrewRotationGenerator;
import org.acme.employeescheduling.service.CrewScheduleFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Path("crew-schedules")
public class CrewScheduleResource {

    private static final Logger LOGGER = LoggerFactory.getLogger(CrewScheduleResource.class);

    private final SolverManager<CrewSchedule, String> solverManager;
    private final CrewDataLoader loader;
    private final CrewRotationGenerator rotationGenerator;
    private final CrewScheduleFactory scheduleFactory;

    // TODO: save results to the database instead of memory
    private final ConcurrentMap<String, Job> jobIdToJob = new ConcurrentHashMap<>();

    @Inject
    public CrewScheduleResource(SolverManager<CrewSchedule, String> solverManager, CrewDataLoader loader,
            CrewRotationGenerator rotationGenerator, CrewScheduleFactory scheduleFactory) {
        this.solverManager = solverManager;
        this.loader = loader;
        this.rotationGenerator = rotationGenerator;
        this.scheduleFactory = scheduleFactory;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Collection<String> list() {
        return jobIdToJob.keySet();
    }

    /** Loads data from the DB, generates the crew schedule and starts solving. Returns the job ID. */
    @POST
    @Path("solve")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.TEXT_PLAIN)
    public String solve(SolveRequest request) {
        validate(request);
        CrewData data = loader.load(request.department());
        Map<Integer, Crew> crewsById = data.crews().stream()
                .collect(Collectors.toMap(Crew::getId, Function.identity()));

        Map<Line, List<Crew>> crewsByLine = new LinkedHashMap<>();
        for (SolveRequest.LineRotation lr : request.lines()) {
            List<Crew> crews = lr.crewIds().stream().map(id -> {
                Crew crew = crewsById.get(id);
                if (crew == null) {
                    throw new BadRequestException("Unknown crew " + id + " in department " + request.department());
                }
                return crew;
            }).toList();
            crewsByLine.put(new Line(lr.lineId(), lr.lineName()), crews);
        }

        List<CrewShift> crewShifts = rotationGenerator.generate(
                request.from(), request.to(), request.anchorDate(), crewsByLine);
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
