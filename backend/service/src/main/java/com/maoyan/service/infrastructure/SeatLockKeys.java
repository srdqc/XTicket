package com.maoyan.service.infrastructure;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/** Canonical Redisson keys shared by seat selection and order creation. */
public final class SeatLockKeys {

    public static final long WAIT_SECONDS = 3;
    public static final long LEASE_SECONDS = 12;

    private SeatLockKeys() {
    }

    public static List<Seat> canonicalize(Collection<Seat> seats) {
        return seats.stream()
                .distinct()
                .sorted(Comparator.comparingInt(Seat::row).thenComparingInt(Seat::col))
                .toList();
    }

    public static List<String> seatKeys(Long scheduleId, Collection<Seat> seats) {
        return canonicalize(seats).stream()
                .map(seat -> "seat:" + scheduleId + ":" + seat.row() + ":" + seat.col())
                .toList();
    }

    public static String selectionKey(Long scheduleId, Long userId) {
        return "selection:" + scheduleId + ":" + userId;
    }

    public record Seat(int row, int col) {
    }
}
