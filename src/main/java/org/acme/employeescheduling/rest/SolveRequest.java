package org.acme.employeescheduling.rest;

import java.time.LocalDate;
import java.util.List;

/**
 * Request to plan one period for one department.
 *
 * <p>Example:
 * <pre>
 * {
 *   "department": "0101011000",
 *   "from": "2026-11-01",
 *   "to": "2026-11-30",
 *   "anchorDate": "2026-01-01",
 *   "lines": [
 *     { "lineId": "L1", "crewIds": [1, 3, 4, 2] },
 *     { "lineId": "L2", "crewIds": [5, 7, 8, 6] }
 *   ]
 * }
 * </pre>
 * {@code lineId}: line code from PLR_PLINES.KRC; the line name is taken from the DB.
 * {@code crewIds}: 4 crews per line, in the order they start their day shift from {@code anchorDate}.
 * Later this can come from a database table instead of the request.
 */
public record SolveRequest(
        String department,
        LocalDate from,
        LocalDate to,
        LocalDate anchorDate,
        List<LineRotation> lines) {

    public record LineRotation(String lineId, List<Integer> crewIds) {
    }
}
