package org.acme.employeescheduling.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;

import org.acme.employeescheduling.domain.CrewSchedule;
import org.acme.employeescheduling.domain.CrewShift;
import org.acme.employeescheduling.domain.Employee;
import org.acme.employeescheduling.domain.Line;
import org.acme.employeescheduling.domain.Position;
import org.acme.employeescheduling.domain.Role;

/**
 * Builds the problem for the solver: one {@link Position} per seat,
 * based on the crew composition (PLR_RLBR).
 */
@ApplicationScoped
public class CrewScheduleFactory {

    public CrewSchedule create(CrewData data, List<Line> lines, List<CrewShift> crewShifts) {
        List<Position> positions = new ArrayList<>();
        for (CrewShift crewShift : crewShifts) {
            Map<Role, Integer> rolesNeeded = data.composition().getOrDefault(crewShift.getCrew().getId(), Map.of());
            for (Map.Entry<Role, Integer> entry : rolesNeeded.entrySet()) {
                for (int i = 0; i < entry.getValue(); i++) {
                    String id = crewShift.getId() + "-" + entry.getKey().getId() + "-" + i;
                    positions.add(new Position(id, crewShift, entry.getKey()));
                }
            }
        }
        return new CrewSchedule(lines, data.crews(), data.employees(), crewShifts, positions);
    }

    /**
     * A separate version with the same crew shifts and the same assignments as {@code source}.
     * New {@link Position} objects are created, because the solver changes the positions of the version it solves.
     */
    public CrewSchedule copyOf(CrewSchedule source, CrewData data) {
        CrewSchedule copy = create(data, source.getLines(), source.getCrewShifts());
        Map<String, Position> sourcePositions = source.getPositions().stream()
                .collect(Collectors.toMap(Position::getId, Function.identity()));
        Map<Integer, Employee> employeesById = copy.getEmployees().stream()
                .collect(Collectors.toMap(Employee::getId, Function.identity()));
        for (Position position : copy.getPositions()) {
            Position sourcePosition = sourcePositions.get(position.getId());
            if (sourcePosition != null && sourcePosition.getEmployee() != null) {
                position.setEmployee(employeesById.get(sourcePosition.getEmployee().getId()));
                position.setPinned(sourcePosition.isPinned());
            }
        }
        return copy;
    }
}
