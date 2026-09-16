package com.maoyan.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.maoyan.dao.mapper.OrderMapper;
import com.maoyan.dao.mapper.OrderSeatMapper;
import com.maoyan.dao.mapper.PaymentRecordMapper;
import com.maoyan.dao.mapper.SeatLockMapper;
import com.maoyan.dao.mapper.UserMapper;
import com.maoyan.domain.enums.OrderStatusEnum;
import com.maoyan.domain.exception.BizException;
import com.maoyan.domain.model.po.OrderPO;
import com.maoyan.domain.model.po.PaymentRecordPO;
import com.maoyan.domain.model.po.SeatLockPO;
import com.maoyan.domain.model.po.UserPO;
import com.maoyan.domain.model.vo.OrderVO;
import com.maoyan.service.infrastructure.DistributedLockService;
import com.maoyan.service.event.OrderEventOutboxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentServicePaymentRecordTest {

    @Mock
    private OrderMapper orderMapper;
    @Mock
    private SeatLockMapper seatLockMapper;
    @Mock
    private OrderSeatMapper orderSeatMapper;
    @Mock
    private PaymentRecordMapper paymentRecordMapper;
    @Mock
    private UserMapper userMapper;
    @Mock
    private DistributedLockService lockService;
    @Mock
    private OrderClosureService orderClosureService;
    @Mock
    private TicketService ticketService;
    @Mock
    private OrderEventOutboxService orderEventOutboxService;

    private PaymentService paymentService;

    @BeforeEach
    void setUp() {
        paymentService = new PaymentService(orderMapper, seatLockMapper, orderSeatMapper,
                paymentRecordMapper, userMapper, lockService, orderClosureService, ticketService,
                orderEventOutboxService);
        when(lockService.<OrderVO>executeWithBoundedLock(anyString(), anyLong(), anyLong(), any()))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    Supplier<OrderVO> task = invocation.getArgument(3, Supplier.class);
                    return task.get();
                });
    }

    @Test
    void successfulMockPaymentWritesOneAuditablePaymentRecordFromOrderSnapshotAmount() {
        OrderPO order = pendingOrder("MO_PAY_001", new BigDecimal("110.00"));
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(order);
        when(seatLockMapper.selectLocksByOrderNo("MO_PAY_001")).thenReturn(List.of(lock(1, 1), lock(1, 2)));
        when(userMapper.selectById(1001L)).thenReturn(user(500), user(390));
        when(userMapper.deductPoints(1001L, 110)).thenReturn(1);
        when(orderMapper.markOrderPaid(eq("MO_PAY_001"), any(LocalDateTime.class))).thenReturn(1);

        OrderVO result = paymentService.payOrder(1001L, "MO_PAY_001");

        ArgumentCaptor<PaymentRecordPO> recordCaptor = ArgumentCaptor.forClass(PaymentRecordPO.class);
        verify(paymentRecordMapper).insert(recordCaptor.capture());
        PaymentRecordPO record = recordCaptor.getValue();
        assertThat(record.getPaymentNo()).startsWith("PAY");
        assertThat(record.getOrderNo()).isEqualTo("MO_PAY_001");
        assertThat(record.getUserId()).isEqualTo(1001L);
        assertThat(record.getAmount()).isEqualByComparingTo("110.00");
        assertThat(record.getChannel()).isEqualTo("MOCK_POINTS");
        assertThat(record.getStatus()).isEqualTo("SUCCESS");
        assertThat(record.getPaidAt()).isNotNull();
        assertThat(result.getRemainingPoints()).isEqualTo(390);

        verify(orderSeatMapper, times(2)).insert(any());
        ArgumentCaptor<com.maoyan.domain.model.po.OrderSeatPO> seatCaptor =
                ArgumentCaptor.forClass(com.maoyan.domain.model.po.OrderSeatPO.class);
        verify(orderSeatMapper, times(2)).insert(seatCaptor.capture());
        assertThat(seatCaptor.getAllValues()).allMatch(seat -> Integer.valueOf(1).equals(seat.getActiveSaleMarker()));
        InOrder writes = inOrder(userMapper, orderMapper, seatLockMapper, paymentRecordMapper, ticketService);
        writes.verify(userMapper).deductPoints(1001L, 110);
        writes.verify(orderMapper).markOrderPaid(eq("MO_PAY_001"), any(LocalDateTime.class));
        writes.verify(seatLockMapper).markAsPurchased(eq("MO_PAY_001"), any(LocalDateTime.class));
        writes.verify(paymentRecordMapper).insert(any(PaymentRecordPO.class));
        writes.verify(ticketService).issueTickets("MO_PAY_001");
    }

    @Test
    void repeatedPaidOrderDoesNotWritePaymentRecord() {
        OrderPO order = pendingOrder("MO_PAY_002", new BigDecimal("65.00"));
        order.setStatus(OrderStatusEnum.PAID.getCode());
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(order);

        assertThatThrownBy(() -> paymentService.payOrder(1001L, "MO_PAY_002"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("订单已支付");

        verify(paymentRecordMapper, never()).insert(any(PaymentRecordPO.class));
        verify(userMapper, never()).deductPoints(any(), anyInt());
        verify(ticketService, never()).issueTickets(anyString());
    }

    @Test
    void insufficientPointsDoesNotWritePaymentRecord() {
        OrderPO order = pendingOrder("MO_PAY_003", new BigDecimal("65.00"));
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(order);
        when(seatLockMapper.selectLocksByOrderNo("MO_PAY_003")).thenReturn(List.of(lock(1, 3)));
        when(userMapper.selectById(1001L)).thenReturn(user(10));

        assertThatThrownBy(() -> paymentService.payOrder(1001L, "MO_PAY_003"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("积分不足");

        verify(paymentRecordMapper, never()).insert(any(PaymentRecordPO.class));
        verify(userMapper, never()).deductPoints(any(), anyInt());
        verify(orderMapper, never()).markOrderPaid(anyString(), any(LocalDateTime.class));
    }

    @Test
    void expiredOrderPaymentClosesOrderAndDoesNotWritePaymentRecord() {
        OrderPO order = pendingOrder("MO_PAY_004", new BigDecimal("65.00"));
        order.setExpireTime(LocalDateTime.now().minusMinutes(1));
        OrderPO closed = pendingOrder("MO_PAY_004", new BigDecimal("65.00"));
        closed.setStatus(OrderStatusEnum.CANCELLED.getCode());
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(order);
        when(orderClosureService.closeExpiredOrder("MO_PAY_004", "PAYMENT_LAZY_EXPIRE"))
                .thenReturn(OrderClosureService.CloseResult.closed(closed, 1, 1));

        assertThatThrownBy(() -> paymentService.payOrder(1001L, "MO_PAY_004"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("订单已过期");

        verify(paymentRecordMapper, never()).insert(any(PaymentRecordPO.class));
        verify(userMapper, never()).deductPoints(any(), anyInt());
    }

    @Test
    void orderSeatConflictDoesNotReachPaymentRecordInsert() {
        OrderPO order = pendingOrder("MO_PAY_005", new BigDecimal("65.00"));
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(order);
        when(seatLockMapper.selectLocksByOrderNo("MO_PAY_005")).thenReturn(List.of(lock(1, 5)));
        when(userMapper.selectById(1001L)).thenReturn(user(500));
        when(userMapper.deductPoints(1001L, 65)).thenReturn(1);
        when(orderMapper.markOrderPaid(eq("MO_PAY_005"), any(LocalDateTime.class))).thenReturn(1);
        when(orderSeatMapper.insert(any())).thenThrow(new DuplicateKeyException("duplicate order_seat"));

        assertThatThrownBy(() -> paymentService.payOrder(1001L, "MO_PAY_005"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("所选座位已被其他订单确认");

        verify(paymentRecordMapper, never()).insert(any(PaymentRecordPO.class));
        verify(seatLockMapper, never()).markAsPurchased(anyString(), any(LocalDateTime.class));
    }

    @Test
    void ticketIssuanceFailurePropagatesFromTransactionalPayment() throws Exception {
        OrderPO order = pendingOrder("MO_PAY_006", new BigDecimal("65.00"));
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(order);
        when(seatLockMapper.selectLocksByOrderNo("MO_PAY_006")).thenReturn(List.of(lock(2, 1)));
        when(userMapper.selectById(1001L)).thenReturn(user(500));
        when(userMapper.deductPoints(1001L, 65)).thenReturn(1);
        when(orderMapper.markOrderPaid(eq("MO_PAY_006"), any(LocalDateTime.class))).thenReturn(1);
        doThrow(new IllegalStateException("ticket persistence failed"))
                .when(ticketService).issueTickets("MO_PAY_006");

        assertThatThrownBy(() -> paymentService.payOrder(1001L, "MO_PAY_006"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ticket persistence failed");

        assertThat(order.getStatus()).isEqualTo(OrderStatusEnum.PENDING.getCode());
        verify(paymentRecordMapper).insert(any(PaymentRecordPO.class));
        verify(ticketService).issueTickets("MO_PAY_006");
        assertThat(PaymentService.class.getMethod("payOrder", Long.class, String.class)
                .getAnnotation(org.springframework.transaction.annotation.Transactional.class)
                .rollbackFor()).contains(Exception.class);
    }

    private static OrderPO pendingOrder(String orderNo, BigDecimal totalPrice) {
        OrderPO order = new OrderPO();
        order.setId(1L);
        order.setOrderNo(orderNo);
        order.setUserId(1001L);
        order.setScheduleId(40L);
        order.setLockToken("lock-token");
        order.setMovieName("Snapshot Movie");
        order.setCinemaName("Snapshot Cinema");
        order.setHallName("Hall A");
        order.setShowTime("2026-09-02 11:00");
        order.setSeatCount(totalPrice.compareTo(new BigDecimal("100")) > 0 ? 2 : 1);
        order.setSeatsInfo("1排1座");
        order.setUnitPrice(new BigDecimal("65.00"));
        order.setTotalPrice(totalPrice);
        order.setStatus(OrderStatusEnum.PENDING.getCode());
        order.setExpireTime(LocalDateTime.now().plusMinutes(10));
        return order;
    }

    private static SeatLockPO lock(int row, int col) {
        SeatLockPO lock = new SeatLockPO();
        lock.setScheduleId(40L);
        lock.setUserId(1001L);
        lock.setOrderNo("unused");
        lock.setRowNum(row);
        lock.setColNum(col);
        lock.setStatus(1);
        return lock;
    }

    private static UserPO user(int points) {
        UserPO user = new UserPO();
        user.setId(1001L);
        user.setPoints(points);
        return user;
    }
}
