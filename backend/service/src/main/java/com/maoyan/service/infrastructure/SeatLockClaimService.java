package com.maoyan.service.infrastructure;

import com.maoyan.dao.mapper.SeatLockMapper;
import com.maoyan.domain.enums.ResponseCodeEnum;
import com.maoyan.domain.exception.BizException;
import com.maoyan.domain.model.po.SeatLockPO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.sql.SQLException;
import java.time.LocalDateTime;

/** Persists one logical seat-lock row without a delete-then-insert gap-lock cycle. */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeatLockClaimService {

    private final SeatLockMapper seatLockMapper;

    public void claim(Long scheduleId, int row, int col, Long userId, String lockToken,
                      LocalDateTime lockUntil, LocalDateTime now, int requestSeatCount) {
        SeatLockPO current = seatLockMapper.selectSeatLock(scheduleId, row, col);
        if (current != null) {
            reclaimOrConflict(current, userId, lockToken, lockUntil, now);
            return;
        }

        SeatLockPO lock = new SeatLockPO();
        lock.setScheduleId(scheduleId);
        lock.setRowNum(row);
        lock.setColNum(col);
        lock.setUserId(userId);
        lock.setLockToken(lockToken);
        lock.setLockUntil(lockUntil);
        lock.setStatus(1);
        lock.setCreateTime(now);
        lock.setUpdateTime(now);
        try {
            seatLockMapper.insert(lock);
        } catch (DuplicateKeyException e) {
            SeatLockPO raced = seatLockMapper.selectSeatLockForUpdate(scheduleId, row, col);
            if (raced != null && tryReclaim(raced, userId, lockToken, lockUntil, now)) {
                return;
            }
            SQLException sqlException = findSqlException(e);
            log.warn("[Seat] Seat lock conflict after insert race: scheduleId={}, row={}, col={}, springException={}, sqlErrorCode={}, sqlState={}, requestSeatCount={}",
                    scheduleId, row, col, e.getClass().getSimpleName(),
                    sqlException != null ? sqlException.getErrorCode() : null,
                    sqlException != null ? sqlException.getSQLState() : null,
                    requestSeatCount);
            throw new BizException(ResponseCodeEnum.SEAT_LOCKED);
        }
    }

    private void reclaimOrConflict(SeatLockPO current, Long userId, String lockToken,
                                   LocalDateTime lockUntil, LocalDateTime now) {
        if (!tryReclaim(current, userId, lockToken, lockUntil, now)) {
            throw new BizException(ResponseCodeEnum.SEAT_LOCKED);
        }
    }

    private boolean tryReclaim(SeatLockPO current, Long userId, String lockToken,
                               LocalDateTime lockUntil, LocalDateTime now) {
        if (!isReclaimable(current, now)) {
            return false;
        }
        return seatLockMapper.reclaimSeatLock(
                current.getId(), userId, lockToken, lockUntil, now) == 1;
    }

    private boolean isReclaimable(SeatLockPO current, LocalDateTime now) {
        if (current.getOrderNo() != null && !current.getOrderNo().isBlank()) {
            return false;
        }
        return Integer.valueOf(0).equals(current.getStatus()) ||
                (Integer.valueOf(1).equals(current.getStatus()) &&
                        current.getLockUntil() != null && !current.getLockUntil().isAfter(now));
    }

    private SQLException findSqlException(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SQLException sqlException) {
                return sqlException;
            }
            current = current.getCause();
        }
        return null;
    }
}
