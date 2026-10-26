package org.acme.employeescheduling.service;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import org.acme.employeescheduling.domain.Crew;
import org.acme.employeescheduling.domain.CrewSchedule;
import org.acme.employeescheduling.domain.CrewShift;
import org.acme.employeescheduling.domain.Employee;
import org.acme.employeescheduling.domain.Line;
import org.acme.employeescheduling.domain.Position;
import org.acme.employeescheduling.domain.Role;

/**
 * Builds schedule versions. Every version has its own copies of the crews, employees, composition,
 * crew shifts and positions, because the user changes them in the editor.
 */
@Service
public class CrewScheduleFactory {

    /** A new version for {@code month} without crew shifts: the user enters the crew schedule. */
    public CrewSchedule createEmpty(YearMonth month, List<Line> lines, CrewData data) {
        return copy(month, lines, data.crews(), data.roles(), data.employees(), data.composition(),
                List.of(), List.of());
    }

    /** A separate version with the same content as {@code source}; changing one does not change the other. */
    public CrewSchedule copyOf(CrewSchedule source) {
        return copy(source.getMonth(), source.getLines(), source.getCrews(), source.getRoles(),
                source.getEmployees(), source.getComposition(), source.getCrewShifts(), source.getPositions());
    }

    /**
     * One {@link Position} per seat of a crew shift: {@code count} positions for every role.
     * Ids are "crew shift id-role id-index", so they stay the same when the composition changes.
     */
    public List<Position> createPositions(CrewShift crewShift, Map<Role, Integer> rolesNeeded) {
        List<Position> positions = new ArrayList<>();
        for (Map.Entry<Role, Integer> entry : rolesNeeded.entrySet()) {
            for (int i = 0; i < entry.getValue(); i++) {
                String id = crewShift.getId() + "-" + entry.getKey().getId() + "-" + i;
                positions.add(new Position(id, crewShift, entry.getKey()));
            }
        }
        return positions;
    }

    private CrewSchedule copy(YearMonth month, List<Line> lines, List<Crew> crews, List<Role> roles,
            List<Employee> employees, Map<Integer, Map<Role, Integer>> composition, List<CrewShift> crewShifts,
            List<Position> positions) {
        Map<Integer, Crew> crewsById = new HashMap<>();
        List<Crew> crewCopies = new ArrayList<>();
        for (Crew crew : crews) {
            Crew copy = new Crew(crew.getId(), crew.getName());
            copy.setStaffCount(crew.getStaffCount());
            crewsById.put(copy.getId(), copy);
            crewCopies.add(copy);
        }

        Map<Integer, Employee> employeesById = new HashMap<>();
        List<Employee> employeeCopies = new ArrayList<>();
        for (Employee employee : employees) {
            Crew crew = crewsById.get(employee.getCrew().getId());
            Employee copy = new Employee(employee.getId(), employee.getFullName(), crew,
                    new HashSet<>(employee.getRoles()));
            copy.setUnavailableDates(new HashSet<>(employee.getUnavailableDates()));
            crew.getEmployees().add(copy);
            employeesById.put(copy.getId(), copy);
            employeeCopies.add(copy);
        }

        Map<Integer, Map<Role, Integer>> compositionCopy = new HashMap<>();
        composition.forEach((crewId, rolesNeeded) -> compositionCopy.put(crewId, new HashMap<>(rolesNeeded)));

        List<CrewShift> crewShiftCopies = new ArrayList<>();
        for (CrewShift crewShift : crewShifts) {
            CrewShift copy = new CrewShift(crewShift.getId(), crewShift.getDate(), crewShift.getType(),
                    crewShift.getLine(), crewsById.get(crewShift.getCrew().getId()));
            copy.setPinned(crewShift.isPinned());
            crewShift.getAbsentEmployees().forEach(e -> copy.getAbsentEmployees().add(employeesById.get(e.getId())));
            crewShift.getExtraEmployees().forEach(e -> copy.getExtraEmployees().add(employeesById.get(e.getId())));
            crewShiftCopies.add(copy);
        }
        Map<String, CrewShift> crewShiftsById = crewShiftCopies.stream()
                .collect(Collectors.toMap(CrewShift::getId, Function.identity()));

        List<Position> positionCopies = new ArrayList<>();
        for (Position position : positions) {
            Position copy = new Position(position.getId(), crewShiftsById.get(position.getCrewShift().getId()),
                    position.getRole());
            if (position.getEmployee() != null) {
                copy.setEmployee(employeesById.get(position.getEmployee().getId()));
            }
            copy.setPinned(position.isPinned());
            positionCopies.add(copy);
        }

        return new CrewSchedule(month, lines, crewCopies, roles, employeeCopies, compositionCopy,
                crewShiftCopies, positionCopies);
    }
}
