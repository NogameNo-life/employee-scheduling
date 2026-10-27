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
// The whole version (GET /crew-schedules) for the "By employee" tab
let lastSchedule = null;
// The line shift the crew picker is open for: {date, type, lineId, crewShiftId (null: new), crewId}
let pickerSlot = null;
// What the employee dialog of the "By employee" tab is open for: {mode, employeeId, crewShiftId}
let employeeDialog = null;
// The crew shift whose people dialog is open (null: closed)
let peopleDialogCrewShiftId = null;

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
  $("#shiftEmployeeModalSave").click(function () {
    saveShiftEmployee();
  });
  $("#shiftEmployeeModalSelect").on("change", updateShiftEmployeeButton);
  $("#shiftPeopleModalAddButton").click(function () {
    const employeeId = parseInt($("#shiftPeopleModalAdd").val());
    if (!isNaN(employeeId)) {
      changeShiftPeople(send("PUT", `${crewShiftUrl(peopleDialogCrewShiftId)}/employees/${employeeId}`));
    }
  });
  $("#shiftPeopleModal").on("hidden.bs.modal", () => peopleDialogCrewShiftId = null);
  $("#shiftPeopleModalCrewButton").click(function () {
    const crewShift = lastSchedule == null ? null : lastSchedule.crewShifts.find(s => s.id === peopleDialogCrewShiftId);
    bootstrap.Modal.getOrCreateInstance(document.getElementById("shiftPeopleModal")).hide();
    if (crewShift != null) {
      openCrew(crewShift.crew.id);
    }
  });
  // HACK to allow vis-timeline to work within Bootstrap tabs
  $("#byLineTab").on('shown.bs.tab', function (event) {
    byLineTimeline.redraw();
  })
  $("#byEmployeeTab").on('shown.bs.tab', function (event) {
    byEmployeeTimeline.redraw();
  })
  // Click on a shift: the lock icon pins/unpins it, the crew name or the people icon opens the people of this
  // shift. Hovering over a shift shows its people (the item title).
  // A click elsewhere only selects the shift (to drag it or remove it with the x).
  byLineTimeline.on("click", props => {
    if (props.item == null) {
      return;
    }
    if (clickedOn(props.event, ".pin-toggle")) {
      togglePinned(props.item);
    } else if (clickedOn(props.event, ".crew-link, .people-link")) {
      openShiftPeopleDialog(props.item);
    }
  });
  byLineTimeline.on("doubleClick", onLineDoubleClick);
  byEmployeeTimeline.on("click", props => openCrewOfItem(byEmployeeItemDataSet, props.item));
  byEmployeeTimeline.on("doubleClick", onEmployeeDoubleClick);

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
  $.getJSON("/crew-schedules/crews", function (crews) {
    renderCrews(crews);
  }).fail(function (xhr, ajaxOptions, thrownError) {
    showError("Getting the crews failed.", xhr);
  });
}

// The whole version: people of every crew shift for the "By employee" tab, and the positions
function refreshPositions() {
  return $.getJSON("/crew-schedules", function (schedule) {
    lastSchedule = schedule;
    const unassigned = schedule.positions.filter(position => position.employee == null).length;
    $("#unassignedPositions").text(`Unassigned positions: ${unassigned} / ${schedule.positions.length}`);
    renderEmployees(schedule);
    renderShiftPeopleTitles(schedule);
    renderShiftPeopleDialog();
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
  // People of this shift; "(-1, +1)" when it differs from the crew (absent employees, substitutes)
  const changes = [];
  if (crewShift.absentEmployeeIds.length > 0) {
    changes.push(`\u2212${crewShift.absentEmployeeIds.length}`);
  }
  if (crewShift.extraEmployeeIds.length > 0) {
    changes.push(`+${crewShift.extraEmployeeIds.length}`);
  }
  const people = `${crewShift.employeeIds.length} people` + (changes.length === 0 ? "" : ` (${changes.join(", ")})`);
  const element = $(`<div class="card-body p-2"/>`)
    .append($(`<h5 class="card-title mb-1"/>`).append(pin)
      .append($(`<span class="crew-link"/>`).text(crewName(crew))))
    .append($(`<p class="card-text ms-2 mb-0"/>`).text(`${start.format(timeFormat)} - ${end.format(timeFormat)}, `)
      .append($(`<span class="people-link"/>`)
        .append($(`<span class="fas fa-users me-1"/>`)).append($(`<span/>`).text(people))));
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

// People dialog of a crew shift ("By line" tab): who works this one shift, with buttons to take an employee
// off, let an absent employee work again, replace an employee or add one. Only this shift changes, not the crew.
function openShiftPeopleDialog(crewShiftId) {
  if (lastSchedule == null || lastSchedule.crewShifts.find(s => s.id === crewShiftId) == null) {
    return;
  }
  peopleDialogCrewShiftId = crewShiftId;
  renderShiftPeopleDialog();
  bootstrap.Modal.getOrCreateInstance(document.getElementById("shiftPeopleModal")).show();
}

// Draws the open people dialog again (also after every change, from the new version)
function renderShiftPeopleDialog() {
  if (peopleDialogCrewShiftId == null || lastSchedule == null) {
    return;
  }
  const crewShift = lastSchedule.crewShifts.find(s => s.id === peopleDialogCrewShiftId);
  if (crewShift == null) {
    // The crew shift was removed or moved (its id changed)
    bootstrap.Modal.getOrCreateInstance(document.getElementById("shiftPeopleModal")).hide();
    return;
  }
  const employeesById = new Map(lastSchedule.employees.map(employee => [employee.id, employee]));
  // Absent employees of this shift are not offered here: they have "Works again"
  const free = freeEmployees(crewShift.date, crewShift.type).filter(e => !crewShift.absentEmployeeIds.includes(e.id));
  $("#shiftPeopleModalTitle").text(`People of the crew shift (${crewShift.employeeIds.length})`);
  $("#shiftPeopleModalSlot").text(describeShift(crewShift));

  // Working employees first (crew members, then substitutes), then the absent ones; by name within each
  const status = id => crewShift.absentEmployeeIds.includes(id) ? 2 : (crewShift.extraEmployeeIds.includes(id) ? 1 : 0);
  const ids = [...crewShift.employeeIds, ...crewShift.absentEmployeeIds]
    .filter(id => employeesById.has(id))
    .sort((a, b) => status(a) - status(b) || employeesById.get(a).fullName.localeCompare(employeesById.get(b).fullName));
  const url = crewShiftUrl(crewShift.id);
  const rows = ids.map((id, i) => {
    const employee = employeesById.get(id);
    const absent = status(id) === 2;
    const actions = $(`<td class="text-nowrap"/>`);
    if (absent) {
      actions.append($(`<button type="button" class="btn btn-sm btn-outline-primary"/>`).text("Works again")
        .click(() => changeShiftPeople(send("PUT", `${url}/employees/${id}`))));
    } else {
      const replace = $(`<select class="form-select form-select-sm d-inline-block w-auto me-1"/>`)
        .append($(`<option value=""/>`).text("Replace by..."))
        .append(free.map(other => $(`<option/>`).val(other.id).text(`${other.fullName} (${crewName(other.crew)})`)))
        .prop("disabled", free.length === 0)
        .on("change", event => {
          const substituteId = parseInt($(event.target).val());
          if (!isNaN(substituteId)) {
            changeShiftPeople(send("POST", `${url}/replacements`, {employeeId: id, substituteId: substituteId}));
          }
        });
      actions.append(replace)
        .append($(`<button type="button" class="btn btn-sm btn-outline-danger"/>`).text("Take off")
          .click(() => changeShiftPeople(send("DELETE", `${url}/employees/${id}`))));
    }
    return $(`<tr/>`).toggleClass("text-muted", absent)
      .append($(`<td/>`).text(i + 1))
      .append($(`<td/>`).text(employee.fullName).toggleClass("text-decoration-line-through", absent))
      .append($(`<td/>`).text(employee.roles.map(role => role.name).sort().join(", ")))
      .append($(`<td/>`).text(absent ? "absent" : status(id) === 1 ? `substitute (${crewName(employee.crew)})` : "crew"))
      .append(actions);
  });
  $("#shiftPeopleModalList").empty().append(rows.length === 0
    ? $(`<p class="text-muted mb-0"/>`).text("Nobody works this shift.")
    : $(`<table class="table table-sm align-middle mb-0"/>`)
      .append($(`<thead/>`).append($(`<tr/>`)
        .append($(`<th style="width: 2rem"/>`).text("#"))
        .append($(`<th/>`).text("Full name"))
        .append($(`<th/>`).text("Roles"))
        .append($(`<th/>`).text("In this shift"))
        .append($(`<th/>`))))
      .append($(`<tbody/>`).append(rows)));

  $("#shiftPeopleModalAdd").empty()
    .append(free.length === 0 ? $(`<option value=""/>`).text("Nobody is free at that time") : null)
    .append(free.map(other => $(`<option/>`).val(other.id).text(`${other.fullName} (${crewName(other.crew)})`)));
  $("#shiftPeopleModalAddButton").prop("disabled", free.length === 0);
}

// Tooltip of every "By line" shift: its people (names come with the whole version, after the crew schedule)
function renderShiftPeopleTitles(schedule) {
  const employeesById = new Map(schedule.employees.map(employee => [employee.id, employee]));
  const name = id => employeesById.has(id) ? employeesById.get(id).fullName : String(id);
  const updates = schedule.crewShifts
    .filter(crewShift => byLineItemDataSet.get(crewShift.id) != null)
    .map(crewShift => {
      const title = $(`<div/>`).append($(`<strong/>`).text(`${crewName(crewShift.crew)}: ${crewShift.employeeIds.length} people`));
      crewShift.employeeIds.map(id => [name(id), crewShift.extraEmployeeIds.includes(id)])
        .sort((a, b) => a[0].localeCompare(b[0]))
        .forEach(([fullName, substitute]) => title.append($(`<div/>`).text(fullName + (substitute ? " (substitute)" : ""))));
      crewShift.absentEmployeeIds.map(name).sort()
        .forEach(fullName => title.append($(`<div class="text-muted text-decoration-line-through"/>`).text(fullName)));
      return {id: crewShift.id, title: title.html()};
    });
  byLineItemDataSet.update(updates);
}

// The dialog stays open; it is drawn again when the new version is loaded (refreshPositions)
function changeShiftPeople(request) {
  request.done(onRosterChanged)
    .fail(xhr => {
      showError("Changing the people of the crew shift failed.", xhr);
      renderShiftPeopleDialog();
    });
}

// Gantt chart by employee: employees grouped by their crew (sorted by number), sorted by name within the crew.
// A bar is a crew shift the employee works: with their own crew, or as a substitute in another crew (dashed).
// A grey bar is a shift of their crew they do not work (absent). The label has the roles, and the shifts and
// hours of the month. Double-click a bar to take the employee off the shift or replace them, or an empty place
// to add the employee to a crew shift at that time.
function renderEmployees(schedule) {
  byEmployeeGroupDataSet.clear();
  byEmployeeItemDataSet.clear();

  const employeesByCrew = new Map();
  $.each(schedule.employees, (index, employee) => {
    if (!employeesByCrew.has(employee.crew.id)) {
      employeesByCrew.set(employee.crew.id, {crew: employee.crew, employees: []});
    }
    employeesByCrew.get(employee.crew.id).employees.push(employee);
  });
  const shiftCount = new Map();
  const hourCount = new Map();
  $.each(schedule.crewShifts, (index, crewShift) => {
    const hours = (new Date(crewShift.end) - new Date(crewShift.start)) / (1000 * 60 * 60);
    crewShift.employeeIds.forEach(id => {
      shiftCount.set(id, (shiftCount.get(id) || 0) + 1);
      hourCount.set(id, (hourCount.get(id) || 0) + hours);
    });
  });

  let order = 0;
  [...employeesByCrew.values()]
    .sort((a, b) => a.crew.id - b.crew.id)
    .forEach(({crew, employees}) => {
      employees.sort((a, b) => a.fullName.localeCompare(b.fullName) || a.id - b.id);
      byEmployeeGroupDataSet.add({
        id: `crew-${crew.id}`, order: order++, showNested: true,
        nestedGroups: employees.map(employee => employee.id),
        content: $(`<div/>`).append($(`<strong/>`).text(crewName(crew))
          .append($(`<span class="crew-color ms-2"/>`).css("background-color", pickColor("crew" + crew.id)))).html()
      });
      employees.forEach(employee => {
        const roles = employee.roles.map(role => role.name).sort().join(", ");
        byEmployeeGroupDataSet.add({
          id: employee.id, order: order++,
          content: $(`<div/>`)
            .append($(`<div/>`).text(employee.fullName))
            .append(roles ? $(`<div class="small"/>`).text(roles) : null)
            .append($(`<div class="text-muted small"/>`)
              .text(`${shiftCount.get(employee.id) || 0} shifts, ${hourCount.get(employee.id) || 0} h`)).html()
        });
      });
    });

  $.each(schedule.crewShifts, (index, crewShift) => {
    const crew = crewShift.crew;
    crewShift.employeeIds.forEach(employeeId => {
      const substitute = crewShift.extraEmployeeIds.includes(employeeId);
      addEmployeeShiftItem(crewShift, employeeId, false, substitute
        ? `${crewName(crew)}, substitute` : crewName(crew));
    });
    crewShift.absentEmployeeIds.forEach(employeeId => addEmployeeShiftItem(crewShift, employeeId, true, "absent"));
  });
}

function addEmployeeShiftItem(crewShift, employeeId, absent, text) {
  const substitute = crewShift.extraEmployeeIds.includes(employeeId);
  const element = $(`<div class="card-body p-2"/>`)
    .append($(`<h5 class="card-title mb-1"/>`).text(crewShift.line.name))
    .append($(`<p class="card-text ms-2 mb-0"/>`).text(text));
  byEmployeeItemDataSet.add({
    id: `${crewShift.id}/${employeeId}`, group: employeeId, crewId: crewShift.crew.id,
    crewShiftId: crewShift.id, employeeId: employeeId, absent: absent,
    className: absent ? "absent" : (substitute ? "substitute" : ""),
    content: element.html(),
    start: crewShift.start, end: crewShift.end,
    style: absent ? "" : "background-color: " + pickColor("crew" + crewShift.crew.id)
  });
}

// Double-click in the "By employee" tab: on a bar, change this employee on this shift; on an empty place in
// an employee row, add the employee to a crew shift at that time
function onEmployeeDoubleClick(props) {
  if (lastSchedule == null) {
    return;
  }
  if (props.item != null) {
    const item = byEmployeeItemDataSet.get(props.item);
    if (item != null) {
      openShiftEmployeeDialog(item.absent ? "absent" : "works", item.employeeId, item.crewShiftId);
    }
    return;
  }
  if (props.what !== "background" || typeof props.group !== "number" || props.time == null) {
    return; // Not an employee row (e.g. a crew row)
  }
  const slot = slotOf(props.time);
  const crewShifts = lastSchedule.crewShifts.filter(crewShift => crewShift.date === slot.date && crewShift.type === slot.type);
  if (crewShifts.length === 0) {
    showSimpleError(`No crew works on ${slot.date}, ${shiftName(slot.type)}. Add a crew shift in "By line" first.`);
    return;
  }
  openShiftEmployeeDialog("add", props.group, null, crewShifts);
}

// mode: "works" (take off or replace), "absent" (works again) or "add" (choose one of crewShifts)
function openShiftEmployeeDialog(mode, employeeId, crewShiftId, crewShifts) {
  const employee = lastSchedule.employees.find(e => e.id === employeeId);
  const crewShift = lastSchedule.crewShifts.find(s => s.id === crewShiftId);
  if (employee == null || (mode !== "add" && crewShift == null)) {
    return;
  }
  employeeDialog = {mode: mode, employeeId: employeeId, crewShiftId: crewShiftId};
  const select = $("#shiftEmployeeModalSelect").empty();
  $("#shiftEmployeeModalTitle").text(employee.fullName);
  $("#shiftEmployeeModalSelectGroup").toggle(mode !== "absent");

  if (mode === "works") {
    $("#shiftEmployeeModalText").text(`Works on ${describeShift(crewShift)}. Only this shift changes.`);
    $("#shiftEmployeeModalSelectLabel").text("Replaced by");
    select.append($(`<option value=""/>`).text("Nobody (one person less)"));
    freeEmployees(crewShift.date, crewShift.type)
      .forEach(other => select.append($(`<option/>`).val(other.id).text(`${other.fullName} (${crewName(other.crew)})`)));
  } else if (mode === "absent") {
    $("#shiftEmployeeModalText").text(`Does not work on ${describeShift(crewShift)} (absent).`);
  } else {
    const first = crewShifts[0];
    $("#shiftEmployeeModalText").text(`Add to a crew shift on ${first.date}, ${shiftName(first.type)}. Only this shift changes.`);
    $("#shiftEmployeeModalSelectLabel").text("Crew shift");
    crewShifts.forEach(other => select.append($(`<option/>`).val(other.id)
      .text(`${other.line.name}, ${crewName(other.crew)}`)
      .prop("disabled", other.employeeIds.includes(employeeId))));
    const selectable = select.find("option:not(:disabled)");
    if (selectable.length === 0) {
      showSimpleError(`${employee.fullName} already works at that time.`);
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
  const text = employeeDialog.mode === "absent" ? "Works this shift again"
    : employeeDialog.mode === "add" ? "Add"
      : $("#shiftEmployeeModalSelect").val() ? "Replace" : "Take off this shift";
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
  }).fail(xhr => showError("Changing the people of the crew shift failed.", xhr));
}

// Employees who do not work any crew shift on this date and shift, sorted by crew and name
function freeEmployees(date, type) {
  const busy = new Set();
  lastSchedule.crewShifts
    .filter(crewShift => crewShift.date === date && crewShift.type === type)
    .forEach(crewShift => crewShift.employeeIds.forEach(id => busy.add(id)));
  return lastSchedule.employees
    .filter(employee => !busy.has(employee.id))
    .sort((a, b) => a.crew.id - b.crew.id || a.fullName.localeCompare(b.fullName));
}

function describeShift(crewShift) {
  return `${crewShift.date}, ${shiftName(crewShift.type)}, ${crewShift.line.name}, crew ${crewName(crewShift.crew)}`;
}

function shiftName(type) {
  return type === "DAY" ? "day shift (08:00-20:00)" : "night shift (20:00-08:00)";
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

// People needed per shift for each role of the crew (PLR_RLBR), read-only
function renderComposition(details) {
  if (details.roles.length === 0) {
    return $(`<p class="text-muted mb-0"/>`).text("No roles.");
  }
  return $(`<table class="table table-sm mb-0 w-auto"/>`)
    .append($(`<thead/>`).append($(`<tr/>`)
      .append($(`<th/>`).text("Role"))
      .append($(`<th/>`).text("People"))))
    .append($(`<tbody/>`).append(details.roles.map(roleCount => $(`<tr/>`)
      .append($(`<td/>`).text(roleCount.role.name))
      .append($(`<td/>`).text(roleCount.count)))));
}

// Employees of the crew (its default composition); the crew column moves an employee to another crew
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
