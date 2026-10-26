package org.acme.employeescheduling.service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.function.Predicate;

import org.springframework.stereotype.Service;

import org.acme.employeescheduling.domain.Crew;
import org.acme.employeescheduling.domain.CrewSchedule;
import org.acme.employeescheduling.domain.CrewShift;
import org.acme.employeescheduling.domain.Employee;
import org.acme.employeescheduling.domain.Line;
import org.acme.employeescheduling.domain.Position;
import org.acme.employeescheduling.domain.ShiftType;

/**
 * Manual changes to one schedule version: the crew schedule (which crew works on which line and shift),
 * the default composition of a crew (its employees) and the people of one crew shift (absent employees and
 * substitutes, see {@link CrewShift}). The changes stay in the version (in memory); nothing is written to the DB.
 * Roles are read-only.
 *
 * <p>
 * Errors: {@link NoSuchElementException} (unknown line, crew, employee or crew shift),
 * {@link IllegalArgumentException} (invalid request), {@link IllegalStateException} (conflict with the
 * schedule, e.g. the shift already has a crew, the crew shift is pinned or the employee already works then).
 * The caller must not change the same version from two threads at once.
 */
@Service
public class CrewScheduleEditor {

    private final CrewScheduleFactory scheduleFactory;

    public CrewScheduleEditor(CrewScheduleFactory scheduleFactory) {
        this.scheduleFactory = scheduleFactory;
    }

    /** Puts a crew on a line for one shift. Every line shift has at most one crew. */
    public CrewShift addCrewShift(CrewSchedule schedule, CrewShiftRequest request) {
        CrewShift crewShift = newCrewShift(schedule, request, null);
        add(schedule, crewShift);
        return crewShift;
    }

    /**
     * Moves a crew shift to another line, date or shift, or gives it another crew. Its absent employees and
     * substitutes are kept only if it moves to another line at the same time with the same crew.
     */
    public CrewShift moveCrewShift(CrewSchedule schedule, String crewShiftId, CrewShiftRequest request) {
        CrewShift current = requireUnpinned(schedule, crewShiftId, "moved");
        CrewShift moved = newCrewShift(schedule, request, current);
        if (moved.getId().equals(current.getId()) && moved.getCrew().equals(current.getCrew())) {
            return current; // Dropped at the same place: keep the positions with their employees
        }
        if (moved.getDate().equals(current.getDate()) && moved.getType() == current.getType()
                && moved.getCrew().equals(current.getCrew())) {
            moved.getAbsentEmployees().addAll(current.getAbsentEmployees());
            moved.getExtraEmployees().addAll(current.getExtraEmployees());
        }
        remove(schedule, current);
        add(schedule, moved);
        return moved;
    }

    public void removeCrewShift(CrewSchedule schedule, String crewShiftId) {
        remove(schedule, requireUnpinned(schedule, crewShiftId, "removed"));
    }

    public CrewShift setPinned(CrewSchedule schedule, String crewShiftId, boolean pinned) {
        CrewShift crewShift = findCrewShift(schedule, crewShiftId);
        crewShift.setPinned(pinned);
        return crewShift;
    }

    /**
     * Moves an employee to another crew (the default composition of both crews changes for all their shifts).
     * Their absences from the old crew's shifts and their extra shifts with the new crew are no longer needed.
     * Where they already work another shift at the same time as a shift of the new crew, they are absent from
     * the new crew's shift. Positions of shifts they no longer work lose this employee.
     */
    public void moveEmployee(CrewSchedule schedule, int employeeId, int crewId) {
        Employee employee = findEmployee(schedule, employeeId);
        Crew crew = findCrew(schedule, crewId);
        if (crew.equals(employee.getCrew())) {
            return;
        }
        Crew oldCrew = employee.getCrew();
        oldCrew.getEmployees().remove(employee);
        crew.getEmployees().add(employee);
        employee.setCrew(crew);
        for (CrewShift crewShift : schedule.getCrewShifts()) {
            if (crewShift.getCrew().equals(oldCrew)) {
                crewShift.getAbsentEmployees().remove(employee);
            } else if (crewShift.getCrew().equals(crew)) {
                crewShift.getExtraEmployees().remove(employee);
                if (otherShiftAtTheSameTime(schedule, employee, crewShift).isPresent()) {
                    crewShift.getAbsentEmployees().add(employee);
                }
            }
        }
        for (Position position : schedule.getPositions()) {
            if (employee.equals(position.getEmployee()) && !position.getCrewShift().isWorkedBy(employee)) {
                position.setEmployee(null);
                position.setPinned(false);
            }
        }
    }

    /**
     * The employee does not work this crew shift (only this one): an employee of the crew becomes absent,
     * an extra employee is taken off the shift.
     */
    public void removeEmployeeFromShift(CrewSchedule schedule, String crewShiftId, int employeeId) {
        CrewShift crewShift = findCrewShift(schedule, crewShiftId);
        Employee employee = findEmployee(schedule, employeeId);
        if (!crewShift.isWorkedBy(employee)) {
            throw new IllegalStateException(employee.getFullName() + " does not work the crew shift " + crewShift
                    + ".");
        }
        if (!crewShift.getExtraEmployees().remove(employee)) {
            crewShift.getAbsentEmployees().add(employee);
        }
        for (Position position : schedule.getPositions()) {
            if (position.getCrewShift().equals(crewShift) && employee.equals(position.getEmployee())) {
                position.setEmployee(null);
                position.setPinned(false);
            }
        }
    }

    /**
     * The employee works this crew shift (only this one): an absent employee of the crew works it again,
     * an employee of another crew works it too (e.g. as a substitute). The employee must not work another
     * shift at the same time.
     */
    public void addEmployeeToShift(CrewSchedule schedule, String crewShiftId, int employeeId) {
        CrewShift crewShift = findCrewShift(schedule, crewShiftId);
        Employee employee = findEmployee(schedule, employeeId);
        checkCanJoin(schedule, crewShift, employee);
        if (!crewShift.getAbsentEmployees().remove(employee)) {
            crewShift.getExtraEmployees().add(employee);
        }
    }

    /** {@code employeeId} does not work this crew shift and {@code substituteId} works it instead, in one step. */
    public void replaceEmployee(CrewSchedule schedule, String crewShiftId, int employeeId, int substituteId) {
        if (employeeId == substituteId) {
            throw new IllegalArgumentException("An employee cannot replace themselves.");
        }
        CrewShift crewShift = findCrewShift(schedule, crewShiftId);
        // Check the substitute first, so nothing changes if they cannot work this shift
        checkCanJoin(schedule, crewShift, findEmployee(schedule, substituteId));
        removeEmployeeFromShift(schedule, crewShiftId, employeeId);
        addEmployeeToShift(schedule, crewShiftId, substituteId);
    }

    private static void checkCanJoin(CrewSchedule schedule, CrewShift crewShift, Employee employee) {
        if (crewShift.isWorkedBy(employee)) {
            throw new IllegalStateException(employee.getFullName() + " already works the crew shift " + crewShift
                    + ".");
        }
        otherShiftAtTheSameTime(schedule, employee, crewShift).ifPresent(other -> {
            throw new IllegalStateException(employee.getFullName() + " already works on line " + other.getLine()
                    + " (crew " + other.getCrew() + ") at that time.");
        });
    }

    /** Another crew shift on the same date and shift that the employee works. */
    private static Optional<CrewShift> otherShiftAtTheSameTime(CrewSchedule schedule, Employee employee,
            CrewShift crewShift) {
        return schedule.getCrewShifts().stream()
                .filter(other -> other != crewShift && other.getDate().equals(crewShift.getDate())
                        && other.getType() == crewShift.getType() && other.isWorkedBy(employee))
                .findFirst();
    }

    /**
     * Checks the request and makes the crew shift (not added yet).
     *
     * @param ignored the crew shift that is being moved: it does not block its own new place
     */
    private CrewShift newCrewShift(CrewSchedule schedule, CrewShiftRequest request, CrewShift ignored) {
        if (request == null || request.date() == null || request.type() == null || request.lineId() == null
                || request.crewId() == null) {
            throw new IllegalArgumentException("date, type, lineId and crewId are required.");
        }
        LocalDate date = request.date();
        ShiftType type = request.type();
        if (!YearMonth.from(date).equals(schedule.getMonth())) {
            throw new IllegalArgumentException("Date " + date + " is not in " + schedule.getMonth() + ".");
        }
        String lineId = request.lineId().trim();
        Line line = find(schedule.getLines(), l -> l.getId().equals(lineId), "Line " + lineId);
        Crew crew = findCrew(schedule, request.crewId());

        for (CrewShift other : schedule.getCrewShifts()) {
            if (other == ignored || !other.getDate().equals(date) || other.getType() != type) {
                continue;
            }
            if (other.getLine().equals(line)) {
                throw new IllegalStateException("Line " + line + " already has crew " + other.getCrew()
                        + " on " + date + ", " + type + " shift.");
            }
            if (other.getCrew().equals(crew)) {
                throw new IllegalStateException("Crew " + crew + " already works on line " + other.getLine()
                        + " on " + date + ", " + type + " shift.");
            }
            for (Employee extra : other.getExtraEmployees()) {
                if (extra.getCrew().equals(crew)) {
                    throw new IllegalStateException(extra.getFullName() + " of crew " + crew
                            + " already works on line " + other.getLine() + " on " + date + ", " + type
                            + " shift (as a substitute). Take them off that shift first.");
                }
            }
        }
        // One crew per line shift, so date, shift and line are enough for a unique id
        return new CrewShift(date + "-" + type + "-" + line.getId(), date, type, line, crew);
    }

    private void add(CrewSchedule schedule, CrewShift crewShift) {
        schedule.getCrewShifts().add(crewShift);
        schedule.getPositions().addAll(scheduleFactory.createPositions(crewShift,
                schedule.getComposition().getOrDefault(crewShift.getCrew().getId(), Map.of())));
    }

    private void remove(CrewSchedule schedule, CrewShift crewShift) {
        schedule.getCrewShifts().remove(crewShift);
        schedule.getPositions().removeIf(position -> position.getCrewShift().equals(crewShift));
    }

    private CrewShift requireUnpinned(CrewSchedule schedule, String crewShiftId, String action) {
        CrewShift crewShift = findCrewShift(schedule, crewShiftId);
        if (crewShift.isPinned()) {
            throw new IllegalStateException("Crew shift " + crewShift + " is pinned and cannot be " + action
                    + ". Unpin it first.");
        }
        return crewShift;
    }

    private static CrewShift findCrewShift(CrewSchedule schedule, String crewShiftId) {
        return find(schedule.getCrewShifts(), c -> c.getId().equals(crewShiftId), "Crew shift " + crewShiftId);
    }

    private static Crew findCrew(CrewSchedule schedule, int crewId) {
        return find(schedule.getCrews(), c -> c.getId().equals(crewId), "Crew " + crewId);
    }

    private static Employee findEmployee(CrewSchedule schedule, int employeeId) {
        return find(schedule.getEmployees(), e -> e.getId().equals(employeeId), "Employee " + employeeId);
    }

    private static <T> T find(List<T> items, Predicate<T> predicate, String what) {
        return items.stream().filter(predicate).findFirst()
                .orElseThrow(() -> new NoSuchElementException(what + " was not found in this schedule version."));
    }

    /** Where a crew works: line, date and shift. */
    public record CrewShiftRequest(LocalDate date, ShiftType type, String lineId, Integer crewId) {
    }
}
