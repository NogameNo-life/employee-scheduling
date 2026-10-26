package org.acme.employeescheduling.domain;

import java.time.LocalDate;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * People needed per shift for each role of one crew (PLR_RLBR), in versions: a version is valid from its date
 * until the next version. So a change from a date does not change the crew shifts before that date.
 */
public class CrewComposition {

    /** Valid from -> role -> people needed per shift. */
    private final TreeMap<LocalDate, Map<Role, Integer>> versions = new TreeMap<>();

    public CrewComposition() {
    }

    public CrewComposition(LocalDate validFrom, Map<Role, Integer> rolesNeeded) {
        versions.put(validFrom, new HashMap<>(rolesNeeded));
    }

    /** The composition valid on {@code date}; empty before the first version. */
    public Map<Role, Integer> rolesNeededOn(LocalDate date) {
        Map.Entry<LocalDate, Map<Role, Integer>> entry = versions.floorEntry(date);
        return entry == null ? Map.of() : Collections.unmodifiableMap(entry.getValue());
    }

    public int staffCountOn(LocalDate date) {
        return rolesNeededOn(date).values().stream().mapToInt(Integer::intValue).sum();
    }

    /**
     * Sets the number of people of {@code role} from {@code validFrom} until the next version (0 removes the role).
     * If there is no version on {@code validFrom} yet, it is made as a copy of the version valid on that date.
     */
    public void setRoleCount(LocalDate validFrom, Role role, int count) {
        Map<Role, Integer> version = versions.get(validFrom);
        if (version == null) {
            version = new HashMap<>(rolesNeededOn(validFrom));
            versions.put(validFrom, version);
        }
        if (count == 0) {
            version.remove(role);
        } else {
            version.put(role, count);
        }
    }

    /** Removes a version: from its date on, the previous version is valid again. */
    public boolean removeVersion(LocalDate validFrom) {
        return versions.remove(validFrom) != null;
    }

    /** All versions, sorted by date (read-only). */
    public NavigableMap<LocalDate, Map<Role, Integer>> getVersions() {
        return Collections.unmodifiableNavigableMap(versions);
    }

    public CrewComposition copy() {
        CrewComposition copy = new CrewComposition();
        versions.forEach((validFrom, rolesNeeded) -> copy.versions.put(validFrom, new HashMap<>(rolesNeeded)));
        return copy;
    }
}
