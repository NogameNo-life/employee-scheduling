var autoRefreshIntervalId = null;
let jobId = null;
let loadedRoster = null;
let loadedSchedule = null;

const WEEKDAYS = ["Su", "Mo", "Tu", "We", "Th", "Fr", "Sa"];

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

  setupAjax();
  refreshSolvingButtons(false);
  refreshSchedule();
});

function setupAjax() {
  $.ajaxSetup({
    headers: {
      'Content-Type': 'application/json',
      'Accept': 'application/json,text/plain', // plain text is required by solve() returning UUID of the solver job
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
  }).fail(function (xhr, ajaxOptions, thrownError) {
    showError("Getting the crew schedule failed.", xhr);
  });
  refreshSolution();
}

// Employees assigned by the solver (after "Solve")
function refreshSolution() {
  if (jobId == null) {
    return;
  }
  $.getJSON("/crew-schedules/" + jobId, function (schedule) {
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
    jobId = null;
    loadedSchedule = null;
    $("#score").text("Score: ?");
    $("#unassignedPositions").text("");
    $("#employeeVisualization").children().remove();
    renderRoster(roster);
  }).fail(function (xhr, ajaxOptions, thrownError) {
    showError("Generating the crew schedule failed.", xhr);
  });
}

function scheduleDays(roster) {
  return roster.lines.length === 0 ? [] : roster.lines[0].days.map(day => day.date);
}

function dayHeader(dates) {
  const header = $(`<tr/>`);
  $.each(dates, (i, date) => {
    const weekday = new Date(date + "T00:00").getDay();
    header.append($(`<th/>`).addClass(isWeekend(date) ? "weekend" : "")
      .append($(`<div/>`).text(`Day${i + 1}`))
      .append($(`<small class="text-muted"/>`).text(`${date.slice(8)} ${WEEKDAYS[weekday]}`)));
  });
  return header;
}

function isWeekend(date) {
  const weekday = new Date(date + "T00:00").getDay();
  return weekday === 0 || weekday === 6;
}

// Table from "Сценарий работы": one row per line, one column per day, "day crew, night crew" in each cell
function renderRoster(roster) {
  loadedRoster = roster;
  $("#monthInput").val(roster.month);
  const lineVisualization = $("#lineVisualization");
  lineVisualization.children().remove();
  if (roster.lines.length === 0) {
    lineVisualization.append($(`<p/>`).text("There is no crew schedule: no lines or not enough crews (4 per line). Press Generate."));
    return;
  }

  const header = dayHeader(scheduleDays(roster)).prepend($(`<th/>`).text("Line"));
  const body = $(`<tbody/>`);
  $.each(roster.lines, (index, lineSchedule) => {
    const row = $(`<tr/>`).append($(`<th/>`).text(lineSchedule.line.name));
    $.each(lineSchedule.days, (i, day) => {
      row.append($(`<td/>`).addClass(isWeekend(day.date) ? "weekend" : "")
        .append(crewElement(day.dayCrew, "Day 08:00-20:00"))
        .append(", ")
        .append(crewElement(day.nightCrew, "Night 20:00-08:00")));
    });
    body.append(row);
  });
  lineVisualization.append($(`<table class="table table-bordered table-sm schedule"/>`)
    .append($(`<thead/>`).append(header))
    .append(body));
}

function crewElement(crew, shift) {
  if (crew == null) {
    return $(`<span/>`).text("-");
  }
  return $(`<span class="crew"/>`)
    .css("background-color", pickColor("crew" + crew.id))
    .attr("title", `${shift}, crew ${crew.id}`)
    .text(crew.name == null ? crew.id : crew.name.trim());
}

// One row per employee: on which day and shift the solver put them
function renderEmployees(schedule) {
  const employeeVisualization = $("#employeeVisualization");
  employeeVisualization.children().remove();
  const dates = loadedRoster == null ? [] : scheduleDays(loadedRoster);

  const positionsByEmployee = new Map();
  $.each(schedule.positions, (index, position) => {
    if (position.employee != null) {
      if (!positionsByEmployee.has(position.employee.id)) {
        positionsByEmployee.set(position.employee.id, {employee: position.employee, byDate: new Map()});
      }
      positionsByEmployee.get(position.employee.id).byDate.set(position.crewShift.date, position);
    }
  });
  if (positionsByEmployee.size === 0) {
    employeeVisualization.append($(`<p/>`).text("No employees are assigned yet."));
    return;
  }

  const header = dayHeader(dates).prepend($(`<th/>`).text("Employee")).append($(`<th/>`).text("Shifts"));
  const body = $(`<tbody/>`);
  [...positionsByEmployee.values()]
    .sort((a, b) => a.employee.fullName.localeCompare(b.employee.fullName))
    .forEach(({employee, byDate}) => {
      const row = $(`<tr/>`).append($(`<th/>`).text(employee.fullName));
      $.each(dates, (i, date) => {
        const position = byDate.get(date);
        const cell = $(`<td/>`).addClass(isWeekend(date) ? "weekend" : "");
        if (position != null) {
          cell.append($(`<span class="crew"/>`)
            .css("background-color", pickColor("crew" + position.crewShift.crew.id))
            .attr("title", `${position.crewShift.line.name}, ${position.crewShift.crew.name}, ${position.role.name}`)
            .text(position.crewShift.type === "DAY" ? "D" : "N"));
        }
        row.append(cell);
      });
      row.append($(`<td/>`).text(byDate.size));
      body.append(row);
    });
  employeeVisualization.append($(`<table class="table table-bordered table-sm schedule"/>`)
    .append($(`<thead/>`).append(header))
    .append(body));
}

function solve() {
  $.post("/crew-schedules/rotation/solve", function (data) {
    jobId = data;
    refreshSolvingButtons(true);
  }, "text").fail(function (xhr, ajaxOptions, thrownError) {
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
    $.getJSON(`/crew-schedules/${jobId}/analyze`, function (scoreAnalysis) {
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
  $.delete(`/crew-schedules/${jobId}`, function () {
    refreshSolvingButtons(false);
    refreshSolution();
  }).fail(function (xhr, ajaxOptions, thrownError) {
    showError("Stop solving failed.", xhr);
  });
}
