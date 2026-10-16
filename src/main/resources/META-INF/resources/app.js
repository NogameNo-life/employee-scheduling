const COLORS = ["#1f77b4", "#d62728", "#2ca02c", "#9467bd", "#e377c2", "#8c564b",
    "#17becf", "#bcbd22", "#ff7f0e", "#7f7f7f", "#393b79", "#637939"];

const linesEl = document.getElementById("lines");
const errorEl = document.getElementById("error");
const dateInput = document.getElementById("dateInput");

document.getElementById("initButton").addEventListener("click", () => {
    const date = dateInput.value ? dateInput.value + "-01" : "";
    load(fetch("/crew-schedules/init?date=" + date, {method: "POST"}));
});

load(fetch("/crew-schedules/rotation"));

function load(request) {
    errorEl.classList.add("d-none");
    request
        .then(response => response.ok ? response.json() : response.text().then(text => Promise.reject(text)))
        .then(render)
        .catch(error => {
            errorEl.textContent = "Could not load the schedule: " + error;
            errorEl.classList.remove("d-none");
        });
}

function render(roster) {
    document.getElementById("month").textContent = roster.month;
    dateInput.value = roster.month;
    linesEl.innerHTML = "";
    if (roster.lines.length === 0) {
        linesEl.innerHTML = '<p class="text-muted">No schedule: no lines or not enough crews (4 per line) in the database.</p>';
        return;
    }
    roster.lines.forEach(lineSchedule => linesEl.appendChild(renderLine(lineSchedule)));
}

function renderLine({line, days}) {
    const section = document.createElement("section");
    section.className = "mb-4";
    section.innerHTML = `<h2 class="h6">${escapeHtml(line.name)} <small class="text-muted">${escapeHtml(line.id)}</small></h2>`;

    const weekend = days.map(d => [0, 6].includes(new Date(d.date + "T00:00").getDay()) ? "weekend" : "");
    const header = days.map((d, i) => `<th class="${weekend[i]}">${Number(d.date.slice(8))}</th>`).join("");
    const row = (label, crewOf) => `<tr><th class="shift">${label}</th>`
        + days.map((d, i) => `<td class="${weekend[i]}">${crewBadge(crewOf(d))}</td>`).join("") + "</tr>";

    section.insertAdjacentHTML("beforeend", `
        <div class="table-responsive">
            <table class="table table-bordered table-sm schedule mb-0">
                <thead><tr><th class="shift">Shift</th>${header}</tr></thead>
                <tbody>
                    ${row("Day 08–20", d => d.dayCrew)}
                    ${row("Night 20–08", d => d.nightCrew)}
                </tbody>
            </table>
        </div>`);
    return section;
}

function crewBadge(crew) {
    if (!crew) {
        return "";
    }
    const color = COLORS[Math.abs(crew.id) % COLORS.length];
    return `<span class="crew" style="background:${color}" title="Crew ${crew.id}">${escapeHtml(crew.name ?? String(crew.id))}</span>`;
}

function escapeHtml(text) {
    const div = document.createElement("div");
    div.textContent = text ?? "";
    return div.innerHTML;
}
