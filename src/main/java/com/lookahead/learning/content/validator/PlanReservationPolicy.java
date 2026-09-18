package com.lookahead.learning.content.validator;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Pure policy for an owner's already-normalized ACTIVE reservations.
 * Dates denote UTC calendar dates; subject IDs must already be canonical.
 * Ownership, lifecycle decisions and legacy-data normalization belong upstream.
 */
public final class PlanReservationPolicy {
    public static final int MAX_ACTIVE_PLANS = 4;
    public static final int MAX_DAILY_MINUTES = 1080;

    public record Reservation(UUID planId, Set<String> subjectIds, LocalDate startDate,
                              int dayCount, int dailyMinutes) {
        public Reservation {
            // Retain invalid values for structured validation instead of failing in the constructor.
            subjectIds = subjectIds == null ? null
                    : Collections.unmodifiableSet(new LinkedHashSet<>(subjectIds));
        }
    }

    public enum Code {
        INVALID_RESERVATION,
        DUPLICATE_PLAN_ID,
        REPLACEMENT_NOT_FOUND,
        ACTIVE_PLAN_LIMIT,
        SUBJECT_CONFLICT,
        DAILY_CAPACITY_EXCEEDED
    }

    /** Capacity dates describe a half-open interval, never a fabricated per-day allocation. */
    public record Violation(Code code, Set<UUID> conflictingPlanIds, Set<String> subjectIds,
                            LocalDate startDate, LocalDate endDateExclusive,
                            Long totalDailyMinutes, String detail) {
        public Violation {
            conflictingPlanIds = Set.copyOf(conflictingPlanIds);
            subjectIds = Set.copyOf(subjectIds);
        }
    }

    public record Result(List<Violation> violations) {
        public Result {
            violations = List.copyOf(violations);
        }

        public boolean eligible() {
            return violations.isEmpty();
        }
    }

    private record Interval(Reservation reservation, LocalDate endExclusive) {}
    private record Event(Reservation reservation, boolean starts) {}

    public Result validate(List<Reservation> active, Reservation candidate) {
        return validate(active, candidate, null);
    }

    /**
     * Checks the complete resulting ACTIVE set without modifying or truncating stored inputs.
     * An explicit replacement excludes its prior reservation, permitting repair of legacy fields.
     * The store must separately enforce ownership, expected revision and atomic application.
     */
    public Result validate(List<Reservation> active, Reservation candidate, UUID replacingPlanId) {
        List<Violation> violations = new ArrayList<>();
        if (active == null) {
            violations.add(invalid(null, "ACTIVE reservations are required"));
            return new Result(violations);
        }

        Set<UUID> originalIds = new HashSet<>();
        for (Reservation reservation : active) {
            if (reservation == null || reservation.planId() == null) {
                violations.add(invalid(reservation, "Existing reservation needs a plan ID"));
            } else if (!originalIds.add(reservation.planId())) {
                violations.add(violation(Code.DUPLICATE_PLAN_ID, Set.of(reservation.planId()),
                        "ACTIVE reservations contain a duplicate plan ID"));
            }
        }
        if (replacingPlanId != null && !originalIds.contains(replacingPlanId)) {
            violations.add(violation(Code.REPLACEMENT_NOT_FOUND, Set.of(replacingPlanId),
                    "The reservation to replace is not ACTIVE"));
        }
        if (candidate != null && candidate.planId() != null && originalIds.contains(candidate.planId())
                && !candidate.planId().equals(replacingPlanId)) {
            violations.add(violation(Code.DUPLICATE_PLAN_ID, Set.of(candidate.planId()),
                    "The candidate plan ID is already ACTIVE"));
        }

        List<Reservation> effective = new ArrayList<>();
        for (Reservation reservation : active) {
            if (reservation == null || replacingPlanId == null || !replacingPlanId.equals(reservation.planId())) {
                effective.add(reservation);
            }
        }
        effective.add(candidate);
        List<Interval> intervals = new ArrayList<>();
        for (Reservation reservation : effective) {
            Interval interval = validateReservation(reservation, violations);
            if (interval != null) intervals.add(interval);
        }
        if (effective.size() > MAX_ACTIVE_PLANS) {
            violations.add(violation(Code.ACTIVE_PLAN_LIMIT, planIds(effective),
                    "At most four plans may be ACTIVE"));
        }
        // An invalid legacy entry must not disappear from subject/capacity accounting.
        // Reject the input rather than calculating a misleading partial eligibility result.
        if (violations.stream().anyMatch(v -> v.code() == Code.INVALID_RESERVATION
                || v.code() == Code.DUPLICATE_PLAN_ID || v.code() == Code.REPLACEMENT_NOT_FOUND)) {
            return new Result(violations);
        }

        checkSubjects(intervals, violations);
        checkCapacity(intervals, violations);
        return new Result(violations);
    }

    private Interval validateReservation(Reservation reservation, List<Violation> violations) {
        if (reservation == null || reservation.planId() == null || reservation.startDate() == null
                || reservation.dayCount() <= 0 || reservation.dailyMinutes() <= 0
                || reservation.subjectIds() == null || reservation.subjectIds().isEmpty()
                || reservation.subjectIds().stream().anyMatch(id -> id == null || id.isBlank())) {
            violations.add(invalid(reservation,
                    "A reservation needs a plan ID, UTC start date, positive days/minutes and canonical subjects"));
            return null;
        }
        try {
            return new Interval(reservation, reservation.startDate().plusDays(reservation.dayCount()));
        } catch (DateTimeException | ArithmeticException ex) {
            violations.add(invalid(reservation, "The exclusive reservation end date is outside the supported date range"));
            return null;
        }
    }

    private void checkSubjects(List<Interval> intervals, List<Violation> violations) {
        Map<String, Set<UUID>> subjectOwners = new TreeMap<>();
        for (Interval interval : intervals) {
            Reservation reservation = interval.reservation();
            for (String subject : reservation.subjectIds()) {
                subjectOwners.computeIfAbsent(subject, ignored -> new LinkedHashSet<>()).add(reservation.planId());
            }
        }
        for (var entry : subjectOwners.entrySet()) {
            if (entry.getValue().size() > 1) {
                violations.add(new Violation(Code.SUBJECT_CONFLICT, entry.getValue(), Set.of(entry.getKey()),
                        null, null, null, "A canonical subject can belong to only one ACTIVE plan"));
            }
        }
    }

    private void checkCapacity(List<Interval> intervals, List<Violation> violations) {
        TreeMap<LocalDate, List<Event>> events = new TreeMap<>();
        for (Interval interval : intervals) {
            Reservation reservation = interval.reservation();
            events.computeIfAbsent(reservation.startDate(), ignored -> new ArrayList<>()).add(new Event(reservation, true));
            events.computeIfAbsent(interval.endExclusive(), ignored -> new ArrayList<>()).add(new Event(reservation, false));
        }
        Map<UUID, Reservation> present = new LinkedHashMap<>();
        long totalMinutes = 0;
        for (var boundary : events.entrySet()) {
            // Apply every event at a boundary before assessing the following interval.
            // Thus a plan ending today never overlaps another plan starting today.
            for (Event event : boundary.getValue()) {
                Reservation reservation = event.reservation();
                if (event.starts()) {
                    present.put(reservation.planId(), reservation);
                    totalMinutes += reservation.dailyMinutes();
                } else {
                    present.remove(reservation.planId());
                    totalMinutes -= reservation.dailyMinutes();
                }
            }
            LocalDate nextBoundary = events.higherKey(boundary.getKey());
            if (nextBoundary != null && totalMinutes > MAX_DAILY_MINUTES) {
                violations.add(new Violation(Code.DAILY_CAPACITY_EXCEEDED, present.keySet(), Set.of(),
                        boundary.getKey(), nextBoundary, totalMinutes,
                        "Planned time exceeds 1080 minutes on these overlapping UTC dates"));
            }
        }
    }

    private static Set<UUID> planIds(List<Reservation> reservations) {
        Set<UUID> ids = new HashSet<>();
        for (Reservation reservation : reservations) {
            if (reservation != null && reservation.planId() != null) ids.add(reservation.planId());
        }
        return ids;
    }

    private static Violation invalid(Reservation reservation, String detail) {
        Set<UUID> ids = reservation == null || reservation.planId() == null ? Set.of() : Set.of(reservation.planId());
        return violation(Code.INVALID_RESERVATION, ids, detail);
    }

    private static Violation violation(Code code, Set<UUID> ids, String detail) {
        return new Violation(code, ids, Set.of(), null, null, null, detail);
    }
}
