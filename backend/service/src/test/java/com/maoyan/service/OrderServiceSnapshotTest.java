package com.maoyan.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maoyan.dao.mapper.OrderMapper;
import com.maoyan.dao.mapper.OrderSeatMapper;
import com.maoyan.dao.mapper.ActivitySessionMapper;
import com.maoyan.dao.mapper.SeatLockMapper;
import com.maoyan.domain.enums.ResponseCodeEnum;
import com.maoyan.domain.exception.BizException;
import com.maoyan.domain.model.dto.CreateOrderDTO;
import com.maoyan.domain.model.dto.LockSeatsDTO;
import com.maoyan.domain.model.dto.OrderSnapshotSourceDTO;
import com.maoyan.domain.model.po.OrderPO;
import com.maoyan.domain.model.po.ActivitySessionPO;
import com.maoyan.domain.model.po.SeatLockPO;
import com.maoyan.domain.model.vo.OrderVO;
import com.maoyan.service.infrastructure.DistributedLockService;
import com.maoyan.service.infrastructure.SeatLockClaimService;
import com.maoyan.service.infrastructure.StockService;
import com.maoyan.service.event.OrderEventOutboxService;
import com.maoyan.service.observability.BusinessMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceSnapshotTest {

    @Mock
    private OrderMapper orderMapper;
    @Mock
    private ActivitySessionMapper activitySessionMapper;
    @Mock
    private SeatLockMapper seatLockMapper;
    @Mock
    private OrderSeatMapper orderSeatMapper;
    @Mock
    private StockService stockService;
    @Mock
    private DistributedLockService lockService;
    @Mock
    private SeatLockClaimService seatLockClaimService;
    @Mock
    private PlatformTransactionManager transactionManager;
    @Mock
    private OrderClosureService orderClosureService;
    @Mock
    private OrderEventOutboxService orderEventOutboxService;
    @Mock
    private BusinessMetrics businessMetrics;

    private OrderService orderService;

    @BeforeEach
    void setUp() {
        orderService = new OrderService(orderMapper, activitySessionMapper, seatLockMapper, orderSeatMapper,
                stockService, lockService, seatLockClaimService, transactionManager, orderClosureService, orderEventOutboxService,
                businessMetrics);
        lenient().when(transactionManager.getTransaction(any())).thenAnswer(invocation -> new SimpleTransactionStatus());
        when(lockService.<OrderVO>executeWithBoundedLocks(anyList(), anyLong(), anyLong(), any()))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    Supplier<OrderVO> task = invocation.getArgument(3, Supplier.class);
                    return task.get();
                });
    }

    @Test
    void createOrderWritesTrustedSnapshotAndServerSeatInfo() throws Exception {
        CreateOrderDTO dto = orderRequest("lock-token-1", List.of(seat(2, 3), seat(1, 4)), "client text");
        when(activitySessionMapper.selectById(40L)).thenReturn(activeSchedule());
        when(orderMapper.selectByLockToken("lock-token-1")).thenReturn(null);
        when(seatLockMapper.selectActiveLocksByTokenOnly(eq("lock-token-1"), any()))
                .thenReturn(List.of(lock(2, 3), lock(1, 4)));
        when(activitySessionMapper.selectOrderSnapshotSource(40L)).thenReturn(snapshot());
        when(stockService.preDeduct(40L, 2)).thenReturn(218L);
        when(activitySessionMapper.deductStock(40L, 2)).thenReturn(1);
        when(orderMapper.insert(any(OrderPO.class))).thenReturn(1);
        when(seatLockMapper.bindLocksToOrder(eq(40L), eq(1001L), eq("lock-token-1"),
                anyString(), any(LocalDateTime.class), any(LocalDateTime.class))).thenReturn(2);

        OrderVO result = orderService.createOrder(1001L, dto);

        ArgumentCaptor<OrderPO> orderCaptor = ArgumentCaptor.forClass(OrderPO.class);
        verify(orderMapper).insert(orderCaptor.capture());
        OrderPO saved = orderCaptor.getValue();
        assertThat(saved.getMovieName()).isEqualTo("Snapshot Movie");
        assertThat(saved.getCinemaName()).isEqualTo("Snapshot Cinema");
        assertThat(saved.getHallName()).isEqualTo("Hall A");
        assertThat(saved.getShowTime()).isEqualTo("2026-09-02 11:00");
        assertThat(saved.getSeatCount()).isEqualTo(2);
        assertThat(saved.getSeatsInfo()).isEqualTo("1排4座,2排3座");
        assertThat(saved.getUnitPrice()).isEqualByComparingTo("65.00");
        assertThat(saved.getTotalPrice()).isEqualByComparingTo("130.00");

        assertThat(result.getMovieName()).isEqualTo("Snapshot Movie");
        assertThat(result.getActivityName()).isEqualTo("Snapshot Movie");
        assertThat(result.getVenueName()).isEqualTo("Snapshot Cinema");
        assertThat(result.getSeatsInfo()).isEqualTo("1排4座,2排3座");
        JsonNode json = new ObjectMapper().valueToTree(result);
        assertThat(json.get("activityName").asText()).isEqualTo("Snapshot Movie");
        assertThat(json.get("venueName").asText()).isEqualTo("Snapshot Cinema");
        assertThat(json.get("movieName").asText()).isEqualTo("Snapshot Movie");
        assertThat(json.get("cinemaName").asText()).isEqualTo("Snapshot Cinema");
        verify(activitySessionMapper).deductStock(40L, 2);
        verify(businessMetrics).orderCreated();
        InOrder writeOrder = inOrder(orderMapper, seatLockMapper, orderEventOutboxService,
                activitySessionMapper, stockService);
        writeOrder.verify(orderMapper).insert(any(OrderPO.class));
        writeOrder.verify(seatLockMapper).bindLocksToOrder(eq(40L), eq(1001L), eq("lock-token-1"),
                anyString(), any(LocalDateTime.class), any(LocalDateTime.class));
        writeOrder.verify(orderEventOutboxService).append(
                eq(com.maoyan.domain.model.event.OrderEvent.Type.CREATED), any(OrderPO.class));
        writeOrder.verify(activitySessionMapper).deductStock(40L, 2);
        writeOrder.verify(stockService).initScheduleDetail(any(ActivitySessionPO.class));
    }

    @Test
    void createOrderFailsBeforeStockDeductWhenSnapshotSourceIsIncomplete() {
        CreateOrderDTO dto = orderRequest("lock-token-2", List.of(seat(1, 1)), "client text");
        OrderSnapshotSourceDTO incomplete = snapshot();
        incomplete.setMovieName(null);
        when(activitySessionMapper.selectById(40L)).thenReturn(activeSchedule());
        when(orderMapper.selectByLockToken("lock-token-2")).thenReturn(null);
        when(seatLockMapper.selectActiveLocksByTokenOnly(eq("lock-token-2"), any()))
                .thenReturn(List.of(lock(1, 1)));
        when(activitySessionMapper.selectOrderSnapshotSource(40L)).thenReturn(incomplete);

        assertThatThrownBy(() -> orderService.createOrder(1001L, dto))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getCode())
                .isEqualTo(ResponseCodeEnum.ORDER_CREATE_FAILED.getCode());

        verify(stockService, never()).preDeduct(anyLong(), anyInt());
        verify(activitySessionMapper, never()).deductStock(anyLong(), anyInt());
        verify(orderMapper, never()).insert(any(OrderPO.class));
    }

    @Test
    void consumedLockTokenReturnsExistingOrderWithoutRewritingSnapshot() {
        CreateOrderDTO dto = orderRequest("lock-token-3", List.of(seat(3, 5)), "client text");
        OrderPO existing = new OrderPO();
        existing.setOrderNo("MO202609020001");
        existing.setUserId(1001L);
        existing.setScheduleId(40L);
        existing.setLockToken("lock-token-3");
        existing.setMovieName("Original Movie");
        existing.setCinemaName("Original Cinema");
        existing.setHallName("Original Hall");
        existing.setShowTime("2026-09-02 12:00");
        existing.setSeatCount(1);
        existing.setSeatsInfo("3排5座");
        existing.setUnitPrice(new BigDecimal("50.00"));
        existing.setTotalPrice(new BigDecimal("50.00"));
        existing.setStatus(0);
        when(orderMapper.selectByLockToken("lock-token-3")).thenReturn(existing);
        when(seatLockMapper.selectLocksByToken("lock-token-3")).thenReturn(List.of(lock(3, 5)));

        OrderVO result = orderService.createOrder(1001L, dto);

        assertThat(result.getOrderNo()).isEqualTo("MO202609020001");
        assertThat(result.getMovieName()).isEqualTo("Original Movie");
        assertThat(result.getActivityName()).isEqualTo("Original Movie");
        assertThat(result.getVenueName()).isEqualTo("Original Cinema");
        assertThat(result.getSeatsInfo()).isEqualTo("3排5座");
        verify(activitySessionMapper, never()).selectOrderSnapshotSource(anyLong());
        verify(stockService, never()).preDeduct(anyLong(), anyInt());
        verify(orderMapper, never()).insert(any(OrderPO.class));
        verify(businessMetrics, never()).orderCreated();
    }

    @Test
    void outboxFailureRollsBackTransactionAndCompensatesRedisStock() {
        CreateOrderDTO dto = orderRequest("lock-token-rollback", List.of(seat(4, 6)), "client text");
        when(activitySessionMapper.selectById(40L)).thenReturn(activeSchedule());
        when(orderMapper.selectByLockToken("lock-token-rollback")).thenReturn(null);
        when(seatLockMapper.selectActiveLocksByTokenOnly(eq("lock-token-rollback"), any()))
                .thenReturn(List.of(lock(4, 6)));
        when(activitySessionMapper.selectOrderSnapshotSource(40L)).thenReturn(snapshot());
        when(stockService.preDeduct(40L, 1)).thenReturn(219L);
        when(orderMapper.insert(any(OrderPO.class))).thenReturn(1);
        when(seatLockMapper.bindLocksToOrder(eq(40L), eq(1001L), eq("lock-token-rollback"),
                anyString(), any(LocalDateTime.class), any(LocalDateTime.class))).thenReturn(1);
        doThrow(new IllegalStateException("injected outbox failure"))
                .when(orderEventOutboxService).append(eq(com.maoyan.domain.model.event.OrderEvent.Type.CREATED),
                        any(OrderPO.class));

        assertThatThrownBy(() -> orderService.createOrder(1001L, dto))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getCode())
                .isEqualTo(ResponseCodeEnum.ORDER_CREATE_FAILED.getCode());

        verify(transactionManager).rollback(any());
        verify(stockService).rollback(40L, 1);
        verify(orderMapper).insert(any(OrderPO.class));
        verify(activitySessionMapper, never()).deductStock(anyLong(), anyInt());
    }

    @Test
    void atomicStockFailureRollsBackPriorWritesAndCompensatesRedisOnce() {
        CreateOrderDTO dto = orderRequest("lock-token-stock", List.of(seat(5, 7)), "client text");
        when(activitySessionMapper.selectById(40L)).thenReturn(activeSchedule());
        when(orderMapper.selectByLockToken("lock-token-stock")).thenReturn(null);
        when(seatLockMapper.selectActiveLocksByTokenOnly(eq("lock-token-stock"), any()))
                .thenReturn(List.of(lock(5, 7)));
        when(activitySessionMapper.selectOrderSnapshotSource(40L)).thenReturn(snapshot());
        when(stockService.preDeduct(40L, 1)).thenReturn(219L);
        when(orderMapper.insert(any(OrderPO.class))).thenReturn(1);
        when(seatLockMapper.bindLocksToOrder(eq(40L), eq(1001L), eq("lock-token-stock"),
                anyString(), any(LocalDateTime.class), any(LocalDateTime.class))).thenReturn(1);
        when(activitySessionMapper.deductStock(40L, 1)).thenReturn(0);

        assertThatThrownBy(() -> orderService.createOrder(1001L, dto))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getCode())
                .isEqualTo(ResponseCodeEnum.STOCK_NOT_ENOUGH.getCode());

        verify(transactionManager).getTransaction(any());
        verify(transactionManager).rollback(any());
        verify(stockService).preDeduct(40L, 1);
        verify(stockService).rollback(40L, 1);
        verify(orderMapper).insert(any(OrderPO.class));
        verify(seatLockMapper).bindLocksToOrder(eq(40L), eq(1001L), eq("lock-token-stock"),
                anyString(), any(LocalDateTime.class), any(LocalDateTime.class));
        verify(orderEventOutboxService).append(
                eq(com.maoyan.domain.model.event.OrderEvent.Type.CREATED), any(OrderPO.class));
        verify(stockService, never()).initScheduleDetail(any(ActivitySessionPO.class));
    }

    private static CreateOrderDTO orderRequest(String lockToken, List<LockSeatsDTO.SeatPos> seats, String seatsInfo) {
        CreateOrderDTO dto = new CreateOrderDTO();
        dto.setScheduleId(40L);
        dto.setLockToken(lockToken);
        dto.setSeats(seats);
        dto.setSeatCount(seats.size());
        dto.setSeatsInfo(seatsInfo);
        return dto;
    }

    private static LockSeatsDTO.SeatPos seat(int row, int col) {
        LockSeatsDTO.SeatPos seat = new LockSeatsDTO.SeatPos();
        seat.setRow(row);
        seat.setCol(col);
        return seat;
    }

    private static SeatLockPO lock(int row, int col) {
        SeatLockPO lock = new SeatLockPO();
        lock.setScheduleId(40L);
        lock.setUserId(1001L);
        lock.setRowNum(row);
        lock.setColNum(col);
        lock.setLockToken("unused");
        lock.setStatus(1);
        lock.setLockUntil(LocalDateTime.now().plusMinutes(10));
        return lock;
    }

    private static ActivitySessionPO activeSchedule() {
        return activeSchedule(7);
    }

    private static ActivitySessionPO activeSchedule(int version) {
        ActivitySessionPO schedule = new ActivitySessionPO();
        schedule.setId(40L);
        schedule.setDeleted(0);
        schedule.setStatus(1);
        schedule.setVersion(version);
        schedule.setAvailableSeats(220);
        schedule.setPrice(new BigDecimal("65.00"));
        return schedule;
    }

    private static OrderSnapshotSourceDTO snapshot() {
        return snapshot(7);
    }

    private static OrderSnapshotSourceDTO snapshot(int version) {
        OrderSnapshotSourceDTO snapshot = new OrderSnapshotSourceDTO();
        snapshot.setScheduleId(40L);
        snapshot.setMovieId(3L);
        snapshot.setMovieName("Snapshot Movie");
        snapshot.setCinemaId(4L);
        snapshot.setCinemaName("Snapshot Cinema");
        snapshot.setHallName("Hall A");
        snapshot.setShowDate("2026-09-02");
        snapshot.setShowTime("11:00");
        snapshot.setUnitPrice(new BigDecimal("65.00"));
        snapshot.setStatus(1);
        snapshot.setVersion(version);
        return snapshot;
    }
}
