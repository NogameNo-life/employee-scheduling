package org.acme.employeescheduling.service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import org.acme.employeescheduling.domain.Crew;
import org.acme.employeescheduling.domain.CrewComposition;
import org.acme.employeescheduling.domain.CrewSchedule;
import org.acme.employeescheduling.domain.CrewShift;
import org.acme.employeescheduling.domain.Employee;
import org.acme.employeescheduling.domain.Line;
import org.acme.employeescheduling.domain.Position;
import org.acme.employeescheduling.domain.Role;
import org.acme.employeescheduling.domain.ShiftType;

/**
 * Manual changes to one schedule version: the crew schedule (which crew works on which line and shift)
 * and the crew composition (people per role and shift, employees of a crew). The changes stay in the
 * version (in memory); nothing is written to the DB.
 *
 * <p>
 * Errors: {@link NoSuchElementException} (unknown line, crew, role, employee or crew shift),
 * {@link IllegalArgumentException} (invalid request), {@link IllegalStateException} (conflict with the
 * schedule, e.g. the shift already has a crew or the crew shift is pinned).
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

    /** Moves a crew shift to another line, date or shift, or gives it another crew. */
    public CrewShift moveCrewShift(CrewSchedule schedule, String crewShiftId, CrewShiftRequest request) {
        CrewShift current = requireUnpinned(schedule, crewShiftId, "moved");
        CrewShift moved = newCrewShift(schedule, request, current);
        if (moved.getId().equals(current.getId()) && moved.getCrew().equals(current.getCrew())) {
            return current; // Dropped at the same place: keep the positions with their employees
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
     * Sets how many people of {@code roleId} the crew needs per shift from {@code validFrom} (null: the first day
     * of the month) until the next composition version; 0 removes the role. Only the crew shifts in that period
     * get other positions (pinned ones too); positions that still exist keep their employee.
     */
    public void setRoleCount(CrewSchedule schedule, int crewId, int roleId, int count, LocalDate validFrom) {
        if (count < 0) {
            throw new IllegalArgumentException("The number of people must be 0 or more, got: " + count);
        }
        Crew crew = findCrew(schedule, crewId);
        Role role = find(schedule.getRoles(), r -> r.getId().equals(roleId), "Role " + roleId);
        LocalDate from = validFrom == null ? schedule.getMonth().atDay(1) : validFrom;
        if (!YearMonth.from(from).equals(schedule.getMonth())) {
            throw new IllegalArgumentException("Date " + from + " is not in " + schedule.getMonth() + ".");
        }

        schedule.getComposition().computeIfAbsent(crewId, id -> new CrewComposition()).setRoleCount(from, role, count);
        updatePositions(schedule, crew);
    }

    /**
     * Removes the composition version of the crew from {@code validFrom}: from that date on, the previous version
     * is valid again. The version from the first day of the month (from the DB) cannot be removed.
     */
    public void removeCompositionVersion(CrewSchedule schedule, int crewId, LocalDate validFrom) {
        Crew crew = findCrew(schedule, crewId);
        if (schedule.getMonth().atDay(1).equals(validFrom)) {
            throw new IllegalStateException("The composition from the first day of the month cannot be removed;"
                    + " change its numbers instead.");
        }
        CrewComposition composition = schedule.getComposition().get(crewId);
        if (composition == null || !composition.removeVersion(validFrom)) {
            throw new NoSuchElementException("Crew " + crew + " has no composition from " + validFrom + ".");
        }
        updatePositions(schedule, crew);
    }

    /**
     * Moves an employee to another crew. Positions of the old crew lose this employee
     * (an employee works only in their own crew).
     */
    public void moveEmployee(CrewSchedule schedule, int employeeId, int crewId) {
        Employee employee = find(schedule.getEmployees(), e -> e.getId().equals(employeeId),
                "Employee " + employeeId);
        Crew crew = findCrew(schedule, crewId);
        if (crew.equals(employee.getCrew())) {
            return;
        }
        employee.getCrew().getEmployees().remove(employee);
        crew.getEmployees().add(employee);
        employee.setCrew(crew);
        for (Position position : schedule.getPositions()) {
            if (employee.equals(position.getEmployee()) && !position.getCrew().equals(crew)) {
                position.setEmployee(null);
                position.setPinned(false);
            }
        }
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
        }
        // One crew per line shift, so date, shift and line are enough for a unique id
        return new CrewShift(date + "-" + type + "-" + line.getId(), date, type, line, crew);
    }

    private void add(CrewSchedule schedule, CrewShift crewShift) {
        schedule.getCrewShifts().add(crewShift);
        schedule.getPositions().addAll(scheduleFactory.createPositions(crewShift, rolesNeeded(schedule, crewShift)));
    }

    /** The composition valid on the date of the crew shift. */
    private static Map<Role, Integer> rolesNeeded(CrewSchedule schedule, CrewShift crewShift) {
        CrewComposition composition = schedule.getComposition().get(crewShift.getCrew().getId());
        return composition == null ? Map.of() : composition.rolesNeededOn(crewShift.getDate());
    }

    /**
     * After a composition change: {@link Crew#getStaffCount()} (first day of the month) and the positions of the
     * crew's shifts, each from the composition valid on its date. Positions that still exist (same id) are kept
     * with their employee, so crew shifts outside the changed period do not change.
     */
    private void updatePositions(CrewSchedule schedule, Crew crew) {
        CrewComposition composition = schedule.getComposition().get(crew.getId());
        crew.setStaffCount(composition == null ? 0 : composition.staffCountOn(schedule.getMonth().atDay(1)));

        Map<String, Position> existing = schedule.getPositions().stream()
                .filter(position -> position.getCrew().equals(crew))
                .collect(Collectors.toMap(Position::getId, Function.identity()));
        List<Position> updated = schedule.getCrewShifts().stream()
                .filter(crewShift -> crewShift.getCrew().equals(crew))
                .flatMap(crewShift -> scheduleFactory.createPositions(crewShift, rolesNeeded(schedule, crewShift))
                        .stream())
                .map(position -> existing.getOrDefault(position.getId(), position))
                .toList();
        schedule.getPositions().removeIf(position -> position.getCrew().equals(crew));
        schedule.getPositions().addAll(updated);
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

    private static <T> T find(List<T> items, Predicate<T> predicate, String what) {
        return items.stream().filter(predicate).findFirst()
                .orElseThrow(() -> new NoSuchElementException(what + " was not found in this schedule version."));
    }

    /** Where a crew works: line, date and shift. */
    public record CrewShiftRequest(LocalDate date, ShiftType type, String lineId, Integer crewId) {
    }
}
