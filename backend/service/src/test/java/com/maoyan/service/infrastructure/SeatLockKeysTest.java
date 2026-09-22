package com.maoyan.service.infrastructure;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SeatLockKeysTest {

    @Test
    void seatKeysAreDeduplicatedAndSortedByRowThenColumn() {
        List<String> keys = SeatLockKeys.seatKeys(40L, List.of(
                new SeatLockKeys.Seat(2, 1),
                new SeatLockKeys.Seat(1, 3),
                new SeatLockKeys.Seat(1, 2),
                new SeatLockKeys.Seat(1, 2)));

        assertThat(keys).containsExactly("seat:40:1:2", "seat:40:1:3", "seat:40:2:1");
        assertThat(SeatLockKeys.selectionKey(40L, 1001L)).isEqualTo("selection:40:1001");
    }

    @Test
    void tenSeatSetProducesTenDeterministicallyOrderedLockKeys() {
        List<String> keys = SeatLockKeys.seatKeys(40L, java.util.stream.IntStream.rangeClosed(1, 10)
                .mapToObj(column -> new SeatLockKeys.Seat(4, column))
                .toList());

        assertThat(keys).hasSize(10);
        assertThat(keys.get(0)).isEqualTo("seat:40:4:1");
        assertThat(keys.get(9)).isEqualTo("seat:40:4:10");
    }
}
