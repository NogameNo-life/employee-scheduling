package org.acme.employeescheduling.service;

import java.time.Clock;
import java.time.YearMonth;
import java.util.NoSuchElementException;
import java.util.function.Consumer;

import org.acme.employeescheduling.config.SessionHeader;
import org.acme.employeescheduling.domain.CrewSchedule;
import org.acme.employeescheduling.exception.ScheduleException;
import org.acme.employeescheduling.repository.CrewScheduleRepository;
import org.acme.employeescheduling.repository.CrewScheduleRepository.ScheduleVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Gives every user (X-Session-Id) their own schedule version, like ScheduleSessionService in
 * food-packaging-quarkus. The default version (current month, no crew shifts) is made at startup;
 * a user without a version gets a copy of it, so their changes don't affect anybody else.
 */
@Service
public class CrewScheduleSessionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(CrewScheduleSessionService.class);

    private final CrewScheduleRepository repository;
    private final CrewRosterService rosterService;
    private final CrewScheduleFactory scheduleFactory;
    private final CrewDataCache crewDataCache;
    private final Clock clock;

    public CrewScheduleSessionService(CrewScheduleRepository repository, CrewRosterService rosterService,
            CrewScheduleFactory scheduleFactory, CrewDataCache crewDataCache, Clock clock) {
        this.repository = repository;
        this.rosterService = rosterService;
        this.scheduleFactory = scheduleFactory;
        this.crewDataCache = crewDataCache;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    void onStart() {
        try {
            // Lines first and on their own: the frontend shows them even if the crews cannot be loaded
            crewDataCache.refreshLines();
        } catch (RuntimeException e) {
            LOGGER.error("Could not load the lines at startup.", e);
        }
        try {
            init(SessionHeader.DEFAULT_SESSION_ID, YearMonth.now(clock));
        } catch (RuntimeException e) {
            // Keep the application running (e.g. the DB is not reachable); POST /crew-schedules/init retries.
            LOGGER.error("Could not create the default schedule version at startup.", e);
        }
    }

    /** Makes a new version for {@code month} without crew shifts and makes it the version of the session. */
    public ScheduleVersion init(String sessionId, YearMonth month) {
        CrewSchedule schedule = rosterService.createVersion(month);
        ScheduleVersion version = repository.create(sessionId, schedule);
        LOGGER.info("Session {}: new schedule version {}.", sessionId, version.id());
        return version;
    }

    /** The version of the session; a session without one gets a copy of the default version. */
    public ScheduleVersion requireVersion(String sessionId) {
        return repository.readOrCreate(sessionId, () -> {
            ScheduleVersion defaultVersion = repository.read(SessionHeader.DEFAULT_SESSION_ID);
            if (defaultVersion == null) {
                throw new ScheduleException(sessionId, HttpStatus.NOT_FOUND,
                        "No schedule version yet. Press New empty month.");
            }
            CrewSchedule copy;
            synchronized (defaultVersion.schedule()) {
                copy = scheduleFactory.copyOf(defaultVersion.schedule());
            }
            LOGGER.info("Session {}: copy of the default schedule version.", sessionId);
            return copy;
        });
    }

    /**
     * Changes the version of the session in place (see {@link CrewScheduleEditor}). Changes of one version
     * run one after another. Errors of the change become a {@link ScheduleException}: unknown element 404,
     * invalid request 400, conflict with the schedule 409.
     */
    public ScheduleVersion edit(String sessionId, Consumer<CrewSchedule> change) {
        ScheduleVersion version = requireVersion(sessionId);
        synchronized (version.schedule()) {
            try {
                change.accept(version.schedule());
            } catch (NoSuchElementException e) {
                throw new ScheduleException(sessionId, HttpStatus.NOT_FOUND, e.getMessage());
            } catch (IllegalArgumentException e) {
                throw new ScheduleException(sessionId, HttpStatus.BAD_REQUEST, e.getMessage());
            } catch (IllegalStateException e) {
                throw new ScheduleException(sessionId, HttpStatus.CONFLICT, e.getMessage());
            }
        }
        return version;
    }
}
