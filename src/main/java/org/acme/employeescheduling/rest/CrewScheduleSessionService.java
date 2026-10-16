package org.acme.employeescheduling.rest;

import java.time.LocalDate;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.enterprise.event.Observes;
import jakarta.ws.rs.core.Response;

import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;

import ai.timefold.solver.core.api.score.buildin.hardmediumsoft.HardMediumSoftScore;
import ai.timefold.solver.core.api.solver.SolutionManager;
import ai.timefold.solver.core.api.solver.SolutionUpdatePolicy;

import org.acme.employeescheduling.config.SessionHeaderFilter;
import org.acme.employeescheduling.domain.CrewSchedule;
import org.acme.employeescheduling.repository.CrewScheduleRepository;
import org.acme.employeescheduling.repository.CrewScheduleRepository.ScheduleVersion;
import org.acme.employeescheduling.rest.exception.EmployeeScheduleSolverException;
import org.acme.employeescheduling.service.CrewDataCache;
import org.acme.employeescheduling.service.CrewRosterService;
import org.acme.employeescheduling.service.CrewScheduleFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Gives every user (X-Session-Id) their own schedule version, like ScheduleSessionService in
 * food-packaging-quarkus. The default version is generated at startup; a user without a version
 * gets a copy of it, so their changes and solving don't affect anybody else.
 */
@ApplicationScoped
public class CrewScheduleSessionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(CrewScheduleSessionService.class);

    private final CrewScheduleRepository repository;
    private final CrewRosterService rosterService;
    private final CrewScheduleFactory scheduleFactory;
    private final CrewDataCache crewDataCache;
    private final SolutionManager<CrewSchedule, HardMediumSoftScore> solutionManager;

    public CrewScheduleSessionService(CrewScheduleRepository repository, CrewRosterService rosterService,
            CrewScheduleFactory scheduleFactory, CrewDataCache crewDataCache,
            SolutionManager<CrewSchedule, HardMediumSoftScore> solutionManager) {
        this.repository = repository;
        this.rosterService = rosterService;
        this.scheduleFactory = scheduleFactory;
        this.crewDataCache = crewDataCache;
        this.solutionManager = solutionManager;
    }

    /** Observer calls go through the CDI proxy, so the request context (needed by Hibernate) is active. */
    @ActivateRequestContext
    void onStart(@Observes StartupEvent event) {
        if (LaunchMode.current() == LaunchMode.TEST) {
            return;
        }
        try {
            init(SessionHeaderFilter.DEFAULT_SESSION_ID, LocalDate.now());
        } catch (RuntimeException e) {
            // Keep the application running (e.g. the DB is not reachable); POST /crew-schedules/init retries.
            LOGGER.error("Could not generate the default schedule version at startup.", e);
        }
    }

    /** Problem ID for the SolverManager: one solver per session. */
    public String getProblemId(String sessionId) {
        return sessionId;
    }

    /** Generates a new version for the month of {@code date} and makes it the version of the session. */
    public ScheduleVersion init(String sessionId, LocalDate date) {
        CrewSchedule schedule = rosterService.createVersion(date);
        solutionManager.update(schedule, SolutionUpdatePolicy.UPDATE_ALL);
        ScheduleVersion version = repository.create(sessionId, schedule);
        LOGGER.info("Session {}: new schedule version {}.", sessionId, version.id());
        return version;
    }

    /** The version of the session; a session without one gets a copy of the default version. */
    public ScheduleVersion requireVersion(String sessionId) {
        return repository.readOrCreate(sessionId, () -> {
            ScheduleVersion defaultVersion = repository.read(SessionHeaderFilter.DEFAULT_SESSION_ID);
            if (defaultVersion == null) {
                throw new EmployeeScheduleSolverException(sessionId, Response.Status.NOT_FOUND,
                        "No schedule version yet. Press Generate.");
            }
            CrewSchedule copy = scheduleFactory.copyOf(defaultVersion.schedule(), crewDataCache.getAll());
            solutionManager.update(copy, SolutionUpdatePolicy.UPDATE_ALL);
            LOGGER.info("Session {}: copy of the default schedule version.", sessionId);
            return copy;
        });
    }
}
