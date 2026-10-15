package org.acme.employeescheduling.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;

import org.acme.employeescheduling.domain.CrewSchedule;
import org.acme.employeescheduling.domain.CrewShift;
import org.acme.employeescheduling.domain.Position;
import org.acme.employeescheduling.domain.Role;

/**
 * Builds the problem for the solver: one {@link Position} per seat,
 * based on the crew composition (PLR_RLBR).
 */
@ApplicationScoped
public class CrewScheduleFactory {

    public CrewSchedule create(CrewData data, List<CrewShift> crewShifts) {
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
        return new CrewSchedule(data.employees(), crewShifts, positions);
    }
}
