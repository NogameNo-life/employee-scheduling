// Every browser tab is one user with its own schedule version (same key as production-automation-portal-react)
const sessionId = getSessionId();

const DAY_SHIFT_START = JSJoda.LocalTime.of(8, 0);
const NIGHT_SHIFT_START = JSJoda.LocalTime.of(20, 0);
const timeFormat = JSJoda.DateTimeFormatter.ofPattern("HH:mm");

const byLinePanel = document.getElementById("lineVisualization");
const byLineTimelineOptions = {
  timeAxis: {scale: "day"},
  orientation: {axis: "top"},
  stack: false,
  xss: {disabled: true}, // Items are XSS safe through JQuery
  zoomMin: 1000 * 60 * 60 * 24 // One day in milliseconds
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

$(document).ready(function () {
  $("#generateButton").click(function () {
    generate();
  });
  $("#refreshButton").click(function () {
    refreshSchedule();
  });
  // HACK to allow vis-timeline to work within Bootstrap tabs
  $("#byLineTab").on('shown.bs.tab', function (event) {
    byLineTimeline.redraw();
  })
  $("#byEmployeeTab").on('shown.bs.tab', function (event) {
    byEmployeeTimeline.redraw();
  })

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

  // Extend jQuery to support $.put() and $.delete()
  jQuery.each(["put", "delete"], function (i, method) {
    jQuery[method] = function (url, data, callback, type) {
      if (jQuery.isFunction(data)) {
        type = type || callback;
        callback = data;
        data = undefined;
      }
      return jQuery.ajax({
        url: url,
        type: method,
        dataType: type,
        data: data,
        success: callback
      });
    };
  });
}

// Crew schedule: generated when the application starts, or by the "Generate" button
function refreshSchedule() {
  $.getJSON("/crew-schedules/rotation", function (roster) {
    renderRoster(roster);
    refreshPositions();
  }).fail(function (xhr, ajaxOptions, thrownError) {
    showError("Getting the crew schedule failed.", xhr);
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

function generate() {
  const month = $("#monthInput").val();
  $.post("/crew-schedules/init?date=" + (month ? month + "-01" : ""), function (roster) {
    renderRoster(roster);
    refreshPositions();
  }).fail(function (xhr, ajaxOptions, thrownError) {
    showError("Generating the crew schedule failed.", xhr);
  });
}

// Gantt chart of the crew schedule: one row per line, one bar per shift with the crew that works it
function renderRoster(roster) {
  $("#version").text(`Session: ${roster.sessionId}, version: ${roster.versionId}`);
  $("#monthInput").val(roster.month);
  byLineGroupDataSet.clear();
  byLineItemDataSet.clear();
  if (roster.lines.length === 0) {
    showSimpleError("There is no crew schedule: no lines or not enough crews (4 per line). Press Generate.");
    return;
  }

  $.each(roster.lines, (index, lineSchedule) => {
    const line = lineSchedule.line;
    byLineGroupDataSet.add({id: line.id, order: index, content: $(`<div/>`).append($(`<h5 class="card-title mb-1"/>`).text(line.name)).html()});
    $.each(lineSchedule.days, (i, day) => {
      const date = JSJoda.LocalDate.parse(day.date);
      addCrewShiftItem(line, day.dayCrew, date.atTime(DAY_SHIFT_START), date.atTime(NIGHT_SHIFT_START));
      addCrewShiftItem(line, day.nightCrew, date.atTime(NIGHT_SHIFT_START), date.plusDays(1).atTime(DAY_SHIFT_START));
    });
  });
  setWindow(roster);
}

function addCrewShiftItem(line, crew, start, end) {
  if (crew == null) {
    return;
  }
  const crewName = crew.name == null ? String(crew.id) : crew.name.trim();
  const element = $(`<div class="card-body p-2"/>`)
    .append($(`<h5 class="card-title mb-1"/>`).text(crewName))
    .append($(`<p class="card-text ms-2 mb-0"/>`).text(`${start.format(timeFormat)} - ${end.format(timeFormat)}`));
  byLineItemDataSet.add({
    id: `${line.id}_${start}`, group: line.id,
    content: element.html(),
    start: start.toString(), end: end.toString(),
    style: "background-color: " + pickColor("crew" + crew.id)
  });
}

// Shows the first week of the month; scroll or zoom to see the rest
function setWindow(roster) {
  const firstDay = JSJoda.YearMonth.parse(roster.month).atDay(1);
  byLineTimeline.setWindow(firstDay.toString(), firstDay.plusDays(7).toString());
  byEmployeeTimeline.setWindow(firstDay.toString(), firstDay.plusDays(7).toString());
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
      id: position.id, group: position.employee.id,
      content: element.html(),
      start: crewShift.start, end: crewShift.end,
      style: "background-color: " + pickColor("crew" + crewShift.crew.id)
    });
  });
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
