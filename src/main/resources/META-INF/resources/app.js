var autoRefreshIntervalId = null;
// Every browser tab is one user with its own schedule version (same key as production-automation-portal-react)
const sessionId = getSessionId();
let loadedSchedule = null;

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
  replaceTimefoldAutoHeaderFooter();

  $("#generateButton").click(function () {
    generate();
  });
  $("#refreshButton").click(function () {
    refreshSchedule();
  });
  $("#solveButton").click(function () {
    solve();
  });
  $("#stopSolvingButton").click(function () {
    stopSolving();
  });
  $("#analyzeButton").click(function () {
    analyze();
  });
  // HACK to allow vis-timeline to work within Bootstrap tabs
  $("#byLineTab").on('shown.bs.tab', function (event) {
    byLineTimeline.redraw();
  })
  $("#byEmployeeTab").on('shown.bs.tab', function (event) {
    byEmployeeTimeline.redraw();
  })

  setupAjax();
  refreshSolvingButtons(false);
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
    refreshSolution();
  }).fail(function (xhr, ajaxOptions, thrownError) {
    showError("Getting the crew schedule failed.", xhr);
  });
}

// Employees assigned by the solver (after "Solve")
function refreshSolution() {
  $.getJSON("/crew-schedules", function (schedule) {
    loadedSchedule = schedule;
    refreshSolvingButtons(schedule.solverStatus != null && schedule.solverStatus !== "NOT_SOLVING");
    $("#score").text("Score: " + (schedule.score == null ? "?" : schedule.score));
    const unassigned = schedule.positions.filter(position => position.employee == null).length;
    $("#unassignedPositions").text(`Unassigned positions: ${unassigned} / ${schedule.positions.length}`);
    renderEmployees(schedule);
  }).fail(function (xhr, ajaxOptions, thrownError) {
    showError("Getting the solution failed.", xhr);
    refreshSolvingButtons(false);
  });
}

function generate() {
  const month = $("#monthInput").val();
  $.post("/crew-schedules/init?date=" + (month ? month + "-01" : ""), function (roster) {
    renderRoster(roster);
    refreshSolution();
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
    byLineGroupDataSet.add({id: line.id, content: $(`<div/>`).append($(`<h5 class="card-title mb-1"/>`).text(line.name)).html()});
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
  const firstDay = JSJoda.LocalDate.parse(roster.lines[0].days[0].date);
  byLineTimeline.setWindow(firstDay.toString(), firstDay.plusDays(7).toString());
  byEmployeeTimeline.setWindow(firstDay.toString(), firstDay.plusDays(7).toString());
}

// Gantt chart of the solution: one row per employee, one bar per assigned position
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
    .forEach(employee => byEmployeeGroupDataSet.add({id: employee.id, content: employee.fullName}));

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

function solve() {
  $.post("/crew-schedules/solve", function () {
    refreshSolvingButtons(true);
  }).fail(function (xhr, ajaxOptions, thrownError) {
    showError("Start solving failed.", xhr);
  });
}

function analyze() {
  new bootstrap.Modal("#scoreAnalysisModal").show()
  const scoreAnalysisModalContent = $("#scoreAnalysisModalContent");
  scoreAnalysisModalContent.children().remove();
  if (loadedSchedule == null || loadedSchedule.score == null || loadedSchedule.score.indexOf('init') != -1) {
    scoreAnalysisModalContent.text("No score to analyze yet, please first press the 'solve' button.");
  } else {
    $('#scoreAnalysisScoreLabel').text(`(${loadedSchedule.score})`);
    $.getJSON("/crew-schedules/analyze", function (scoreAnalysis) {
      let constraints = scoreAnalysis.constraints;
      constraints.sort((a, b) => {
        let aComponents = getScoreComponents(a.score), bComponents = getScoreComponents(b.score);
        if (aComponents.hard < 0 && bComponents.hard > 0) return -1;
        if (aComponents.hard > 0 && bComponents.soft < 0) return 1;
        if (Math.abs(aComponents.hard) > Math.abs(bComponents.hard)) {
          return -1;
        } else {
          if (aComponents.medium < 0 && bComponents.medium > 0) return -1;
          if (aComponents.medium > 0 && bComponents.medium < 0) return 1;
          if (Math.abs(aComponents.medium) > Math.abs(bComponents.medium)) {
            return -1;
          } else {
            if (aComponents.soft < 0 && bComponents.soft > 0) return -1;
            if (aComponents.soft > 0 && bComponents.soft < 0) return 1;

            return Math.abs(bComponents.soft) - Math.abs(aComponents.soft);
          }
        }
      });
      constraints.map((e) => {
        let components = getScoreComponents(e.weight);
        e.type = components.hard != 0 ? 'hard' : (components.medium != 0 ? 'medium' : 'soft');
        e.weight = components[e.type];
        let scores = getScoreComponents(e.score);
        e.implicitScore = scores.hard != 0 ? scores.hard : (scores.medium != 0 ? scores.medium : scores.soft);
      });
      scoreAnalysis.constraints = constraints;

      scoreAnalysisModalContent.children().remove();
      scoreAnalysisModalContent.text("");

      const analysisTable = $(`<table class="table"/>`).css({textAlign: 'center'});
      const analysisTHead = $(`<thead/>`).append($(`<tr/>`)
          .append($(`<th></th>`))
          .append($(`<th>Constraint</th>`).css({textAlign: 'left'}))
          .append($(`<th>Type</th>`))
          .append($(`<th># Matches</th>`))
          .append($(`<th>Weight</th>`))
          .append($(`<th>Score</th>`))
          .append($(`<th></th>`)));
      analysisTable.append(analysisTHead);
      const analysisTBody = $(`<tbody/>`)
      $.each(scoreAnalysis.constraints, (index, constraintAnalysis) => {
        let icon = constraintAnalysis.type == "hard" && constraintAnalysis.implicitScore < 0 ? '<span class="fas fa-exclamation-triangle" style="color: red"></span>' : '';
        if (!icon) icon = constraintAnalysis.matches.length == 0 ? '<span class="fas fa-check-circle" style="color: green"></span>' : '';

        let row = $(`<tr/>`);
        row.append($(`<td/>`).html(icon))
            .append($(`<td/>`).text(constraintAnalysis.name).css({textAlign: 'left'}))
            .append($(`<td/>`).text(constraintAnalysis.type))
            .append($(`<td/>`).html(`<b>${constraintAnalysis.matches.length}</b>`))
            .append($(`<td/>`).text(constraintAnalysis.weight))
            .append($(`<td/>`).text(constraintAnalysis.implicitScore));
        analysisTBody.append(row);
        row.append($(`<td/>`));
      });
      analysisTable.append(analysisTBody);
      scoreAnalysisModalContent.append(analysisTable);
    }).fail(function (xhr, ajaxOptions, thrownError) {
      showError("Analyze failed.", xhr);
    });
  }
}

function getScoreComponents(score) {
  let components = {hard: 0, medium: 0, soft: 0};

  $.each([...score.matchAll(/(-?[0-9]+)(hard|medium|soft)/g)], (i, parts) => {
    components[parts[2]] = parseInt(parts[1], 10);
  });

  return components;
}

function refreshSolvingButtons(solving) {
  if (solving) {
    $("#solveButton").hide();
    $("#stopSolvingButton").show();
    if (autoRefreshIntervalId == null) {
      autoRefreshIntervalId = setInterval(refreshSolution, 2000);
    }
  } else {
    $("#solveButton").show();
    $("#stopSolvingButton").hide();
    if (autoRefreshIntervalId != null) {
      clearInterval(autoRefreshIntervalId);
      autoRefreshIntervalId = null;
    }
  }
}

function stopSolving() {
  $.post("/crew-schedules/stopSolving", function () {
    refreshSolvingButtons(false);
    refreshSolution();
  }).fail(function (xhr, ajaxOptions, thrownError) {
    showError("Stop solving failed.", xhr);
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
