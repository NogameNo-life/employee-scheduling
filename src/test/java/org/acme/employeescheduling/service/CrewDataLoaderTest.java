package org.acme.employeescheduling.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.acme.employeescheduling.domain.Crew;
import org.acme.employeescheduling.entity.plr.PlrBr;
import org.acme.employeescheduling.entity.tabu.BdCard;
import org.junit.jupiter.api.Test;

class CrewDataLoaderTest {

    @Test
    void fullNameIsOneTrimmedString() {
        assertThat(CrewDataLoader.fullName(row(1, "Иванов   Иван Иванович                    ")))
                .isEqualTo("Иванов Иван Иванович");
    }

    @Test
    void personnelNumberWhenThereIsNoName() {
        assertThat(CrewDataLoader.fullName(row(1234, "   "))).isEqualTo("1234");
        assertThat(CrewDataLoader.fullName(row(1234, null))).isEqualTo("1234");
    }

    @Test
    void crewHasATrimmedNameAndTheStaffCountOfNtb() {
        PlrBr row = new PlrBr();
        row.crewId = 5;
        row.crewName = "Line2/1            ";
        row.staffCount = 5;

        Crew crew = CrewDataLoader.toCrew(row);

        assertThat(crew.getId()).isEqualTo(5);
        assertThat(crew.getName()).isEqualTo("Line2/1");
        assertThat(crew.getStaffCount()).isEqualTo(5);
    }

    @Test
    void crewWithoutNtbNeedsNoPeople() {
        PlrBr row = new PlrBr();
        row.crewId = 5;

        Crew crew = CrewDataLoader.toCrew(row);

        assertThat(crew.getName()).isEqualTo("5");
        assertThat(crew.getStaffCount()).isZero();
    }

    private static BdCard row(int employeeId, String fullName) {
        BdCard row = new BdCard();
        row.employeeId = employeeId;
        row.fullName = fullName;
        return row;
    }
}
