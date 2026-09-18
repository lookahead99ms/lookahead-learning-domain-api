package com.lookahead.learning.content.validator;

import static com.lookahead.learning.content.validator.PlanReservationPolicy.Code.*;
import static org.junit.jupiter.api.Assertions.*;

import com.lookahead.learning.content.validator.PlanReservationPolicy.Reservation;
import com.lookahead.learning.content.validator.PlanReservationPolicy.Result;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PlanReservationPolicyTest {
    private final PlanReservationPolicy policy = new PlanReservationPolicy();
    private final LocalDate start = LocalDate.of(2026, 9, 12);

    private Reservation reservation(int id, String subject, LocalDate date, int days, int minutes) {
        return new Reservation(new UUID(0, id), Set.of(subject), date, days, minutes);
    }

    private Reservation reservation(int id) {
        return reservation(id, "subject-" + id, start, 30, 60);
    }

    private void assertCode(Result result, PlanReservationPolicy.Code code) {
        assertFalse(result.eligible());
        assertTrue(result.violations().stream().anyMatch(v -> v.code() == code), result.toString());
    }

    @Test void permitsFourActivePlansAndRejectsFifthWithoutTruncatingExistingPlans() {
        var active = new ArrayList<>(List.of(reservation(1), reservation(2), reservation(3)));
        assertTrue(policy.validate(active, reservation(4)).eligible());
        active.add(reservation(4));
        Result fifth = policy.validate(active, reservation(5));
        assertCode(fifth, ACTIVE_PLAN_LIMIT);
        assertEquals(5, fifth.violations().getFirst().conflictingPlanIds().size());
        assertEquals(4, active.size());
    }

    @Test void replacementExcludesOwnPriorSubjectCapacityAndCount() {
        var active = List.of(reservation(1), reservation(2), reservation(3), reservation(4));
        Reservation candidate = reservation(1, "subject-1", start, 45, 400);
        assertTrue(policy.validate(active, candidate, reservation(1).planId()).eligible());
        assertEquals(60, active.getFirst().dailyMinutes());
    }

    @Test void requiresExplicitExistingReplacementAndRejectsDuplicateIds() {
        var active = List.of(reservation(1));
        assertCode(policy.validate(active, reservation(2), new UUID(0, 99)), REPLACEMENT_NOT_FOUND);
        assertCode(policy.validate(active, reservation(1)), DUPLICATE_PLAN_ID);
        assertCode(policy.validate(List.of(reservation(1), reservation(1)), reservation(2)), DUPLICATE_PLAN_ID);
        assertCode(policy.validate(List.of(reservation(1), reservation(1)), reservation(1), reservation(1).planId()), DUPLICATE_PLAN_ID);
    }

    @Test void canonicalSubjectsRemainExclusiveAcrossDisjointDates() {
        Reservation first = reservation(1, "dsa", start, 7, 60);
        Reservation candidate = new Reservation(new UUID(0, 2), Set.of("architecture", "dsa"), start.plusYears(2), 7, 60);
        Result result = policy.validate(List.of(first), candidate);
        assertCode(result, SUBJECT_CONFLICT);
        var conflict = result.violations().getFirst();
        assertEquals(Set.of("dsa"), conflict.subjectIds());
        assertEquals(Set.of(first.planId(), candidate.planId()), conflict.conflictingPlanIds());
    }

    @Test void distinctCanonicalSubjectsCanShareDates() {
        assertTrue(policy.validate(List.of(reservation(1, "dsa", start, 30, 120)),
                reservation(2, "architecture", start, 30, 240)).eligible());
    }

    @Test void longAndShortPlansUseDailyReservationsInsteadOfSummedPlanWork() {
        assertTrue(policy.validate(List.of(reservation(1, "dsa", start, 150, 120)),
                reservation(2, "architecture", start.plusDays(100), 7, 240)).eligible());
    }

    @Test void acceptsExactly1080AndReportsOnlyOverflowingDateInterval() {
        Reservation first = reservation(1, "dsa", start, 30, 800);
        assertTrue(policy.validate(List.of(first), reservation(2, "architecture", start.plusDays(10), 7, 280)).eligible());
        Reservation overflow = reservation(2, "architecture", start.plusDays(10), 7, 281);
        Result result = policy.validate(List.of(first), overflow);
        assertCode(result, DAILY_CAPACITY_EXCEEDED);
        var capacity = result.violations().getFirst();
        assertEquals(start.plusDays(10), capacity.startDate());
        assertEquals(start.plusDays(17), capacity.endDateExclusive());
        assertEquals(1081L, capacity.totalDailyMinutes());
        assertEquals(Set.of(first.planId(), overflow.planId()), capacity.conflictingPlanIds());
    }

    @Test void touchingAndDisjointIntervalsDoNotShareCapacity() {
        Reservation first = reservation(1, "dsa", start, 7, 1080);
        assertTrue(policy.validate(List.of(first), reservation(2, "architecture", start.plusDays(7), 7, 1080)).eligible());
        assertTrue(policy.validate(List.of(first), reservation(2, "architecture", start.plusDays(8), 7, 1080)).eligible());
        assertCode(policy.validate(List.of(first), reservation(2, "architecture", start.plusDays(6), 7, 1080)), DAILY_CAPACITY_EXCEEDED);
    }

    @Test void aggregatesAllReservationsAndReportsChangingParticipants() {
        Reservation first = reservation(1, "dsa", start, 10, 400);
        Reservation second = reservation(2, "architecture", start.plusDays(2), 2, 400);
        Reservation third = reservation(3, "java", start.plusDays(3), 4, 400);
        Result result = policy.validate(List.of(first, second), third);
        assertCode(result, DAILY_CAPACITY_EXCEEDED);
        assertEquals(1, result.violations().size());
        var capacity = result.violations().getFirst();
        assertEquals(start.plusDays(3), capacity.startDate());
        assertEquals(start.plusDays(4), capacity.endDateExclusive());
        assertEquals(1200L, capacity.totalDailyMinutes());
    }

    @Test void usesUtcCalendarDateArithmeticAcrossLeapDaysAndDstBoundaries() {
        LocalDate leapDay = LocalDate.of(2028, 2, 29);
        assertTrue(policy.validate(List.of(reservation(1, "dsa", leapDay, 1, 1080)),
                reservation(2, "architecture", LocalDate.of(2028, 3, 1), 1, 1080)).eligible());
        LocalDate dstBoundary = LocalDate.of(2026, 11, 1);
        assertTrue(policy.validate(List.of(reservation(1, "dsa", dstBoundary, 1, 1080)),
                reservation(2, "architecture", dstBoundary.plusDays(1), 1, 1080)).eligible());
    }

    @Test void rejectsMissingOrInvalidLegacyFieldsWithoutChangingInputs() {
        Reservation invalid = new Reservation(new UUID(0, 1), Set.of("dsa"), null, 0, 0);
        var active = new ArrayList<>(List.of(invalid));
        assertCode(policy.validate(active, reservation(2)), INVALID_RESERVATION);
        assertEquals(List.of(invalid), active);
        assertNull(active.getFirst().startDate());
        assertEquals(0, active.getFirst().dayCount());
        // Explicit replacement can repair that reservation; no default dates are inferred.
        assertTrue(policy.validate(active, reservation(1), invalid.planId()).eligible());
    }

    @Test void validatesEveryRequiredFieldAndDateRange() {
        List<Reservation> invalid = Arrays.asList(null,
                new Reservation(null, Set.of("dsa"), start, 1, 60),
                new Reservation(new UUID(0, 1), null, start, 1, 60),
                new Reservation(new UUID(0, 1), Set.of(), start, 1, 60),
                new Reservation(new UUID(0, 1), Set.of(" "), start, 1, 60),
                new Reservation(new UUID(0, 1), new HashSet<>(Arrays.asList((String) null)), start, 1, 60),
                new Reservation(new UUID(0, 1), Set.of("dsa"), null, 1, 60),
                reservation(1, "dsa", start, 0, 60), reservation(1, "dsa", start, -1, 60),
                reservation(1, "dsa", start, 1, 0), reservation(1, "dsa", start, 1, -1),
                reservation(1, "dsa", LocalDate.MAX, 1, 60));
        for (Reservation candidate : invalid) assertCode(policy.validate(List.of(), candidate), INVALID_RESERVATION);
        assertCode(policy.validate(null, reservation(1)), INVALID_RESERVATION);
        assertCode(policy.validate(Arrays.asList((Reservation) null), reservation(1)), INVALID_RESERVATION);
    }

    @Test void preservesExistingOverCapacityEvenWhenCandidateDoesNotOverlap() {
        var active = new ArrayList<>(List.of(reservation(1, "dsa", start, 7, 800),
                reservation(2, "architecture", start, 7, 400)));
        var original = List.copyOf(active);
        Result result = policy.validate(active, reservation(3, "java", start.plusDays(100), 7, 60));
        assertCode(result, DAILY_CAPACITY_EXCEEDED);
        assertEquals(original, active);
        assertEquals(Set.of(active.get(0).planId(), active.get(1).planId()), result.violations().getFirst().conflictingPlanIds());
    }

    @Test void usesBoundarySweepWithoutExpandingHugeHorizonOrOverflowingMinuteSum() {
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            assertTrue(policy.validate(List.of(reservation(1, "dsa", start, Integer.MAX_VALUE, 120)),
                    reservation(2, "architecture", start, Integer.MAX_VALUE, 240)).eligible());
            Result overflow = policy.validate(List.of(reservation(1, "dsa", start, 7, Integer.MAX_VALUE)),
                    reservation(2, "architecture", start, 7, Integer.MAX_VALUE));
            assertCode(overflow, DAILY_CAPACITY_EXCEEDED);
            assertEquals(2L * Integer.MAX_VALUE, overflow.violations().getFirst().totalDailyMinutes());
        });
    }

    @Test void defensivelyCopiesSubjectsAndViolationCollections() {
        Set<String> subjects = new HashSet<>(Set.of("dsa"));
        Reservation candidate = new Reservation(new UUID(0, 2), subjects, start, 1, 60);
        subjects.clear();
        assertEquals(Set.of("dsa"), candidate.subjectIds());
        Result result = policy.validate(List.of(reservation(1, "dsa", start, 1, 60)), candidate);
        assertCode(result, SUBJECT_CONFLICT);
        assertThrows(UnsupportedOperationException.class, () -> result.violations().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.violations().getFirst().conflictingPlanIds().clear());
    }
}
