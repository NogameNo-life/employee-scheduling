// Crew schedule UI: "По линиям" (grid of crew shifts per line and day) and "По сотрудникам" (shift codes per
// employee and day), a drawer on the right for one crew shift or one employee. Everything is drawn from the
// schedule version of this browser tab (X-Session-Id). Text is inserted escaped (escapeHtml / jQuery .text()).

// Every browser tab is one user with its own schedule version (same key as production-automation-portal-react)
const sessionId = getSessionId();

const MONTH_NAMES = ["Январь", "Февраль", "Март", "Апрель", "Май", "Июнь", "Июль", "Август", "Сентябрь",
  "Октябрь", "Ноябрь", "Декабрь"];
const WEEKDAYS = ["пн", "вт", "ср", "чт", "пт", "сб", "вс"];
const ABSENCES = {
  SICK_LEAVE: {code: "Б", name: "Больничный", css: "code-sick"},
  VACATION: {code: "ОТ", name: "Отпуск", css: "code-vacation"},
  DISMISSAL: {code: "У", name: "Увольнение", css: "code-dismissal"},
  OTHER: {code: "ПР", name: "Другое отсутствие", css: "code-other"}
};
// Corner markers of a "По сотрудникам" cell (also in the legend)
const OFF_MARKER = `<span class="off-marker fas fa-user-minus" aria-hidden="true"></span>`;
const WARN_MARKER = `<span class="warn-marker"></span>`;

// ---------- State ----------
// The whole version (GET /crew-schedules) and the rest warnings of the last answer (RotationResponse)
let lastSchedule = null;
let lastWarnings = [];
// Month of the version (yyyy-MM); without a version: the current month
let shownMonth = JSJoda.YearMonth.now().toString();
let view = loadView();
// What the drawer shows: {kind: "shift", id} or {kind: "employee", id}; null: closed
let drawer = null;
// The empty line shift the crew picker is open for: {date, type, lineId}
let pickerSlot = null;
// What the employee dialog of a "По сотрудникам" cell is open for: {mode, employeeId, crewShiftId}
let employeeDialog = null;
// The crew shift that is being dragged (HTML5 drag and drop)
let draggedShiftId = null;
// Times of the shift types (PLR_SM) from the server; these defaults are used until the first answer
let shiftTimes = [
  {type: "DAY", start: "08:00", end: "20:00", hours: 12},
  {type: "NIGHT", start: "20:00", end: "08:00", hours: 12}
];
// The generation waiting for the answer of the overwrite dialog: {lineId, from, startCrewId, pin}
let pendingGeneration = null;
// The period of the generation that was just sent ({from, to}), for the result toast
let generationPeriod = null;
// Employees chosen in the export dialog (personnel numbers); everybody on every open and switch to "По сотрудникам"
const exportSelection = new Set();
// What the clear dialog is open for: {lineId, preview}
let pendingClear = null;
// The line of the open sample dialog
let sampleLineId = null;
// Lookups, built again for every version (buildIndex)
let index = emptyIndex();

$(document).ready(function () {
  $("#prevMonthButton").click(() => openNewMonthDialog(plusMonths(shownMonth, -1)));
  $("#nextMonthButton").click(() => openNewMonthDialog(plusMonths(shownMonth, 1)));
  $("#newMonthButton").click(() => openNewMonthDialog(shownMonth));
  $("#newMonthModalSave").click(startNewMonth);
  $("#generateButton").click(openGenerateDialog);
  $("#generateLine").on("change", renderGenerateStartCrews);
  $("#generateFrom").on("change", onGenerateFromChange);
  $("#generateModalSave").click(startGeneration);
  $("#overwriteModalReplace").click(() => answerOverwrite(true));
  $("#overwriteModalFill").click(() => answerOverwrite(false));
  $("#bulkPinButton").click(() => bulkPin(true));
  $("#bulkUnpinButton").click(() => bulkPin(false));
  $("#clearButton").click(clearFromGenerateDialog);
  $("#exportButton").click(openExportDialog);
  $("input[name=exportWhat]").on("change", () => {
    if (exportMode() === "employees") {
      selectAllExportEmployees();
    }
    updateExportDialog();
  });
  // The search only filters the list, the selection stays
  $("#exportSearch").on("input", renderExportEmployees);
  $("#exportSelectAll").on("change", event => {
    event.target.checked ? selectAllExportEmployees() : exportSelection.clear();
    updateExportChecks();
  });
  $("#exportEmployeeList").on("change", ".export-employee-check", event => {
    const id = parseInt(event.target.value);
    event.target.checked ? exportSelection.add(id) : exportSelection.delete(id);
    updateExportChecks();
  });
  $("#exportEmployeeList").on("change", ".export-crew-check", event => {
    const crewId = parseInt($(event.target).data("crew"));
    employeesOfCrew(crewId).forEach(employee => event.target.checked
      ? exportSelection.add(employee.id) : exportSelection.delete(employee.id));
    updateExportChecks();
  });
  $("#exportModalSave").click(saveExport);
  $("#clearIncludePinned").on("change", renderClearDialog);
  $("#clearModalSave").click(saveClear);
  $("#lineMenuClear").click(() => {
    const lineId = $("#lineMenu").data("line");
    closeLineMenu();
    openClearDialog(lineId, null, null);
  });
  // The line menu closes on a click elsewhere, Escape and scrolling
  $(document).on("click", event => {
    if (!$(event.target).closest("#lineMenu, .line-menu-button").length) {
      closeLineMenu();
    }
  });
  $(document).on("keydown", event => {
    if (event.key === "Escape") {
      closeLineMenu();
    }
  });
  $(window).on("resize", closeLineMenu);
  $("#generateLineList").on("click", ".sample-link", event => openSampleDialog(String($(event.currentTarget).data("line"))));
  $("#sampleFrom").on("change", () => {
    updateSampleToMin();
    renderSampleDays();
  });
  $("#sampleSave").click(() => saveSample(false));
  $("#sampleSaveFill").click(() => saveSample(true));
  $("#viewLinesButton").click(() => setView("lines"));
  $("#viewEmployeesButton").click(() => setView("employees"));
  $("#warningChip").click(() => scrollToFirst("warning"));
  $("#deficitChip").click(() => scrollToFirst("deficit"));
  $("#crewShiftModalSave").click(saveCrewPicker);
  $("#shiftEmployeeModalSave").click(saveShiftEmployee);
  $("#shiftEmployeeModalSelect").on("change", updateShiftEmployeeButton);
  $("#drawerClose").click(closeDrawer);
  $(window).on("resize", fitGridHeight);

  // Grid events (the grid is drawn again after every change, so the handlers are delegated)
  const grid = $("#gridScroll");
  grid.on("click", ".shift-card[data-id]", event => openDrawer("shift", $(event.currentTarget).data("id")));
  grid.on("click", ".shift-card.empty", event => {
    const card = $(event.currentTarget);
    openCrewPicker({date: card.data("date"), type: card.data("type"), lineId: String(card.data("line"))});
  });
  grid.on("click", ".line-menu-button", event => toggleLineMenu($(event.currentTarget)));
  grid.on("scroll", closeLineMenu);
  grid.on("click", ".emp-label", event => openDrawer("employee", $(event.currentTarget).data("employee")));
  grid.on("click", ".emp-cell", event => {
    const cell = $(event.currentTarget);
    onEmployeeCellClick(cell.data("employee"), cell.data("date"));
  });
  grid.on("dragstart", ".shift-card[draggable=true]", onDragStart);
  grid.on("dragover", ".shift-card", onDragOver);
  grid.on("dragleave", ".shift-card", event => $(event.currentTarget).removeClass("drop-target"));
  grid.on("drop", ".shift-card", onDrop);
  grid.on("dragend", ".shift-card", () => {
    draggedShiftId = null;
    $(".shift-card.dragging, .shift-card.drop-target").removeClass("dragging drop-target");
  });

  setupAjax();
  renderAll();
  refreshSchedule();
});

function setupAjax() {
  $.ajaxSetup({
    headers: {
      'Content-Type': 'application/json',
      'Accept': 'application/json',
      'X-Session-Id': sessionId,
    }
  });
}

// Sends a JSON request; the answer is JSON too
function send(method, url, body) {
  return $.ajax({url: url, type: method, data: body == null ? undefined : JSON.stringify(body), dataType: "json"});
}

function crewShiftUrl(crewShiftId) {
  return "/crew-schedules/crew-shifts/" + encodeURIComponent(crewShiftId);
}

// ---------- Loading ----------

function refreshSchedule() {
  $.getJSON("/crew-schedules/rotation", onRosterChanged).fail(xhr => {
    if (xhr.status === 404) {
      // No version yet (e.g. the crews could not be loaded at startup)
      lastSchedule = null;
      lastWarnings = [];
      renderAll();
    }
    showError("Не удалось загрузить график.", xhr);
  });
}

// Every change is answered with the crew schedule (RotationResponse): warnings, shift times, generation.
// The whole version (people of every shift, employees) is loaded after it.
function onRosterChanged(roster) {
  lastWarnings = roster.warnings || [];
  setShiftTimes(roster.shifts);
  $("#version").text(`Сессия: ${roster.sessionId}, версия: ${roster.versionId}`);
  showGeneration(roster.generation);
  return $.getJSON("/crew-schedules", schedule => {
    lastSchedule = schedule;
    shownMonth = schedule.month;
    setShiftTimes(schedule.shifts);
    renderAll();
  }).fail(xhr => showError("Не удалось загрузить график.", xhr));
}

function setShiftTimes(shifts) {
  if (shifts != null && shifts.length > 0) {
    shiftTimes = shifts;
  }
}

// ---------- Lookups ----------

function emptyIndex() {
  return {
    days: [], lines: [], linesById: new Map(), crewsById: new Map(), employeesById: new Map(),
    shiftsById: new Map(), shiftsBySlot: new Map(), shiftsByDate: new Map(), warningsByShift: new Map(),
    // employee id -> date -> {worked: [crew shifts], off: [crew shifts they were taken off]}
    daysByEmployee: new Map(), employeeWarnings: new Map()
  };
}

function buildIndex() {
  index = emptyIndex();
  const month = JSJoda.YearMonth.parse(shownMonth);
  for (let day = 1; day <= month.lengthOfMonth(); day++) {
    index.days.push(month.atDay(day).toString());
  }
  if (lastSchedule == null) {
    return;
  }
  index.lines = lastSchedule.lines;
  lastSchedule.lines.forEach(line => index.linesById.set(line.id, line));
  lastSchedule.crews.forEach(crew => index.crewsById.set(crew.id, crew));
  lastSchedule.employees.forEach(employee => {
    index.employeesById.set(employee.id, employee);
    index.daysByEmployee.set(employee.id, new Map());
  });
  lastSchedule.crewShifts.forEach(shift => {
    index.shiftsById.set(shift.id, shift);
    index.shiftsBySlot.set(slotKey(shift.line.id, shift.date, shift.type), shift);
    pushTo(index.shiftsByDate, shift.date, shift);
    shift.employeeIds.forEach(id => employeeDay(id, shift.date).worked.push(shift));
    shift.absentEmployeeIds.forEach(id => employeeDay(id, shift.date).off.push(shift));
  });
  lastWarnings.forEach(warning => pushTo(index.warningsByShift, warning.crewShiftId, warning));
  // Rest warnings of an employee: their own, and the ones of their crew on shifts they work as a member
  // (the server does not repeat a crew warning for every member)
  lastWarnings.forEach(warning => {
    const shift = index.shiftsById.get(warning.crewShiftId);
    if (warning.employeeId != null) {
      pushTo(index.employeeWarnings, warning.employeeId, warning);
    } else if (shift != null) {
      shift.employeeIds.filter(id => !shift.extraEmployeeIds.includes(id))
        .forEach(id => pushTo(index.employeeWarnings, id, warning));
    }
  });
}

function employeeDay(employeeId, date) {
  let days = index.daysByEmployee.get(employeeId);
  if (days == null) {
    days = new Map();
    index.daysByEmployee.set(employeeId, days);
  }
  if (!days.has(date)) {
    days.set(date, {worked: [], off: []});
  }
  return days.get(date);
}

function pushTo(map, key, value) {
  if (!map.has(key)) {
    map.set(key, []);
  }
  map.get(key).push(value);
}

function slotKey(lineId, date, type) {
  return `${lineId}|${date}|${type}`;
}

// staffCount null = the crew has no PLR_BR.NTB: no deficit then
function hasStaffCount(shift) {
  return shift.staffCount != null;
}

function isDeficit(shift) {
  return hasStaffCount(shift) && shift.employeeIds.length < shift.staffCount;
}

// "10/12", or only "10" when the crew has no norm
function peopleText(shift, separator = "/") {
  return hasStaffCount(shift) ? `${shift.employeeIds.length}${separator}${shift.staffCount}` : `${shift.employeeIds.length}`;
}

// ---------- Drawing ----------

function renderAll() {
  closeLineMenu();
  buildIndex();
  renderHeader();
  renderLegend();
  fitGridHeight();
  renderGrid();
  renderStats();
  renderDrawer();
}

function renderHeader() {
  $("#monthLabel").text(monthTitle(shownMonth));
  $("#viewLinesButton").toggleClass("active", view === "lines").attr("aria-pressed", String(view === "lines"));
  $("#viewEmployeesButton").toggleClass("active", view === "employees")
    .attr("aria-pressed", String(view === "employees"));
  const warnings = lastWarnings.length;
  const deficits = lastSchedule == null ? 0 : lastSchedule.crewShifts.filter(isDeficit).length;
  $("#warningChip").toggleClass("active", warnings > 0).prop("disabled", warnings === 0)
    .attr("title", warnings > 0 ? "Показать первое нарушение" : "")
    .find(".text").text(`${warnings} ${plural(warnings, "нарушение", "нарушения", "нарушений")} отдыха`);
  $("#deficitChip").toggleClass("active", deficits > 0).prop("disabled", deficits === 0)
    .attr("title", deficits > 0 ? "Показать первую смену с дефицитом" : "")
    .find(".text").text(`${deficits} ${plural(deficits, "смена", "смены", "смен")} с дефицитом`);
}

// The legend shows what the current view draws: cards in "По линиям", letters in "По сотрудникам"
function renderLegend() {
  const items = view === "lines" ? [
    legendCard("day", `<span class="dot"></span>`, shiftLabel("DAY")),
    legendCard("night", `<span class="dot"></span>`, shiftLabel("NIGHT")),
    legendCard("day", `<span class="pin fas fa-lock"></span>`, "закреплена"),
    legendCard("day deficit", `<span class="people">5/6</span>`, "не хватает людей"),
    legendCard("day rest-warning", "", "нарушение отдыха"),
    legendCard("empty", `<span class="fas fa-plus"></span>`, "нет бригады")
  ] : [
    legendItem("Д", "code-day", shiftLabel("DAY")),
    legendItem("Н", "code-night", shiftLabel("NIGHT")),
    legendItem("О", "code-sleep", "Отсыпной"),
    legendItem("В", "code-off", "Выходной"),
    ...Object.values(ABSENCES).map(absence => legendItem(absence.code, absence.css, absence.name)),
    legendItem("Д", "code-day not-working", "Снят со смены", OFF_MARKER),
    legendItem("Д", "code-day substitute", "Замена"),
    legendItem("Д", "code-day", "Нарушение отдыха", WARN_MARKER)
  ];
  const hint = view === "lines"
    ? "Нажмите на смену — подробности. Перетащите на смену той же линии и типа — обмен бригадами, на пустую — перенос."
    : "ФИО — карточка сотрудника, клетка — изменить смену в этот день.";
  $("#legend").html(items.join("") + `<span class="legend-hint" title="${escapeHtml(hint)}">${escapeHtml(hint)}</span>`);
}

function legendItem(code, css, text, marker = "") {
  return `<span class="legend-item"><span class="cell-badge"><span class="code ${css}">${escapeHtml(code)}</span>${marker}</span>`
    + `${escapeHtml(text)}</span>`;
}

// A small shift card (only for the legend: no data-id, so the grid events do not apply)
function legendCard(classes, content, text) {
  return `<span class="legend-item"><span class="shift-card legend-card ${classes}" aria-hidden="true">${content}</span>`
    + `${escapeHtml(text)}</span>`;
}

// The top of the grid on the page (header + legend) can change (the toolbar wraps on narrow screens),
// so it is measured: the grid then fills the rest of the window and scrolls inside
function fitGridHeight() {
  const top = document.getElementById("gridScroll").getBoundingClientRect().top + window.scrollY;
  document.documentElement.style.setProperty("--grid-top", `${Math.round(top)}px`);
}

function renderGrid() {
  const container = $("#gridScroll");
  const scroll = {left: container.scrollLeft(), top: container.scrollTop()};
  if (lastSchedule == null) {
    container.html(`<div class="empty-state">Нет версии графика. Нажмите «Новый пустой месяц».</div>`);
    return;
  }
  if (lastSchedule.lines.length === 0) {
    container.html(`<div class="empty-state">В этом месяце нет линий.</div>`);
    return;
  }
  container.html(view === "lines" ? linesGridHtml() : employeesGridHtml());
  container.scrollLeft(scroll.left).scrollTop(scroll.top);
  if (drawer != null && drawer.kind === "shift") {
    container.find(`.shift-card[data-id="${cssEscape(drawer.id)}"]`).addClass("selected");
  }
}

// ----- "По линиям" -----

function linesGridHtml() {
  const today = JSJoda.LocalDate.now().toString();
  const head = index.days.map(date => {
    const shifts = index.shiftsByDate.get(date) || [];
    const people = shifts.reduce((sum, shift) => sum + shift.employeeIds.length, 0);
    // The norm counts only the shifts of crews that have one (PLR_BR.NTB)
    const withNorm = shifts.filter(hasStaffCount);
    const needed = withNorm.reduce((sum, shift) => sum + shift.staffCount, 0);
    const peopleWithNorm = withNorm.reduce((sum, shift) => sum + shift.employeeIds.length, 0);
    const short = peopleWithNorm < needed;
    let text = "—";
    let title = "";
    if (shifts.length > 0 && withNorm.length === 0) {
      text = `${people}`;
      title = "Людей во всех сменах дня (норма бригад не задана в PLR_BR.NTB)";
    } else if (withNorm.length === shifts.length) {
      text = `${people} / ${needed}`;
      title = "Людей во всех сменах дня / нужно";
    } else if (shifts.length > 0) {
      text = `${peopleWithNorm} / ${needed}`;
      title = `Людей / нужно в сменах бригад с нормой (PLR_BR.NTB). Всего людей в сменах дня: ${people}`;
    }
    return `<th class="day-head${weekendClass(date)}${date === today ? " today" : ""}" scope="col"><span
      class="day-number">${dayNumber(date)}</span><span class="day-week">${weekday(date)}</span><span
      class="day-people${short ? " short" : ""}" title="${title}">${text.replace(" / ", "/")}</span></th>`;
  }).join("");
  const rows = index.lines.map(line => {
    const cells = index.days.map(date => `<td class="line-cell${date === today ? " today" : ""}">`
      + shiftCardHtml(line, date, "DAY") + shiftCardHtml(line, date, "NIGHT") + `</td>`).join("");
    const code = line.name === line.id ? "" : `<div class="line-code">${escapeHtml(line.id)}</div>`;
    const menu = `<button type="button" class="line-menu-button" data-line="${escapeHtml(line.id)}" aria-haspopup="menu"
      aria-label="Меню линии ${escapeHtml(line.name)}" title="Меню линии"><span class="fas fa-ellipsis-h"></span></button>`;
    return `<tr><th class="sticky-col line-head" scope="row">${menu}<div class="line-name">${escapeHtml(line.name)}</div>${code}</th>${cells}</tr>`;
  }).join("");
  return `<table class="grid-table"><thead><tr><th class="sticky-col line-head" scope="col">Линия</th>${head}</tr></thead>`
    + `<tbody>${rows}</tbody></table>`;
}

function shiftCardHtml(line, date, type) {
  const shift = index.shiftsBySlot.get(slotKey(line.id, date, type));
  const typeClass = type === "DAY" ? "day" : "night";
  if (shift == null) {
    const label = `${type === "DAY" ? "Дневная" : "Ночная"} смена ${formatDate(date)}: нет бригады. Нажмите, чтобы поставить.`;
    return `<button type="button" class="shift-card empty" data-line="${escapeHtml(line.id)}" data-date="${date}"
      data-type="${type}" title="${escapeHtml(label)}"><span class="fas fa-plus"></span>${type === "DAY" ? "Д" : "Н"}</button>`;
  }
  const warnings = index.warningsByShift.get(shift.id) || [];
  const classes = ["shift-card", typeClass];
  if (isDeficit(shift)) {
    classes.push("deficit");
  }
  if (warnings.length > 0) {
    classes.push("rest-warning");
  }
  if (shift.pinned) {
    classes.push("pinned");
  }
  // The card has no room for the shift type and times: they are in the tooltip (and the drawer)
  const tooltip = [`${crewName(shift.crew)}, ${shiftLabel(type)}, ${formatDate(date)}`,
    hasStaffCount(shift) ? `Людей: ${shift.employeeIds.length} из ${shift.staffCount}`
      : `Людей: ${shift.employeeIds.length} (норма бригады не задана в PLR_BR.NTB)`,
    ...(shift.pinned ? ["Закреплена"] : []),
    ...warnings.map(warning => warning.message)].join("\n");
  return `<button type="button" class="${classes.join(" ")}" data-id="${escapeHtml(shift.id)}"
      draggable="${!shift.pinned}" title="${escapeHtml(tooltip)}">
<span class="dot"></span><span class="crew">${escapeHtml(crewName(shift.crew))}</span><span
    class="people">${peopleText(shift)}</span>${shift.pinned ? `<span class="pin fas fa-lock" aria-label="Закреплена"></span>` : ""}</button>`;
}

// ----- Drag and drop: onto a card of the same line and shift type = swap the crews, onto an empty card = move -----

function onDragStart(event) {
  draggedShiftId = $(event.currentTarget).data("id");
  event.originalEvent.dataTransfer.effectAllowed = "move";
  event.originalEvent.dataTransfer.setData("text/plain", draggedShiftId);
  $(event.currentTarget).addClass("dragging");
}

function onDragOver(event) {
  const target = $(event.currentTarget);
  if (dropAction(target) != null) {
    event.preventDefault();
    event.originalEvent.dataTransfer.dropEffect = "move";
    target.addClass("drop-target");
  }
}

function onDrop(event) {
  event.preventDefault();
  const target = $(event.currentTarget).removeClass("drop-target");
  const action = dropAction(target);
  const source = index.shiftsById.get(draggedShiftId);
  draggedShiftId = null;
  if (action === "swap") {
    swapCrews(source, index.shiftsById.get(target.data("id")));
  } else if (action === "move") {
    moveCrewShift(source, {date: target.data("date"), type: target.data("type"), lineId: String(target.data("line"))});
  }
}

// "swap", "move" or null (not allowed)
function dropAction(target) {
  const source = draggedShiftId == null ? null : index.shiftsById.get(draggedShiftId);
  if (source == null || source.pinned) {
    return null;
  }
  if (target.hasClass("empty")) {
    return "move";
  }
  const other = index.shiftsById.get(target.data("id"));
  if (other == null || other.id === source.id || other.pinned) {
    return null;
  }
  return other.line.id === source.line.id && other.type === source.type ? "swap" : null;
}

// Two PUT calls: a gets b's crew, then b gets a's crew. If the second one fails, the version is loaded again.
function swapCrews(a, b) {
  const body = (shift, crewId) => ({date: shift.date, type: shift.type, lineId: shift.line.id, crewId: crewId});
  send("PUT", crewShiftUrl(a.id), body(a, b.crew.id))
    .done(() => send("PUT", crewShiftUrl(b.id), body(b, a.crew.id))
      .done(onRosterChanged)
      .fail(xhr => {
        showError("Обмен бригадами не завершён: вторая смена не изменена. График загружен заново.", xhr);
        refreshSchedule();
      }))
    .fail(xhr => showError("Не удалось поменять бригады местами.", xhr));
}

function moveCrewShift(shift, slot) {
  send("PUT", crewShiftUrl(shift.id), {...slot, crewId: shift.crew.id})
    .done(roster => {
      if (drawer != null && drawer.kind === "shift" && drawer.id === shift.id) {
        drawer.id = `${slot.date}-${slot.type}-${slot.lineId}`; // The id of the moved crew shift
      }
      onRosterChanged(roster);
    })
    .fail(xhr => showError("Не удалось перенести смену.", xhr));
}

// ----- "По сотрудникам" -----

function employeesGridHtml() {
  const today = JSJoda.LocalDate.now().toString();
  const head = index.days.map(date => `<th class="emp-day-head${weekendClass(date)}${date === today ? " today" : ""}"
    scope="col">${dayNumber(date)}<span class="day-week">${weekday(date)}</span></th>`).join("");
  const employeesByCrew = new Map();
  lastSchedule.employees.forEach(employee => pushTo(employeesByCrew, employee.crew.id, employee));
  const rows = [...lastSchedule.crews].sort((a, b) => a.id - b.id).map(crew => {
    const employees = (employeesByCrew.get(crew.id) || []).sort(byPositionAndName);
    const line = crew.lineId == null ? null : index.linesById.get(crew.lineId);
    const lineText = line == null ? (crew.lineId || "без линии") : line.name;
    const count = `${employees.length} ${plural(employees.length, "сотрудник", "сотрудника", "сотрудников")}`;
    const crewRow = `<tr class="crew-row"><td class="sticky-col crew-row-label">${escapeHtml(crewName(crew))}
      <span class="muted">${escapeHtml(lineText)} · ${count}</span></td><td colspan="${index.days.length}"></td></tr>`;
    return crewRow + employees.map(employee => employeeRowHtml(employee, today)).join("");
  }).join("");
  return `<table class="grid-table"><thead><tr><th class="sticky-col emp-head" scope="col">Сотрудник</th>${head}</tr></thead>`
    + `<tbody>${rows}</tbody></table>`;
}

function employeeRowHtml(employee, today) {
  const totals = monthTotals(employee);
  const label = `<th class="sticky-col emp-label" scope="row" data-employee="${employee.id}" title="Открыть карточку сотрудника">
    <span class="emp-name">${escapeHtml(employee.fullName)}</span>
    <span class="emp-sub">таб. № ${employee.id} · ${totals.shifts} ${plural(totals.shifts, "смена", "смены", "смен")}, ${formatHours(totals.hours)} ч</span></th>`;
  const warned = new Set((index.employeeWarnings.get(employee.id) || []).map(warning => warning.crewShiftId));
  const cells = index.days.map(date => {
    const cell = employeeCell(employee, date);
    const marker = (cell.takenOff ? OFF_MARKER : "")
      + (cell.shifts.some(shift => warned.has(shift.id)) ? WARN_MARKER : "");
    return `<td class="emp-cell${date === today ? " today" : ""}" data-employee="${employee.id}" data-date="${date}"
      title="${escapeHtml(cell.title)}"><span class="cell-badge"><span class="code ${cell.css}">${escapeHtml(cell.code)}</span>${marker}</span></td>`;
  }).join("");
  return `<tr>${label}${cells}</tr>`;
}

// TODO: the same rule is on the server (EmployeeDayStatus, used by the Excel export); use one source later.
// What an employee does on a date: works (Д / Н, dashed = substitute), is absent (Б, ОТ, У, ПР), was taken off a
// shift of the crew (crossed out), otherwise О (after a night shift) or В
function employeeCell(employee, date) {
  const day = index.daysByEmployee.get(employee.id)?.get(date) || {worked: [], off: []};
  if (day.worked.length > 0) {
    const shifts = [...day.worked].sort((a, b) => a.type.localeCompare(b.type));
    const substitute = shifts.some(shift => shift.extraEmployeeIds.includes(employee.id));
    return {
      shifts: shifts,
      code: shifts.map(shift => shift.type === "DAY" ? "Д" : "Н").join(""),
      css: (shifts[0].type === "DAY" ? "code-day" : "code-night") + (substitute ? " substitute" : ""),
      title: shifts.map(shift => `${shiftLabel(shift.type)}, ${shift.line.name}, бригада ${crewName(shift.crew)}`
        + (shift.extraEmployeeIds.includes(employee.id) ? " (замена)" : "")).concat(warningsText(employee, shifts)).join("\n")
    };
  }
  const absence = absenceOn(employee, date);
  if (absence != null) {
    const type = ABSENCES[absence.type] || ABSENCES.OTHER;
    return {shifts: [], code: type.code, css: type.css, title: describeAbsence(absence)};
  }
  if (day.off.length > 0) {
    const shift = day.off[0];
    return {
      shifts: [], takenOff: true, code: shift.type === "DAY" ? "Д" : "Н",
      css: (shift.type === "DAY" ? "code-day" : "code-night") + " not-working",
      title: `Снят со смены: ${shiftLabel(shift.type)}, ${shift.line.name}. Нажмите, чтобы вернуть.`
    };
  }
  const previous = JSJoda.LocalDate.parse(date).minusDays(1).toString();
  const afterNight = (index.daysByEmployee.get(employee.id)?.get(previous)?.worked || []).some(s => s.type === "NIGHT");
  return afterNight
    ? {shifts: [], code: "О", css: "code-sleep", title: "Отсыпной после ночной смены"}
    : {shifts: [], code: "В", css: "code-off", title: "Выходной. Нажмите, чтобы добавить в смену бригады."};
}

function warningsText(employee, shifts) {
  const ids = new Set(shifts.map(shift => shift.id));
  return (index.employeeWarnings.get(employee.id) || []).filter(w => ids.has(w.crewShiftId)).map(w => w.message);
}

// Crew shifts, DAY / NIGHT and hours the employee works in the month (hours from the shift times)
function monthTotals(employee) {
  const totals = {shifts: 0, day: 0, night: 0, hours: 0};
  (index.daysByEmployee.get(employee.id) || new Map()).forEach(day => day.worked.forEach(shift => {
    totals.shifts++;
    totals[shift.type === "DAY" ? "day" : "night"]++;
    totals.hours += shift.hours;
  }));
  return totals;
}

function onEmployeeCellClick(employeeId, date) {
  const employee = index.employeesById.get(employeeId);
  if (employee == null) {
    return;
  }
  const day = index.daysByEmployee.get(employeeId)?.get(date) || {worked: [], off: []};
  if (day.worked.length > 0) {
    openShiftEmployeeDialog("works", employeeId, day.worked[0].id);
  } else if (absenceOn(employee, date) != null) {
    openDrawer("employee", employeeId);
  } else if (day.off.length > 0) {
    openShiftEmployeeDialog("absent", employeeId, day.off[0].id);
  } else {
    const shifts = index.shiftsByDate.get(date) || [];
    if (shifts.length === 0) {
      showSimpleError(`${formatDate(date)} нет смен бригад. Сначала поставьте бригаду в виде «По линиям».`);
      return;
    }
    openShiftEmployeeDialog("add", employeeId, null, shifts);
  }
}

// ----- Footer -----

function renderStats() {
  const shifts = lastSchedule == null ? [] : lastSchedule.crewShifts;
  const stats = [
    ["fas fa-industry", "Линий в версии", lastSchedule == null ? 0 : lastSchedule.lines.length],
    ["fas fa-calendar-check", "Смен бригад в месяце", shifts.length],
    ["fas fa-user-minus", "Смен с дефицитом", shifts.filter(isDeficit).length],
    ["fas fa-exclamation-triangle", "Нарушений отдыха", lastWarnings.length]
  ];
  $("#stats").html(stats.map(([icon, label, value]) => `<div class="panel stat">
    <span class="stat-icon"><span class="${icon}"></span></span>
    <div><div class="label">${escapeHtml(label)}</div><div class="value num">${value}</div></div></div>`).join(""));
}

// ----- View switch and chips -----

function setView(newView) {
  view = newView;
  try {
    sessionStorage.setItem("crewView", view);
  } catch (e) {
    // No sessionStorage: the view is not remembered
  }
  renderAll();
}

function loadView() {
  try {
    return sessionStorage.getItem("crewView") === "employees" ? "employees" : "lines";
  } catch (e) {
    return "lines";
  }
}

// Scrolls to the first crew shift with a rest warning or a deficit (in "По сотрудникам": the first cell with a
// rest warning; a deficit is only shown in "По линиям")
function scrollToFirst(kind) {
  if (kind === "deficit" && view !== "lines") {
    setView("lines");
  }
  let element = kind === "deficit"
    ? $("#gridScroll .shift-card.deficit").first()
    : (view === "lines" ? $("#gridScroll .shift-card.rest-warning").first() : $("#gridScroll .warn-marker").first().closest(".emp-cell"));
  if (element.length === 0 && kind === "warning" && view !== "lines") {
    setView("lines");
    element = $("#gridScroll .shift-card.rest-warning").first();
  }
  if (element.length === 0) {
    return;
  }
  element[0].scrollIntoView({behavior: "smooth", block: "center", inline: "center"});
  element.removeClass("flash");
  setTimeout(() => element.addClass("flash"), 10);
}

// ---------- Drawer ----------

function openDrawer(kind, id) {
  drawer = {kind: kind, id: id};
  renderGrid();
  renderDrawer();
}

function closeDrawer() {
  drawer = null;
  $("#gridScroll .shift-card.selected").removeClass("selected");
  $("#drawer").prop("hidden", true);
}

function renderDrawer() {
  if (drawer == null || lastSchedule == null) {
    $("#drawer").prop("hidden", true);
    return;
  }
  const found = drawer.kind === "shift"
    ? renderShiftDrawer(index.shiftsById.get(drawer.id))
    : renderEmployeeDrawer(index.employeesById.get(drawer.id));
  if (!found) {
    closeDrawer(); // The crew shift was removed (or moved by someone else) or the employee is gone
    return;
  }
  $("#drawer").prop("hidden", false);
}

// ----- One crew shift: line, date, shift, crew, people with the existing actions, change crew, pin, remove -----

function renderShiftDrawer(shift) {
  if (shift == null) {
    return false;
  }
  const typeClass = shift.type === "DAY" ? "day" : "night";
  $("#drawerHeader").empty()
    .append($(`<div class="d-flex flex-wrap gap-2 align-items-center mb-2"/>`)
      .append($(`<span class="pill ${typeClass}"/>`).text(shiftLabel(shift.type)))
      .append($(`<span class="text-muted"/>`).text(`${formatDate(shift.date)}, ${weekday(shift.date)}`)))
    .append($(`<h2 class="drawer-title"/>`).text(shift.line.name))
    .append($(`<div class="drawer-subtitle"/>`).text(`Бригада ${crewName(shift.crew)}`
      + (shift.line.name === shift.line.id ? "" : ` · ${shift.line.id}`)));

  const body = $("#drawerBody").empty();
  const deficit = isDeficit(shift);
  body.append($(`<div class="drawer-section"/>`).append($(`<div class="stat-boxes"/>`)
    .append(statBox("Людей", peopleText(shift, " / "),
      deficit ? "не хватает" : hasStaffCount(shift) ? "" : "норма не задана", deficit))
    .append(statBox("Часы", formatHours(shift.hours), `${timeOf(shift.type).start}–${timeOf(shift.type).end}`))
    .append(statBox("Закреплена", shift.pinned ? "Да" : "Нет", ""))));

  const warnings = index.warningsByShift.get(shift.id) || [];
  if (warnings.length > 0) {
    body.append($(`<div class="drawer-section"/>`).append($(`<h6/>`).text("Нарушения отдыха"))
      .append($(`<ul class="warning-list"/>`).append(warnings.map(w => $(`<li/>`).text(w.message)))));
  }

  // Crew, pin, remove
  const crewSelect = $(`<select class="form-select form-select-sm" id="drawerCrew" aria-label="Бригада"/>`);
  fillCrewSelect(crewSelect, {date: shift.date, type: shift.type, lineId: shift.line.id, crewShiftId: shift.id},
    shift.crew.id);
  const crewSection = $(`<div class="drawer-section"/>`).append($(`<h6/>`).text("Бригада"))
    .append($(`<div class="input-group input-group-sm mb-2"/>`).append(crewSelect)
      .append($(`<button type="button" class="btn btn-outline-dark"/>`).text("Сменить")
        .prop("disabled", shift.pinned)
        .click(() => changeCrew(shift, parseInt(crewSelect.val())))))
    .append($(`<div class="d-flex gap-2"/>`)
      .append($(`<button type="button" class="btn btn-sm btn-outline-dark"/>`)
        .append($(`<span class="fas me-1"/>`).addClass(shift.pinned ? "fa-lock-open" : "fa-lock"))
        .append(document.createTextNode(shift.pinned ? "Открепить" : "Закрепить"))
        .click(() => setPinned(shift, !shift.pinned)))
      .append($(`<button type="button" class="btn btn-sm btn-outline-danger"/>`)
        .append($(`<span class="fas fa-trash-alt me-1"/>`)).append(document.createTextNode("Удалить смену"))
        .prop("disabled", shift.pinned)
        .click(() => removeCrewShift(shift))));
  if (shift.pinned) {
    crewSelect.prop("disabled", true);
    crewSection.append($(`<p class="text-muted small mt-2 mb-0"/>`)
      .text("Закреплённую смену нельзя перенести, удалить или отдать другой бригаде. Людей менять можно."));
  }
  body.append(crewSection);
  body.append(shiftPeopleSection(shift));
  return true;
}

// People of one crew shift: take off, works again, replace, add. Only this shift changes, not the crew.
function shiftPeopleSection(shift) {
  const free = freeEmployees(shift.date, shift.type).filter(e => !shift.absentEmployeeIds.includes(e.id));
  // Working employees first (crew members, then substitutes), then the ones taken off, then the absent ones
  const status = id => shift.unavailableEmployeeIds.includes(id) ? 3
    : shift.absentEmployeeIds.includes(id) ? 2 : (shift.extraEmployeeIds.includes(id) ? 1 : 0);
  const ids = [...new Set([...shift.employeeIds, ...shift.absentEmployeeIds, ...shift.unavailableEmployeeIds])]
    .filter(id => index.employeesById.has(id))
    .sort((a, b) => status(a) - status(b)
      || byPositionAndName(index.employeesById.get(a), index.employeesById.get(b)));
  const url = crewShiftUrl(shift.id);
  const section = $(`<div class="drawer-section"/>`)
    .append($(`<h6/>`).text(`Люди смены (${peopleText(shift, " из ")})`))
    .append($(`<p class="text-muted small"/>`).text("Изменения касаются только этой смены, не бригады."));
  if (ids.length === 0) {
    section.append($(`<p class="text-muted"/>`).text("В этой смене никто не работает."));
  }
  ids.forEach(id => {
    const employee = index.employeesById.get(id);
    const inactive = status(id) >= 2;
    const actions = $(`<div class="person-actions"/>`);
    let statusText;
    if (status(id) === 3) {
      const absence = absenceOn(employee, shift.date);
      statusText = absence == null ? "отсутствует" : (ABSENCES[absence.type] || ABSENCES.OTHER).name.toLowerCase();
      actions.append($(`<button type="button" class="btn btn-sm btn-outline-secondary"/>`).text("Отсутствия")
        .click(() => openDrawer("employee", id)));
    } else if (inactive) {
      statusText = "снят с этой смены";
      actions.append($(`<button type="button" class="btn btn-sm btn-outline-primary"/>`).text("Вернуть")
        .click(() => changeShiftPeople(send("PUT", `${url}/employees/${id}`))));
    } else {
      statusText = status(id) === 1 ? `замена из бригады ${crewName(crewOn(employee, shift.date))}` : "бригада";
      actions.append($(`<select class="form-select form-select-sm" aria-label="Заменить"/>`)
        .append($(`<option value=""/>`).text("Заменить…"))
        .append(free.map(other => employeeOption(other, shift.date)))
        .prop("disabled", free.length === 0)
        .on("change", event => {
          const substituteId = parseInt($(event.target).val());
          if (!isNaN(substituteId)) {
            changeShiftPeople(send("POST", `${url}/replacements`, {employeeId: id, substituteId: substituteId}));
          }
        }))
        .append($(`<button type="button" class="btn btn-sm btn-outline-danger" title="Снять с этой смены"/>`)
          .append($(`<span class="fas fa-user-minus"/>`)).attr("aria-label", "Снять с этой смены")
          .click(() => changeShiftPeople(send("DELETE", `${url}/employees/${id}`))));
    }
    section.append($(`<div class="person"/>`).toggleClass("inactive", inactive)
      .append($(`<span class="avatar"/>`).text(initials(employee.fullName)))
      .append($(`<div class="person-info"/>`)
        .append($(`<div class="person-name employee-link"/>`).text(employee.fullName)
          .attr("title", "Открыть карточку сотрудника").css("cursor", "pointer")
          .click(() => openDrawer("employee", id)))
        .append($(`<div class="person-status"/>`).text(statusText)))
      .append(actions));
  });
  const addSelect = $(`<select class="form-select form-select-sm" aria-label="Добавить сотрудника"/>`)
    .append(free.length === 0 ? $(`<option value=""/>`).text("В это время свободных нет") : null)
    .append(free.map(other => employeeOption(other, shift.date)));
  section.append($(`<label class="form-label small mt-3 mb-1"/>`).text("Добавить сотрудника в эту смену"))
    .append($(`<div class="input-group input-group-sm"/>`).append(addSelect)
      .append($(`<button type="button" class="btn btn-dark"/>`).prop("disabled", free.length === 0)
        .append($(`<span class="fas fa-user-plus me-1"/>`)).append(document.createTextNode("Добавить"))
        .click(() => {
          const employeeId = parseInt(addSelect.val());
          if (!isNaN(employeeId)) {
            changeShiftPeople(send("PUT", `${url}/employees/${employeeId}`));
          }
        })));
  return section;
}

function changeShiftPeople(request) {
  request.done(onRosterChanged).fail(xhr => {
    showError("Не удалось изменить людей смены.", xhr);
    renderDrawer();
  });
}

function changeCrew(shift, crewId) {
  if (isNaN(crewId) || crewId === shift.crew.id) {
    return;
  }
  send("PUT", crewShiftUrl(shift.id), {date: shift.date, type: shift.type, lineId: shift.line.id, crewId: crewId})
    .done(onRosterChanged)
    .fail(xhr => showError("Не удалось сменить бригаду.", xhr));
}

function setPinned(shift, pinned) {
  send("PUT", crewShiftUrl(shift.id) + "/pinned", {pinned: pinned})
    .done(onRosterChanged)
    .fail(xhr => showError("Не удалось закрепить смену.", xhr));
}

function removeCrewShift(shift) {
  if (!confirm(`Удалить смену бригады ${crewName(shift.crew)} (${shift.line.name}, ${formatDate(shift.date)}, ${shiftLabel(shift.type)})?`)) {
    return;
  }
  send("DELETE", crewShiftUrl(shift.id))
    .done(roster => {
      closeDrawer();
      onRosterChanged(roster);
    })
    .fail(xhr => showError("Не удалось удалить смену.", xhr));
}

// ----- One employee: shifts and hours, crew and crew changes, absences, rest warnings -----

function renderEmployeeDrawer(employee) {
  if (employee == null) {
    return false;
  }
  const monthEnd = JSJoda.YearMonth.parse(shownMonth).atEndOfMonth().toString();
  $("#drawerHeader").empty().append($(`<div class="d-flex gap-2 align-items-center"/>`)
    .append($(`<span class="avatar"/>`).text(initials(employee.fullName)))
    .append($(`<div/>`)
      .append($(`<h2 class="drawer-title"/>`).text(employee.fullName))
      .append($(`<div class="drawer-subtitle"/>`)
        .text(`Таб. № ${employee.id} · бригада ${crewName(crewOn(employee, monthEnd))}`))))
    .append($(`<button type="button" class="btn btn-sm btn-outline-dark mt-2"/>`)
      .append($(`<span class="fas fa-file-excel me-1"/>`))
      .append(document.createTextNode("Экспорт графика сотрудника"))
      .click(() => downloadExport({employeeIds: [employee.id]})));

  const body = $("#drawerBody").empty();
  const totals = monthTotals(employee);
  const warnings = index.employeeWarnings.get(employee.id) || [];
  body.append($(`<div class="drawer-section"/>`).append($(`<h6/>`).text(`Время за ${monthTitle(shownMonth).toLowerCase()}`))
    .append($(`<div class="stat-boxes"/>`)
      .append(statBox("Смены", totals.shifts, `${totals.day} Д / ${totals.night} Н`))
      .append(statBox("Часы", formatHours(totals.hours), "по времени смен"))
      .append(statBox("Нарушения отдыха", warnings.length, "", warnings.length > 0))));

  const warningSection = $(`<div class="drawer-section"/>`).append($(`<h6/>`).text("Отдых между сменами"));
  if (warnings.length === 0) {
    warningSection.append($(`<span class="pill ok"/>`).append($(`<span class="fas fa-check"/>`))
      .append(document.createTextNode("Нарушений нет")));
  } else {
    warningSection.append($(`<ul class="warning-list"/>`).append(warnings.map(w => $(`<li/>`).text(w.message))));
  }
  body.append(warningSection);

  // Crew and crew changes
  const first = JSJoda.YearMonth.parse(shownMonth).atDay(1).toString();
  const crewList = $(`<ul class="plain-list mb-2"/>`)
    .append($(`<li/>`).append($(`<span/>`).text(`С ${formatDate(first)}: ${crewName(employee.crew)}`)))
    .append(employee.crewChanges.map(change => $(`<li/>`)
      .append($(`<span/>`).text(`С ${formatDate(change.from)}: ${crewName(change.crew)}`))
      .append($(`<button type="button" class="btn btn-sm btn-outline-danger py-0"/>`).text("Отменить")
        .click(() => send("DELETE", `/crew-schedules/employees/${employee.id}/crew-changes/${change.from}`)
          .done(refreshSchedule)
          .fail(xhr => showError("Не удалось отменить перевод.", xhr))))));
  const crewSelect = $(`<select class="form-select form-select-sm" aria-label="Перевести в бригаду"/>`)
    .append([...lastSchedule.crews].sort((a, b) => a.id - b.id)
      .map(crew => $(`<option/>`).val(crew.id).text(crewName(crew))))
    .val(crewOn(employee, monthEnd).id);
  const crewFrom = $(`<input type="date" class="form-control form-control-sm" aria-label="С даты"/>`)
    .attr({min: first, max: monthEnd});
  body.append($(`<div class="drawer-section"/>`).append($(`<h6/>`).text("Бригада"))
    .append(crewList)
    .append($(`<div class="row g-2 align-items-end"/>`)
      .append($(`<div class="col"/>`).append($(`<label class="form-label small mb-0"/>`).text("Перевести в бригаду"))
        .append(crewSelect))
      .append($(`<div class="col"/>`).append($(`<label class="form-label small mb-0"/>`).text("с даты (пусто — весь месяц)"))
        .append(crewFrom))
      .append($(`<div class="col-auto"/>`).append($(`<button type="button" class="btn btn-sm btn-dark"/>`)
        .text("Перевести")
        .click(() => send("PUT", `/crew-schedules/employees/${employee.id}/crew`,
          {crewId: parseInt(crewSelect.val()), from: crewFrom.val() || null})
          .done(refreshSchedule)
          .fail(xhr => showError("Не удалось перевести сотрудника.", xhr)))))));

  // Absences
  const absenceList = $(`<ul class="plain-list mb-2"/>`).append(employee.absences.length === 0
    ? $(`<li class="text-muted"/>`).text("Отсутствий нет.")
    : employee.absences.map(absence => $(`<li/>`)
      .append($(`<span/>`).text(describeAbsence(absence)))
      .append($(`<button type="button" class="btn btn-sm btn-outline-danger py-0"/>`).text("Удалить")
        .click(() => send("DELETE", `/crew-schedules/employees/${employee.id}/absences/${encodeURIComponent(absence.id)}`)
          .done(onRosterChanged)
          .fail(xhr => showError("Не удалось удалить отсутствие.", xhr))))));
  const typeSelect = $(`<select class="form-select form-select-sm" aria-label="Тип отсутствия"/>`)
    .append(Object.entries(ABSENCES).map(([type, absence]) => $(`<option/>`).val(type).text(absence.name)));
  const from = $(`<input type="date" class="form-control form-control-sm" aria-label="С"/>`).attr("max", monthEnd);
  const to = $(`<input type="date" class="form-control form-control-sm" aria-label="По"/>`).attr("min", first);
  typeSelect.on("change", () => to.prop("disabled", typeSelect.val() === "DISMISSAL"));
  body.append($(`<div class="drawer-section"/>`).append($(`<h6/>`).text("Отсутствия"))
    .append(absenceList)
    .append($(`<div class="row g-2 align-items-end"/>`)
      .append($(`<div class="col-12"/>`).append(typeSelect))
      .append($(`<div class="col"/>`).append($(`<label class="form-label small mb-0"/>`).text("с")).append(from))
      .append($(`<div class="col"/>`).append($(`<label class="form-label small mb-0"/>`).text("по (включительно)")).append(to))
      .append($(`<div class="col-auto"/>`).append($(`<button type="button" class="btn btn-sm btn-dark"/>`)
        .text("Добавить")
        .click(() => addAbsence(employee, typeSelect.val(), from.val(), to.val()))))));
  return true;
}

function addAbsence(employee, type, from, to) {
  const dismissal = type === "DISMISSAL";
  if (!from || (!dismissal && !to)) {
    showSimpleError(dismissal ? "Укажите первый день без работы." : "Укажите первый и последний день.");
    return;
  }
  send("POST", `/crew-schedules/employees/${employee.id}/absences`, {type: type, from: from, to: dismissal ? null : to})
    .done(onRosterChanged)
    .fail(xhr => showError("Не удалось добавить отсутствие.", xhr));
}

function statBox(label, value, hint, alert = false) {
  return $(`<div class="stat-box"/>`)
    .append($(`<div class="label"/>`).text(label))
    .append($(`<div class="value num"/>`).text(value).css("color", alert ? "var(--deficit-text)" : ""))
    .append($(`<div class="hint"/>`).text(hint));
}

// ---------- Dialogs ----------

// New month (also previous / next month): replaces the version; "Сгенерировать график" -> generate=true
function openNewMonthDialog(month) {
  $("#newMonthInput").val(month);
  $("#newMonthGenerate").prop("checked", false);
  bootstrap.Modal.getOrCreateInstance(document.getElementById("newMonthModal")).show();
}

function startNewMonth() {
  const month = $("#newMonthInput").val();
  if (!month) {
    showSimpleError("Выберите месяц.");
    return;
  }
  const generate = $("#newMonthGenerate").is(":checked");
  send("POST", `/crew-schedules/init?month=${encodeURIComponent(month)}&generate=${generate}`).done(roster => {
    bootstrap.Modal.getOrCreateInstance(document.getElementById("newMonthModal")).hide();
    closeDrawer();
    onRosterChanged(roster);
  }).fail(xhr => showError("Не удалось создать график месяца.", xhr));
}

// Generator: line (all or one), from date, start crew, pin, the sample of each line, pin / unpin of a period
function openGenerateDialog() {
  if (lastSchedule == null) {
    showSimpleError("Графика ещё нет. Сначала создайте месяц.");
    return;
  }
  const lineSelect = $("#generateLine").empty().append($(`<option value=""/>`).text("Все линии"));
  index.lines.forEach(line => lineSelect.append($(`<option/>`).val(line.id).text(line.name)));
  const month = JSJoda.YearMonth.parse(shownMonth);
  const range = {min: month.atDay(1).toString(), max: month.atEndOfMonth().toString()};
  $("#generateFrom").val(range.min).attr(range);
  // "По дату": the last day of the shown month (also after the month changed)
  $("#generateTo").val(range.max).attr(range);
  $("#bulkPinFrom").val(range.min).attr(range);
  $("#bulkPinTo").val(range.max).attr(range);
  $("#generatePin").prop("checked", true);
  renderGenerateStartCrews();
  renderGenerateLines();
  modal("generateModal").show();
}

function modal(id) {
  return bootstrap.Modal.getOrCreateInstance(document.getElementById(id));
}

// Every line with the source of its order: pinned shifts ("своя очередь") or the default
function renderGenerateLines() {
  const pinnedLines = new Set(lastSchedule.crewShifts.filter(shift => shift.pinned).map(shift => shift.line.id));
  $("#generateLineList").empty().append(index.lines.map(line => $(`<li/>`)
    .append($(`<span/>`).text(line.name + " "))
    .append($(`<span class="queue-badge"/>`).toggleClass("own", pinnedLines.has(line.id))
      .text(pinnedLines.has(line.id) ? "своя очередь" : "по умолчанию"))
    .append($(`<button type="button" class="btn btn-link btn-sm p-0 sample-link"/>`).attr("data-line", line.id)
      .text("Задать очередь бригад"))));
}

// "По дату" cannot be before "С даты"
function onGenerateFromChange() {
  const from = $("#generateFrom").val();
  const month = JSJoda.YearMonth.parse(shownMonth);
  $("#generateTo").attr("min", from || month.atDay(1).toString());
  if (from && $("#generateTo").val() && $("#generateTo").val() < from) {
    $("#generateTo").val(from);
  }
}

// A start crew can only be chosen for one line, from the crews of that line (Crew.lineId)
function renderGenerateStartCrews() {
  const lineId = $("#generateLine").val();
  const select = $("#generateStartCrew").empty()
    .append($(`<option value=""/>`).text("Авто (по очереди линии)"));
  if (lineId) {
    lastSchedule.crews.filter(crew => crew.lineId === lineId).sort((a, b) => a.id - b.id)
      .forEach(crew => select.append($(`<option/>`).val(crew.id).text(crewName(crew))));
  }
  select.prop("disabled", !lineId);
}

function startGeneration() {
  const startCrewId = $("#generateStartCrew").val();
  const from = $("#generateFrom").val() || null;
  const to = $("#generateTo").val() || null; // empty: up to the end of the month
  if (from && to && to < from) {
    showSimpleError("Дата «по» раньше даты «с».");
    return;
  }
  runGeneration({
    lineId: $("#generateLine").val() || null,
    from: from,
    to: to,
    startCrewId: startCrewId ? parseInt(startCrewId) : null,
    pin: $("#generatePin").is(":checked")
  });
}

// Preview first: nothing to do / generate without asking / ask what to do with the unpinned crew shifts
function runGeneration(request) {
  const params = new URLSearchParams();
  if (request.lineId) {
    params.set("lineId", request.lineId);
  }
  if (request.from) {
    params.set("from", request.from);
  }
  if (request.to) {
    params.set("to", request.to);
  }
  $.getJSON(`/crew-schedules/generate/preview?${params}`, preview => {
    const total = preview.total;
    const period = periodText(preview.from, preview.to);
    if (total.empty === 0 && total.unpinned === 0) {
      modal("generateModal").hide();
      showInfo("Месяц уже заполнен", `В период ${period} нет пустых и незакреплённых смен.`);
    } else if (total.unpinned === 0) {
      sendGeneration(request, false, preview);
    } else {
      pendingGeneration = {request: request, preview: preview};
      $("#overwriteModalText").text(`В период ${period}: ${total.unpinned} ${plural(total.unpinned, "незакреплённая смена",
        "незакреплённые смены", "незакреплённых смен")}. Что сделать?`);
      $("#overwriteModalPinned").text(`Закреплённые ${total.pinned} ${plural(total.pinned, "смена", "смены", "смен")} `
        + `${total.pinned === 1 ? "останется" : "останутся"}. Пустых мест: ${total.empty}.`);
      $("#overwriteModalFill").prop("disabled", total.empty === 0);
      modal("generateModal").hide();
      modal("overwriteModal").show();
    }
  }).fail(xhr => showError("Не удалось проверить период.", xhr));
}

function answerOverwrite(overwrite) {
  const pending = pendingGeneration;
  pendingGeneration = null;
  modal("overwriteModal").hide();
  if (pending != null) {
    sendGeneration(pending.request, overwrite, pending.preview);
  }
}

// preview: the answer of the preview, for the period in the result toast
function sendGeneration(request, overwrite, preview) {
  generationPeriod = {from: preview.from, to: preview.to};
  send("POST", "/crew-schedules/generate", {...request, overwrite: overwrite}).done(roster => {
    modal("generateModal").hide();
    onRosterChanged(roster);
  }).fail(xhr => {
    generationPeriod = null;
    showError("Не удалось заполнить график.", xhr);
  });
}

// "с 05.11 по 15.11"
function periodText(from, to) {
  return `с ${shortDate(from)} по ${shortDate(to)}`;
}

// ----- Excel export -----

// "Экспорт": period (default: the shown month, reset on every open like the generator dialog), "По бригадам" (the
// whole schedule) or "По сотрудникам" (chosen employees; all of them are chosen on every open). The drawer button
// "Экспорт графика сотрудника" does not open this dialog: it downloads that one employee directly.
function openExportDialog() {
  if (lastSchedule == null) {
    showSimpleError("Нет графика для выгрузки.");
    return;
  }
  const month = JSJoda.YearMonth.parse(shownMonth);
  const range = {min: month.atDay(1).toString(), max: month.atEndOfMonth().toString()};
  $("#exportFrom").val(range.min).attr(range);
  $("#exportTo").val(range.max).attr(range);
  selectAllExportEmployees();
  $("#exportSearch").val("");
  renderExportEmployees();
  updateExportDialog();
  modal("exportModal").show();
}

function exportMode() {
  return $("input[name=exportWhat]:checked").val();
}

function updateExportDialog() {
  $("#exportEmployeesBox").prop("hidden", exportMode() !== "employees");
  updateExportChecks();
}

function selectAllExportEmployees() {
  exportSelection.clear();
  lastSchedule.employees.forEach(employee => exportSelection.add(employee.id));
}

// Employees whose crew at the start of the month is this crew, in the order of the UI
function employeesOfCrew(crewId) {
  return lastSchedule.employees.filter(employee => employee.crew.id === crewId).sort(byPositionAndName);
}

// The list grouped by crew; the search matches the name or the personnel number. The selection is kept.
function renderExportEmployees() {
  const query = ($("#exportSearch").val() || "").trim().toLowerCase();
  const matches = employee => query === "" || employee.fullName.toLowerCase().includes(query)
    || String(employee.id).includes(query);
  const groups = [...lastSchedule.crews].sort((a, b) => a.id - b.id).map(crew => {
    const employees = employeesOfCrew(crew.id);
    const shown = employees.filter(matches);
    if (shown.length === 0) {
      return "";
    }
    const line = crew.lineId == null ? null : index.linesById.get(crew.lineId);
    const lineText = line == null ? (crew.lineId || "без линии") : line.name;
    const rows = shown.map(employee => `<label class="export-employee">
        <input type="checkbox" class="form-check-input export-employee-check" value="${employee.id}">
        <span>${escapeHtml(employee.fullName)}</span><span class="muted">таб. № ${employee.id}</span></label>`).join("");
    return `<div class="export-crew"><label class="export-crew-head">
        <input type="checkbox" class="form-check-input export-crew-check" data-crew="${crew.id}"
          aria-label="Вся бригада ${escapeHtml(crewName(crew))}">
        <span>${escapeHtml(crewName(crew))}</span>
        <span class="muted">${escapeHtml(lineText)} · ${employees.length} чел.</span></label>${rows}</div>`;
  }).join("");
  $("#exportEmployeeList").html(groups || `<p class="text-muted small m-2">Никого не найдено.</p>`);
  updateExportChecks();
}

// Checkboxes, crew checkboxes (all / some = indeterminate), counter and the download button from the selection
function updateExportChecks() {
  const list = $("#exportEmployeeList");
  list.find(".export-employee-check").each((i, el) => {
    el.checked = exportSelection.has(parseInt(el.value));
  });
  list.find(".export-crew-check").each((i, el) => {
    const employees = employeesOfCrew(parseInt($(el).data("crew")));
    const chosen = employees.filter(employee => exportSelection.has(employee.id)).length;
    el.checked = employees.length > 0 && chosen === employees.length;
    el.indeterminate = chosen > 0 && chosen < employees.length;
  });
  const total = lastSchedule.employees.length;
  const all = $("#exportSelectAll")[0];
  all.checked = total > 0 && exportSelection.size === total;
  all.indeterminate = exportSelection.size > 0 && exportSelection.size < total;
  $("#exportCount").text(`Выбрано: ${exportSelection.size} из ${total}`);
  $("#exportModalSave").prop("disabled", exportMode() === "employees" && exportSelection.size === 0);
}

function saveExport() {
  const from = $("#exportFrom").val();
  const to = $("#exportTo").val();
  if (!from || !to || to < from) {
    showSimpleError("Укажите период: дата «по» не раньше даты «с».");
    return;
  }
  const params = {from: from, to: to};
  if (exportMode() === "employees") {
    params.employeeIds = [...exportSelection];
  }
  modal("exportModal").hide();
  downloadExport(params);
}

// fetch (not a link): the request must have the X-Session-Id header. The file name comes from Content-Disposition.
// params: {from?, to?, employeeIds?: [personnel numbers]}; nothing = the whole schedule of the month
async function downloadExport(params) {
  const button = $("#exportButton").prop("disabled", true);
  const query = new URLSearchParams();
  if (params.from) {
    query.set("from", params.from);
  }
  if (params.to) {
    query.set("to", params.to);
  }
  if (params.employeeIds && params.employeeIds.length > 0) {
    query.set("employeeIds", params.employeeIds.join(","));
  }
  const url = "/crew-schedules/export.xlsx" + (query.toString() ? `?${query}` : "");
  try {
    const response = await fetch(url, {headers: {"X-Session-Id": sessionId}});
    if (!response.ok) {
      let detail = response.statusText;
      try {
        detail = (await response.json()).message || detail;
      } catch (e) {
        // Not JSON: keep the status text
      }
      showNotification("Не удалось выгрузить график в Excel.", detail);
      return;
    }
    const blob = await response.blob();
    const link = document.createElement("a");
    link.href = URL.createObjectURL(blob);
    link.download = fileNameOf(response.headers.get("Content-Disposition")) || "crew-schedule.xlsx";
    document.body.appendChild(link);
    link.click();
    link.remove();
    setTimeout(() => URL.revokeObjectURL(link.href), 10000);
  } catch (e) {
    showNotification("Не удалось выгрузить график в Excel.", String(e));
  } finally {
    button.prop("disabled", false);
  }
}

// filename*=UTF-8''<url-encoded> (Cyrillic name), else filename="<ascii>"
function fileNameOf(disposition) {
  if (!disposition) {
    return null;
  }
  const utf8 = /filename\*=UTF-8''([^;]+)/i.exec(disposition);
  if (utf8) {
    try {
      return decodeURIComponent(utf8[1].trim());
    } catch (e) {
      // Broken encoding: use the ASCII name
    }
  }
  const ascii = /filename="([^"]+)"/i.exec(disposition);
  return ascii ? ascii[1] : null;
}

// ----- Clear the crew shifts of a line / all lines in a period -----

// The line menu (⋯) of a row in "По линиям": a fixed box under the button, so the scrolling grid does not cut it
function toggleLineMenu(button) {
  const menu = $("#lineMenu");
  const lineId = String(button.data("line"));
  if (!menu.prop("hidden") && menu.data("line") === lineId) {
    closeLineMenu();
    return;
  }
  const rect = button[0].getBoundingClientRect();
  menu.data("line", lineId).prop("hidden", false).css({top: rect.bottom + 4, left: rect.left});
  button.attr("aria-expanded", "true");
  menu.find("button").first().trigger("focus");
}

function closeLineMenu() {
  $("#lineMenu").prop("hidden", true);
  $(".line-menu-button[aria-expanded]").removeAttr("aria-expanded");
}

// "Очистить" of the generator dialog: its line and the dates next to the button
function clearFromGenerateDialog() {
  const from = $("#bulkPinFrom").val();
  const to = $("#bulkPinTo").val();
  if (!from || !to || to < from) {
    showSimpleError("Укажите период: первый и последний день.");
    return;
  }
  openClearDialog($("#generateLine").val() || null, from, to);
}

// The counts come from the generator preview (pinned / unpinned in the period)
function openClearDialog(lineId, from, to) {
  const params = new URLSearchParams();
  if (lineId) {
    params.set("lineId", lineId);
  }
  if (from) {
    params.set("from", from);
  }
  if (to) {
    params.set("to", to);
  }
  $.getJSON(`/crew-schedules/generate/preview?${params}`, preview => {
    pendingClear = {lineId: lineId, preview: preview};
    $("#clearIncludePinned").prop("checked", false);
    renderClearDialog();
    modal("generateModal").hide();
    modal("clearModal").show();
  }).fail(xhr => showError("Не удалось посчитать смены периода.", xhr));
}

function renderClearDialog() {
  if (pendingClear == null) {
    return;
  }
  const preview = pendingClear.preview;
  const pinned = preview.total.pinned;
  const unpinned = preview.total.unpinned;
  const includePinned = $("#clearIncludePinned").is(":checked");
  const count = unpinned + (includePinned ? pinned : 0);
  const line = pendingClear.lineId == null ? null : index.linesById.get(pendingClear.lineId);
  const where = pendingClear.lineId == null ? "всех линий" : `линии ${line == null ? pendingClear.lineId : line.name}`;
  const period = periodText(preview.from, preview.to);
  $("#clearIncludePinnedLabel").text(`Включая закреплённые (${pinned})`);
  $("#clearIncludePinned").prop("disabled", pinned === 0);
  let note = null;
  if (pinned + unpinned === 0) {
    $("#clearModalText").text(`У ${where} нет смен в период ${period}.`);
  } else if (count === 0) {
    $("#clearModalText").text(`У ${where} в период ${period} только закреплённые смены (${pinned}).`);
    note = "Все смены периода закреплены — отметьте «Включая закреплённые» или открепите их.";
  } else {
    $("#clearModalText").text(`Удалить ${count} ${plural(count, "смену", "смены", "смен")} ${where} ${period}? `
      + "Замены и снятия в этих сменах тоже удалятся.");
  }
  $("#clearModalNote").text(note || "").prop("hidden", note == null);
  $("#clearModalSave").prop("disabled", count === 0);
}

function saveClear() {
  const pending = pendingClear;
  if (pending == null) {
    return;
  }
  const body = {
    lineId: pending.lineId,
    from: pending.preview.from,
    to: pending.preview.to,
    includePinned: $("#clearIncludePinned").is(":checked")
  };
  send("POST", "/crew-schedules/crew-shifts/clear", body).done(roster => {
    pendingClear = null;
    modal("clearModal").hide();
    onRosterChanged(roster);
    const cleared = roster.cleared;
    const removed = `${plural(cleared.removed, "Удалена", "Удалено", "Удалено")} ${cleared.removed} `
      + plural(cleared.removed, "смена", "смены", "смен");
    const kept = cleared.keptPinned === 0 ? "" : `, закреплённых оставлено: ${cleared.keptPinned}`;
    showInfo("Очистка", `${removed}${kept}.`);
  }).fail(xhr => showError("Не удалось очистить смены.", xhr));
}

// "Закрепить всё" / "Открепить всё" for the line chosen in the dialog (or all lines) and a date range
function bulkPin(pinned) {
  const lineId = $("#generateLine").val() || null;
  const from = $("#bulkPinFrom").val();
  const to = $("#bulkPinTo").val();
  if (!from || !to || to < from) {
    showSimpleError("Укажите период: первый и последний день.");
    return;
  }
  const inRange = lastSchedule.crewShifts.filter(shift => (lineId == null || shift.line.id === lineId)
    && shift.date >= from && shift.date <= to).length;
  const where = lineId == null ? "всех линий" : `линии ${index.linesById.get(lineId).name}`;
  if (!confirm(`${pinned ? "Закрепить" : "Открепить"} ${inRange} ${plural(inRange, "смену", "смены", "смен")} ${where} `
    + `с ${formatDate(from)} по ${formatDate(to)}?`)) {
    return;
  }
  send("POST", "/crew-schedules/crew-shifts/pinned", {lineId: lineId, from: from, to: to, pinned: pinned})
    .done(roster => onRosterChanged(roster).done(() => renderGenerateLines()))
    .fail(xhr => showError("Не удалось изменить закрепление.", xhr));
}

// ----- Sample ("Очередь бригад"): DAY crews of 4 days in a row, any crews, the line's own crews first -----

function openSampleDialog(lineId) {
  sampleLineId = lineId;
  const line = index.linesById.get(lineId);
  const month = JSJoda.YearMonth.parse(shownMonth);
  $("#sampleModalTitle").text(`Очередь бригад: ${line == null ? lineId : line.name}`);
  $("#sampleFrom").val(month.atDay(1).toString())
    .attr({min: month.atDay(1).toString(), max: month.atEndOfMonth().minusDays(3).toString()});
  // "По дату": reset on every open to the last day of the shown month
  $("#sampleTo").val(month.atEndOfMonth().toString()).attr("max", month.atEndOfMonth().toString());
  updateSampleToMin();
  renderSampleDays();
  modal("generateModal").hide();
  modal("sampleModal").show();
}

// The 4th day of the queue: "По дату" must not be earlier (min of the field; a later date is moved up to it)
function sampleLastQueueDay() {
  const from = $("#sampleFrom").val();
  return from ? JSJoda.LocalDate.parse(from).plusDays(3).toString() : null;
}

function updateSampleToMin() {
  const min = sampleLastQueueDay();
  $("#sampleTo").attr("min", min || "");
  if (min && $("#sampleTo").val() && $("#sampleTo").val() < min) {
    $("#sampleTo").val(min);
  }
}

function renderSampleDays() {
  const from = $("#sampleFrom").val();
  const box = $("#sampleDays").empty();
  if (!from) {
    return;
  }
  const crews = [...lastSchedule.crews].sort((a, b) => a.id - b.id);
  const options = list => list.map(crew => $(`<option/>`).val(crew.id).text(crewName(crew)));
  const own = crews.filter(crew => crew.lineId === sampleLineId);
  const others = crews.filter(crew => crew.lineId !== sampleLineId);
  for (let day = 0; day < 4; day++) {
    const date = JSJoda.LocalDate.parse(from).plusDays(day).toString();
    const current = index.shiftsBySlot.get(slotKey(sampleLineId, date, "DAY"));
    const select = $(`<select class="form-select form-select-sm sample-crew"/>`)
      .attr("aria-label", `Дневная бригада ${formatDate(date)}`)
      .append($(`<option value=""/>`).text("— не задана —"))
      .append($(`<optgroup label="Бригады линии"/>`).append(options(own)))
      .append($(`<optgroup label="Другие линии"/>`).append(options(others)))
      .val(current != null ? current.crew.id : (own[[0, 2, 3, 1][day]]?.id ?? ""));
    box.append($(`<div class="sample-day"/>`)
      .append($(`<span/>`).text(`${formatDate(date)}, ${weekday(date)}`)).append(select));
  }
}

function saveSample(fill) {
  const from = $("#sampleFrom").val();
  const lineId = sampleLineId;
  const dayCrewIds = $("#sampleDays .sample-crew").toArray().map(el => el.value ? parseInt(el.value) : null);
  const chosen = dayCrewIds.filter(id => id != null);
  if (!from || chosen.length < 3 || new Set(chosen).size !== chosen.length) {
    showSimpleError("Выберите минимум 3 разные бригады.");
    return;
  }
  // "По дату" is used only to fill the line; empty = up to the end of the month
  const to = fill ? ($("#sampleTo").val() || null) : null;
  const lastQueueDay = sampleLastQueueDay();
  if (to != null && to < lastQueueDay) {
    showSimpleError(`Дата «по» должна быть не раньше ${formatDate(lastQueueDay)}.`);
    return;
  }
  send("PUT", `/crew-schedules/lines/${encodeURIComponent(lineId)}/sample`, {from: from, dayCrewIds: dayCrewIds})
    .done(roster => {
      modal("sampleModal").hide();
      onRosterChanged(roster).done(() => {
        if (fill) {
          runGeneration({lineId: lineId, from: from, to: to, startCrewId: null, pin: true});
        } else {
          modal("generateModal").show();
          renderGenerateLines();
        }
      });
    })
    .fail(xhr => showError("Не удалось поставить очередь.", xhr));
}

// What the generator did: created and kept shifts, messages (skipped lines, shifts left empty)
function showGeneration(generation) {
  const period = generationPeriod;
  generationPeriod = null;
  if (generation == null) {
    return;
  }
  const delay = generation.messages.length === 0 ? 8000 : 30000;
  const toast = $(`<div class="toast" role="status" aria-live="polite" aria-atomic="true"/>`).attr("data-bs-delay", delay)
    .append($(`<div class="toast-header"/>`)
      .append($(`<span class="fas fa-magic me-2"/>`))
      .append($(`<strong class="me-auto"/>`).text("Авто-заполнение"))
      .append($(`<button type="button" class="btn-close" data-bs-dismiss="toast" aria-label="Закрыть"/>`)))
    .append($(`<div class="toast-body"/>`)
      .append(period == null ? null : $(`<p class="mb-1 fw-semibold"/>`).text(`Период: ${periodText(period.from, period.to)}`))
      .append($(`<p class="mb-1"/>`).text(`Создано смен: ${generation.created}, оставлено как было: ${generation.kept}.`))
      .append(generation.messages.length === 0 ? null : $(`<ul class="generation-messages small mb-0 ps-3"/>`)
        .append(generation.messages.map(message => $(`<li/>`).text(message)))));
  showToast(toast);
}

// Crew picker for an empty line shift
function openCrewPicker(slot) {
  if (lastSchedule == null || lastSchedule.crews.length === 0) {
    showSimpleError("Нет бригад.");
    return;
  }
  pickerSlot = slot;
  const line = index.linesById.get(slot.lineId);
  $("#crewShiftModalSlot").text(`${line == null ? slot.lineId : line.name}, ${formatDate(slot.date)}, ${shiftLabel(slot.type)}`);
  const select = $("#crewShiftModalCrew");
  fillCrewSelect(select, {...slot, crewShiftId: null}, null);
  const selectable = select.find("option:not(:disabled)");
  if (selectable.length > 0) {
    select.val(selectable.first().val());
  }
  bootstrap.Modal.getOrCreateInstance(document.getElementById("crewShiftModal")).show();
}

function saveCrewPicker() {
  const slot = pickerSlot;
  const crewId = parseInt($("#crewShiftModalCrew").val());
  if (slot == null || isNaN(crewId)) {
    showSimpleError("Выберите бригаду.");
    return;
  }
  send("POST", "/crew-schedules/crew-shifts", {date: slot.date, type: slot.type, lineId: slot.lineId, crewId: crewId})
    .done(roster => {
      bootstrap.Modal.getOrCreateInstance(document.getElementById("crewShiftModal")).hide();
      onRosterChanged(roster);
    })
    .fail(xhr => showError("Не удалось поставить бригаду.", xhr));
}

// The crews of the line first, then the crews of other lines. A crew that already works at the same time on
// another line cannot be chosen.
function fillCrewSelect(select, slot, selectedCrewId) {
  const busy = new Map();
  (index.shiftsByDate.get(slot.date) || [])
    .filter(shift => shift.type === slot.type && shift.id !== slot.crewShiftId)
    .forEach(shift => busy.set(shift.crew.id, shift.line.name));
  const option = crew => $(`<option/>`).val(crew.id)
    .text(crewName(crew) + (busy.has(crew.id) ? ` (работает: ${busy.get(crew.id)})` : ""))
    .prop("disabled", busy.has(crew.id) && crew.id !== selectedCrewId);
  const crews = [...lastSchedule.crews].sort((a, b) => a.id - b.id);
  const own = crews.filter(crew => crew.lineId === slot.lineId);
  const others = crews.filter(crew => crew.lineId !== slot.lineId);
  select.empty();
  if (own.length > 0) {
    select.append($(`<optgroup label="Бригады линии"/>`).append(own.map(option)));
  }
  if (others.length > 0) {
    select.append($(`<optgroup label="Другие линии"/>`).append(others.map(option)));
  }
  if (selectedCrewId != null) {
    select.val(selectedCrewId);
  }
}

// One employee on one crew shift ("По сотрудникам" cell). mode: "works" (take off or replace), "absent" (works
// again) or "add" (choose one of crewShifts)
function openShiftEmployeeDialog(mode, employeeId, crewShiftId, crewShifts) {
  const employee = index.employeesById.get(employeeId);
  const shift = index.shiftsById.get(crewShiftId);
  if (employee == null || (mode !== "add" && shift == null)) {
    return;
  }
  employeeDialog = {mode: mode, employeeId: employeeId, crewShiftId: crewShiftId};
  const select = $("#shiftEmployeeModalSelect").empty();
  $("#shiftEmployeeModalTitle").text(employee.fullName);
  $("#shiftEmployeeModalSelectGroup").toggle(mode !== "absent");
  if (mode === "works") {
    $("#shiftEmployeeModalText").text(`Работает: ${describeShift(shift)}. Изменится только эта смена.`);
    $("#shiftEmployeeModalSelectLabel").text("Кто выйдет вместо него");
    select.append($(`<option value=""/>`).text("Никто (на одного меньше)"));
    freeEmployees(shift.date, shift.type).forEach(other => select.append(employeeOption(other, shift.date)));
  } else if (mode === "absent") {
    $("#shiftEmployeeModalText").text(`Снят со смены: ${describeShift(shift)}.`);
  } else {
    $("#shiftEmployeeModalText").text(`Добавить в смену бригады ${formatDate(crewShifts[0].date)}. Изменится только эта смена.`);
    $("#shiftEmployeeModalSelectLabel").text("Смена бригады");
    const busyTypes = new Set(crewShifts.filter(s => s.employeeIds.includes(employeeId)).map(s => s.type));
    [...crewShifts].sort((a, b) => a.type.localeCompare(b.type) || a.line.name.localeCompare(b.line.name))
      .forEach(other => select.append($(`<option/>`).val(other.id)
        .text(`${shiftLabel(other.type)}, ${other.line.name}, бригада ${crewName(other.crew)}`)
        .prop("disabled", busyTypes.has(other.type))));
    const selectable = select.find("option:not(:disabled)");
    if (selectable.length === 0) {
      showSimpleError(`${employee.fullName} уже работает в это время.`);
      return;
    }
    select.val(selectable.first().val());
  }
  updateShiftEmployeeButton();
  bootstrap.Modal.getOrCreateInstance(document.getElementById("shiftEmployeeModal")).show();
}

function updateShiftEmployeeButton() {
  if (employeeDialog == null) {
    return;
  }
  const text = employeeDialog.mode === "absent" ? "Вернуть в смену"
    : employeeDialog.mode === "add" ? "Добавить"
      : $("#shiftEmployeeModalSelect").val() ? "Заменить" : "Снять с этой смены";
  $("#shiftEmployeeModalSave").text(text);
}

function saveShiftEmployee() {
  const dialog = employeeDialog;
  if (dialog == null) {
    return;
  }
  const value = $("#shiftEmployeeModalSelect").val();
  let request;
  if (dialog.mode === "absent") {
    request = send("PUT", `${crewShiftUrl(dialog.crewShiftId)}/employees/${dialog.employeeId}`);
  } else if (dialog.mode === "add") {
    request = send("PUT", `${crewShiftUrl(value)}/employees/${dialog.employeeId}`);
  } else if (value) {
    request = send("POST", `${crewShiftUrl(dialog.crewShiftId)}/replacements`,
      {employeeId: dialog.employeeId, substituteId: parseInt(value)});
  } else {
    request = send("DELETE", `${crewShiftUrl(dialog.crewShiftId)}/employees/${dialog.employeeId}`);
  }
  request.done(roster => {
    bootstrap.Modal.getOrCreateInstance(document.getElementById("shiftEmployeeModal")).hide();
    onRosterChanged(roster);
  }).fail(xhr => showError("Не удалось изменить людей смены.", xhr));
}

// ---------- Domain helpers (like the server) ----------

// Employees who do not work any crew shift on this date and shift and are not absent, sorted by crew and name
function freeEmployees(date, type) {
  const busy = new Set();
  (index.shiftsByDate.get(date) || []).filter(shift => shift.type === type)
    .forEach(shift => shift.employeeIds.forEach(id => busy.add(id)));
  return lastSchedule.employees
    .filter(employee => !busy.has(employee.id) && absenceOn(employee, date) == null)
    .sort((a, b) => crewOn(a, date).id - crewOn(b, date).id || byPositionAndName(a, b));
}

function employeeOption(employee, date) {
  return $(`<option/>`).val(employee.id).text(`${employee.fullName} (${crewName(crewOn(employee, date))})`);
}

// The crew of the employee on a date (yyyy-MM-dd): the crew of the month start, or of the last change up to then
function crewOn(employee, date) {
  let crew = employee.crew;
  employee.crewChanges.forEach(change => {
    if (change.from <= date) {
      crew = change.crew;
    }
  });
  return crew;
}

// The absence of the employee on a date, or undefined. ISO dates compare as strings.
function absenceOn(employee, date) {
  return employee.absences.find(absence => absence.from <= date && (absence.to == null || date <= absence.to));
}

function describeAbsence(absence) {
  const name = (ABSENCES[absence.type] || ABSENCES.OTHER).name;
  return absence.to == null
    ? `${name} с ${formatDate(absence.from)}`
    : `${name}: ${formatDate(absence.from)} – ${formatDate(absence.to)}`;
}

// Order of the employees of a crew, like the server: by position (PLR_TB.POS; none: last), then by name
function byPositionAndName(a, b) {
  const positionA = a.position == null ? Number.MAX_SAFE_INTEGER : a.position;
  const positionB = b.position == null ? Number.MAX_SAFE_INTEGER : b.position;
  return positionA - positionB || a.fullName.localeCompare(b.fullName, "ru") || a.id - b.id;
}

function describeShift(shift) {
  return `${formatDate(shift.date)}, ${shiftLabel(shift.type)}, ${shift.line.name}, бригада ${crewName(shift.crew)}`;
}

// Start and end ("08:00") of a shift type, from PLR_SM
function timeOf(type) {
  const shift = shiftTimes.find(s => s.type === type);
  return shift == null ? {start: "", end: ""} : {start: shift.start.substring(0, 5), end: shift.end.substring(0, 5)};
}

// "Дневная 08:00–20:00", with the times from PLR_SM
function shiftLabel(type) {
  const time = timeOf(type);
  return `${type === "DAY" ? "Дневная" : "Ночная"} ${time.start}–${time.end}`;
}

function crewName(crew) {
  return crew.name == null ? String(crew.id) : crew.name.trim();
}

// ---------- Formatting ----------

function monthTitle(month) {
  const yearMonth = JSJoda.YearMonth.parse(month);
  return `${MONTH_NAMES[yearMonth.monthValue() - 1]} ${yearMonth.year()}`;
}

function plusMonths(month, months) {
  return JSJoda.YearMonth.parse(month).plusMonths(months).toString();
}

function dayNumber(date) {
  return date.substring(8, 10);
}

function weekday(date) {
  return WEEKDAYS[JSJoda.LocalDate.parse(date).dayOfWeek().value() - 1];
}

function weekendClass(date) {
  return JSJoda.LocalDate.parse(date).dayOfWeek().value() >= 6 ? " weekend" : "";
}

// yyyy-MM-dd -> dd.MM
function shortDate(date) {
  return `${date.substring(8, 10)}.${date.substring(5, 7)}`;
}

// yyyy-MM-dd -> dd.MM.yyyy
function formatDate(date) {
  return `${date.substring(8, 10)}.${date.substring(5, 7)}.${date.substring(0, 4)}`;
}

function formatHours(hours) {
  return Number.isInteger(hours) ? String(hours) : hours.toFixed(1).replace(".", ",");
}

// 1 смена, 2 смены, 5 смен
function plural(count, one, few, many) {
  const n10 = count % 10;
  const n100 = count % 100;
  if (n10 === 1 && n100 !== 11) {
    return one;
  }
  return n10 >= 2 && n10 <= 4 && (n100 < 12 || n100 > 14) ? few : many;
}

// "Иванов Иван Иванович" -> "ИИ"; a personnel number as the name -> "#"
function initials(fullName) {
  const words = fullName.split(/\s+/).filter(word => /^\p{L}/u.test(word));
  return words.length === 0 ? "#" : words.slice(0, 2).map(word => word[0].toUpperCase()).join("");
}

function escapeHtml(value) {
  return String(value).replace(/[&<>"']/g, ch => ({"&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;"})[ch]);
}

function cssEscape(value) {
  return window.CSS && CSS.escape ? CSS.escape(value) : String(value).replace(/["\\]/g, "\\$&");
}

function getSessionId() {
  let id = null;
  try {
    id = sessionStorage.getItem("solverSessionId");
    if (id == null) {
      id = window.crypto && crypto.randomUUID ? crypto.randomUUID() : String(Date.now()) + Math.random();
      sessionStorage.setItem("solverSessionId", id);
    }
  } catch (e) {
    // No sessionStorage (e.g. blocked): the server uses the default version
  }
  return id ?? "";
}

// ---------- Notifications (Bootstrap toasts) ----------

function showInfo(title, text) {
  const toast = $(`<div class="toast" role="status" aria-live="polite" aria-atomic="true" data-bs-delay="8000"/>`)
    .append($(`<div class="toast-header"/>`)
      .append($(`<span class="fas fa-info-circle me-2"/>`))
      .append($(`<strong class="me-auto"/>`).text(title))
      .append($(`<button type="button" class="btn-close" data-bs-dismiss="toast" aria-label="Закрыть"/>`)))
    .append($(`<div class="toast-body"/>`).text(text));
  showToast(toast);
}

function showSimpleError(title) {
  showNotification(title, null);
}

function showError(title, xhr) {
  let detail = null;
  if (xhr != null) {
    const body = xhr.responseJSON;
    detail = body != null && body.message ? body.message : (xhr.responseText || xhr.statusText || null);
  }
  showNotification(title, detail);
}

function showNotification(title, detail) {
  console.error(title + (detail ? "\n" + detail : ""));
  const toast = $(`<div class="toast" role="alert" aria-live="assertive" aria-atomic="true" data-bs-delay="30000"/>`)
    .append($(`<div class="toast-header text-danger"/>`)
      .append($(`<span class="fas fa-exclamation-circle me-2"/>`))
      .append($(`<strong class="me-auto"/>`).text("Ошибка"))
      .append($(`<button type="button" class="btn-close" data-bs-dismiss="toast" aria-label="Закрыть"/>`)))
    .append($(`<div class="toast-body"/>`)
      .append($(`<p class="mb-1"/>`).text(title))
      .append(detail ? $(`<pre class="mb-0 small text-wrap"/>`).text(detail) : null));
  showToast(toast);
}

function showToast(toast) {
  $("#notificationPanel").append(toast);
  toast.on("hidden.bs.toast", () => toast.remove());
  new bootstrap.Toast(toast[0]).show();
}
