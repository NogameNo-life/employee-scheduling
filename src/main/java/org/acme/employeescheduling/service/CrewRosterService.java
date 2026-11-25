package org.acme.employeescheduling.service;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import org.acme.employeescheduling.domain.Crew;
import org.acme.employeescheduling.domain.CrewSchedule;
import org.acme.employeescheduling.domain.CrewShift;
import org.acme.employeescheduling.domain.Employee;
import org.acme.employeescheduling.domain.Line;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds new, empty schedule versions (the user enters the crew schedule, step 3) and the views of a version
 * for the frontend.
 */
@Service
public class CrewRosterService {

    private static final Logger LOGGER = LoggerFactory.getLogger(CrewRosterService.class);

    private final CrewDataCache crewDataCache;
    private final CrewScheduleFactory scheduleFactory;

    public CrewRosterService(CrewDataCache crewDataCache, CrewScheduleFactory scheduleFactory) {
        this.crewDataCache = crewDataCache;
        this.scheduleFactory = scheduleFactory;
    }

    /** Reloads crews and lines from the DB and makes a version for {@code month} without crew shifts. */
    public CrewSchedule createVersion(YearMonth month) {
        crewDataCache.refresh(month);
        CrewSchedule schedule = scheduleFactory.createEmpty(month, crewDataCache.getLines(month),
                crewDataCache.getShifts(), crewDataCache.getAll(month));
        LOGGER.info("Empty crew schedule for {}: {} lines, {} crews.", month, schedule.getLines().size(),
                schedule.getCrews().size());
        return schedule;
    }

    /** The crew schedule of a version, grouped by line and day for the frontend. */
    public Rotation toRotation(CrewSchedule schedule) {
        Map<Line, LineSchedule> byLine = new LinkedHashMap<>();
        LineSchedule.of(schedule.getCrewShifts()).forEach(lineSchedule -> byLine.put(lineSchedule.line(), lineSchedule));
        // Every line of the version, also the ones without crew shifts, so the frontend shows them all
        List<LineSchedule> lines = schedule.getLines().stream()
                .map(line -> byLine.getOrDefault(line, new LineSchedule(line, List.of())))
                .toList();
        return new Rotation(schedule.getMonth(), lines);
    }

    /**
     * Crews of a version for the crew list in the frontend, sorted by number: the lines the crew works on
     * in this version and all its employees, sorted by position and name.
     */
    public List<CrewDetails> toCrewDetails(CrewSchedule schedule) {
        Map<Integer, Set<Line>> linesByCrew = new LinkedHashMap<>();
        for (CrewShift crewShift : schedule.getCrewShifts()) {
            linesByCrew.computeIfAbsent(crewShift.getCrew().getId(), id -> new LinkedHashSet<>())
                    .add(crewShift.getLine());
        }
        Map<Integer, List<Employee>> employeesByCrew = new LinkedHashMap<>();
        for (Employee employee : schedule.getEmployees()) {
            employeesByCrew.computeIfAbsent(employee.getCrew().getId(), id -> new ArrayList<>()).add(employee);
        }
        return schedule.getCrews().stream()
                .sorted(Comparator.comparing(Crew::getId))
                .map(crew -> new CrewDetails(crew,
                        List.copyOf(linesByCrew.getOrDefault(crew.getId(), Set.of())),
                        employeesByCrew.getOrDefault(crew.getId(), List.of()).stream()
                                .sorted(Employee.BY_POSITION_AND_NAME)
                                .toList()))
                .toList();
    }

    /**
     * @param lines day shift and night shift for every line and day
     */
    public record Rotation(YearMonth month, List<LineSchedule> lines) {
    }

    /**
     * @param lines     lines the crew works on in the version (empty: the crew has no crew shifts)
     * @param employees employees of the crew (PLR_TB, changed in the version); each one also has {@code crew}
     */
    public record CrewDetails(Crew crew, List<Line> lines, List<Employee> employees) {
    }
}
