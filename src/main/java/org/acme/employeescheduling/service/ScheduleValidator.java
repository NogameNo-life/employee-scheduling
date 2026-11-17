package org.acme.employeescheduling.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import org.acme.employeescheduling.domain.Crew;
import org.acme.employeescheduling.domain.CrewSchedule;
import org.acme.employeescheduling.domain.CrewShift;
import org.acme.employeescheduling.domain.Employee;
import org.acme.employeescheduling.domain.ShiftType;

/**
 * Soft rules of a schedule version: warnings only, nothing is blocked (the hard rules are in
 * {@link CrewScheduleEditor}). Calculated for every response, never stored.
 *
 * <p>
 * Rest rule: between the end of one shift and the start of the next one there must be at least
 * {@code app.schedule.min-rest-hours} hours. Checked for every crew (its crew shifts on all lines) and every
 * employee (the crew shifts they really work, {@link CrewShift#getEmployees()}: substitutes count, employees on an
 * absence do not). Shift times are the real ones (PLR_SM). An employee warning is not added for a pair of crew
 * shifts that already has a crew warning (all employees of the crew would repeat it).
 *
 * <p>
 * TODO: the border with the previous month (its last crew shifts are not loaded yet).
 */
@Service
public class ScheduleValidator {

    private final double minRestHours;

    public ScheduleValidator(@Value("${app.schedule.min-rest-hours:12}") double minRestHours) {
        this.minRestHours = minRestHours;
    }

    public List<ScheduleWarning> validate(CrewSchedule schedule) {
        List<ScheduleWarning> warnings = new ArrayList<>();
        Set<String> crewPairs = new HashSet<>();

        Map<Crew, List<CrewShift>> byCrew = new LinkedHashMap<>();
        for (CrewShift shift : schedule.getCrewShifts()) {
            byCrew.computeIfAbsent(shift.getCrew(), crew -> new ArrayList<>()).add(shift);
        }
        byCrew.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(Crew::getId)))
                .forEach(entry -> forEachShortRest(entry.getValue(), (previous, next, hours) -> {
                    crewPairs.add(previous.getId() + "|" + next.getId());
                    warnings.add(new ScheduleWarning(WarningType.CREW_REST, next.getId(), previous.getId(),
                            entry.getKey().getId(), null, hours,
                            "Бригада " + entry.getKey().getName() + ": " + describe(previous, next, hours)));
                }));

        Map<Employee, List<CrewShift>> byEmployee = new LinkedHashMap<>();
        for (CrewShift shift : schedule.getCrewShifts()) {
            shift.getEmployees().forEach(employee ->
                    byEmployee.computeIfAbsent(employee, e -> new ArrayList<>()).add(shift));
        }
        byEmployee.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(Employee::getId)))
                .forEach(entry -> forEachShortRest(entry.getValue(), (previous, next, hours) -> {
                    if (crewPairs.contains(previous.getId() + "|" + next.getId())) {
                        return;
                    }
                    Employee employee = entry.getKey();
                    warnings.add(new ScheduleWarning(WarningType.EMPLOYEE_REST, next.getId(), previous.getId(),
                            next.getCrew().getId(), employee.getId(), hours,
                            employee.getFullName() + " (" + employee.getId() + "): "
                                    + describe(previous, next, hours)));
                }));
        return warnings;
    }

    /** Consecutive crew shifts (by start) with less rest than the minimum between them. */
    private void forEachShortRest(List<CrewShift> shifts, ShortRest action) {
        List<CrewShift> sorted = shifts.stream()
                .sorted(Comparator.comparing(CrewShift::getStart).thenComparing(CrewShift::getId))
                .toList();
        for (int i = 1; i < sorted.size(); i++) {
            CrewShift previous = sorted.get(i - 1);
            CrewShift next = sorted.get(i);
            double hours = Duration.between(previous.getEnd(), next.getStart()).toMinutes() / 60.0;
            if (hours < minRestHours) {
                action.accept(previous, next, hours);
            }
        }
    }

    private String describe(CrewShift previous, CrewShift next, double hours) {
        return "отдых " + format(hours) + " ч между сменами " + describe(previous) + " и " + describe(next)
                + ", нужно не меньше " + format(minRestHours) + " ч.";
    }

    private static String describe(CrewShift shift) {
        return shift.getDate() + " " + (shift.getType() == ShiftType.DAY ? "день" : "ночь")
                + " (" + shift.getLine().getId() + ")";
    }

    private static String format(double hours) {
        return hours == Math.rint(hours) ? String.valueOf((long) hours) : String.valueOf(hours);
    }

    @FunctionalInterface
    private interface ShortRest {
        void accept(CrewShift previous, CrewShift next, double hours);
    }

    public enum WarningType {
        CREW_REST,
        EMPLOYEE_REST
    }

    /**
     * @param crewShiftId         the later crew shift
     * @param previousCrewShiftId the crew shift before it
     * @param crewId              the crew (CREW_REST) or the crew of the later shift (EMPLOYEE_REST)
     * @param employeeId          null for CREW_REST
     * @param restHours           hours between the end of the previous and the start of the later shift
     * @param message             for the user, in Russian
     */
    public record ScheduleWarning(WarningType type, String crewShiftId, String previousCrewShiftId, Integer crewId,
            Integer employeeId, double restHours, String message) {
    }
}
