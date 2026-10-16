package org.acme.employeescheduling.service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.enterprise.event.Observes;

import com.fasterxml.jackson.annotation.JsonIgnore;

import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;

import org.acme.employeescheduling.domain.Crew;
import org.acme.employeescheduling.domain.CrewShift;
import org.acme.employeescheduling.domain.Line;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The crew schedule (step 3) kept in memory.
 *
 * <p>
 * There is no crew -> line table yet, so crews are given to lines at random:
 * all crews are shuffled and every line takes the next {@link CrewRotationGenerator#CYCLE_LENGTH}.
 * The shuffled order is also the rotation order. Generated when the application starts
 * (for the current month) and again on every {@link #init}.
 */
@ApplicationScoped
public class CrewRosterService {

    private static final Logger LOGGER = LoggerFactory.getLogger(CrewRosterService.class);

    private final CrewDataCache crewDataCache;
    private final CrewRotationGenerator rotationGenerator;
    private final Random random = new Random();

    private volatile Roster roster = new Roster(YearMonth.now(), Map.of(), List.of(), List.of());

    public CrewRosterService(CrewDataCache crewDataCache, CrewRotationGenerator rotationGenerator) {
        this.crewDataCache = crewDataCache;
        this.rotationGenerator = rotationGenerator;
    }

    /** Observer calls go through the CDI proxy, so the request context (needed by Hibernate) is active. */
    @ActivateRequestContext
    void onStart(@Observes StartupEvent event) {
        if (LaunchMode.current() == LaunchMode.TEST) {
            return;
        }
        try {
            init(LocalDate.now());
        } catch (RuntimeException e) {
            // Keep the application running (e.g. the DB is not reachable); POST /crew-schedules/init retries.
            LOGGER.error("Could not generate the crew schedule at startup.", e);
        }
    }

    /**
     * Reloads crews and lines from the DB, gives crews to lines at random
     * and generates the schedule for the month of {@code date}.
     */
    public Roster init(LocalDate date) {
        crewDataCache.refresh();
        Map<Line, List<Crew>> crewsByLine = assignCrewsToLines(crewDataCache.getLines(),
                crewDataCache.getAllCrews(), random);

        YearMonth month = YearMonth.from(date);
        // The rotation starts on the first day of the month, because the crews are new every time.
        List<CrewShift> crewShifts = rotationGenerator.generate(month.atDay(1), month.atEndOfMonth(),
                month.atDay(1), crewsByLine);
        roster = new Roster(month, crewsByLine, crewShifts, rotationGenerator.toLineSchedules(crewShifts));
        LOGGER.info("Crew schedule for {}: {} lines, {} crew shifts.", month, crewsByLine.size(), crewShifts.size());
        return roster;
    }

    public Roster getRoster() {
        return roster;
    }

    /**
     * Shuffles the crews and gives each line the next 4. Lines left without 4 crews get none
     * and crews left over get no line; both are logged.
     */
    static Map<Line, List<Crew>> assignCrewsToLines(List<Line> lines, List<Crew> crews, Random random) {
        List<Crew> shuffled = new ArrayList<>(crews);
        Collections.shuffle(shuffled, random);

        int size = CrewRotationGenerator.CYCLE_LENGTH;
        Map<Line, List<Crew>> crewsByLine = new LinkedHashMap<>();
        int next = 0;
        for (Line line : lines) {
            if (next + size > shuffled.size()) {
                LOGGER.warn("Not enough crews: line {} and the following lines get no crews.", line);
                break;
            }
            crewsByLine.put(line, List.copyOf(shuffled.subList(next, next + size)));
            next += size;
        }
        if (next < shuffled.size()) {
            LOGGER.warn("{} crews have no line: {}", shuffled.size() - next, shuffled.subList(next, shuffled.size()));
        }
        return crewsByLine;
    }

    /**
     * @param crewsByLine 4 crews per line, in rotation order
     * @param crewShifts the same schedule as problem facts for the solver
     * @param lines the schedule for the frontend
     */
    public record Roster(YearMonth month, @JsonIgnore Map<Line, List<Crew>> crewsByLine,
            @JsonIgnore List<CrewShift> crewShifts, List<LineSchedule> lines) {
    }
}
