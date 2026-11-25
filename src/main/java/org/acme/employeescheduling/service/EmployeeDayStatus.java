package org.acme.employeescheduling.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.acme.employeescheduling.domain.Absence;
import org.acme.employeescheduling.domain.CrewSchedule;
import org.acme.employeescheduling.domain.CrewShift;
import org.acme.employeescheduling.domain.Employee;
import org.acme.employeescheduling.domain.ShiftType;

/**
 * What an employee does on a date of one schedule version: works (Д / Н), is absent (Б, ОТ, У, ПР), otherwise
 * О (sleep day after a night shift) or В (day off). Pure: built once from a {@link CrewSchedule} (one pass over the
 * crew shifts), then read per employee and date. The schedule must not change while it is used.
 *
 * <p>
 * The same rule is in app.js ({@code employeeCell}). TODO: let the frontend use this one (one source of the rule).
 */
public final class EmployeeDayStatus {

    /** The code of a day, with its letter in the UI and the export. */
    public enum Code {
        DAY("Д"),
        NIGHT("Н"),
        /** Day and night shift on the same date (possible, the rest rule warns). */
        DAY_AND_NIGHT("ДН"),
        SLEEP("О"),
        OFF("В"),
        SICK_LEAVE("Б"),
        VACATION("ОТ"),
        DISMISSAL("У"),
        OTHER_ABSENCE("ПР");

        private final String letter;

        Code(String letter) {
            this.letter = letter;
        }

        public String getLetter() {
            return letter;
        }
    }

    /**
     * @param code       what the employee does on the date
     * @param worked     the crew shifts the employee works on the date, DAY first
     * @param substitute the crew shifts of {@code worked} worked as a substitute (extra employee)
     * @param takenOff   crew shifts of their crew the employee was taken off on that date (they do not work them)
     * @param absence    the absence on the date, or null
     */
    public record DayStatus(Code code, List<CrewShift> worked, List<CrewShift> substitute, List<CrewShift> takenOff,
            Absence absence) {

        /** Hours of the worked crew shifts, from their real times. */
        public double hours() {
            return worked.stream().mapToDouble(CrewShift::getHours).sum();
        }
    }

    private record Key(Employee employee, LocalDate date) {
    }

    private final Map<Key, List<CrewShift>> worked = new HashMap<>();
    private final Map<Key, List<CrewShift>> takenOff = new HashMap<>();

    private EmployeeDayStatus(CrewSchedule schedule) {
        for (CrewShift shift : schedule.getCrewShifts()) {
            for (Employee employee : shift.getEmployees()) {
                worked.computeIfAbsent(new Key(employee, shift.getDate()), key -> new ArrayList<>()).add(shift);
            }
            for (Employee employee : shift.getAbsentEmployees()) {
                if (shift.isMember(employee)) {
                    takenOff.computeIfAbsent(new Key(employee, shift.getDate()), key -> new ArrayList<>()).add(shift);
                }
            }
        }
        worked.values().forEach(shifts -> shifts.sort(Comparator.comparing(CrewShift::getType)));
    }

    public static EmployeeDayStatus of(CrewSchedule schedule) {
        return new EmployeeDayStatus(schedule);
    }

    /**
     * The status of the employee on the date. A sleep day needs a night shift on the day before; for the 1st of the
     * month the previous month is not known yet (TODO, like the rest rule), so it is a day off.
     */
    public DayStatus get(Employee employee, LocalDate date) {
        List<CrewShift> shifts = worked.getOrDefault(new Key(employee, date), List.of());
        List<CrewShift> off = takenOff.getOrDefault(new Key(employee, date), List.of());
        if (!shifts.isEmpty()) {
            boolean day = shifts.stream().anyMatch(shift -> shift.getType() == ShiftType.DAY);
            boolean night = shifts.stream().anyMatch(shift -> shift.getType() == ShiftType.NIGHT);
            Code code = day && night ? Code.DAY_AND_NIGHT : day ? Code.DAY : Code.NIGHT;
            List<CrewShift> substitute = shifts.stream()
                    .filter(shift -> shift.getExtraEmployees().contains(employee)).toList();
            return new DayStatus(code, List.copyOf(shifts), substitute, List.copyOf(off), null);
        }
        Absence absence = employee.getAbsenceOn(date).orElse(null);
        if (absence != null) {
            Code code = switch (absence.type()) {
                case SICK_LEAVE -> Code.SICK_LEAVE;
                case VACATION -> Code.VACATION;
                case DISMISSAL -> Code.DISMISSAL;
                default -> Code.OTHER_ABSENCE;
            };
            return new DayStatus(code, List.of(), List.of(), List.copyOf(off), absence);
        }
        boolean afterNight = worked.getOrDefault(new Key(employee, date.minusDays(1)), List.of()).stream()
                .anyMatch(shift -> shift.getType() == ShiftType.NIGHT);
        return new DayStatus(afterNight ? Code.SLEEP : Code.OFF, List.of(), List.of(), List.copyOf(off), null);
    }
}
