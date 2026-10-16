package org.acme.employeescheduling.service;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import jakarta.enterprise.context.ApplicationScoped;

import org.acme.employeescheduling.domain.Crew;
import org.acme.employeescheduling.domain.Line;
import org.acme.employeescheduling.entity.plr.PlrLines;
import org.acme.employeescheduling.repository.CrewRepository;
import org.acme.employeescheduling.repository.LineRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Master data kept in memory: {@link CrewData} per department and all production lines.
 * Filled when the application starts by {@link CrewRosterService} (like LoadDataService in
 * food-packaging-quarkus). Call {@link #refresh()} after crews, roles or employees are changed in the DB.
 */
@ApplicationScoped
public class CrewDataCache {

    private static final Logger LOGGER = LoggerFactory.getLogger(CrewDataCache.class);

    private final CrewDataLoader loader;
    private final CrewRepository crewRepository;
    private final LineRepository lineRepository;

    /** Department (OBJ, trimmed) -> data. */
    private final ConcurrentMap<String, CrewData> dataByDepartment = new ConcurrentHashMap<>();
    /** Line code (KRC, trimmed) -> line. */
    private ConcurrentHashMap<String, Line> linesById = new ConcurrentHashMap<>();

    public CrewDataCache(CrewDataLoader loader, CrewRepository crewRepository, LineRepository lineRepository) {
        this.loader = loader;
        this.crewRepository = crewRepository;
        this.lineRepository = lineRepository;
    }

    public void refresh() {
        ConcurrentHashMap<String, Line> lines = new ConcurrentHashMap<>();
        for (PlrLines row : lineRepository.findActive()) {
            String id = row.lineId.trim();
            lines.putIfAbsent(id, new Line(id, row.lineName.trim()));
        }
        linesById = lines;

        List<String> departments = crewRepository.findDepartments().stream().map(String::trim).distinct().toList();
        dataByDepartment.clear();
        for (String department : departments) {
            dataByDepartment.put(department, loader.load(department));
        }
        LOGGER.info("Loaded {} lines and crew data for departments {}.", lines.size(), departments);
    }

    /** Data for one department. A department that was not loaded at startup is loaded now. */
    public CrewData get(String department) {
        return dataByDepartment.computeIfAbsent(department.trim(), loader::load);
    }

    public Optional<Line> getLine(String lineId) {
        return Optional.ofNullable(linesById.get(lineId.trim()));
    }

    /** All lines, sorted by code. */
    public List<Line> getLines() {
        return linesById.values().stream().sorted(Comparator.comparing(Line::getId)).toList();
    }

    /** Crews of all loaded departments, sorted by number. */
    public List<Crew> getAllCrews() {
        return dataByDepartment.values().stream()
                .flatMap(data -> data.crews().stream())
                .distinct()
                .sorted(Comparator.comparing(Crew::getId))
                .toList();
    }
}
