package org.acme.employeescheduling.service;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import org.acme.employeescheduling.domain.Crew;
import org.acme.employeescheduling.domain.CrewChange;
import org.acme.employeescheduling.domain.CrewSchedule;
import org.acme.employeescheduling.domain.CrewShift;
import org.acme.employeescheduling.domain.Employee;
import org.acme.employeescheduling.domain.Line;
import org.acme.employeescheduling.domain.ShiftTime;

/**
 * Builds schedule versions. Every version has its own copies of the crews, employees and crew shifts,
 * because the user changes them in the editor.
 */
@Service
public class CrewScheduleFactory {

    /** A new version for {@code month} without crew shifts: the user enters the crew schedule. */
    public CrewSchedule createEmpty(YearMonth month, List<Line> lines, List<ShiftTime> shifts, CrewData data) {
        return copy(month, lines, shifts, data, List.of());
    }

    /** A separate version with the same content as {@code source}; changing one does not change the other. */
    public CrewSchedule copyOf(CrewSchedule source) {
        CrewData data = new CrewData(source.getCrews(), source.getEmployees());
        return copy(source.getMonth(), source.getLines(), source.getShifts(), data, source.getCrewShifts());
    }

    private CrewSchedule copy(YearMonth month, List<Line> lines, List<ShiftTime> shifts, CrewData data,
            List<CrewShift> crewShifts) {
        Map<Integer, Crew> crewsById = new HashMap<>();
        List<Crew> crewCopies = new ArrayList<>();
        for (Crew crew : data.crews()) {
            Crew copy = new Crew(crew.getId(), crew.getName());
            copy.setStaffCount(crew.getStaffCount());
            copy.setLineId(crew.getLineId());
            crewsById.put(copy.getId(), copy);
            crewCopies.add(copy);
        }

        Map<Integer, Employee> employeesById = new HashMap<>();
        List<Employee> employeeCopies = new ArrayList<>();
        for (Employee employee : data.employees()) {
            Crew crew = crewsById.get(employee.getCrew().getId());
            Employee copy = new Employee(employee.getId(), employee.getFullName(), crew);
            copy.setPosition(employee.getPosition());
            employee.getCrewChanges().forEach(change -> copy.getCrewChanges()
                    .add(new CrewChange(change.from(), crewsById.get(change.crew().getId()))));
            copy.getAbsences().addAll(employee.getAbsences());
            // A crew has every employee who is in it on some day of the month
            copy.getCrewsOfMonth().forEach(crewOfMonth -> crewOfMonth.getEmployees().add(copy));
            employeesById.put(copy.getId(), copy);
            employeeCopies.add(copy);
        }

        List<CrewShift> crewShiftCopies = new ArrayList<>();
        for (CrewShift crewShift : crewShifts) {
            CrewShift copy = new CrewShift(crewShift.getId(), crewShift.getDate(), crewShift.getTime(),
                    crewShift.getLine(), crewsById.get(crewShift.getCrew().getId()));
            copy.setPinned(crewShift.isPinned());
            crewShift.getAbsentEmployees().forEach(e -> copy.getAbsentEmployees().add(employeesById.get(e.getId())));
            crewShift.getExtraEmployees().forEach(e -> copy.getExtraEmployees().add(employeesById.get(e.getId())));
            crewShiftCopies.add(copy);
        }

        return new CrewSchedule(month, lines, shifts, crewCopies, employeeCopies, crewShiftCopies);
    }
}
