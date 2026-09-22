package com.maoyan.service.infrastructure;

import com.maoyan.dao.mapper.SeatLockMapper;
import com.maoyan.domain.enums.ResponseCodeEnum;
import com.maoyan.domain.exception.BizException;
import com.maoyan.domain.model.po.SeatLockPO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SeatLockClaimServiceTest {

    @Mock
    private SeatLockMapper seatLockMapper;

    private SeatLockClaimService service;
    private LocalDateTime now;

    @BeforeEach
    void setUp() {
        service = new SeatLockClaimService(seatLockMapper);
        now = LocalDateTime.of(2026, 9, 22, 16, 0);
    }

    @Test
    void missingSeatInsertsOneRow() {
        when(seatLockMapper.selectSeatLock(40L, 1, 1)).thenReturn(null);

        claim(1, 1);

        verify(seatLockMapper).insert(any(SeatLockPO.class));
        verify(seatLockMapper, never()).reclaimSeatLock(any(), any(), any(), any(), any());
    }

    @Test
    void activeSeatIsBusinessConflict() {
        when(seatLockMapper.selectSeatLock(40L, 1, 2))
                .thenReturn(lock(1L, 1, now.plusMinutes(5), null));

        assertConflict(() -> claim(1, 2));

        verify(seatLockMapper, never()).insert(any(SeatLockPO.class));
        verify(seatLockMapper, never()).reclaimSeatLock(any(), any(), any(), any(), any());
    }

    @Test
    void expiredUnboundSeatIsReclaimedInPlace() {
        SeatLockPO expired = lock(2L, 1, now.minusSeconds(1), null);
        when(seatLockMapper.selectSeatLock(40L, 1, 3)).thenReturn(expired);
        when(seatLockMapper.reclaimSeatLock(eq(2L), eq(1001L), eq("token"), any(), eq(now)))
                .thenReturn(1);

        claim(1, 3);

        verify(seatLockMapper).reclaimSeatLock(eq(2L), eq(1001L), eq("token"), any(), eq(now));
        verify(seatLockMapper, never()).insert(any(SeatLockPO.class));
    }

    @Test
    void boundSeatCannotBeReclaimedEvenWhenExpired() {
        when(seatLockMapper.selectSeatLock(40L, 1, 4))
                .thenReturn(lock(3L, 1, now.minusSeconds(1), "MO-BOUND"));

        assertConflict(() -> claim(1, 4));

        verify(seatLockMapper, never()).reclaimSeatLock(any(), any(), any(), any(), any());
    }

    @Test
    void duplicateInsertRaceRereadsAndReclaimsAtMostOnce() {
        SeatLockPO expired = lock(4L, 1, now.minusSeconds(1), null);
        when(seatLockMapper.selectSeatLock(40L, 1, 5)).thenReturn(null);
        when(seatLockMapper.insert(any(SeatLockPO.class)))
                .thenThrow(new DuplicateKeyException("race"));
        when(seatLockMapper.selectSeatLockForUpdate(40L, 1, 5)).thenReturn(expired);
        when(seatLockMapper.reclaimSeatLock(eq(4L), eq(1001L), eq("token"), any(), eq(now)))
                .thenReturn(1);

        claim(1, 5);

        verify(seatLockMapper).selectSeatLockForUpdate(40L, 1, 5);
        verify(seatLockMapper).reclaimSeatLock(eq(4L), eq(1001L), eq("token"), any(), eq(now));
    }

    private void claim(int row, int col) {
        service.claim(40L, row, col, 1001L, "token", now.plusMinutes(15), now, 1);
    }

    private void assertConflict(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BizException.class)
                .extracting(error -> ((BizException) error).getCode())
                .isEqualTo(ResponseCodeEnum.SEAT_LOCKED.getCode());
    }

    private SeatLockPO lock(Long id, int status, LocalDateTime lockUntil, String orderNo) {
        SeatLockPO lock = new SeatLockPO();
        lock.setId(id);
        lock.setStatus(status);
        lock.setLockUntil(lockUntil);
        lock.setOrderNo(orderNo);
        return lock;
    }
}
