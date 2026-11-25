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
    /** Title, "Сформировано ..." and the column header. */
    static final int HEADER_ROWS = 3;
    /** Columns before the days: "Линия", "Смена" / "№", "Таб. №", "ФИО". */
    static final int LINES_FIRST_DAY_COLUMN = 2;
    static final int EMPLOYEES_FIRST_DAY_COLUMN = 3;

    private static final String[] MONTHS = { "Январь", "Февраль", "Март", "Апрель", "Май", "Июнь", "Июль", "Август",
            "Сентябрь", "Октябрь", "Ноябрь", "Декабрь" };
    private static final String[] WEEKDAYS = { "пн", "вт", "ср", "чт", "пт", "сб", "вс" };
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    // Colors of DESIGN.md / style.css (shift tints); "rose" and "amber" a bit stronger, so they are seen on paper
    private static final String WHITE = "ffffff";
    private static final String WEEKEND = "f1f5f9";
    private static final String BORDER = "cbd5e1";
    private static final String TEXT = "0f172a";
    private static final String EMPTY_ROSE = "ffe4e6";
    private static final String DEFICIT_AMBER = "fef3c7";
    private static final String GROUP = "e2e8f0";

    /** Background and text color of every day code, as the {@code .code-*} classes of style.css. */
    private static final Map<Code, String[]> CODE_COLORS = Map.of(
            Code.DAY, new String[] { "f0fdfa", "115e59" },
            Code.NIGHT, new String[] { "f5f3ff", "5b21b6" },
            Code.DAY_AND_NIGHT, new String[] { "f5f3ff", "5b21b6" },
            Code.SLEEP, new String[] { "fafaf9", "44403c" },
            Code.OFF, new String[] { WHITE, "75777d" },
            Code.SICK_LEAVE, new String[] { "fff1f2", "9f1239" },
            Code.VACATION, new String[] { "fffbeb", "92400e" },
            Code.DISMISSAL, new String[] { "f1f5f9", "334155" },
            Code.OTHER_ABSENCE, new String[] { "f8fafc", "334155" });

    private final Clock clock;

    public ScheduleExcelExporter(Clock clock) {
        this.clock = clock;
    }

    /** "График_бригад_2026-11.xlsx", with a line "График_бригад_2026-11_БерЦГС1.xlsx". */
    public static String fileName(YearMonth month, String lineId) {
        return "График_бригад_" + month + (lineId == null || lineId.isBlank() ? "" : "_" + lineId.trim()) + ".xlsx";
    }

    /** ASCII file name for old clients: "crew-schedule-2026-11.xlsx". */
    public static String asciiFileName(YearMonth month) {
        return "crew-schedule-" + month + ".xlsx";
    }

    /**
     * @param lineId   null or blank: all lines; otherwise only this line (404 if unknown) and the employees who work on
     *                 it in the month
     * @param warnings rest warnings of the version ({@link ScheduleValidator#validate})
     */
    public void export(CrewSchedule schedule, String lineId, List<ScheduleWarning> warnings, OutputStream out) {
        Line line = findLine(schedule, lineId);
        List<Line> lines = line == null ? schedule.getLines() : List.of(line);
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Styles styles = new Styles(workbook);
            String title = "График бригад — " + monthTitle(schedule.getMonth())
                    + (line == null ? "" : " — " + lineName(line));
            String generated = "Сформировано " + LocalDateTime.now(clock).format(DATE_TIME);
            EmployeeDayStatus status = EmployeeDayStatus.of(schedule);

            writeLines(workbook.createSheet(LINES_SHEET), schedule, lines, styles, title, generated);
            writeEmployees(workbook.createSheet(EMPLOYEES_SHEET), schedule, line, status, styles, title, generated);
            List<ScheduleWarning> shown = warnings.stream()
                    .filter(warning -> line == null || isOnLine(schedule, warning, line))
                    .toList();
            if (!shown.isEmpty()) {
                writeWarnings(workbook.createSheet(WARNINGS_SHEET), schedule, shown, styles, title, generated);
            }
            workbook.write(out);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write the Excel workbook.", e);
        }
    }

    // ---------- "По линиям" ----------

    private void writeLines(Sheet sheet, CrewSchedule schedule, List<Line> lines, Styles styles, String title,
            String generated) {
        YearMonth month = schedule.getMonth();
        int days = month.lengthOfMonth();
        int totalColumn = LINES_FIRST_DAY_COLUMN + days;
        writeTitle(sheet, styles, title, generated);
        Row header = headerRow(sheet);
        text(header, 0, "Линия", styles.header(false));
        text(header, 1, "Смена", styles.header(false));
        writeDayHeaders(header, month, LINES_FIRST_DAY_COLUMN, styles);
        text(header, totalColumn, "Смен", styles.header(false));

        Map<String, CrewShift> bySlot = new HashMap<>();
        schedule.getCrewShifts().forEach(shift -> bySlot.put(slot(shift.getLine().getId(), shift.getDate(),
                shift.getType()), shift));
        Drawing<?> drawing = sheet.createDrawingPatriarch();
        int rowIndex = HEADER_ROWS;
        for (Line line : lines) {
            for (ShiftType type : ShiftType.values()) {
                Row row = sheet.createRow(rowIndex);
                text(row, 0, lineName(line) + (line.getName().equals(line.getId()) ? "" : "\n" + line.getId()),
                        styles.get(WHITE, TEXT, true, HorizontalAlignment.LEFT, true));
                text(row, 1, shiftLabel(schedule.getShift(type)), styles.get(type == ShiftType.DAY
                        ? CODE_COLORS.get(Code.DAY)[0] : CODE_COLORS.get(Code.NIGHT)[0],
                        type == ShiftType.DAY ? CODE_COLORS.get(Code.DAY)[1] : CODE_COLORS.get(Code.NIGHT)[1],
                        true, HorizontalAlignment.LEFT, false));
                int count = 0;
                for (int day = 1; day <= days; day++) {
                    LocalDate date = month.atDay(day);
                    CrewShift shift = bySlot.get(slot(line.getId(), date, type));
                    Cell cell;
                    if (shift == null) {
                        cell = text(row, LINES_FIRST_DAY_COLUMN + day - 1, "", styles.get(EMPTY_ROSE, TEXT, false,
                                HorizontalAlignment.CENTER, false));
                    } else {
                        count++;
                        int people = shift.getEmployees().size();
                        boolean deficit = people < shift.getStaffCount();
                        cell = text(row, LINES_FIRST_DAY_COLUMN + day - 1, crewName(shift.getCrew()),
                                styles.get(deficit ? DEFICIT_AMBER : weekendOr(date, WHITE), TEXT, false,
                                        HorizontalAlignment.CENTER, false));
                        if (deficit) {
                            comment(sheet, drawing, cell, people + " из " + shift.getStaffCount());
                        }
                    }
                }
                number(row, totalColumn, count, styles.get(WHITE, TEXT, true, HorizontalAlignment.CENTER, false));
                rowIndex++;
            }
            // The line name once for its two rows
            sheet.addMergedRegion(new CellRangeAddress(rowIndex - 2, rowIndex - 1, 0, 0));
        }
        sheet.setColumnWidth(0, 22 * 256);
        sheet.setColumnWidth(1, 15 * 256);
        // Crew names ("Линия1/1") need more room than the employee letters
        for (int day = 0; day < days; day++) {
            sheet.setColumnWidth(LINES_FIRST_DAY_COLUMN + day, 9 * 256);
        }
        sheet.setColumnWidth(totalColumn, 7 * 256);
        sheet.createFreezePane(LINES_FIRST_DAY_COLUMN, HEADER_ROWS);
        printSetup(sheet);
    }

    // ---------- "По сотрудникам" ----------

    private void writeEmployees(Sheet sheet, CrewSchedule schedule, Line line, EmployeeDayStatus status,
            Styles styles, String title, String generated) {
        YearMonth month = schedule.getMonth();
        int days = month.lengthOfMonth();
        int totalsColumn = EMPLOYEES_FIRST_DAY_COLUMN + days;
        int lastColumn = totalsColumn + 3;
        writeTitle(sheet, styles, title, generated);
        Row header = headerRow(sheet);
        text(header, 0, "№", styles.header(false));
        text(header, 1, "Таб. №", styles.header(false));
        text(header, 2, "ФИО", styles.header(false));
        writeDayHeaders(header, month, EMPLOYEES_FIRST_DAY_COLUMN, styles);
        String[] totals = { "Д", "Н", "Смен", "Часов" };
        for (int i = 0; i < totals.length; i++) {
            text(header, totalsColumn + i, totals[i], styles.header(false));
        }

        // Employees grouped by their crew at the start of the month, crews by id, as in the UI
        Map<Crew, List<Employee>> byCrew = new LinkedHashMap<>();
        schedule.getEmployees().stream()
                .filter(employee -> line == null || worksOnLine(schedule, status, employee, line))
                .sorted(Comparator.comparing((Employee employee) -> employee.getCrew().getId())
                        .thenComparing(Employee.BY_POSITION_AND_NAME))
                .forEach(employee -> byCrew.computeIfAbsent(employee.getCrew(), crew -> new ArrayList<>())
                        .add(employee));

        Drawing<?> drawing = sheet.createDrawingPatriarch();
        int rowIndex = HEADER_ROWS;
        int number = 0;
        for (Map.Entry<Crew, List<Employee>> group : byCrew.entrySet()) {
            Row groupRow = sheet.createRow(rowIndex);
            for (int column = 0; column <= lastColumn; column++) {
                text(groupRow, column, "", styles.get(GROUP, TEXT, true, HorizontalAlignment.LEFT, false));
            }
            groupRow.getCell(0).setCellValue(crewName(group.getKey()) + " — " + ownLine(schedule, group.getKey())
                    + " · " + group.getValue().size() + " чел.");
            sheet.addMergedRegion(new CellRangeAddress(rowIndex, rowIndex, 0, 2));
            rowIndex++;
            for (Employee employee : group.getValue()) {
                Row row = sheet.createRow(rowIndex++);
                number(row, 0, ++number, styles.get(WHITE, TEXT, false, HorizontalAlignment.CENTER, false));
                number(row, 1, employee.getId(), styles.get(WHITE, TEXT, false, HorizontalAlignment.CENTER, false));
                text(row, 2, employee.getFullName(), styles.get(WHITE, TEXT, false, HorizontalAlignment.LEFT, false));
                int dayShifts = 0;
                int nightShifts = 0;
                double hours = 0;
                for (int day = 1; day <= days; day++) {
                    LocalDate date = month.atDay(day);
                    DayStatus dayStatus = status.get(employee, date);
                    String[] colors = CODE_COLORS.get(dayStatus.code());
                    boolean neutral = dayStatus.code() == Code.OFF || dayStatus.code() == Code.SLEEP;
                    Cell cell = text(row, EMPLOYEES_FIRST_DAY_COLUMN + day - 1, dayStatus.code().getLetter(),
                            styles.get(neutral ? weekendOr(date, colors[0]) : colors[0], colors[1],
                                    !neutral, HorizontalAlignment.CENTER, false));
                    String note = note(dayStatus);
                    if (note != null) {
                        comment(sheet, drawing, cell, note);
                    }
                    for (CrewShift shift : dayStatus.worked()) {
                        if (shift.getType() == ShiftType.DAY) {
                            dayShifts++;
                        } else {
                            nightShifts++;
                        }
                    }
                    hours += dayStatus.hours();
                }
                XSSFCellStyle total = styles.get(WHITE, TEXT, true, HorizontalAlignment.CENTER, false);
                number(row, totalsColumn, dayShifts, total);
                number(row, totalsColumn + 1, nightShifts, total);
                number(row, totalsColumn + 2, dayShifts + nightShifts, total);
                number(row, totalsColumn + 3, hours, styles.hours());
            }
        }
        sheet.setColumnWidth(0, 5 * 256);
        sheet.setColumnWidth(1, 9 * 256);
        sheet.setColumnWidth(2, 34 * 256);
        for (int day = 0; day < days; day++) {
            sheet.setColumnWidth(EMPLOYEES_FIRST_DAY_COLUMN + day, (int) (4.5 * 256));
        }
        for (int i = 0; i < totals.length; i++) {
            sheet.setColumnWidth(totalsColumn + i, (i < 2 ? 5 : 7) * 256);
        }
        sheet.createFreezePane(EMPLOYEES_FIRST_DAY_COLUMN, HEADER_ROWS);
        printSetup(sheet);
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

    private static boolean worksOnLine(CrewSchedule schedule, EmployeeDayStatus status, Employee employee, Line line) {
        YearMonth month = schedule.getMonth();
        for (int day = 1; day <= month.lengthOfMonth(); day++) {
            if (status.get(employee, month.atDay(day)).worked().stream()
                    .anyMatch(shift -> shift.getLine().equals(line))) {
                return true;
            }
        }
        return false;
    }

    private static String ownLine(CrewSchedule schedule, Crew crew) {
        if (crew.getLineId() == null) {
            return "без линии";
        }
        return schedule.getLines().stream().filter(line -> line.getId().equals(crew.getLineId())).findFirst()
                .map(ScheduleExcelExporter::lineName).orElse(crew.getLineId());
    }

    // ---------- "Нарушения" ----------

    private void writeWarnings(Sheet sheet, CrewSchedule schedule, List<ScheduleWarning> warnings, Styles styles,
            String title, String generated) {
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
        printSetup(sheet);
    }

    private static String who(CrewSchedule schedule, ScheduleWarning warning) {
        if (warning.type() == WarningType.EMPLOYEE_REST && warning.employeeId() != null) {
            return schedule.getEmployees().stream().filter(e -> e.getId().equals(warning.employeeId())).findFirst()
                    .map(e -> e.getFullName() + " (" + e.getId() + ")").orElse(String.valueOf(warning.employeeId()));
        }
        return schedule.getCrews().stream().filter(c -> c.getId().equals(warning.crewId())).findFirst()
                .map(ScheduleExcelExporter::crewName).orElse(Objects.toString(warning.crewId(), ""));
    }

    private static boolean isOnLine(CrewSchedule schedule, ScheduleWarning warning, Line line) {
        return schedule.getCrewShifts().stream()
                .anyMatch(shift -> shift.getId().equals(warning.crewShiftId()) && shift.getLine().equals(line));
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
    private static void writeDayHeaders(Row header, YearMonth month, int firstColumn, Styles styles) {
        for (int day = 1; day <= month.lengthOfMonth(); day++) {
            LocalDate date = month.atDay(day);
            text(header, firstColumn + day - 1, day + "\n" + WEEKDAYS[date.getDayOfWeek().getValue() - 1],
                    styles.header(isWeekend(date)));
        }
    }

    /** Landscape A3, one page wide, the header rows on every page. */
    private static void printSetup(Sheet sheet) {
        PrintSetup print = sheet.getPrintSetup();
        print.setLandscape(true);
        print.setPaperSize(PrintSetup.A3_PAPERSIZE);
        sheet.setFitToPage(true);
        sheet.setAutobreaks(true);
        print.setFitWidth((short) 1);
        print.setFitHeight((short) 0);
        sheet.setRepeatingRows(new CellRangeAddress(0, HEADER_ROWS - 1, -1, -1));
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

    /** One cell style per look: a workbook has at most 64000 styles, a month has thousands of cells. */
    private static final class Styles {

        private final XSSFWorkbook workbook;
        private final Map<String, XSSFCellStyle> cache = new HashMap<>();

        Styles(XSSFWorkbook workbook) {
            this.workbook = workbook;
        }

        XSSFCellStyle get(String fill, String fontColor, boolean bold, HorizontalAlignment align, boolean wrap) {
            String key = fill + "|" + fontColor + "|" + bold + "|" + align + "|" + wrap;
            return cache.computeIfAbsent(key, k -> {
                XSSFCellStyle style = workbook.createCellStyle();
                style.setFillForegroundColor(color(fill));
                style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
                style.setAlignment(align);
                style.setVerticalAlignment(VerticalAlignment.CENTER);
                style.setWrapText(wrap);
                for (BorderStyleSetter setter : BorderStyleSetter.values()) {
                    setter.set(style, color(BORDER));
                }
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
            return get(weekend ? "e2e8f0" : "f8fafc", TEXT, true, HorizontalAlignment.CENTER, true);
        }

        XSSFCellStyle hours() {
            return cache.computeIfAbsent("hours", k -> {
                XSSFCellStyle style = workbook.createCellStyle();
                style.cloneStyleFrom(get(WHITE, TEXT, true, HorizontalAlignment.CENTER, false));
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

        private static XSSFColor color(String hex) {
            return new XSSFColor(new byte[] { (byte) Integer.parseInt(hex.substring(0, 2), 16),
                    (byte) Integer.parseInt(hex.substring(2, 4), 16),
                    (byte) Integer.parseInt(hex.substring(4, 6), 16) }, null);
        }
    }

    /** Thin light borders on all four sides. */
    private enum BorderStyleSetter {
        TOP, BOTTOM, LEFT, RIGHT;

        void set(XSSFCellStyle style, XSSFColor color) {
            switch (this) {
                case TOP -> {
                    style.setBorderTop(BorderStyle.THIN);
                    style.setTopBorderColor(color);
                }
                case BOTTOM -> {
                    style.setBorderBottom(BorderStyle.THIN);
                    style.setBottomBorderColor(color);
                }
                case LEFT -> {
                    style.setBorderLeft(BorderStyle.THIN);
                    style.setLeftBorderColor(color);
                }
                case RIGHT -> {
                    style.setBorderRight(BorderStyle.THIN);
                    style.setRightBorderColor(color);
                }
            }
        }
    }
}
