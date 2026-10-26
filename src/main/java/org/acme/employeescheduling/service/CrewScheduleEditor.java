package org.acme.employeescheduling.service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import org.acme.employeescheduling.domain.Crew;
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
     * Sets how many people of {@code roleId} the crew needs per shift (0 removes the role from the crew),
     * and updates the positions of all its crew shifts (pinned ones too). Positions that still exist keep
     * their employee.
     */
    public void setRoleCount(CrewSchedule schedule, int crewId, int roleId, int count) {
        if (count < 0) {
            throw new IllegalArgumentException("The number of people must be 0 or more, got: " + count);
        }
        Crew crew = findCrew(schedule, crewId);
        Role role = find(schedule.getRoles(), r -> r.getId().equals(roleId), "Role " + roleId);

        Map<Role, Integer> rolesNeeded = schedule.getComposition().computeIfAbsent(crewId, id -> new HashMap<>());
        if (count == 0) {
            rolesNeeded.remove(role);
        } else {
            rolesNeeded.put(role, count);
        }
        crew.setStaffCount(rolesNeeded.values().stream().mapToInt(Integer::intValue).sum());

        Map<String, Position> existing = schedule.getPositions().stream()
                .filter(position -> position.getCrew().equals(crew))
                .collect(Collectors.toMap(Position::getId, Function.identity()));
        List<Position> updated = schedule.getCrewShifts().stream()
                .filter(crewShift -> crewShift.getCrew().equals(crew))
                .flatMap(crewShift -> scheduleFactory.createPositions(crewShift, rolesNeeded).stream())
                .map(position -> existing.getOrDefault(position.getId(), position))
                .toList();
        schedule.getPositions().removeIf(position -> position.getCrew().equals(crew));
        schedule.getPositions().addAll(updated);
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

    private static <T> T find(List<T> items, Predicate<T> predicate, String what) {
        return items.stream().filter(predicate).findFirst()
                .orElseThrow(() -> new NoSuchElementException(what + " was not found in this schedule version."));
    }

    /** Where a crew works: line, date and shift. */
    public record CrewShiftRequest(LocalDate date, ShiftType type, String lineId, Integer crewId) {
    }
}
