package org.acme.employeescheduling.solver;

import static ai.timefold.solver.core.api.score.stream.ConstraintCollectors.count;
import static ai.timefold.solver.core.api.score.stream.ConstraintCollectors.toConsecutiveSequences;
import static ai.timefold.solver.core.api.score.stream.Joiners.equal;


import ai.timefold.solver.core.api.score.buildin.hardmediumsoft.HardMediumSoftScore;
import ai.timefold.solver.core.api.score.stream.Constraint;
import ai.timefold.solver.core.api.score.stream.ConstraintFactory;
import ai.timefold.solver.core.api.score.stream.ConstraintProvider;
import ai.timefold.solver.core.api.score.stream.common.SequenceChain;

import org.acme.employeescheduling.domain.Position;
import org.acme.employeescheduling.domain.ShiftType;

public class CrewScheduleConstraintProvider implements ConstraintProvider {

    public static final int MAX_CONSECUTIVE_WORK_DAYS = 6;

    @Override
    public Constraint[] defineConstraints(ConstraintFactory factory) {
        return new Constraint[] {
                // Hard: rules that must never be broken
                missingRole(factory),
                wrongCrew(factory),
                unavailableEmployee(factory),
                oneShiftPerDay(factory),
                restAfterNightShift(factory),
                maxConsecutiveWorkDays(factory),
                // Medium: fill as many seats as possible
                unfilledPosition(factory),
                // Soft: spread the work evenly
                balanceShiftsPerEmployee(factory)
        };
    }

    // ************************************************************************
    // Hard constraints
    // ************************************************************************

    Constraint missingRole(ConstraintFactory factory) {
        // forEach() skips positions with employee == null, so no null checks are needed
        return factory.forEach(Position.class)
                .filter(p -> !p.getEmployee().canPerform(p.getRole()))
                .penalize(HardMediumSoftScore.ONE_HARD)
                .asConstraint("Employee lacks the required role");
    }

    Constraint wrongCrew(ConstraintFactory factory) {
        return factory.forEach(Position.class)
                .filter(p -> !p.getCrew().equals(p.getEmployee().getCrew()))
                .penalize(HardMediumSoftScore.ONE_HARD)
                .asConstraint("Employee from another crew");
    }

    Constraint unavailableEmployee(ConstraintFactory factory) {
        return factory.forEach(Position.class)
                .filter(p -> p.getEmployee().isUnavailableOn(p.getDate()))
                .penalize(HardMediumSoftScore.ONE_HARD)
                .asConstraint("Employee is unavailable");
    }

    Constraint oneShiftPerDay(ConstraintFactory factory) {
        return factory.forEachUniquePair(Position.class,
                        equal(Position::getEmployee),
                        equal(Position::getDate))
                .penalize(HardMediumSoftScore.ONE_HARD)
                .asConstraint("More than one shift per day");
    }

    Constraint restAfterNightShift(ConstraintFactory factory) {
        // Night shift ends at 08:00 next day -> no shift on the next day
        return factory.forEach(Position.class)
                .filter(p -> p.getShiftType() == ShiftType.NIGHT)
                .join(Position.class,
                        equal(Position::getEmployee),
                        equal(p -> p.getDate().plusDays(1), Position::getDate))
                .penalize(HardMediumSoftScore.ONE_HARD)
                .asConstraint("No rest after night shift");
    }

    Constraint maxConsecutiveWorkDays(ConstraintFactory factory) {
        // Pinned positions from the last week of the previous month are included,
        // so a sequence that started last month is counted correctly.
        return factory.forEach(Position.class)
                .groupBy(Position::getEmployee, Position::getDate) // one row per employee per working day
                .groupBy((employee, date) -> employee,
                        toConsecutiveSequences((employee, date) -> date,
                                date -> (int) date.toEpochDay()))
                .flattenLast(SequenceChain::getConsecutiveSequences)
                .filter((employee, sequence) -> sequence.getItems().size() > MAX_CONSECUTIVE_WORK_DAYS)
                .penalize(HardMediumSoftScore.ONE_HARD,
                        (employee, sequence) -> sequence.getItems().size() - MAX_CONSECUTIVE_WORK_DAYS)
                .asConstraint("Too many consecutive work days");
    }

    // ************************************************************************
    // Medium constraints
    // ************************************************************************

    Constraint unfilledPosition(ConstraintFactory factory) {
        return factory.forEachIncludingUnassigned(Position.class)
                .filter(p -> p.getEmployee() == null)
                .penalize(HardMediumSoftScore.ONE_MEDIUM)
                .asConstraint("Unfilled position");
    }

    // ************************************************************************
    // Soft constraints
    // ************************************************************************

    Constraint balanceShiftsPerEmployee(ConstraintFactory factory) {
        // Squared penalty: 3+3 shifts (9+9=18) is better than 6+0 (36+0=36).
        // All shifts are 12 h, so balancing shifts = balancing hours.
        return factory.forEach(Position.class)
                .groupBy(Position::getEmployee, count())
                .penalize(HardMediumSoftScore.ONE_SOFT, (employee, shiftCount) -> shiftCount * shiftCount)
                .asConstraint("Balance shifts between employees");
    }
}
