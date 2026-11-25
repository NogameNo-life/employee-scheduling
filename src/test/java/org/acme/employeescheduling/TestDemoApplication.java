package org.acme.employeescheduling;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.acme.employeescheduling.domain.Crew;
import org.acme.employeescheduling.domain.Employee;
import org.acme.employeescheduling.domain.Line;
import org.acme.employeescheduling.domain.ShiftTime;
import org.acme.employeescheduling.domain.ShiftType;
import org.acme.employeescheduling.service.CrewData;
import org.acme.employeescheduling.service.CrewDataCache;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Runs the application with DEMO data instead of the databases, to try the UI without MES2 / TABU2:
 * <pre>
 * mvn spring-boot:test-run
 * </pre>
 * Then open http://localhost:8080. 6 lines (БерЦГС1 ... БерЦГС6), 4 crews per line ("Линия1/1" ...), 6 or 5 people
 * per shift. Crew "Линия4/2" has one employee less than it needs (a shift deficit). The names are made up.
 * Only in the test sources: it is never part of the application jar.
 */
public class TestDemoApplication {

    public static void main(String[] args) {
        SpringApplication.from(EmployeeSchedulingApplication::main).with(DemoData.class).run(args);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class DemoData {

        @Bean
        @Primary
        CrewDataCache demoCrewDataCache() {
            return new DemoCrewDataCache();
        }
    }

    /** The cache with fixed demo data; nothing is read from a database. */
    static class DemoCrewDataCache extends CrewDataCache {

        private static final String[] SURNAMES = { "Иванов", "Петров", "Сидоров", "Кузнецов", "Смирнов", "Попов",
                "Волков", "Зайцев", "Соколов", "Лебедев", "Козлов", "Новиков", "Морозов", "Павлов" };
        private static final String[] INITIALS = { "А. В.", "Б. Г.", "В. Д.", "Г. Е.", "Д. И.", "Е. К." };

        private final List<Line> lines = new ArrayList<>();
        private final List<Crew> crews = new ArrayList<>();
        private final List<Employee> employees = new ArrayList<>();

        DemoCrewDataCache() {
            super(null, null, null, null, null);
            int employeeId = 50001;
            for (int line = 1; line <= 6; line++) {
                String kc = "БерЦГС" + line;
                lines.add(new Line(kc, "1706100" + line + "0000", "Линия " + line));
                for (int number = 1; number <= 4; number++) {
                    Crew crew = new Crew(10000 + line * 10 + number, "Линия" + line + "/" + number);
                    crew.setStaffCount(line <= 3 ? 6 : 5);
                    crew.setLineId(kc);
                    crews.add(crew);
                    int people = line == 4 && number == 2 ? crew.getStaffCount() - 1 : crew.getStaffCount();
                    for (int position = 1; position <= people; position++) {
                        String name = SURNAMES[employeeId % SURNAMES.length] + " "
                                + INITIALS[(employeeId / SURNAMES.length) % INITIALS.length];
                        Employee employee = new Employee(employeeId++, name, crew);
                        employee.setPosition(position);
                        crew.getEmployees().add(employee);
                        employees.add(employee);
                    }
                }
            }
        }

        @Override
        public void refresh(YearMonth month) {
            // Fixed data
        }

        @Override
        public void refreshLines() {
            // Fixed data
        }

        @Override
        public void refreshShifts() {
            // Default shift times
        }

        @Override
        public List<ShiftTime> getShifts() {
            return List.of(ShiftTime.defaultFor(ShiftType.DAY), ShiftTime.defaultFor(ShiftType.NIGHT));
        }

        @Override
        public List<Line> getLines(YearMonth month) {
            return lines;
        }

        @Override
        public Optional<Line> getLine(String kc) {
            return lines.stream().filter(line -> line.getId().equals(kc.trim())).findFirst();
        }

        @Override
        public Optional<Line> getLineByKrc(String krc) {
            return lines.stream().filter(line -> line.getKrc().equals(krc.trim())).findFirst();
        }

        @Override
        public CrewData get(String department, YearMonth month) {
            return getAll(month);
        }

        @Override
        public CrewData getAll(YearMonth month) {
            return new CrewData(crews, employees);
        }
    }
}
