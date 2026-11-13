package org.acme.employeescheduling.service;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Service;

import org.acme.employeescheduling.domain.Crew;
import org.acme.employeescheduling.domain.Line;
import org.acme.employeescheduling.domain.Role;
import org.acme.employeescheduling.entity.plr.PlrLines;
import org.acme.employeescheduling.repository.plr.CrewRepository;
import org.acme.employeescheduling.repository.plr.LineRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Master data kept in memory: {@link CrewData} per department and all production lines.
 * Filled when the application starts by {@link CrewRosterService} (like LoadDataService in
 * food-packaging-quarkus). Call {@link #refresh()} after crews, roles or employees are changed in the DB.
 */
@Service
public class CrewDataCache {

    private static final Logger LOGGER = LoggerFactory.getLogger(CrewDataCache.class);

    private final CrewDataLoader loader;
    private final CrewRepository crewRepository;
    private final LineRepository lineRepository;

    /**
     * Department (OBJ, trimmed) -> data. Replaced as a whole by {@link #refresh()}, so other users never see a
     * half-loaded cache; {@link #get} adds departments that were not loaded yet.
     */
    private final AtomicReference<ConcurrentMap<String, CrewData>> dataByDepartment =
            new AtomicReference<>(new ConcurrentHashMap<>());
    /** Line code (KRC, trimmed) -> line. Read-only, replaced as a whole by {@link #refreshLines()}. */
    private final AtomicReference<Map<String, Line>> linesById = new AtomicReference<>(Map.of());

    public CrewDataCache(CrewDataLoader loader, CrewRepository crewRepository, LineRepository lineRepository) {
        this.loader = loader;
        this.crewRepository = crewRepository;
        this.lineRepository = lineRepository;
    }

    /** Reloads lines and crew data. */
    public void refresh() {
        refreshLines();
        refreshCrews();
    }

    /**
     * Reloads the lines only. Called at startup on its own, so the frontend can show the lines
     * even when the crews cannot be loaded.
     */
    public void refreshLines() {
        Map<String, Line> lines = new HashMap<>();
        for (PlrLines row : lineRepository.findActive()) {
            String id = row.lineId.trim();
            lines.putIfAbsent(id, new Line(id, row.lineName.trim()));
        }
        linesById.set(Map.copyOf(lines));
        LOGGER.info("Loaded {} lines.", lines.size());
    }

    private void refreshCrews() {
        List<String> departments = crewRepository.findDepartments().stream().map(String::trim).distinct().toList();
        ConcurrentMap<String, CrewData> data = new ConcurrentHashMap<>();
        for (String department : departments) {
            data.put(department, loader.load(department));
        }
        dataByDepartment.set(data);
        LOGGER.info("Loaded crew data for departments {}.", departments);
    }

    /** Data for one department. A department that was not loaded at startup is loaded now. */
    public CrewData get(String department) {
        return dataByDepartment.get().computeIfAbsent(department.trim(), loader::load);
    }

    /** Data of all loaded departments together: crews of different departments can share the schedule. */
    public CrewData getAll() {
        List<CrewData> all = List.copyOf(dataByDepartment.get().values());
        Map<Integer, Map<Role, Integer>> composition = new HashMap<>();
        all.forEach(data -> composition.putAll(data.composition()));
        return new CrewData(
                all.stream().flatMap(data -> data.crews().stream()).distinct().toList(),
                all.stream().flatMap(data -> data.roles().stream()).distinct().toList(),
                all.stream().flatMap(data -> data.employees().stream()).distinct().toList(),
                composition);
    }

    public Optional<Line> getLine(String lineId) {
        return Optional.ofNullable(linesById.get().get(lineId.trim()));
    }

    /** All lines, sorted by name. */
    public List<Line> getLines() {
        return linesById.get().values().stream()
                .sorted(Comparator.comparing(Line::getName).thenComparing(Line::getId))
                .toList();
    }

    /** Crews of all loaded departments, sorted by number. */
    public List<Crew> getAllCrews() {
        return dataByDepartment.get().values().stream()
                .flatMap(data -> data.crews().stream())
                .distinct()
                .sorted(Comparator.comparing(Crew::getId))
                .toList();
    }
}
