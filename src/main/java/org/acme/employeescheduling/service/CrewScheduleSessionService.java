package org.acme.employeescheduling.service;

import java.time.LocalDate;

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
 * food-packaging-quarkus. The default version is generated at startup; a user without a version
 * gets a copy of it, so their changes don't affect anybody else.
 */
@Service
public class CrewScheduleSessionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(CrewScheduleSessionService.class);

    private final CrewScheduleRepository repository;
    private final CrewRosterService rosterService;
    private final CrewScheduleFactory scheduleFactory;
    private final CrewDataCache crewDataCache;

    public CrewScheduleSessionService(CrewScheduleRepository repository, CrewRosterService rosterService,
            CrewScheduleFactory scheduleFactory, CrewDataCache crewDataCache) {
        this.repository = repository;
        this.rosterService = rosterService;
        this.scheduleFactory = scheduleFactory;
        this.crewDataCache = crewDataCache;
    }

    @EventListener(ApplicationReadyEvent.class)
    void onStart() {
        try {
            init(SessionHeader.DEFAULT_SESSION_ID, LocalDate.now());
        } catch (RuntimeException e) {
            // Keep the application running (e.g. the DB is not reachable); POST /crew-schedules/init retries.
            LOGGER.error("Could not generate the default schedule version at startup.", e);
        }
    }

    /** Generates a new version for the month of {@code date} and makes it the version of the session. */
    public ScheduleVersion init(String sessionId, LocalDate date) {
        CrewSchedule schedule = rosterService.createVersion(date);
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
                        "No schedule version yet. Press Generate.");
            }
            CrewSchedule copy = scheduleFactory.copyOf(defaultVersion.schedule(), crewDataCache.getAll());
            LOGGER.info("Session {}: copy of the default schedule version.", sessionId);
            return copy;
        });
    }
}
