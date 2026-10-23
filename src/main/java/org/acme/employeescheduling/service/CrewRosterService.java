package org.acme.employeescheduling.service;

import java.time.Clock;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.springframework.stereotype.Service;

import org.acme.employeescheduling.domain.Crew;
import org.acme.employeescheduling.domain.CrewSchedule;
import org.acme.employeescheduling.domain.CrewShift;
import org.acme.employeescheduling.domain.Line;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds a new schedule version: the crew schedule (step 3) for one month plus empty positions for the editor.
 *
 * <p>
 * There is no crew -> line table yet, so crews are given to lines at random:
 * all crews are shuffled and every line takes the next {@link CrewRotationGenerator#CYCLE_LENGTH}.
 * The shuffled order is also the rotation order.
 */
@Service
public class CrewRosterService {

    private static final Logger LOGGER = LoggerFactory.getLogger(CrewRosterService.class);

    private final CrewDataCache crewDataCache;
    private final CrewRotationGenerator rotationGenerator;
    private final CrewScheduleFactory scheduleFactory;
    private final Clock clock;
    private final Random random = new Random();

    public CrewRosterService(CrewDataCache crewDataCache, CrewRotationGenerator rotationGenerator,
            CrewScheduleFactory scheduleFactory, Clock clock) {
        this.crewDataCache = crewDataCache;
        this.rotationGenerator = rotationGenerator;
        this.scheduleFactory = scheduleFactory;
        this.clock = clock;
    }

    /**
     * Reloads crews and lines from the DB, gives crews to lines at random
     * and generates the schedule for {@code month}.
     */
    public CrewSchedule createVersion(YearMonth month) {
        crewDataCache.refresh();
        Map<Line, List<Crew>> crewsByLine = assignCrewsToLines(crewDataCache.getLines(),
                crewDataCache.getAllCrews(), random);

        // The rotation starts on the first day of the month, because the crews are new every time.
        List<CrewShift> crewShifts = rotationGenerator.generate(month.atDay(1), month.atEndOfMonth(),
                month.atDay(1), crewsByLine);
        LOGGER.info("Crew schedule for {}: {} lines, {} crew shifts.", month, crewsByLine.size(), crewShifts.size());
        return scheduleFactory.create(crewDataCache.getAll(), crewDataCache.getLines(), crewShifts);
    }

    /** The crew schedule of a version, grouped by line and day for the frontend. */
    public Rotation toRotation(CrewSchedule schedule) {
        List<CrewShift> crewShifts = schedule.getCrewShifts();
        YearMonth month = crewShifts.isEmpty() ? YearMonth.now(clock) : YearMonth.from(crewShifts.get(0).getDate());
        Map<Line, LineSchedule> byLine = new LinkedHashMap<>();
        rotationGenerator.toLineSchedules(crewShifts).forEach(lineSchedule -> byLine.put(lineSchedule.line(), lineSchedule));
        // Every line of the version, also the ones without crews (not enough crews), so the frontend shows them all
        List<LineSchedule> lines = schedule.getLines().stream()
                .map(line -> byLine.getOrDefault(line, new LineSchedule(line, List.of())))
                .toList();
        return new Rotation(month, lines);
    }

    /**
     * Shuffles the crews and deals them out to the lines one by one (round-robin), so every line gets crews
     * and, while there are no more crews than lines, every crew works on a different line.
     * A line gets at most {@link CrewRotationGenerator#CYCLE_LENGTH} crews; crews left over get no line.
     */
    static Map<Line, List<Crew>> assignCrewsToLines(List<Line> lines, List<Crew> crews, Random random) {
        if (lines.isEmpty()) {
            LOGGER.warn("There are no lines, so no crew gets a line.");
            return Map.of();
        }
        List<Crew> shuffled = new ArrayList<>(crews);
        Collections.shuffle(shuffled, random);

        int capacity = lines.size() * CrewRotationGenerator.CYCLE_LENGTH;
        Map<Line, List<Crew>> crewsByLine = new LinkedHashMap<>();
        for (int i = 0; i < Math.min(shuffled.size(), capacity); i++) {
            Line line = lines.get(i % lines.size());
            crewsByLine.computeIfAbsent(line, l -> new ArrayList<>()).add(shuffled.get(i));
        }
        if (shuffled.size() > capacity) {
            LOGGER.warn("{} crews have no line (at most {} per line): {}", shuffled.size() - capacity,
                    CrewRotationGenerator.CYCLE_LENGTH, shuffled.subList(capacity, shuffled.size()));
        }
        crewsByLine.forEach((line, lineCrews) -> {
            if (lineCrews.size() < CrewRotationGenerator.CYCLE_LENGTH) {
                LOGGER.warn("Line {} has only {} crews: some of its shifts have no crew.", line, lineCrews.size());
            }
        });
        crewsByLine.replaceAll((line, lineCrews) -> List.copyOf(lineCrews));
        return crewsByLine;
    }

    /**
     * @param lines day crew and night crew for every line and day
     */
    public record Rotation(YearMonth month, List<LineSchedule> lines) {
    }
}
