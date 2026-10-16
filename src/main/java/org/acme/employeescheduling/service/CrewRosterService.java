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

import org.acme.employeescheduling.domain.Crew;
import org.acme.employeescheduling.domain.CrewSchedule;
import org.acme.employeescheduling.domain.CrewShift;
import org.acme.employeescheduling.domain.Line;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds a new schedule version: the crew schedule (step 3) for one month plus empty positions for the solver.
 *
 * <p>
 * There is no crew -> line table yet, so crews are given to lines at random:
 * all crews are shuffled and every line takes the next {@link CrewRotationGenerator#CYCLE_LENGTH}.
 * The shuffled order is also the rotation order.
 */
@ApplicationScoped
public class CrewRosterService {

    private static final Logger LOGGER = LoggerFactory.getLogger(CrewRosterService.class);

    private final CrewDataCache crewDataCache;
    private final CrewRotationGenerator rotationGenerator;
    private final CrewScheduleFactory scheduleFactory;
    private final Random random = new Random();

    public CrewRosterService(CrewDataCache crewDataCache, CrewRotationGenerator rotationGenerator,
            CrewScheduleFactory scheduleFactory) {
        this.crewDataCache = crewDataCache;
        this.rotationGenerator = rotationGenerator;
        this.scheduleFactory = scheduleFactory;
    }

    /**
     * Reloads crews and lines from the DB, gives crews to lines at random
     * and generates the schedule for the month of {@code date}.
     */
    public CrewSchedule createVersion(LocalDate date) {
        crewDataCache.refresh();
        Map<Line, List<Crew>> crewsByLine = assignCrewsToLines(crewDataCache.getLines(),
                crewDataCache.getAllCrews(), random);

        YearMonth month = YearMonth.from(date);
        // The rotation starts on the first day of the month, because the crews are new every time.
        List<CrewShift> crewShifts = rotationGenerator.generate(month.atDay(1), month.atEndOfMonth(),
                month.atDay(1), crewsByLine);
        LOGGER.info("Crew schedule for {}: {} lines, {} crew shifts.", month, crewsByLine.size(), crewShifts.size());
        return scheduleFactory.create(crewDataCache.getAll(), crewShifts);
    }

    /** The crew schedule of a version, grouped by line and day for the frontend. */
    public Rotation toRotation(CrewSchedule schedule) {
        List<CrewShift> crewShifts = schedule.getCrewShifts();
        YearMonth month = crewShifts.isEmpty() ? YearMonth.now() : YearMonth.from(crewShifts.get(0).getDate());
        return new Rotation(month, rotationGenerator.toLineSchedules(crewShifts));
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
     * @param lines day crew and night crew for every line and day
     */
    public record Rotation(YearMonth month, List<LineSchedule> lines) {
    }
}
