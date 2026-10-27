package org.acme.employeescheduling.repository;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.acme.employeescheduling.config.SessionHeader;
import org.acme.employeescheduling.domain.CrewSchedule;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Repository;

/**
 * Schedule versions kept in memory, one per user session (X-Session-Id).
 * Same idea as PackagingScheduleRepository in food-packaging-quarkus.
 *
 * <p>
 * Every new version (startup, "Generate", copy of the default) gets a new version id. {@link #update} only
 * changes the version it was given, so a late change never overwrites a newer version.
 */
@Repository 
public class CrewScheduleRepository {

    private static final long MAX_IDLE_MILLIS = 24 * 60 * 60 * 1000L;

    private final ConcurrentHashMap<String, SessionCrewSchedule> sessionSchedules = new ConcurrentHashMap<>();

    /** The version of the session, or null if the session has none. */
    public ScheduleVersion read(String sessionId) {
        SessionCrewSchedule session = sessionSchedules.get(sessionId);
        if (session == null) {
            return null;
        }
        session.updateTimestamp();
        return session.toVersion();
    }

    /** The version of the session; creates it with {@code creator} if the session has none yet. */
    public ScheduleVersion readOrCreate(String sessionId, Supplier<CrewSchedule> creator) {
        SessionCrewSchedule session = sessionSchedules.computeIfAbsent(sessionId,
                id -> new SessionCrewSchedule(newVersionId(), creator.get()));
        session.updateTimestamp();
        return session.toVersion();
    }

    /** Replaces the version of the session with a new one. */
    public ScheduleVersion create(String sessionId, CrewSchedule schedule) {
        SessionCrewSchedule session = new SessionCrewSchedule(newVersionId(), schedule);
        sessionSchedules.put(sessionId, session);
        return session.toVersion();
    }

    /** Stores a new state of version {@code versionId}, unless the session has moved to another version. */
    public boolean update(String sessionId, String versionId, CrewSchedule schedule) {
        SessionCrewSchedule updated = sessionSchedules.computeIfPresent(sessionId,
                (id, current) -> current.versionId.equals(versionId)
                        ? new SessionCrewSchedule(versionId, schedule)
                        : current);
        return updated != null && updated.schedule == schedule;
    }

    /** Removes versions nobody has used for 24 hours. The default version is always kept. */
    @Scheduled(fixedRate = 60 * 60 * 1000L)
    public void cleanupOldSessions() {
        long cutoff = System.currentTimeMillis() - MAX_IDLE_MILLIS;
        sessionSchedules.entrySet().removeIf(entry -> !SessionHeader.DEFAULT_SESSION_ID.equals(entry.getKey())
                && entry.getValue().lastUpdated < cutoff);
    }

    private static String newVersionId() {
        return UUID.randomUUID().toString();
    }

    public record ScheduleVersion(String id, CrewSchedule schedule) {
    }

    private static class SessionCrewSchedule {

        private final String versionId;
        private final CrewSchedule schedule;
        private volatile long lastUpdated;

        private SessionCrewSchedule(String versionId, CrewSchedule schedule) {
            this.versionId = versionId;
            this.schedule = schedule;
            this.lastUpdated = System.currentTimeMillis();
        }

        private void updateTimestamp() {
            this.lastUpdated = System.currentTimeMillis();
        }

        private ScheduleVersion toVersion() {
            return new ScheduleVersion(versionId, schedule);
        }
    }
}
