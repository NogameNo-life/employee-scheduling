package org.acme.employeescheduling.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

import org.acme.employeescheduling.domain.AbsenceType;
import org.acme.employeescheduling.domain.Crew;
import org.acme.employeescheduling.domain.CrewSchedule;
import org.acme.employeescheduling.domain.CrewShift;
import org.acme.employeescheduling.domain.Employee;
import org.acme.employeescheduling.domain.Line;
import org.acme.employeescheduling.domain.ShiftTime;
import org.acme.employeescheduling.domain.ShiftType;
import org.acme.employeescheduling.service.CrewRotationGenerator.GenerateRequest;
import org.acme.employeescheduling.service.CrewScheduleEditor.AbsenceRequest;
import org.acme.employeescheduling.service.ScheduleValidator.ScheduleWarning;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.PrintSetup;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ScheduleExcelExporterTest {

    private static final YearMonth NOVEMBER = YearMonth.of(2026, 11);
    private static final LocalDate DAY1 = NOVEMBER.atDay(1);
    private static final int DAY_COLUMN = ScheduleExcelExporter.LINES_FIRST_DAY_COLUMN;
    private static final int EMPLOYEE_DAY_COLUMN = ScheduleExcelExporter.EMPLOYEES_FIRST_DAY_COLUMN;
    private static final int FIRST_ROW = ScheduleExcelExporter.HEADER_ROWS;

    private final Clock clock = Clock.fixed(Instant.parse("2026-11-25T07:30:00Z"), ZoneId.of("Europe/Minsk"));
    private final ScheduleExcelExporter exporter = new ScheduleExcelExporter(clock);
    private final CrewScheduleEditor editor = new CrewScheduleEditor();
    private final CrewRotationGenerator generator = new CrewRotationGenerator(editor);
    private final ScheduleValidator validator = new ScheduleValidator(12);

    /**
     * Lines L1 ("Line 1") and L2 ("Line 2") with crews 11..14 and 21..24 ("Линия1/1" ...), 2 employees per crew
     * (crew id * 10 + 1, + 2), staff count 2; generated for the whole month.
     */
    private CrewSchedule schedule(YearMonth month) {
        List<Crew> crews = new ArrayList<>();
        List<Employee> employees = new ArrayList<>();
        for (int line = 1; line <= 2; line++) {
            for (int number = 1; number <= 4; number++) {
                Crew crew = new Crew(line * 10 + number, "Линия" + line + "/" + number);
                crew.setLineId("L" + line);
                crew.setStaffCount(2);
                crews.add(crew);
                employees.add(new Employee(crew.getId() * 10 + 1, "Сотрудник " + crew.getId() + "-1", crew));
                employees.add(new Employee(crew.getId() * 10 + 2, "Сотрудник " + crew.getId() + "-2", crew));
            }
        }
        CrewSchedule schedule = new CrewScheduleFactory().createEmpty(month,
                List.of(new Line("L1", "1", "Line 1"), new Line("L2", "2", "Line 2")),
                List.of(ShiftTime.defaultFor(ShiftType.DAY), ShiftTime.defaultFor(ShiftType.NIGHT)),
                new CrewData(crews, employees));
        generator.generate(schedule, new GenerateRequest(null, null, null, false));
        return schedule;
    }

    private XSSFWorkbook export(CrewSchedule schedule, String lineId, List<ScheduleWarning> warnings)
            throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        exporter.export(schedule, lineId, warnings, out);
        return new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray()));
    }

    private static String text(Sheet sheet, int row, int column) {
        Row r = sheet.getRow(row);
        Cell cell = r == null ? null : r.getCell(column);
        return cell == null ? null : cell.getStringCellValue();
    }

    private static String fill(Cell cell) {
        return ((XSSFCellStyle) cell.getCellStyle()).getFillForegroundColorColor().getARGBHex();
    }

    /** The row of an employee on "По сотрудникам" (column "Таб. №"). */
    private static Row employeeRow(Sheet sheet, int employeeId) {
        for (Row row : sheet) {
            Cell cell = row.getCell(1);
            if (row.getRowNum() >= FIRST_ROW && cell != null && cell.getCellType() == CellType.NUMERIC
                    && (int) cell.getNumericCellValue() == employeeId) {
                return row;
            }
        }
        return null;
    }

    @Test
    void sheetsTitleAndPrintSetup() throws IOException {
        CrewSchedule schedule = schedule(NOVEMBER);

        try (XSSFWorkbook workbook = export(schedule, null, List.of())) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(2);
            assertThat(workbook.getSheetName(0)).isEqualTo("По линиям");
            assertThat(workbook.getSheetName(1)).isEqualTo("По сотрудникам");
            Sheet lines = workbook.getSheet("По линиям");
            assertThat(text(lines, 0, 0)).isEqualTo("График бригад — Ноябрь 2026");
            assertThat(text(lines, 1, 0)).isEqualTo("Сформировано 25.11.2026 10:30");
            assertThat(text(lines, 2, 0)).isEqualTo("Линия");
            assertThat(text(lines, 2, DAY_COLUMN)).isEqualTo("1\nвс");
            assertThat(lines.getPrintSetup().getLandscape()).isTrue();
            assertThat(lines.getPrintSetup().getPaperSize()).isEqualTo(PrintSetup.A3_PAPERSIZE);
            assertThat(lines.getPrintSetup().getFitWidth()).isEqualTo((short) 1);
            assertThat(lines.getPaneInformation().isFreezePane()).isTrue();
            assertThat(lines.getRepeatingRows().formatAsString()).isEqualTo("1:3");
            // Weekend columns are shaded, weekdays not (the 1st is a Sunday, the 2nd a Monday)
            assertThat(fill(lines.getRow(FIRST_ROW).getCell(DAY_COLUMN))).isEqualTo("FFF1F5F9");
            assertThat(fill(lines.getRow(FIRST_ROW).getCell(DAY_COLUMN + 1))).isEqualTo("FFFFFFFF");
        }
    }

    @Test
    void linesSheetHasTheCrewOfEveryPlaceEmptyAndDeficitPlacesTinted() throws IOException {
        CrewSchedule schedule = schedule(NOVEMBER);
        editor.removeCrewShift(schedule, NOVEMBER.atDay(3) + "-DAY-L1");
        // A sick leave makes the shift of the 2nd short of one person
        CrewShift second = schedule.getCrewShifts().stream()
                .filter(s -> s.getId().equals(NOVEMBER.atDay(2) + "-DAY-L1")).findFirst().orElseThrow();
        int sickId = second.getEmployees().get(0).getId();
        editor.addAbsence(schedule, sickId, new AbsenceRequest(AbsenceType.SICK_LEAVE, NOVEMBER.atDay(2),
                NOVEMBER.atDay(2)));

        try (XSSFWorkbook workbook = export(schedule, null, List.of())) {
            Sheet lines = workbook.getSheet("По линиям");
            // Default order: DAY 11, 13, ...; NIGHT 12, 11, ...
            assertThat(text(lines, FIRST_ROW, 0)).isEqualTo("Line 1\nL1");
            assertThat(text(lines, FIRST_ROW, 1)).isEqualTo("Д 08:00–20:00");
            assertThat(text(lines, FIRST_ROW + 1, 1)).isEqualTo("Н 20:00–08:00");
            assertThat(text(lines, FIRST_ROW, DAY_COLUMN)).isEqualTo("Линия1/1");
            assertThat(text(lines, FIRST_ROW + 1, DAY_COLUMN)).isEqualTo("Линия1/2");
            Cell empty = lines.getRow(FIRST_ROW).getCell(DAY_COLUMN + 2);
            assertThat(empty.getStringCellValue()).isEmpty();
            assertThat(fill(empty)).isEqualTo("FFFFE4E6");
            Cell deficit = lines.getRow(FIRST_ROW).getCell(DAY_COLUMN + 1);
            assertThat(fill(deficit)).isEqualTo("FFFEF3C7");
            assertThat(deficit.getCellComment().getString().getString()).isEqualTo("1 из 2");
            // Shifts of the row in the month
            assertThat(lines.getRow(FIRST_ROW).getCell(DAY_COLUMN + 30).getNumericCellValue()).isEqualTo(29);
            assertThat(lines.getRow(FIRST_ROW + 2).getCell(DAY_COLUMN).getStringCellValue()).isEqualTo("Линия2/1");
        }
    }

    @Test
    void employeesSheetHasDayCodesSubstitutesAndTotals() throws IOException {
        CrewSchedule schedule = schedule(NOVEMBER);
        // Crew 12 works NIGHT on the 1st: О on the 2nd. Employee 121 is sick on the 5th to the 6th.
        editor.addAbsence(schedule, 121, new AbsenceRequest(AbsenceType.SICK_LEAVE, NOVEMBER.atDay(5),
                NOVEMBER.atDay(6)));
        // Employee 141 (crew 14, day off on the 1st) replaces 111 on the DAY shift of the 1st on L1
        editor.replaceEmployee(schedule, DAY1 + "-DAY-L1", 111, 141);

        try (XSSFWorkbook workbook = export(schedule, null, List.of())) {
            Sheet employees = workbook.getSheet("По сотрудникам");
            assertThat(text(employees, 2, 2)).isEqualTo("ФИО");
            assertThat(text(employees, FIRST_ROW, 0)).startsWith("Линия1/1 — Line 1");
            Row row121 = employeeRow(employees, 121);
            assertThat(row121.getCell(2).getStringCellValue()).isEqualTo("Сотрудник 12-1");
            assertThat(row121.getCell(EMPLOYEE_DAY_COLUMN).getStringCellValue()).isEqualTo("Н");
            assertThat(row121.getCell(EMPLOYEE_DAY_COLUMN + 1).getStringCellValue()).isEqualTo("О");
            assertThat(row121.getCell(EMPLOYEE_DAY_COLUMN + 4).getStringCellValue()).isEqualTo("Б");

            Row row141 = employeeRow(employees, 141);
            Cell substitute = row141.getCell(EMPLOYEE_DAY_COLUMN);
            assertThat(substitute.getStringCellValue()).isEqualTo("Д");
            assertThat(substitute.getCellComment().getString().getString()).isEqualTo("замена: Линия1/1, Line 1");
            Row row111 = employeeRow(employees, 111);
            assertThat(row111.getCell(EMPLOYEE_DAY_COLUMN).getStringCellValue()).isEqualTo("В");
            assertThat(row111.getCell(EMPLOYEE_DAY_COLUMN).getCellComment().getString().getString())
                    .isEqualTo("снят со смены: Д, Line 1");

            // Totals: Д, Н, Смен, Часов (12 h per shift)
            int totals = EMPLOYEE_DAY_COLUMN + 30;
            EmployeeDayStatus status = EmployeeDayStatus.of(schedule);
            Employee employee141 = schedule.getEmployees().stream().filter(e -> e.getId() == 141).findFirst()
                    .orElseThrow();
            int shifts = 0;
            for (int day = 1; day <= 30; day++) {
                shifts += status.get(employee141, NOVEMBER.atDay(day)).worked().size();
            }
            double day = row141.getCell(totals).getNumericCellValue();
            double night = row141.getCell(totals + 1).getNumericCellValue();
            assertThat(day + night).isEqualTo(shifts);
            assertThat(row141.getCell(totals + 2).getNumericCellValue()).isEqualTo(shifts);
            assertThat(row141.getCell(totals + 3).getNumericCellValue()).isEqualTo(shifts * 12.0);
            assertThat(text(employees, 2, totals + 3)).isEqualTo("Часов");
        }
    }

    @Test
    void warningsSheetOnlyWithWarnings() throws IOException {
        CrewSchedule schedule = schedule(NOVEMBER);
        // Crew 13 works DAY on the 2nd; giving it the NIGHT of the 2nd too leaves 0 h rest
        editor.removeCrewShift(schedule, NOVEMBER.atDay(2) + "-NIGHT-L1");
        editor.addCrewShift(schedule, new CrewScheduleEditor.CrewShiftRequest(NOVEMBER.atDay(2), ShiftType.NIGHT,
                "L1", 13));
        List<ScheduleWarning> warnings = validator.validate(schedule);
        assertThat(warnings).isNotEmpty();

        try (XSSFWorkbook workbook = export(schedule, null, warnings)) {
            Sheet sheet = workbook.getSheet("Нарушения");
            assertThat(sheet).isNotNull();
            assertThat(text(sheet, 2, 0)).isEqualTo("Дата");
            assertThat(text(sheet, FIRST_ROW, 0)).isEqualTo("02.11.2026");
            assertThat(text(sheet, FIRST_ROW, 1)).isEqualTo("Ночная");
            assertThat(text(sheet, FIRST_ROW, 2)).isEqualTo("Line 1");
            assertThat(text(sheet, FIRST_ROW, 3)).isEqualTo("Линия1/3");
            assertThat(sheet.getRow(FIRST_ROW).getCell(4).getNumericCellValue()).isZero();
        }
        // The other line has no warnings
        try (XSSFWorkbook workbook = export(schedule, "L2", warnings)) {
            assertThat(workbook.getSheet("Нарушения")).isNull();
        }
    }

    @Test
    void oneLineOnlyItsRowsAndTheEmployeesWhoWorkOnIt() throws IOException {
        CrewSchedule schedule = schedule(NOVEMBER);
        // Employee 211 of line 2 works one shift on line 1 as a substitute
        editor.replaceEmployee(schedule, NOVEMBER.atDay(3) + "-DAY-L1", 141, 211);

        try (XSSFWorkbook workbook = export(schedule, "L1", List.of())) {
            Sheet lines = workbook.getSheet("По линиям");
            assertThat(text(lines, 0, 0)).isEqualTo("График бригад — Ноябрь 2026 — Line 1");
            assertThat(lines.getLastRowNum()).isEqualTo(FIRST_ROW + 1);
            Sheet employees = workbook.getSheet("По сотрудникам");
            assertThat(employeeRow(employees, 111)).isNotNull();
            assertThat(employeeRow(employees, 211)).isNotNull();
            assertThat(employeeRow(employees, 221)).isNull();
        }
        assertThatThrownBy(() -> export(schedule, "L9", List.of())).isInstanceOf(NoSuchElementException.class);
    }

    @ParameterizedTest
    @CsvSource({ "2027-02, 28", "2026-11, 30", "2026-12, 31" })
    void oneDayColumnPerDayOfTheMonth(String month, int days) throws IOException {
        try (XSSFWorkbook workbook = export(schedule(YearMonth.parse(month)), null, List.of())) {
            Sheet lines = workbook.getSheet("По линиям");
            assertThat(text(lines, 2, DAY_COLUMN + days - 1)).startsWith(days + "\n");
            assertThat(text(lines, 2, DAY_COLUMN + days)).isEqualTo("Смен");
            Sheet employees = workbook.getSheet("По сотрудникам");
            assertThat(text(employees, 2, EMPLOYEE_DAY_COLUMN + days - 1)).startsWith(days + "\n");
            assertThat(text(employees, 2, EMPLOYEE_DAY_COLUMN + days)).isEqualTo("Д");
        }
    }

    @Test
    void fileNames() {
        assertThat(ScheduleExcelExporter.fileName(NOVEMBER, null)).isEqualTo("График_бригад_2026-11.xlsx");
        assertThat(ScheduleExcelExporter.fileName(NOVEMBER, "БерЦГС1")).isEqualTo("График_бригад_2026-11_БерЦГС1.xlsx");
        assertThat(ScheduleExcelExporter.asciiFileName(NOVEMBER)).isEqualTo("crew-schedule-2026-11.xlsx");
    }
}
