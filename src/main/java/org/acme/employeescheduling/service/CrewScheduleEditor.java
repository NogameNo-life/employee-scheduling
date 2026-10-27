package org.acme.employeescheduling.service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

import org.springframework.stereotype.Service;

import org.acme.employeescheduling.domain.Absence;
import org.acme.employeescheduling.domain.AbsenceType;
import org.acme.employeescheduling.domain.Crew;
import org.acme.employeescheduling.domain.CrewChange;
import org.acme.employeescheduling.domain.CrewSchedule;
import org.acme.employeescheduling.domain.CrewShift;
import org.acme.employeescheduling.domain.Employee;
import org.acme.employeescheduling.domain.Line;
import org.acme.employeescheduling.domain.Position;
import org.acme.employeescheduling.domain.Role;
import org.acme.employeescheduling.domain.ShiftType;

/**
 * Manual changes to one schedule version: the crew schedule (which crew works on which line and shift),
 * the default composition of a crew (its employees, also from a date on), absences of employees (vacation, sick
 * leave, dismissal), and the people of one crew shift (absent employees, substitutes and the number of people per
 * role, see {@link CrewShift}). The changes stay in the version (in memory); nothing is written to the DB.
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

    /** Upper limit for the number of people per role in one shift (a typo guard). */
    static final int MAX_PEOPLE_PER_ROLE = 50;

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
            moved.setComposition(current.getComposition());
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

    /** Moves an employee to another crew for the whole month (see {@link #moveEmployee(CrewSchedule, int, int, LocalDate)}). */
    public void moveEmployee(CrewSchedule schedule, int employeeId, int crewId) {
        moveEmployee(schedule, employeeId, crewId, schedule.getMonth().atDay(1));
    }

    /**
     * Moves an employee to another crew from {@code from} on (the default composition of both crews changes for
     * all their shifts from that date). Later crew changes of the employee are replaced. On the 1st of the month
     * it is the crew for the whole month. Their absences from shifts of a crew they are no longer in, and their
     * extra shifts with a crew they are now in, are no longer needed. Where they already work another shift at
     * the same time as a shift of the new crew, they are absent from the new crew's shift. Positions of shifts
     * they no longer work lose this employee.
     */
    public void moveEmployee(CrewSchedule schedule, int employeeId, int crewId, LocalDate from) {
        Employee employee = findEmployee(schedule, employeeId);
        Crew crew = findCrew(schedule, crewId);
        requireInMonth(schedule, from);
        employee.getCrewChanges().removeIf(change -> !change.from().isBefore(from));
        if (from.equals(schedule.getMonth().atDay(1))) {
            employee.setCrew(crew);
        } else {
            employee.getCrewChanges().add(new CrewChange(from, crew));
        }
        afterCrewChange(schedule, employee);
    }

    /** Removes the crew change of the employee on {@code from}: they stay in their crew before that date. */
    public void removeCrewChange(CrewSchedule schedule, int employeeId, LocalDate from) {
        Employee employee = findEmployee(schedule, employeeId);
        if (!employee.getCrewChanges().removeIf(change -> change.from().equals(from))) {
            throw new NoSuchElementException(employee.getFullName() + " has no crew change on " + from + ".");
        }
        afterCrewChange(schedule, employee);
    }

    private static void afterCrewChange(CrewSchedule schedule, Employee employee) {
        // A change to the crew the employee already has is not needed
        Crew previous = employee.getCrew();
        for (var iterator = employee.getCrewChanges().iterator(); iterator.hasNext(); ) {
            CrewChange change = iterator.next();
            if (change.crew().equals(previous)) {
                iterator.remove();
            } else {
                previous = change.crew();
            }
        }
        for (Crew crew : schedule.getCrews()) {
            boolean member = employee.getCrewsOfMonth().contains(crew);
            if (!member) {
                crew.getEmployees().remove(employee);
            } else if (!crew.getEmployees().contains(employee)) {
                crew.getEmployees().add(employee);
            }
        }
        for (CrewShift crewShift : schedule.getCrewShifts()) {
            if (!crewShift.isMember(employee)) {
                crewShift.getAbsentEmployees().remove(employee);
            } else {
                crewShift.getExtraEmployees().remove(employee);
                if (otherShiftAtTheSameTime(schedule, employee, crewShift).isPresent()) {
                    crewShift.getAbsentEmployees().add(employee);
                }
            }
        }
        clearPositionsNotWorked(schedule, employee);
    }

    /**
     * The employee works no shift that starts from {@code from} to {@code to} (both included). A dismissal has
     * no end ({@code to} is ignored). Absences of one employee cannot overlap.
     */
    public Absence addAbsence(CrewSchedule schedule, int employeeId, AbsenceRequest request) {
        Employee employee = findEmployee(schedule, employeeId);
        if (request == null || request.type() == null || request.from() == null) {
            throw new IllegalArgumentException("type and from are required.");
        }
        LocalDate to = request.type() == AbsenceType.DISMISSAL ? null : request.to();
        if (request.type() != AbsenceType.DISMISSAL && to == null) {
            throw new IllegalArgumentException("to is required (only a dismissal has no end).");
        }
        if (to != null && to.isBefore(request.from())) {
            throw new IllegalArgumentException("from " + request.from() + " is after to " + to + ".");
        }
        if (request.from().isAfter(schedule.getMonth().atEndOfMonth())
                || (to != null && to.isBefore(schedule.getMonth().atDay(1)))) {
            throw new IllegalArgumentException("The absence is not in " + schedule.getMonth() + ".");
        }
        Absence absence = new Absence(UUID.randomUUID().toString(), request.type(), request.from(), to);
        employee.getAbsences().stream().filter(absence::overlaps).findFirst().ifPresent(other -> {
            throw new IllegalStateException(employee.getFullName() + " already has an absence (" + other.type()
                    + ") from " + other.from() + (other.to() == null ? "" : " to " + other.to()) + ".");
        });
        employee.getAbsences().add(absence);
        employee.getAbsences().sort((a, b) -> a.from().compareTo(b.from()));
        clearPositionsNotWorked(schedule, employee);
        return absence;
    }

    public void removeAbsence(CrewSchedule schedule, int employeeId, String absenceId) {
        Employee employee = findEmployee(schedule, employeeId);
        if (!employee.getAbsences().removeIf(absence -> absence.id().equals(absenceId))) {
            throw new NoSuchElementException("Absence " + absenceId + " of " + employee.getFullName()
                    + " was not found in this schedule version.");
        }
    }

    /**
     * Sets the number of people per role for this crew shift only (instead of the crew's composition, PLR_RLBR).
     * Null or empty: the shift has the composition of the crew again. The positions of the shift are made again;
     * positions that stay keep their employee.
     */
    public void setShiftComposition(CrewSchedule schedule, String crewShiftId, Map<Integer, Integer> countsByRoleId) {
        CrewShift crewShift = findCrewShift(schedule, crewShiftId);
        Map<Role, Integer> composition = toComposition(schedule, countsByRoleId);
        // The same numbers as the crew's composition: no change for this shift
        crewShift.setComposition(composition == null || composition.equals(crewComposition(schedule, crewShift.getCrew()))
                ? null : composition);
        replacePositions(schedule, crewShift);
    }

    /** Role id -> count as role -> count, without roles of 0 people; null when there are no counts. */
    private static Map<Role, Integer> toComposition(CrewSchedule schedule, Map<Integer, Integer> countsByRoleId) {
        if (countsByRoleId == null || countsByRoleId.isEmpty()) {
            return Map.of();
        }
        Map<Role, Integer> composition = new HashMap<>();
        for (Map.Entry<Integer, Integer> entry : countsByRoleId.entrySet()) {
            Role role = find(schedule.getRoles(), r -> r.getId().equals(entry.getKey()), "Role " + entry.getKey());
            Integer count = entry.getValue();
            if (count == null || count < 0 || count > MAX_PEOPLE_PER_ROLE) {
                throw new IllegalArgumentException("The number of people for role " + role + " must be 0 to "
                        + MAX_PEOPLE_PER_ROLE + ", got: " + count + ".");
            }
            if (count > 0) {
                composition.put(role, count);
            }
        }
        return composition;
    }

    /** Makes the positions of the crew shift again; positions with the same id keep their employee. */
    private void replacePositions(CrewSchedule schedule, CrewShift crewShift) {
        Map<String, Position> oldPositions = new HashMap<>();
        schedule.getPositions().stream().filter(position -> position.getCrewShift().equals(crewShift))
                .forEach(position -> oldPositions.put(position.getId(), position));
        schedule.getPositions().removeIf(position -> position.getCrewShift().equals(crewShift));
        for (Position position : createPositions(schedule, crewShift)) {
            Position old = oldPositions.get(position.getId());
            if (old != null) {
                position.setEmployee(old.getEmployee());
                position.setPinned(old.isPinned());
            }
            schedule.getPositions().add(position);
        }
    }

    private static Map<Role, Integer> crewComposition(CrewSchedule schedule, Crew crew) {
        return schedule.getComposition().getOrDefault(crew.getId(), Map.of());
    }

    /** Positions of shifts the employee does not work (any more) lose this employee. */
    private static void clearPositionsNotWorked(CrewSchedule schedule, Employee employee) {
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
        employee.getAbsenceOn(crewShift.getDate()).ifPresent(absence -> {
            throw new IllegalStateException(employee.getFullName() + " is absent (" + absence.type() + ") on "
                    + crewShift.getDate() + ".");
        });
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
        requireInMonth(schedule, date);
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
                if (extra.getCrewOn(date).equals(crew)) {
                    throw new IllegalStateException(extra.getFullName() + " of crew " + crew
                            + " already works on line " + other.getLine() + " on " + date + ", " + type
                            + " shift (as a substitute). Take them off that shift first.");
                }
            }
        }
        // One crew per line shift, so date, shift and line are enough for a unique id
        return new CrewShift(date + "-" + type + "-" + line.getId(), date, type, line, crew);
    }

    private static void requireInMonth(CrewSchedule schedule, LocalDate date) {
        if (date == null) {
            throw new IllegalArgumentException("The date is required.");
        }
        if (!YearMonth.from(date).equals(schedule.getMonth())) {
            throw new IllegalArgumentException("Date " + date + " is not in " + schedule.getMonth() + ".");
        }
    }

    private void add(CrewSchedule schedule, CrewShift crewShift) {
        schedule.getCrewShifts().add(crewShift);
        schedule.getPositions().addAll(createPositions(schedule, crewShift));
    }

    private List<Position> createPositions(CrewSchedule schedule, CrewShift crewShift) {
        return scheduleFactory.createPositions(crewShift, crewShift.getComposition() != null
                ? crewShift.getComposition() : crewComposition(schedule, crewShift.getCrew()));
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

    /** {@code to} is ignored for a dismissal. */
    public record AbsenceRequest(AbsenceType type, LocalDate from, LocalDate to) {
    }
}
