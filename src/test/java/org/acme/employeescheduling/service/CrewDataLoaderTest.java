package org.acme.employeescheduling.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.acme.employeescheduling.entity.tabu.BdCrd;
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

    private static BdCrd row(int employeeId, String fullName) {
        BdCrd row = new BdCrd();
        row.employeeId = employeeId;
        row.fullName = fullName;
        return row;
    }
}
