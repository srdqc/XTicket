package com.maoyan.service;

import com.maoyan.dao.mapper.ActivitySessionMapper;
import com.maoyan.dao.mapper.OrderSeatMapper;
import com.maoyan.dao.mapper.SeatLockMapper;
import com.maoyan.dao.mapper.VenueHallMapper;
import com.maoyan.domain.model.po.ActivitySessionPO;
import com.maoyan.domain.model.po.OrderSeatPO;
import com.maoyan.domain.model.po.SeatLockPO;
import com.maoyan.domain.model.po.VenueHallPO;
import com.maoyan.domain.model.vo.CompactSeatLayoutVO;
import com.maoyan.domain.model.vo.SeatLayoutVO;
import com.maoyan.service.infrastructure.DistributedLockService;
import com.maoyan.service.observability.BusinessMetrics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CompactSeatLayoutTest {

    private static final long SESSION_ID = 910001L;
    private static final long USER_ID = 920001L;

    @Mock private VenueHallMapper venueHallMapper;
    @Mock private SeatLockMapper seatLockMapper;
    @Mock private OrderSeatMapper orderSeatMapper;
    @Mock private ActivitySessionMapper activitySessionMapper;
    @Mock private DistributedLockService lockService;
    @Mock private BusinessMetrics businessMetrics;
    @InjectMocks private SeatService seatService;

    @Test
    void compactResponseRestoresAllTwentyThousandSeatsExactly() {
        ActivitySessionPO session = new ActivitySessionPO();
        session.setId(SESSION_ID);
        session.setVenueId(900001L);
        session.setHallName("Benchmark Hall");
        session.setDeleted(0);

        VenueHallPO hall = new VenueHallPO();
        hall.setVenueId(900001L);
        hall.setHallName("Benchmark Hall");
        hall.setHallType("Large Venue");
        hall.setSeatRows(100);
        hall.setSeatCols(200);
        hall.setAisleAfterCol("50,150");
        hall.setCoupleRows("99,100");
        hall.setDisabledSeats("[[1,1],[50,100],[100,200]]");

        SeatLockPO ownLock = lock(2, 3, USER_ID);
        SeatLockPO otherLock = lock(4, 5, USER_ID + 1);
        SeatLockPO disabledLock = lock(1, 1, USER_ID);
        SeatLockPO soldAndLocked = lock(6, 7, USER_ID);
        OrderSeatPO sold = sold(8, 9);
        OrderSeatPO soldPriority = sold(6, 7);

        when(activitySessionMapper.selectById(SESSION_ID)).thenReturn(session);
        when(venueHallMapper.selectByVenueAndHall(900001L, "Benchmark Hall")).thenReturn(hall);
        when(seatLockMapper.selectActiveLocks(eq(SESSION_ID), any()))
                .thenReturn(List.of(ownLock, otherLock, disabledLock, soldAndLocked));
        when(orderSeatMapper.selectPurchasedSeats(SESSION_ID)).thenReturn(List.of(sold, soldPriority));

        SeatLayoutVO full = seatService.getSeatLayout(SESSION_ID, USER_ID);
        CompactSeatLayoutVO compact = seatService.getCompactSeatLayout(SESSION_ID, USER_ID);

        assertThat(compact.getSessionId()).isEqualTo(SESSION_ID);
        assertThat(compact.getLayout().getDisabled()).containsExactly(0, 9899, 19999);
        assertThat(compact.getSold()).containsExactly(1006, 1408);
        assertThat(compact.getLocked()).containsExactly(604);
        assertThat(compact.getMyLocked()).containsExactly(202);

        Set<Integer> disabled = new HashSet<>(compact.getLayout().getDisabled());
        Set<Integer> compactSold = new HashSet<>(compact.getSold());
        Set<Integer> locked = new HashSet<>(compact.getLocked());
        Set<Integer> myLocked = new HashSet<>(compact.getMyLocked());
        Set<Integer> coupleRows = new HashSet<>(compact.getLayout().getCoupleRows());

        int compared = 0;
        for (int rowIndex = 0; rowIndex < compact.getLayout().getRows(); rowIndex++) {
            for (int colIndex = 0; colIndex < compact.getLayout().getCols(); colIndex++) {
                int index = rowIndex * compact.getLayout().getCols() + colIndex;
                int row = rowIndex + 1;
                int col = colIndex + 1;
                SeatLayoutVO.SeatInfo expected = full.getSeats().get(rowIndex).get(colIndex);

                int status = disabled.contains(index) ? -1
                        : compactSold.contains(index) ? 1
                        : locked.contains(index) ? 2
                        : myLocked.contains(index) ? 3
                        : 0;

                assertThat(expected.getRow()).isEqualTo(row);
                assertThat(expected.getCol()).isEqualTo(col);
                assertThat(expected.getLabel()).isEqualTo(row + "排" + col + "座");
                assertThat(expected.getCouple()).isEqualTo(coupleRows.contains(row));
                assertThat(expected.getStatus()).isEqualTo(status);
                compared++;
            }
        }
        assertThat(compared).isEqualTo(20_000);
    }

    private SeatLockPO lock(int row, int col, long userId) {
        SeatLockPO lock = new SeatLockPO();
        lock.setRowNum(row);
        lock.setColNum(col);
        lock.setUserId(userId);
        return lock;
    }

    private OrderSeatPO sold(int row, int col) {
        OrderSeatPO seat = new OrderSeatPO();
        seat.setRowNum(row);
        seat.setColNum(col);
        return seat;
    }
}
