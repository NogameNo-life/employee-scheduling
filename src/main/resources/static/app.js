// Every browser tab is one user with its own schedule version (same key as production-automation-portal-react)
const sessionId = getSessionId();

const timeFormat = JSJoda.DateTimeFormatter.ofPattern("HH:mm");

const byLinePanel = document.getElementById("lineVisualization");
const byLineTimelineOptions = {
  timeAxis: {scale: "day"},
  orientation: {axis: "top"},
  stack: false,
  xss: {disabled: true}, // Items are XSS safe through JQuery
  zoomMin: 1000 * 60 * 60 * 24, // One day in milliseconds
  // Crew shifts are added with a double-click (crew picker), moved by dragging and removed with the x.
  // Pinned crew shifts have editable: false.
  editable: {add: false, remove: true, updateTime: true, updateGroup: true, overrideItems: false},
  snap: snapToShiftStart,
  onMove: moveCrewShift,
  onRemove: removeCrewShift
};
var byLineGroupDataSet = new vis.DataSet();
var byLineItemDataSet = new vis.DataSet();
var byLineTimeline = new vis.Timeline(byLinePanel, byLineItemDataSet, byLineGroupDataSet, byLineTimelineOptions);

const byEmployeePanel = document.getElementById("employeeVisualization");
const byEmployeeTimelineOptions = {
  timeAxis: {scale: "day"},
  orientation: {axis: "top"},
  stack: false,
  xss: {disabled: true}, // Items are XSS safe through JQuery
  zoomMin: 1000 * 60 * 60 * 24 // One day in milliseconds
};
var byEmployeeGroupDataSet = new vis.DataSet();
var byEmployeeItemDataSet = new vis.DataSet();
var byEmployeeTimeline = new vis.Timeline(byEmployeePanel, byEmployeeItemDataSet, byEmployeeGroupDataSet,
  byEmployeeTimelineOptions);

// Month shown in the timelines: the window is set again only when it changes, not after every edit
let shownMonth = null;
// Line id -> line, crews (CrewDetails) and roles of the version: for the crew picker and the crew list
const linesById = new Map();
let crewDetailsList = [];
let allRoles = [];
// The line shift the crew picker is open for: {date, type, lineId, crewShiftId (null: new), crewId}
let pickerSlot = null;

$(document).ready(function () {
  $("#newMonthButton").click(function () {
    newEmptyMonth();
  });
  $("#refreshButton").click(function () {
    refreshSchedule();
  });
  $("#crewShiftModalSave").click(function () {
    saveCrewShift();
  });
  // HACK to allow vis-timeline to work within Bootstrap tabs
  $("#byLineTab").on('shown.bs.tab', function (event) {
    byLineTimeline.redraw();
  })
  $("#byEmployeeTab").on('shown.bs.tab', function (event) {
    byEmployeeTimeline.redraw();
  })
  // Click on a shift: the lock icon pins/unpins it, the crew name opens the crew in the crew list.
  // A click elsewhere only selects the shift (to drag it or remove it with the x).
  byLineTimeline.on("click", props => {
    if (props.item == null) {
      return;
    }
    if (clickedOn(props.event, ".pin-toggle")) {
      togglePinned(props.item);
    } else if (clickedOn(props.event, ".crew-link")) {
      openCrewOfItem(byLineItemDataSet, props.item);
    }
  });
  byLineTimeline.on("doubleClick", onLineDoubleClick);
  byEmployeeTimeline.on("click", props => openCrewOfItem(byEmployeeItemDataSet, props.item));

  setupAjax();
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

// Crew schedule of the version: empty at the start of a month, entered by the user
function refreshSchedule() {
  $.getJSON("/crew-schedules/rotation", function (roster) {
    onRosterChanged(roster);
  }).fail(function (xhr, ajaxOptions, thrownError) {
    if (xhr.status === 404) {
      // No version yet (e.g. the crews could not be loaded at startup): show at least the lines
      refreshLines();
    }
    showError("Getting the crew schedule failed.", xhr);
  });
}

// Lines without a crew schedule: empty timeline rows
function refreshLines() {
  $.getJSON("/crew-schedules/lines", function (lines) {
    byLineGroupDataSet.clear();
    byLineItemDataSet.clear();
    renderLineGroups(lines);
    renderCrews([]);
  }).fail(function (xhr, ajaxOptions, thrownError) {
    showError("Getting the lines failed.", xhr);
  });
}

// Crews of the version with their composition and all their employees, shown below the timeline
function refreshCrews() {
  $.when($.getJSON("/crew-schedules/crews"), $.getJSON("/crew-schedules/roles"))
    .done(function (crewsResult, rolesResult) {
      allRoles = rolesResult[0];
      renderCrews(crewsResult[0]);
    }).fail(function (xhr, ajaxOptions, thrownError) {
      showError("Getting the crews failed.", xhr);
    });
}

// Employees assigned to the positions of the crew shifts
function refreshPositions() {
  $.getJSON("/crew-schedules", function (schedule) {
    const unassigned = schedule.positions.filter(position => position.employee == null).length;
    $("#unassignedPositions").text(`Unassigned positions: ${unassigned} / ${schedule.positions.length}`);
    renderEmployees(schedule);
  }).fail(function (xhr, ajaxOptions, thrownError) {
    showError("Getting the positions failed.", xhr);
  });
}

// The server answers every change of the crew schedule with the whole crew schedule
function onRosterChanged(roster) {
  renderRoster(roster);
  refreshPositions();
  refreshCrews();
}

function newEmptyMonth() {
  const month = $("#monthInput").val();
  if (!month) {
    showSimpleError("Choose a month first.");
    return;
  }
  if (!confirm(`Start an empty crew schedule for ${month}? Your current schedule version will be replaced.`)) {
    return;
  }
  $.post("/crew-schedules/init?month=" + month, function (roster) {
    onRosterChanged(roster);
  }).fail(function (xhr, ajaxOptions, thrownError) {
    showError("Creating the crew schedule failed.", xhr);
  });
}

// Gantt chart of the crew schedule: one row per line, one bar per shift with the crew that works it
function renderRoster(roster) {
  $("#version").text(`Session: ${roster.sessionId}, version: ${roster.versionId}`);
  $("#monthInput").val(roster.month);
  byLineGroupDataSet.clear();
  byLineItemDataSet.clear();
  if (roster.lines.length === 0) {
    showSimpleError("There are no lines.");
    return;
  }

  renderLineGroups(roster.lines.map(lineSchedule => lineSchedule.line));
  $.each(roster.lines, (index, lineSchedule) => {
    $.each(lineSchedule.days, (i, day) => {
      addCrewShiftItem(day.dayShift);
      addCrewShiftItem(day.nightShift);
    });
  });
  if (shownMonth !== roster.month) {
    shownMonth = roster.month;
    setWindow(roster);
  }
}

// One timeline row per line, in the given order
function renderLineGroups(lines) {
  linesById.clear();
  $.each(lines, (index, line) => {
    linesById.set(line.id, line);
    byLineGroupDataSet.add({id: line.id, order: index, content: $(`<div/>`).append($(`<h5 class="card-title mb-1"/>`).text(line.name)).html()});
  });
}

function addCrewShiftItem(crewShift) {
  if (crewShift == null) {
    return;
  }
  const crew = crewShift.crew;
  const start = JSJoda.LocalDateTime.parse(crewShift.start);
  const end = JSJoda.LocalDateTime.parse(crewShift.end);
  const pin = $(`<span class="pin-toggle fas me-1"/>`)
    .addClass(crewShift.pinned ? "fa-lock" : "fa-lock-open")
    .attr("title", crewShift.pinned ? "Pinned. Click to unpin." : "Click to pin.");
  const element = $(`<div class="card-body p-2"/>`)
    .append($(`<h5 class="card-title mb-1"/>`).append(pin).append($(`<span class="crew-link"/>`).text(crewName(crew))))
    .append($(`<p class="card-text ms-2 mb-0"/>`).text(`${start.format(timeFormat)} - ${end.format(timeFormat)}`));
  byLineItemDataSet.add({
    id: crewShift.id, group: crewShift.line.id, crewId: crew.id,
    shiftDate: crewShift.date, shiftType: crewShift.type, pinned: crewShift.pinned,
    editable: !crewShift.pinned, className: crewShift.pinned ? "pinned" : "",
    content: element.html(),
    start: crewShift.start, end: crewShift.end,
    style: "background-color: " + pickColor("crew" + crew.id)
  });
}

// Shows one week from today (or from the 1st, if today is not in the month); scroll or zoom to see the rest
function setWindow(roster) {
  const month = JSJoda.YearMonth.parse(roster.month);
  const today = JSJoda.LocalDate.now();
  const firstDay = JSJoda.YearMonth.from(today).equals(month) ? today : month.atDay(1);
  byLineTimeline.setWindow(firstDay.toString(), firstDay.plusDays(7).toString());
  byEmployeeTimeline.setWindow(firstDay.toString(), firstDay.plusDays(7).toString());
}

// Shifts start at 08:00 and 20:00: a dragged shift snaps to the nearest start
function snapToShiftStart(date) {
  const time = new Date(date);
  const starts = [-4, 8, 20, 32].map(hours => new Date(time.getFullYear(), time.getMonth(), time.getDate(), hours));
  return starts.reduce((best, start) => Math.abs(start - time) < Math.abs(best - time) ? start : best);
}

// The line shift at a time: 08:00-20:00 is the day shift, 20:00-08:00 the night shift of the day it starts
function slotOf(time) {
  const date = new Date(time);
  let day = JSJoda.LocalDate.of(date.getFullYear(), date.getMonth() + 1, date.getDate());
  const hour = date.getHours();
  if (hour >= 8 && hour < 20) {
    return {date: day.toString(), type: "DAY"};
  }
  if (hour < 8) {
    day = day.minusDays(1);
  }
  return {date: day.toString(), type: "NIGHT"};
}

// Double-click on an empty place: add a crew; on a crew shift: give it another crew
function onLineDoubleClick(props) {
  if (props.item != null) {
    const item = byLineItemDataSet.get(props.item);
    if (item == null) {
      return;
    }
    if (item.pinned) {
      showSimpleError("This crew shift is pinned. Unpin it first.");
      return;
    }
    openCrewPicker({date: item.shiftDate, type: item.shiftType, lineId: item.group, crewShiftId: item.id, crewId: item.crewId});
    return;
  }
  if (props.what !== "background" || props.group == null || props.time == null) {
    return;
  }
  openCrewPicker({...slotOf(props.time), lineId: props.group, crewShiftId: null, crewId: null});
}

function openCrewPicker(slot) {
  if (crewDetailsList.length === 0) {
    showSimpleError("There are no crews.");
    return;
  }
  pickerSlot = slot;
  const line = linesById.get(slot.lineId);
  const shiftName = slot.type === "DAY" ? "day shift (08:00-20:00)" : "night shift (20:00-08:00)";
  $("#crewShiftModalTitle").text(slot.crewShiftId == null ? "Add a crew" : "Change the crew");
  $("#crewShiftModalSlot").text(`${line == null ? slot.lineId : line.name}, ${slot.date}, ${shiftName}`);

  // Crews that already work at the same time on another line cannot be chosen
  const busy = new Map();
  byLineItemDataSet.forEach(item => {
    if (item.id !== slot.crewShiftId && item.shiftDate === slot.date && item.shiftType === slot.type) {
      const line = linesById.get(item.group);
      busy.set(item.crewId, line == null ? item.group : line.name);
    }
  });
  const select = $("#crewShiftModalCrew").empty();
  $.each(crewDetailsList, (index, details) => {
    const crew = details.crew;
    const option = $(`<option/>`).val(crew.id)
      .text(crewName(crew) + (busy.has(crew.id) ? ` (works on ${busy.get(crew.id)})` : ""))
      .prop("disabled", busy.has(crew.id));
    select.append(option);
  });
  const selectable = select.find("option:not(:disabled)");
  if (slot.crewId != null) {
    select.val(slot.crewId);
  } else if (selectable.length > 0) {
    select.val(selectable.first().val());
  }
  bootstrap.Modal.getOrCreateInstance(document.getElementById("crewShiftModal")).show();
}

function saveCrewShift() {
  const slot = pickerSlot;
  const crewId = parseInt($("#crewShiftModalCrew").val());
  if (slot == null || isNaN(crewId)) {
    showSimpleError("Choose a crew.");
    return;
  }
  const body = {date: slot.date, type: slot.type, lineId: slot.lineId, crewId: crewId};
  const request = slot.crewShiftId == null
    ? send("POST", "/crew-schedules/crew-shifts", body)
    : send("PUT", crewShiftUrl(slot.crewShiftId), body);
  request.done(roster => {
    bootstrap.Modal.getOrCreateInstance(document.getElementById("crewShiftModal")).hide();
    onRosterChanged(roster);
  }).fail(xhr => showError("Saving the crew shift failed.", xhr));
}

// vis-timeline callback after a drag. The timeline is drawn again from the server's answer.
function moveCrewShift(item, callback) {
  callback(null);
  const body = {...slotOf(item.start), lineId: item.group, crewId: item.crewId};
  send("PUT", crewShiftUrl(item.id), body)
    .done(onRosterChanged)
    .fail(xhr => showError("Moving the crew shift failed.", xhr));
}

// vis-timeline callback for the x of a selected shift
function removeCrewShift(item, callback) {
  callback(null);
  send("DELETE", crewShiftUrl(item.id))
    .done(onRosterChanged)
    .fail(xhr => showError("Removing the crew shift failed.", xhr));
}

function togglePinned(itemId) {
  const item = byLineItemDataSet.get(itemId);
  if (item == null) {
    return;
  }
  send("PUT", crewShiftUrl(item.id) + "/pinned", {pinned: !item.pinned})
    .done(renderRoster)
    .fail(xhr => showError("Pinning the crew shift failed.", xhr));
}

function clickedOn(event, selector) {
  const target = event == null ? null : (event.target || (event.srcEvent && event.srcEvent.target));
  return target != null && $(target).closest(selector).length > 0;
}

function crewShiftUrl(crewShiftId) {
  return "/crew-schedules/crew-shifts/" + encodeURIComponent(crewShiftId);
}

// Gantt chart of the assignments: one row per employee, one bar per assigned position
function renderEmployees(schedule) {
  byEmployeeGroupDataSet.clear();
  byEmployeeItemDataSet.clear();

  const employees = new Map();
  $.each(schedule.positions, (index, position) => {
    if (position.employee != null) {
      employees.set(position.employee.id, position.employee);
    }
  });
  [...employees.values()]
    .sort((a, b) => a.fullName.localeCompare(b.fullName))
    .forEach((employee, index) => byEmployeeGroupDataSet.add({id: employee.id, order: index, content: employee.fullName}));

  $.each(schedule.positions, (index, position) => {
    if (position.employee == null) {
      return;
    }
    const crewShift = position.crewShift;
    const element = $(`<div class="card-body p-2"/>`)
      .append($(`<h5 class="card-title mb-1"/>`).text(position.role.name))
      .append($(`<p class="card-text ms-2 mb-0"/>`).text(`${crewShift.line.name}, ${crewShift.crew.name}`));
    byEmployeeItemDataSet.add({
      id: position.id, group: position.employee.id, crewId: crewShift.crew.id,
      content: element.html(),
      start: crewShift.start, end: crewShift.end,
      style: "background-color: " + pickColor("crew" + crewShift.crew.id)
    });
  });
}

// Crew list: one accordion item per crew; the body has the composition and all employees of the crew.
// Crews that were open stay open when the list is drawn again after a change.
function renderCrews(crews) {
  crewDetailsList = crews;
  const openIds = new Set($("#crewList .accordion-collapse.show").map((i, element) => element.id).get());
  const crewList = $("#crewList").empty();
  const employeeCount = crews.reduce((sum, details) => sum + details.employees.length, 0);
  $("#crewCount").text(crews.length === 0 ? "" : `(${crews.length} crews, ${employeeCount} employees)`);
  if (crews.length === 0) {
    crewList.append($(`<p class="text-muted"/>`).text("No crews."));
    return;
  }

  $.each(crews, (index, details) => {
    const crew = details.crew;
    const bodyId = `crew-${crew.id}`;
    const open = openIds.has(bodyId);
    const lineNames = details.lines.length === 0 ? "no line" : details.lines.map(line => line.name).join(", ");
    const header = $(`<button class="accordion-button py-2" type="button" data-bs-toggle="collapse"/>`)
      .toggleClass("collapsed", !open)
      .attr("aria-expanded", String(open))
      .attr("data-bs-target", "#" + bodyId)
      .append($(`<span class="crew-color me-2"/>`).css("background-color", pickColor("crew" + crew.id)))
      .append($(`<strong class="me-3"/>`).text(crewName(crew)))
      .append($(`<span class="me-3"/>`).text(lineNames))
      .append($(`<span class="text-muted"/>`)
        .text(`${details.employees.length} employees, ${crew.staffCount} per shift`));

    const body = $(`<div class="accordion-body py-2"/>`)
      .append($(`<h4 class="h6"/>`).text("People per shift"))
      .append(renderComposition(details))
      .append($(`<h4 class="h6 mt-3"/>`).text("Employees"))
      .append(renderCrewEmployees(details));

    crewList.append($(`<div class="accordion-item"/>`)
      .append($(`<h3 class="accordion-header"/>`).append(header))
      .append($(`<div class="accordion-collapse collapse"/>`).toggleClass("show", open).attr("id", bodyId)
        .append(body)));
  });
}

// Roles of the crew with the number of people per shift (0 removes the role), and a row to add a role
function renderComposition(details) {
  const crew = details.crew;
  const rows = details.roles.map(roleCount => $(`<tr/>`)
    .append($(`<td/>`).text(roleCount.role.name))
    .append($(`<td/>`).append($(`<input type="number" min="0" step="1" class="form-control form-control-sm role-count"/>`)
      .val(roleCount.count)
      .on("change", event => setRoleCount(crew.id, roleCount.role.id, $(event.target).val())))));

  const usedRoleIds = new Set(details.roles.map(roleCount => roleCount.role.id));
  const freeRoles = allRoles.filter(role => !usedRoleIds.has(role.id));
  const roleSelect = $(`<select class="form-select form-select-sm d-inline-block w-auto me-2"/>`)
    .append(freeRoles.map(role => $(`<option/>`).val(role.id).text(role.name)));
  const countInput = $(`<input type="number" min="1" step="1" class="form-control form-control-sm d-inline-block role-count me-2"/>`)
    .val(1);
  const addButton = $(`<button type="button" class="btn btn-sm btn-outline-secondary"/>`)
    .append($(`<span class="fas fa-plus"/>`)).append(" Add role")
    .on("click", () => setRoleCount(crew.id, parseInt(roleSelect.val()), countInput.val()));
  const addRow = freeRoles.length === 0 ? null : $(`<div class="mt-1"/>`).append(roleSelect, countInput, addButton);

  const table = rows.length === 0
    ? $(`<p class="text-muted mb-0"/>`).text("No roles: this crew needs nobody.")
    : $(`<table class="table table-sm mb-0 w-auto"/>`)
      .append($(`<thead/>`).append($(`<tr/>`)
        .append($(`<th/>`).text("Role"))
        .append($(`<th/>`).text("People"))))
      .append($(`<tbody/>`).append(rows));
  return $(`<div/>`).append(table, addRow);
}

// Employees of the crew; the crew column moves an employee to another crew
function renderCrewEmployees(details) {
  if (details.employees.length === 0) {
    return $(`<p class="text-muted mb-0"/>`).text("No employees.");
  }
  const rows = details.employees.map((employee, i) => {
    const crewSelect = $(`<select class="form-select form-select-sm"/>`)
      .append(crewDetailsList.map(other => $(`<option/>`).val(other.crew.id).text(crewName(other.crew))))
      .val(details.crew.id)
      .on("change", event => moveEmployee(employee.id, parseInt($(event.target).val())));
    return $(`<tr/>`)
      .append($(`<td/>`).text(i + 1))
      .append($(`<td/>`).text(employee.id))
      .append($(`<td/>`).text(employee.fullName))
      .append($(`<td/>`).text(employee.roles.map(role => role.name).sort().join(", ")))
      .append($(`<td/>`).append(crewSelect));
  });
  return $(`<table class="table table-sm table-striped mb-0"/>`)
    .append($(`<thead/>`).append($(`<tr/>`)
      .append($(`<th style="width: 3rem"/>`).text("#"))
      .append($(`<th style="width: 10rem"/>`).text("Personnel number"))
      .append($(`<th/>`).text("Full name"))
      .append($(`<th/>`).text("Roles"))
      .append($(`<th style="width: 10rem"/>`).text("Crew"))))
    .append($(`<tbody/>`).append(rows));
}

function setRoleCount(crewId, roleId, value) {
  const count = Number(value);
  if (isNaN(roleId) || value === "" || !Number.isInteger(count) || count < 0) {
    showSimpleError("The number of people must be a whole number, 0 or more.");
    renderCrews(crewDetailsList);
    return;
  }
  send("PUT", `/crew-schedules/crews/${crewId}/roles/${roleId}`, {count: count})
    .done(crews => {
      renderCrews(crews);
      refreshPositions();
    })
    .fail(xhr => {
      showError("Changing the crew composition failed.", xhr);
      renderCrews(crewDetailsList);
    });
}

function moveEmployee(employeeId, crewId) {
  send("PUT", `/crew-schedules/employees/${employeeId}/crew`, {crewId: crewId})
    .done(crews => {
      renderCrews(crews);
      refreshPositions();
    })
    .fail(xhr => {
      showError("Moving the employee failed.", xhr);
      renderCrews(crewDetailsList);
    });
}

function openCrewOfItem(itemDataSet, itemId) {
  const item = itemId == null ? null : itemDataSet.get(itemId);
  if (item != null && item.crewId != null) {
    openCrew(item.crewId);
  }
}

function openCrew(crewId) {
  const element = document.getElementById(`crew-${crewId}`);
  if (element == null) {
    return;
  }
  bootstrap.Collapse.getOrCreateInstance(element, {toggle: false}).show();
  element.closest(".accordion-item").scrollIntoView({behavior: "smooth", block: "start"});
}

function crewName(crew) {
  return crew.name == null ? String(crew.id) : crew.name.trim();
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

// Notifications (Bootstrap toasts in #notificationPanel)
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
    .append($(`<div class="toast-header bg-danger"/>`)
      .append($(`<strong class="me-auto text-dark"/>`).text("Error"))
      .append($(`<button type="button" class="btn-close" data-bs-dismiss="toast" aria-label="Close"/>`)))
    .append($(`<div class="toast-body"/>`)
      .append($(`<p/>`).text(title))
      .append(detail ? $(`<pre class="mb-0 small"/>`).text(detail) : null));
  $("#notificationPanel").append(toast);
  toast.on("hidden.bs.toast", () => toast.remove());
  new bootstrap.Toast(toast[0]).show();
}

// The same key always gets the same color (e.g. one color per crew)
const COLORS = ["#8AE234", "#FCAF3E", "#729FCF", "#E9B96E", "#AD7FA8", "#FCE94F", "#EF2929", "#73D216",
  "#F57900", "#3465A4", "#C17D11", "#75507B", "#EDD400", "#CC0000", "#4E9A06"];
const colorByKey = new Map();

function pickColor(key) {
  if (!colorByKey.has(key)) {
    colorByKey.set(key, COLORS[colorByKey.size % COLORS.length]);
  }
  return colorByKey.get(key);
}
