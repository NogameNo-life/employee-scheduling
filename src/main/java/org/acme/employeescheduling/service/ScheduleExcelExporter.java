package org.acme.employeescheduling.service;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.stream.Collectors;

import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.ss.usermodel.Comment;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.Drawing;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.PageMargin;
import org.apache.poi.ss.usermodel.PrintSetup;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import org.acme.employeescheduling.domain.Absence;
import org.acme.employeescheduling.domain.Crew;
import org.acme.employeescheduling.domain.CrewSchedule;
import org.acme.employeescheduling.domain.CrewShift;
import org.acme.employeescheduling.domain.Employee;
import org.acme.employeescheduling.domain.Line;
import org.acme.employeescheduling.domain.ShiftTime;
import org.acme.employeescheduling.domain.ShiftType;
import org.acme.employeescheduling.service.EmployeeDayStatus.Code;
import org.acme.employeescheduling.service.EmployeeDayStatus.DayStatus;
import org.acme.employeescheduling.service.ScheduleValidator.ScheduleWarning;
import org.acme.employeescheduling.service.ScheduleValidator.WarningType;

/**
 * Writes the crew schedule of one version (in memory) as an Excel workbook (.xlsx) to an output stream: sheets
 * "По линиям", "По сотрудникам" and, if there are rest warnings, "Нарушения". Nothing is saved on disk. The caller
 * must hold the lock of the schedule (see {@link CrewScheduleSessionService#edit}).
 */
@Service
public class ScheduleExcelExporter {

    public static final String CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    static final String LINES_SHEET = "По линиям";
    static final String EMPLOYEES_SHEET = "По сотрудникам";
    static final String WARNINGS_SHEET = "Нарушения";
    static final String SHIFTS_SHEET = "Смены";
    /** Title, "Сформировано ..." and the column header. */
    static final int HEADER_ROWS = 3;
    /** Columns before the days: "Линия", "Смена" / "№", "Таб. №", "ФИО". */
    static final int LINES_FIRST_DAY_COLUMN = 2;
    static final int EMPLOYEES_FIRST_DAY_COLUMN = 3;
    /** "По линиям": day column width in characters and row height in points (readable on A4). */
    static final int LINES_DAY_WIDTH = 12;
    static final float LINES_ROW_HEIGHT = 22;

    private static final String[] MONTHS = { "Январь", "Февраль", "Март", "Апрель", "Май", "Июнь", "Июль", "Август",
            "Сентябрь", "Октябрь", "Ноябрь", "Декабрь" };
    private static final String[] WEEKDAYS = { "пн", "вт", "ср", "чт", "пт", "сб", "вс" };
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    // Colors of DESIGN.md / style.css (shift tints); "rose" and "amber" a bit stronger, so they are seen on paper
    private static final String WHITE = "ffffff";
    /** Light enough to print as a pale grey (the old slate tint printed dark grey). */
    private static final String WEEKEND = "f2f2f2";
    /** Thin borders that still print on a black-and-white printer. */
    private static final String BORDER = "a6a6a6";
    /** The thicker border between two lines on "По линиям". */
    private static final String LINE_BORDER = "404040";
    /** Markers for black-and-white printers (the tints alone do not print): empty place, deficit. */
    static final String EMPTY_MARK = "—";
    static final String DEFICIT_MARK = " !";
    /** Narrow margins: about 1 cm, in inches. */
    private static final double MARGIN = 0.4;
    /** A period of up to this many days is printed on one page wide. */
    static final int ONE_PAGE_DAYS = 10;
    private static final String TEXT = "0f172a";
    private static final String EMPTY_ROSE = "ffe4e6";
    private static final String DEFICIT_AMBER = "fef3c7";
    private static final String GROUP = "e2e8f0";

    /** Background and text color of every day code, as the {@code .code-*} classes of style.css. */
    private static final Map<Code, String[]> CODE_COLORS = Map.of(
            Code.DAY, new String[] { "f0fdfa", "115e59" },
            Code.NIGHT, new String[] { "f5f3ff", "5b21b6" },
            Code.DAY_AND_NIGHT, new String[] { "f5f3ff", "5b21b6" },
            Code.SLEEP, new String[] { "e0f2fe", "075985" },
            Code.OFF, new String[] { WHITE, "75777d" },
            Code.SICK_LEAVE, new String[] { "fff1f2", "9f1239" },
            Code.VACATION, new String[] { "fffbeb", "92400e" },
            Code.DISMISSAL, new String[] { "f1f5f9", "334155" },
            Code.OTHER_ABSENCE, new String[] { "f8fafc", "334155" });

    private final Clock clock;

    public ScheduleExcelExporter(Clock clock) {
        this.clock = clock;
    }

    /**
     * The file name: "График_бригад_2026-11.xlsx" (whole month), "График_бригад_2026-11-05_2026-11-15.xlsx" (period),
     * "..._БерЦГС1.xlsx" (one line), "График_сотрудников_<period>.xlsx" (chosen employees; with one employee its
     * personnel number is added: "График_сотрудников_2026-11_1001.xlsx").
     *
     * @param from        first day; null: the 1st of the month
     * @param to          last day; null: the last day of the month
     * @param employeeIds null or empty: the whole schedule
     */
    public static String fileName(YearMonth month, LocalDate from, LocalDate to, String lineId,
            List<Integer> employeeIds) {
        List<Integer> ids = cleanIds(employeeIds);
        String period = periodName(month, from, to);
        if (!ids.isEmpty()) {
            return "График_сотрудников_" + period + (ids.size() == 1 ? "_" + ids.get(0) : "") + ".xlsx";
        }
        return "График_бригад_" + period + (lineId == null || lineId.isBlank() ? "" : "_" + lineId.trim()) + ".xlsx";
    }

    /** The whole month, as before. */
    public static String fileName(YearMonth month, String lineId) {
        return fileName(month, null, null, lineId, null);
    }

    /** ASCII file name for old clients: "crew-schedule-2026-11.xlsx", "employee-schedule-2026-11-05_2026-11-15.xlsx". */
    public static String asciiFileName(YearMonth month, LocalDate from, LocalDate to, List<Integer> employeeIds) {
        List<Integer> ids = cleanIds(employeeIds);
        String period = periodName(month, from, to);
        return ids.isEmpty() ? "crew-schedule-" + period + ".xlsx"
                : "employee-schedule-" + period + (ids.size() == 1 ? "-" + ids.get(0) : "") + ".xlsx";
    }

    public static String asciiFileName(YearMonth month) {
        return asciiFileName(month, null, null, null);
    }

    /** "2026-11" for the whole month, else "2026-11-05_2026-11-15". */
    private static String periodName(YearMonth month, LocalDate from, LocalDate to) {
        LocalDate first = from == null ? month.atDay(1) : from;
        LocalDate last = to == null ? month.atEndOfMonth() : to;
        return first.equals(month.atDay(1)) && last.equals(month.atEndOfMonth()) ? month.toString()
                : first + "_" + last;
    }

    /** Without nulls and repeats; empty = everybody. */
    private static List<Integer> cleanIds(List<Integer> employeeIds) {
        return employeeIds == null ? List.of() : employeeIds.stream().filter(Objects::nonNull).distinct().toList();
    }

    /** The whole month, all employees. */
    public void export(CrewSchedule schedule, String lineId, List<ScheduleWarning> warnings, OutputStream out) {
        export(schedule, new ExportRequest(lineId, null, null, null), warnings, out);
    }

    /**
     * Writes the workbook of the request. Errors before anything is written: unknown line or employee
     * ({@link NoSuchElementException}, 404), a date not in the month or {@code to} before {@code from}
     * ({@link IllegalArgumentException}, 400).
     *
     * @param warnings rest warnings of the version ({@link ScheduleValidator#validate})
     */
    public void export(CrewSchedule schedule, ExportRequest request, List<ScheduleWarning> warnings,
            OutputStream out) {
        Scope scope = scope(schedule, request == null ? new ExportRequest(null, null, null, null) : request);
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Styles styles = new Styles(workbook);
            String title = (scope.employees() == null ? "График бригад — " : "График сотрудников — ")
                    + scope.periodTitle() + (scope.line() == null ? "" : " — " + lineName(scope.line()));
            String generated = "Сформировано " + LocalDateTime.now(clock).format(DATE_TIME);
            EmployeeDayStatus status = EmployeeDayStatus.of(schedule);

            if (scope.employees() == null) {
                List<Line> lines = scope.line() == null ? schedule.getLines() : List.of(scope.line());
                writeLines(workbook.createSheet(LINES_SHEET), scope, lines, styles, title, generated);
            }
            List<Employee> employees = shownEmployees(scope, status);
            writeEmployees(workbook.createSheet(EMPLOYEES_SHEET), scope, employees, status, styles, title, generated);
            if (scope.employees() != null) {
                writeShifts(workbook.createSheet(SHIFTS_SHEET), scope, employees, status, styles, title, generated);
            }
            List<ScheduleWarning> shown = warnings.stream().filter(warning -> isShown(scope, warning)).toList();
            if (!shown.isEmpty()) {
                writeWarnings(workbook.createSheet(WARNINGS_SHEET), scope, shown, styles, title, generated);
            }
            workbook.write(out);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write the Excel workbook.", e);
        }
    }

    /** Checks the request and resolves its defaults. */
    private static Scope scope(CrewSchedule schedule, ExportRequest request) {
        YearMonth month = schedule.getMonth();
        LocalDate from = request.from() == null ? month.atDay(1) : request.from();
        LocalDate to = request.to() == null ? month.atEndOfMonth() : request.to();
        if (!YearMonth.from(from).equals(month) || !YearMonth.from(to).equals(month)) {
            throw new IllegalArgumentException("Период " + from.format(DATE) + " – " + to.format(DATE)
                    + " не в месяце графика " + monthTitle(month).toLowerCase() + ".");
        }
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("Дата «по» " + to.format(DATE) + " раньше даты «с» " + from.format(DATE)
                    + ".");
        }
        List<LocalDate> dates = from.datesUntil(to.plusDays(1)).toList();
        Line line = findLine(schedule, request.lineId());
        List<Integer> ids = cleanIds(request.employeeIds());
        List<Employee> employees = null;
        if (!ids.isEmpty()) {
            Map<Integer, Employee> byId = schedule.getEmployees().stream()
                    .collect(Collectors.toMap(Employee::getId, employee -> employee));
            employees = new ArrayList<>();
            for (Integer id : ids) {
                Employee employee = byId.get(id);
                if (employee == null) {
                    throw new NoSuchElementException("Employee " + id + " was not found in this schedule version.");
                }
                employees.add(employee);
            }
        }
        return new Scope(schedule, dates, line, employees);
    }

    // ---------- "По линиям" ----------

    private void writeLines(Sheet sheet, Scope scope, List<Line> lines, Styles styles, String title,
            String generated) {
        CrewSchedule schedule = scope.schedule();
        List<LocalDate> dates = scope.dates();
        int totalColumn = LINES_FIRST_DAY_COLUMN + dates.size();
        writeTitle(sheet, styles, title, generated);
        Row header = headerRow(sheet);
        text(header, 0, "Линия", styles.header(false));
        text(header, 1, "Смена", styles.header(false));
        writeDayHeaders(header, dates, LINES_FIRST_DAY_COLUMN, styles);
        text(header, totalColumn, "Смен", styles.header(false));

        Map<String, CrewShift> bySlot = new HashMap<>();
        schedule.getCrewShifts().forEach(shift -> bySlot.put(slot(shift.getLine().getId(), shift.getDate(),
                shift.getType()), shift));
        Drawing<?> drawing = sheet.createDrawingPatriarch();
        int rowIndex = HEADER_ROWS;
        for (Line line : lines) {
            for (ShiftType type : ShiftType.values()) {
                Row row = sheet.createRow(rowIndex);
                row.setHeightInPoints(LINES_ROW_HEIGHT);
                // The NIGHT row is the last row of the line: a thicker border below it
                boolean last = type == ShiftType.NIGHT;
                text(row, 0, lineName(line) + (line.getName().equals(line.getId()) ? "" : "\n" + line.getId()),
                        styles.get(WHITE, TEXT, true, HorizontalAlignment.LEFT, true, last));
                String[] colors = CODE_COLORS.get(type == ShiftType.DAY ? Code.DAY : Code.NIGHT);
                text(row, 1, shiftLabel(schedule.getShift(type)),
                        styles.get(colors[0], colors[1], true, HorizontalAlignment.LEFT, false, last));
                int count = 0;
                for (int i = 0; i < dates.size(); i++) {
                    LocalDate date = dates.get(i);
                    CrewShift shift = bySlot.get(slot(line.getId(), date, type));
                    if (shift == null) {
                        text(row, LINES_FIRST_DAY_COLUMN + i, EMPTY_MARK, styles.get(EMPTY_ROSE, TEXT, false,
                                HorizontalAlignment.CENTER, false, last));
                        continue;
                    }
                    count++;
                    int people = shift.getEmployees().size();
                    boolean deficit = shift.isDeficit();
                    Cell cell = text(row, LINES_FIRST_DAY_COLUMN + i,
                            crewName(shift.getCrew()) + (deficit ? DEFICIT_MARK : ""),
                            styles.get(deficit ? DEFICIT_AMBER : weekendOr(date, WHITE), TEXT, deficit,
                                    HorizontalAlignment.CENTER, false, last));
                    if (deficit) {
                        comment(sheet, drawing, cell, people + " из " + shift.getStaffCount());
                    }
                }
                number(row, totalColumn, count,
                        styles.get(WHITE, TEXT, true, HorizontalAlignment.CENTER, false, last));
                rowIndex++;
            }
            // The line name once for its two rows
            sheet.addMergedRegion(new CellRangeAddress(rowIndex - 2, rowIndex - 1, 0, 0));
        }
        sheet.setColumnWidth(0, 22 * 256);
        sheet.setColumnWidth(1, 15 * 256);
        // The longest crew name with the deficit mark ("Линия1/1 !") at 10 pt, without wrapping
        for (int i = 0; i < dates.size(); i++) {
            sheet.setColumnWidth(LINES_FIRST_DAY_COLUMN + i, LINES_DAY_WIDTH * 256);
        }
        sheet.setColumnWidth(totalColumn, 7 * 256);
        sheet.createFreezePane(LINES_FIRST_DAY_COLUMN, HEADER_ROWS);
        // All lines on one page tall (6 lines = 12 rows)
        printSetup(sheet, scope, LINES_FIRST_DAY_COLUMN, (short) 1);
    }

    // ---------- "По сотрудникам" ----------

    /**
     * The employees of the export, sorted by their crew at the start of the month (by id), then position and name,
     * as in the UI: the chosen ones, or with a line the ones who work on it in the period, else everybody.
     */
    private static List<Employee> shownEmployees(Scope scope, EmployeeDayStatus status) {
        return (scope.employees() == null ? scope.schedule().getEmployees() : scope.employees()).stream()
                .filter(employee -> scope.line() == null || worksOnLine(scope, status, employee, scope.line()))
                .sorted(Comparator.comparing((Employee employee) -> employee.getCrew().getId())
                        .thenComparing(Employee.BY_POSITION_AND_NAME))
                .toList();
    }

    private void writeEmployees(Sheet sheet, Scope scope, List<Employee> employees, EmployeeDayStatus status,
            Styles styles, String title, String generated) {
        List<LocalDate> dates = scope.dates();
        int totalsColumn = EMPLOYEES_FIRST_DAY_COLUMN + dates.size();
        int lastColumn = totalsColumn + 3;
        writeTitle(sheet, styles, title, generated);
        Row header = headerRow(sheet);
        text(header, 0, "№", styles.header(false));
        text(header, 1, "Таб. №", styles.header(false));
        text(header, 2, "ФИО", styles.header(false));
        writeDayHeaders(header, dates, EMPLOYEES_FIRST_DAY_COLUMN, styles);
        String[] totals = { "Д", "Н", "Смен", "Часов" };
        for (int i = 0; i < totals.length; i++) {
            text(header, totalsColumn + i, totals[i], styles.header(false));
        }

        Map<Crew, List<Employee>> byCrew = new LinkedHashMap<>();
        employees.forEach(employee -> byCrew.computeIfAbsent(employee.getCrew(), crew -> new ArrayList<>())
                .add(employee));

        Drawing<?> drawing = sheet.createDrawingPatriarch();
        int rowIndex = HEADER_ROWS;
        int number = 0;
        for (Map.Entry<Crew, List<Employee>> group : byCrew.entrySet()) {
            Row groupRow = sheet.createRow(rowIndex);
            for (int column = 0; column <= lastColumn; column++) {
                text(groupRow, column, "", styles.get(GROUP, TEXT, true, HorizontalAlignment.LEFT, false));
            }
            groupRow.getCell(0).setCellValue(crewName(group.getKey()) + " — " + ownLine(scope.schedule(),
                    group.getKey()) + " · " + group.getValue().size() + " чел.");
            sheet.addMergedRegion(new CellRangeAddress(rowIndex, rowIndex, 0, 2));
            rowIndex++;
            for (Employee employee : group.getValue()) {
                Row row = sheet.createRow(rowIndex++);
                number(row, 0, ++number, styles.get(WHITE, TEXT, false, HorizontalAlignment.CENTER, false));
                number(row, 1, employee.getId(), styles.get(WHITE, TEXT, false, HorizontalAlignment.CENTER, false));
                text(row, 2, employee.getFullName(), styles.get(WHITE, TEXT, false, HorizontalAlignment.LEFT, false));
                Totals sum = new Totals();
                for (int i = 0; i < dates.size(); i++) {
                    LocalDate date = dates.get(i);
                    DayStatus dayStatus = status.get(employee, date);
                    String[] colors = CODE_COLORS.get(dayStatus.code());
                    // Only "В" is quiet (weekend tint, normal font); "О" has its own tint and is bold like the
                    // shifts, so that it differs from "В" on a black-and-white printout too
                    boolean neutral = dayStatus.code() == Code.OFF;
                    Cell cell = text(row, EMPLOYEES_FIRST_DAY_COLUMN + i, dayStatus.code().getLetter(),
                            styles.get(neutral ? weekendOr(date, colors[0]) : colors[0], colors[1],
                                    !neutral, HorizontalAlignment.CENTER, false));
                    String note = note(dayStatus);
                    if (note != null) {
                        comment(sheet, drawing, cell, note);
                    }
                    sum.add(dayStatus);
                }
                XSSFCellStyle total = styles.get(WHITE, TEXT, true, HorizontalAlignment.CENTER, false);
                number(row, totalsColumn, sum.day, total);
                number(row, totalsColumn + 1, sum.night, total);
                number(row, totalsColumn + 2, sum.day + sum.night, total);
                number(row, totalsColumn + 3, sum.hours, styles.hours());
            }
        }
        sheet.setColumnWidth(0, 5 * 256);
        sheet.setColumnWidth(1, 9 * 256);
        sheet.setColumnWidth(2, 34 * 256);
        for (int i = 0; i < dates.size(); i++) {
            sheet.setColumnWidth(EMPLOYEES_FIRST_DAY_COLUMN + i, (int) (4.5 * 256));
        }
        for (int i = 0; i < totals.length; i++) {
            sheet.setColumnWidth(totalsColumn + i, (i < 2 ? 5 : 7) * 256);
        }
        sheet.createFreezePane(EMPLOYEES_FIRST_DAY_COLUMN, HEADER_ROWS);
        printSetup(sheet, scope, EMPLOYEES_FIRST_DAY_COLUMN, (short) 0);
    }

    /** Comment of a day cell: substitute shifts, shifts the employee was taken off; null if nothing to say. */
    private static String note(DayStatus status) {
        List<String> notes = new ArrayList<>();
        status.substitute().forEach(shift -> notes.add("замена: " + crewName(shift.getCrew()) + ", "
                + lineName(shift.getLine())));
        status.takenOff().forEach(shift -> notes.add("снят со смены: " + (shift.getType() == ShiftType.DAY ? "Д" : "Н")
                + ", " + lineName(shift.getLine())));
        return notes.isEmpty() ? null : String.join("\n", notes);
    }

    private static boolean worksOnLine(Scope scope, EmployeeDayStatus status, Employee employee, Line line) {
        return scope.dates().stream().anyMatch(date -> status.get(employee, date).worked().stream()
                .anyMatch(shift -> shift.getLine().equals(line)));
    }

    private static String ownLine(CrewSchedule schedule, Crew crew) {
        if (crew.getLineId() == null) {
            return "без линии";
        }
        return schedule.getLines().stream().filter(line -> line.getId().equals(crew.getLineId())).findFirst()
                .map(ScheduleExcelExporter::lineName).orElse(crew.getLineId());
    }

    // ---------- "Смены" (chosen employees only) ----------

    /**
     * One row per crew shift worked in the period and one per absence (its part inside the period), by date; then a
     * total row per employee.
     */
    private void writeShifts(Sheet sheet, Scope scope, List<Employee> employees, EmployeeDayStatus status,
            Styles styles, String title, String generated) {
        writeTitle(sheet, styles, title, generated);
        Row header = headerRow(sheet);
        String[] columns = { "Таб. №", "ФИО", "Дата", "День недели", "Смена", "Начало", "Конец", "Линия", "Бригада",
                "Часы", "Замена" };
        for (int i = 0; i < columns.length; i++) {
            text(header, i, columns[i], styles.header(false));
        }
        XSSFCellStyle plain = styles.get(WHITE, TEXT, false, HorizontalAlignment.LEFT, false);
        XSSFCellStyle center = styles.get(WHITE, TEXT, false, HorizontalAlignment.CENTER, false);
        XSSFCellStyle bold = styles.get(GROUP, TEXT, true, HorizontalAlignment.LEFT, false);
        LocalDate first = scope.dates().get(0);
        LocalDate last = scope.dates().get(scope.dates().size() - 1);
        int rowIndex = HEADER_ROWS;
        for (Employee employee : employees) {
            // Rows of the employee by time: shifts and absences
            List<ShiftRow> items = new ArrayList<>();
            Totals sum = new Totals();
            for (LocalDate date : scope.dates()) {
                DayStatus dayStatus = status.get(employee, date);
                sum.add(dayStatus);
                for (CrewShift shift : dayStatus.worked()) {
                    items.add(new ShiftRow(shift.getStart(), shift, dayStatus.substitute().contains(shift), null,
                            null, null));
                }
            }
            for (Absence absence : employee.getAbsences()) {
                LocalDate start = absence.from().isBefore(first) ? first : absence.from();
                LocalDate end = absence.to() == null || absence.to().isAfter(last) ? last : absence.to();
                if (!end.isBefore(start)) {
                    items.add(new ShiftRow(start.atStartOfDay(), null, false, absence, start, end));
                }
            }
            items.sort(Comparator.comparing(ShiftRow::time));
            for (ShiftRow item : items) {
                Row row = sheet.createRow(rowIndex++);
                number(row, 0, employee.getId(), center);
                text(row, 1, employee.getFullName(), plain);
                if (item.shift() != null) {
                    CrewShift shift = item.shift();
                    String[] colors = CODE_COLORS.get(shift.getType() == ShiftType.DAY ? Code.DAY : Code.NIGHT);
                    text(row, 2, shift.getDate().format(DATE), center);
                    text(row, 3, WEEKDAYS[shift.getDate().getDayOfWeek().getValue() - 1], center);
                    text(row, 4, shift.getType() == ShiftType.DAY ? "Д" : "Н",
                            styles.get(colors[0], colors[1], true, HorizontalAlignment.CENTER, false));
                    text(row, 5, shift.getStart().format(TIME), center);
                    text(row, 6, shift.getEnd().format(TIME)
                            + (shift.getEnd().toLocalDate().isAfter(shift.getDate()) ? " (след. день)" : ""), center);
                    text(row, 7, lineName(shift.getLine()), plain);
                    text(row, 8, crewName(shift.getCrew()), plain);
                    number(row, 9, shift.getHours(), styles.hours());
                    text(row, 10, item.substitute() ? "да" : "", center);
                } else {
                    Code code = absenceCode(item.absence());
                    String[] colors = CODE_COLORS.get(code);
                    text(row, 2, item.from().equals(item.to()) ? item.from().format(DATE)
                            : item.from().format(DATE) + " – " + item.to().format(DATE), center);
                    text(row, 3, "", center);
                    text(row, 4, code.getLetter(),
                            styles.get(colors[0], colors[1], true, HorizontalAlignment.CENTER, false));
                    text(row, 5, "", center);
                    text(row, 6, "", center);
                    text(row, 7, absenceName(code), plain);
                    for (int column = 8; column <= 10; column++) {
                        text(row, column, "", center);
                    }
                }
            }
            Row total = sheet.createRow(rowIndex++);
            for (int column = 0; column < columns.length; column++) {
                text(total, column, "", bold);
            }
            total.getCell(1).setCellValue("Итого " + employee.getFullName() + ": " + (sum.day + sum.night) + " "
                    + shiftWord(sum.day + sum.night) + " (Д " + sum.day + ", Н " + sum.night + ")");
            Cell hours = total.createCell(9);
            hours.setCellValue(sum.hours);
            hours.setCellStyle(styles.hoursOn(GROUP));
        }
        int[] widths = { 9, 34, 24, 11, 7, 8, 16, 18, 14, 7, 8 };
        for (int i = 0; i < widths.length; i++) {
            sheet.setColumnWidth(i, widths[i] * 256);
        }
        sheet.createFreezePane(2, HEADER_ROWS);
        printSetup(sheet, scope, 0, (short) 0);
    }

    private static Code absenceCode(Absence absence) {
        return switch (absence.type()) {
            case SICK_LEAVE -> Code.SICK_LEAVE;
            case VACATION -> Code.VACATION;
            case DISMISSAL -> Code.DISMISSAL;
            default -> Code.OTHER_ABSENCE;
        };
    }

    private static String absenceName(Code code) {
        return switch (code) {
            case SICK_LEAVE -> "Больничный";
            case VACATION -> "Отпуск";
            case DISMISSAL -> "Увольнение";
            default -> "Другое отсутствие";
        };
    }

    private static String shiftWord(int count) {
        int lastTwo = count % 100;
        int last = count % 10;
        if (lastTwo >= 11 && lastTwo <= 14) {
            return "смен";
        }
        return last == 1 ? "смена" : last >= 2 && last <= 4 ? "смены" : "смен";
    }

    /** A row of "Смены": a worked crew shift, or an absence with its part inside the period. */
    private record ShiftRow(LocalDateTime time, CrewShift shift, boolean substitute, Absence absence, LocalDate from,
            LocalDate to) {
    }

    /** Day and night shifts and hours of one employee in the period. */
    private static final class Totals {
        int day;
        int night;
        double hours;

        void add(DayStatus status) {
            for (CrewShift shift : status.worked()) {
                if (shift.getType() == ShiftType.DAY) {
                    day++;
                } else {
                    night++;
                }
            }
            hours += status.hours();
        }
    }

    // ---------- "Нарушения" ----------

    private void writeWarnings(Sheet sheet, Scope scope, List<ScheduleWarning> warnings, Styles styles,
            String title, String generated) {
        CrewSchedule schedule = scope.schedule();
        writeTitle(sheet, styles, title, generated);
        Row header = headerRow(sheet);
        String[] columns = { "Дата", "Смена", "Линия", "Бригада / сотрудник", "Отдых, ч", "Сообщение" };
        for (int i = 0; i < columns.length; i++) {
            text(header, i, columns[i], styles.header(false));
        }
        Map<String, CrewShift> shifts = schedule.getCrewShifts().stream()
                .collect(Collectors.toMap(CrewShift::getId, shift -> shift, (a, b) -> a));
        XSSFCellStyle plain = styles.get(WHITE, TEXT, false, HorizontalAlignment.LEFT, false);
        XSSFCellStyle wrapped = styles.get(WHITE, TEXT, false, HorizontalAlignment.LEFT, true);
        int rowIndex = HEADER_ROWS;
        for (ScheduleWarning warning : warnings) {
            CrewShift shift = shifts.get(warning.crewShiftId());
            Row row = sheet.createRow(rowIndex++);
            text(row, 0, shift == null ? "" : shift.getDate().format(DATE), plain);
            text(row, 1, shift == null ? "" : shift.getType() == ShiftType.DAY ? "Дневная" : "Ночная", plain);
            text(row, 2, shift == null ? "" : lineName(shift.getLine()), plain);
            text(row, 3, who(schedule, warning), plain);
            number(row, 4, warning.restHours(), styles.hours());
            text(row, 5, warning.message(), wrapped);
        }
        int[] widths = { 12, 10, 18, 30, 9, 80 };
        for (int i = 0; i < widths.length; i++) {
            sheet.setColumnWidth(i, widths[i] * 256);
        }
        sheet.createFreezePane(0, HEADER_ROWS);
        printSetup(sheet, scope, 0, (short) 0);
    }

    private static String who(CrewSchedule schedule, ScheduleWarning warning) {
        if (warning.type() == WarningType.EMPLOYEE_REST && warning.employeeId() != null) {
            return schedule.getEmployees().stream().filter(e -> e.getId().equals(warning.employeeId())).findFirst()
                    .map(e -> e.getFullName() + " (" + e.getId() + ")").orElse(String.valueOf(warning.employeeId()));
        }
        return schedule.getCrews().stream().filter(c -> c.getId().equals(warning.crewId())).findFirst()
                .map(ScheduleExcelExporter::crewName).orElse(Objects.toString(warning.crewId(), ""));
    }

    /**
     * A warning is shown if its crew shift is in the period (and on the line); with chosen employees only their own
     * warnings and the crew warnings of shifts they work as crew members.
     */
    private static boolean isShown(Scope scope, ScheduleWarning warning) {
        CrewShift shift = scope.schedule().getCrewShifts().stream()
                .filter(s -> s.getId().equals(warning.crewShiftId())).findFirst().orElse(null);
        if (shift == null || !scope.dates().contains(shift.getDate())
                || (scope.line() != null && !shift.getLine().equals(scope.line()))) {
            return false;
        }
        if (scope.employees() == null) {
            return true;
        }
        if (warning.type() == WarningType.EMPLOYEE_REST) {
            return scope.employees().stream().anyMatch(employee -> employee.getId().equals(warning.employeeId()));
        }
        return scope.employees().stream()
                .anyMatch(employee -> shift.isMember(employee) && shift.getEmployees().contains(employee));
    }

    // ---------- Common parts ----------

    private static void writeTitle(Sheet sheet, Styles styles, String title, String generated) {
        text(sheet.createRow(0), 0, title, styles.title());
        text(sheet.createRow(1), 0, generated, styles.get(WHITE, "475569", false, HorizontalAlignment.LEFT, false));
    }

    private static Row headerRow(Sheet sheet) {
        Row header = sheet.createRow(HEADER_ROWS - 1);
        header.setHeightInPoints(30);
        return header;
    }

    /** "1\nвс": day number and short weekday, weekends shaded. */
    private static void writeDayHeaders(Row header, List<LocalDate> dates, int firstColumn, Styles styles) {
        for (int i = 0; i < dates.size(); i++) {
            LocalDate date = dates.get(i);
            text(header, firstColumn + i, date.getDayOfMonth() + "\n" + WEEKDAYS[date.getDayOfWeek().getValue() - 1],
                    styles.header(isWeekend(date)));
        }
    }

    /**
     * The day after which the printed page is split: the middle of the period (day 15 of a month); 0: no split (a
     * period of {@value #ONE_PAGE_DAYS} days or less).
     */
    static int splitAfterDays(int days) {
        return days <= ONE_PAGE_DAYS ? 0 : days / 2;
    }

    /**
     * A4 landscape, margins about 1 cm, centered horizontally, header rows (and the label columns of a day sheet) on
     * every page, "Стр. N из M" in the footer, the period on the right of the header. A day sheet is split into two
     * pages wide after the middle day ({@link #splitAfterDays}).
     *
     * <p>
     * Note: Excel ignores manual page breaks while "Fit to" scaling is on. The column break is still written (it is
     * used if the user switches to a fixed scale), and with equal day columns and the label columns repeated, "fit to
     * 2 pages wide" puts the automatic break in the same place.
     *
     * @param labelColumns columns before the days, repeated on every page; 0: not a day sheet (one page wide)
     * @param pagesTall    1: one page tall; 0: as many pages as needed
     */
    private static void printSetup(Sheet sheet, Scope scope, int labelColumns, short pagesTall) {
        PrintSetup print = sheet.getPrintSetup();
        print.setPaperSize(PrintSetup.A4_PAPERSIZE);
        print.setLandscape(true);
        sheet.setMargin(PageMargin.LEFT, MARGIN);
        sheet.setMargin(PageMargin.RIGHT, MARGIN);
        sheet.setMargin(PageMargin.TOP, MARGIN + 0.2);
        sheet.setMargin(PageMargin.BOTTOM, MARGIN + 0.2);
        sheet.setMargin(PageMargin.HEADER, 0.25);
        sheet.setMargin(PageMargin.FOOTER, 0.25);
        sheet.setHorizontallyCenter(true);
        int pagesWide = 1;
        if (labelColumns > 0) {
            int split = splitAfterDays(scope.dates().size());
            if (split > 0) {
                // Inclusive: the column of the last day of the first page
                sheet.setColumnBreak(labelColumns + split - 1);
                pagesWide = 2;
            }
            sheet.setRepeatingColumns(new CellRangeAddress(-1, -1, 0, labelColumns - 1));
        }
        sheet.setRepeatingRows(new CellRangeAddress(0, HEADER_ROWS - 1, -1, -1));
        sheet.setFitToPage(true);
        sheet.setAutobreaks(true);
        print.setFitWidth((short) pagesWide);
        print.setFitHeight(pagesTall);
        sheet.getHeader().setRight(scope.periodTitle());
        sheet.getFooter().setCenter("Стр. &P из &N");
    }

    private static void comment(Sheet sheet, Drawing<?> drawing, Cell cell, String text) {
        CreationHelper helper = sheet.getWorkbook().getCreationHelper();
        ClientAnchor anchor = helper.createClientAnchor();
        anchor.setCol1(cell.getColumnIndex() + 1);
        anchor.setCol2(cell.getColumnIndex() + 5);
        anchor.setRow1(cell.getRowIndex());
        anchor.setRow2(cell.getRowIndex() + 3);
        Comment comment = drawing.createCellComment(anchor);
        comment.setString(helper.createRichTextString(text));
        comment.setAuthor("График бригад");
        cell.setCellComment(comment);
    }

    private static Cell text(Row row, int column, String value, XSSFCellStyle style) {
        Cell cell = row.createCell(column);
        cell.setCellValue(value);
        cell.setCellStyle(style);
        return cell;
    }

    private static void number(Row row, int column, double value, XSSFCellStyle style) {
        Cell cell = row.createCell(column);
        cell.setCellValue(value);
        cell.setCellStyle(style);
    }

    private static Line findLine(CrewSchedule schedule, String lineId) {
        if (lineId == null || lineId.isBlank()) {
            return null;
        }
        String trimmed = lineId.trim();
        return schedule.getLines().stream().filter(line -> line.getId().equals(trimmed)).findFirst()
                .orElseThrow(() -> new NoSuchElementException("Line " + trimmed
                        + " was not found in this schedule version."));
    }

    private static String slot(String lineId, LocalDate date, ShiftType type) {
        return lineId + "|" + date + "|" + type;
    }

    private static boolean isWeekend(LocalDate date) {
        return date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY;
    }

    private static String weekendOr(LocalDate date, String color) {
        return isWeekend(date) ? WEEKEND : color;
    }

    static String monthTitle(YearMonth month) {
        return MONTHS[month.getMonthValue() - 1] + " " + month.getYear();
    }

    /** "Д 08:00–20:00", times from PLR_SM. */
    private static String shiftLabel(ShiftTime time) {
        return (time.type() == ShiftType.DAY ? "Д " : "Н ") + time.start().format(TIME) + "–" + time.end().format(TIME);
    }

    private static String lineName(Line line) {
        return line.getName() == null ? line.getId() : line.getName();
    }

    private static String crewName(Crew crew) {
        return crew.getName() == null ? String.valueOf(crew.getId()) : crew.getName().trim();
    }

    /**
     * What to export.
     *
     * @param lineId      null or blank: all lines; otherwise only this line and the employees who work on it
     * @param from        first day; null: the 1st of the month
     * @param to          last day; null: the last day of the month
     * @param employeeIds null or empty: the whole schedule; otherwise only these employees (no "По линиям", a "Смены"
     *                    sheet)
     */
    public record ExportRequest(String lineId, LocalDate from, LocalDate to, List<Integer> employeeIds) {
    }

    /** A checked request: the days of the period, the line (or null), the chosen employees (or null = all). */
    private record Scope(CrewSchedule schedule, List<LocalDate> dates, Line line, List<Employee> employees) {

        boolean wholeMonth() {
            YearMonth month = schedule.getMonth();
            return dates.get(0).equals(month.atDay(1)) && dates.get(dates.size() - 1).equals(month.atEndOfMonth());
        }

        /** "Ноябрь 2026" or "с 05.11.2026 по 15.11.2026". */
        String periodTitle() {
            return wholeMonth() ? monthTitle(schedule.getMonth())
                    : "с " + dates.get(0).format(DATE) + " по " + dates.get(dates.size() - 1).format(DATE);
        }
    }

    /** One cell style per look: a workbook has at most 64000 styles, a month has thousands of cells. */
    private static final class Styles {

        private final XSSFWorkbook workbook;
        private final Map<String, XSSFCellStyle> cache = new HashMap<>();

        Styles(XSSFWorkbook workbook) {
            this.workbook = workbook;
        }

        XSSFCellStyle get(String fill, String fontColor, boolean bold, HorizontalAlignment align, boolean wrap) {
            return get(fill, fontColor, bold, align, wrap, false);
        }

        /** @param thickBottom a thicker, darker bottom border (the last row of a line on "По линиям") */
        XSSFCellStyle get(String fill, String fontColor, boolean bold, HorizontalAlignment align, boolean wrap,
                boolean thickBottom) {
            String key = fill + "|" + fontColor + "|" + bold + "|" + align + "|" + wrap + "|" + thickBottom;
            return cache.computeIfAbsent(key, k -> {
                XSSFCellStyle style = workbook.createCellStyle();
                style.setFillForegroundColor(color(fill));
                style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
                style.setAlignment(align);
                style.setVerticalAlignment(VerticalAlignment.CENTER);
                style.setWrapText(wrap);
                XSSFColor thin = color(BORDER);
                style.setBorderTop(BorderStyle.THIN);
                style.setTopBorderColor(thin);
                style.setBorderLeft(BorderStyle.THIN);
                style.setLeftBorderColor(thin);
                style.setBorderRight(BorderStyle.THIN);
                style.setRightBorderColor(thin);
                style.setBorderBottom(thickBottom ? BorderStyle.MEDIUM : BorderStyle.THIN);
                style.setBottomBorderColor(thickBottom ? color(LINE_BORDER) : thin);
                XSSFFont font = workbook.createFont();
                font.setFontName("Arial");
                font.setFontHeightInPoints((short) 10);
                font.setBold(bold);
                font.setColor(color(fontColor));
                style.setFont(font);
                return style;
            });
        }

        XSSFCellStyle header(boolean weekend) {
            return get(weekend ? WEEKEND : "f8fafc", TEXT, true, HorizontalAlignment.CENTER, true);
        }

        XSSFCellStyle hours() {
            return hoursOn(WHITE);
        }

        XSSFCellStyle hoursOn(String fill) {
            // The base style first: a nested computeIfAbsent on the same map is not allowed
            XSSFCellStyle base = get(fill, TEXT, true, HorizontalAlignment.CENTER, false);
            return cache.computeIfAbsent("hours|" + fill, k -> {
                XSSFCellStyle style = workbook.createCellStyle();
                style.cloneStyleFrom(base);
                style.setDataFormat(workbook.createDataFormat().getFormat("0.##"));
                return style;
            });
        }

        XSSFCellStyle title() {
            return cache.computeIfAbsent("title", k -> {
                XSSFCellStyle style = workbook.createCellStyle();
                XSSFFont font = workbook.createFont();
                font.setFontName("Arial");
                font.setFontHeightInPoints((short) 14);
                font.setBold(true);
                style.setFont(font);
                return style;
            });
        }

        /**
         * "rrggbb" -> color. The colors are constants of this class (checked by the tests); a wrong one is a
         * programming error, and {@link HexFormat#parseHex} then throws an {@link IllegalArgumentException}.
         */
        private static XSSFColor color(String hex) {
            return new XSSFColor(HexFormat.of().parseHex(hex), null);
        }
    }
}
