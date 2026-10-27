package org.acme.employeescheduling.repository;

import static org.assertj.core.api.Assertions.assertThat;

import org.acme.employeescheduling.config.SessionHeader;
import org.acme.employeescheduling.domain.CrewSchedule;
import org.acme.employeescheduling.repository.CrewScheduleRepository.ScheduleVersion;
import org.junit.jupiter.api.Test;

class CrewScheduleRepositoryTest {

    private final CrewScheduleRepository repository = new CrewScheduleRepository();

    @Test
    void everySessionHasItsOwnVersion() {
        CrewSchedule first = schedule();
        CrewSchedule second = schedule();

        repository.create("user1", first);
        repository.create("user2", second);

        assertThat(repository.read("user1").schedule()).isSameAs(first);
        assertThat(repository.read("user2").schedule()).isSameAs(second);
        assertThat(repository.read("user3")).isNull();
    }

    @Test
    void newVersionGetsNewId() {
        ScheduleVersion first = repository.create("user1", schedule());
        ScheduleVersion second = repository.create("user1", schedule());

        assertThat(second.id()).isNotEqualTo(first.id());
    }

    @Test
    void updateChangesOnlyTheSameVersion() {
        ScheduleVersion edited = repository.create("user1", schedule());
        CrewSchedule changed = schedule();
        assertThat(repository.update("user1", edited.id(), changed)).isTrue();
        assertThat(repository.read("user1").schedule()).isSameAs(changed);
        assertThat(repository.read("user1").id()).isEqualTo(edited.id());

        // The user starts a new version, then a late change to the old version arrives
        ScheduleVersion started = repository.create("user1", schedule());
        assertThat(repository.update("user1", edited.id(), schedule())).isFalse();
        assertThat(repository.read("user1").schedule()).isSameAs(started.schedule());
    }

    @Test
    void readOrCreateCreatesOnlyOnce() {
        CrewSchedule created = schedule();

        ScheduleVersion first = repository.readOrCreate("user1", () -> created);
        ScheduleVersion second = repository.readOrCreate("user1", CrewScheduleRepositoryTest::schedule);

        assertThat(second.schedule()).isSameAs(created);
        assertThat(second.id()).isEqualTo(first.id());
    }

    @Test
    void cleanupKeepsTheDefaultVersion() {
        repository.create(SessionHeader.DEFAULT_SESSION_ID, schedule());
        repository.create("user1", schedule());

        repository.cleanupOldSessions();

        // Both were used just now, so nothing is removed; the default is never removed
        assertThat(repository.read(SessionHeader.DEFAULT_SESSION_ID)).isNotNull();
        assertThat(repository.read("user1")).isNotNull();
    }

    private static CrewSchedule schedule() {
        return new CrewSchedule();
    }
}
