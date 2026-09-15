package com.maoyan.service;

import com.maoyan.dao.mapper.ActivitySessionMapper;
import com.maoyan.dao.mapper.ElectronicTicketMapper;
import com.maoyan.dao.mapper.OrderMapper;
import com.maoyan.dao.mapper.OrderSeatMapper;
import com.maoyan.dao.mapper.PaymentRecordMapper;
import com.maoyan.dao.mapper.RefundRecordMapper;
import com.maoyan.dao.mapper.SeatLockMapper;
import com.maoyan.dao.mapper.UserMapper;
import com.maoyan.domain.enums.OrderStatusEnum;
import com.maoyan.domain.enums.TicketStatusEnum;
import com.maoyan.domain.enums.UserRoleEnum;
import com.maoyan.domain.exception.BizException;
import com.maoyan.domain.model.po.ElectronicTicketPO;
import com.maoyan.domain.model.po.OrderPO;
import com.maoyan.domain.model.po.OrderSeatPO;
import com.maoyan.domain.model.po.PaymentRecordPO;
import com.maoyan.domain.model.po.RefundRecordPO;
import com.maoyan.domain.model.po.UserPO;
import com.maoyan.domain.model.vo.CheckInResult;
import com.maoyan.domain.model.vo.RefundResult;
import com.maoyan.service.infrastructure.StockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RefundServiceTest {

    private static final long USER_ID = 1001L;
    private static final String ORDER_NO = "ORDER-REFUND-1";

    @Mock private OrderMapper orderMapper;
    @Mock private PaymentRecordMapper paymentRecordMapper;
    @Mock private RefundRecordMapper refundRecordMapper;
    @Mock private ElectronicTicketMapper electronicTicketMapper;
    @Mock private OrderSeatMapper orderSeatMapper;
    @Mock private SeatLockMapper seatLockMapper;
    @Mock private UserMapper userMapper;
    @Mock private ActivitySessionMapper activitySessionMapper;
    @Mock private StockService stockService;

    private RefundService refundService;

    @BeforeEach
    void setUp() {
        refundService = new RefundService(orderMapper, paymentRecordMapper, refundRecordMapper,
                electronicTicketMapper, orderSeatMapper, seatLockMapper, userMapper,
                activitySessionMapper, stockService);
    }

    @Test
    void paidOrderRefundsAllDatabaseFactsAndReturnsRoundedPoints() {
        stubSuccessfulRefund();

        RefundResult result = refundService.refund(USER_ID, ORDER_NO);

        assertThat(result.getStatus()).isEqualTo(OrderStatusEnum.REFUNDED.getCode());
        assertThat(result.getRefundedAmount()).isEqualByComparingTo("65.01");
        assertThat(result.getRefundedPoints()).isEqualTo(66);
        assertThat(result.getInvalidatedTicketCount()).isEqualTo(2);
        assertThat(result.getRefundTime()).isNotBlank();
        verify(userMapper).addPoints(USER_ID, 66);
        verify(orderSeatMapper).releaseActiveSalesByOrderNo(ORDER_NO);
        verify(seatLockMapper).releasePurchasedOrderLocks(ORDER_NO);
        verify(activitySessionMapper).rollbackStock(40L, 2);
        verify(stockService).rollback(40L, 2);

        ArgumentCaptor<RefundRecordPO> record = ArgumentCaptor.forClass(RefundRecordPO.class);
        verify(refundRecordMapper).insert(record.capture());
        assertThat(record.getValue().getRefundNo()).startsWith("RF").hasSize(34);
        assertThat(record.getValue().getPaymentNo()).isEqualTo("PAY-1");
        assertThat(record.getValue().getStatus()).isEqualTo("SUCCESS");
    }

    @Test
    void repeatedRefundReturnsExistingFactWithoutSideEffects() {
        OrderPO order = paidOrder();
        order.setStatus(OrderStatusEnum.REFUNDED.getCode());
        order.setRefundTime(LocalDateTime.of(2026, 9, 15, 10, 0));
        RefundRecordPO record = refundRecord(order.getRefundTime());
        when(orderMapper.selectByOrderNoAndUserIdForUpdate(ORDER_NO, USER_ID)).thenReturn(order);
        when(refundRecordMapper.selectByOrderNo(ORDER_NO)).thenReturn(record);
        when(electronicTicketMapper.selectByOrderNo(ORDER_NO)).thenReturn(invalidatedTickets());

        RefundResult result = refundService.refund(USER_ID, ORDER_NO);

        assertThat(result.getRefundTime()).isEqualTo("2026-09-15 10:00:00");
        assertThat(result.getInvalidatedTicketCount()).isEqualTo(2);
        verify(userMapper, never()).addPoints(any(), anyInt());
        verify(activitySessionMapper, never()).rollbackStock(any(), anyInt());
        verify(refundRecordMapper, never()).insert(any());
    }

    @Test
    void usedOrAlreadyInvalidatedTicketRejectsRefund() {
        for (int status : List.of(TicketStatusEnum.USED.getCode(), TicketStatusEnum.INVALIDATED.getCode())) {
            org.mockito.Mockito.reset(electronicTicketMapper, orderMapper);
            when(orderMapper.selectByOrderNoAndUserIdForUpdate(ORDER_NO, USER_ID)).thenReturn(paidOrder());
            when(electronicTicketMapper.selectByOrderNoForUpdate(ORDER_NO))
                    .thenReturn(List.of(ticket(1L, status), ticket(2L, TicketStatusEnum.ISSUED.getCode())));

            assertThatThrownBy(() -> refundService.refund(USER_ID, ORDER_NO))
                    .isInstanceOf(BizException.class);
        }
        verify(userMapper, never()).addPoints(any(), anyInt());
    }

    @Test
    void pendingCancelledAndOtherUsersCannotRefund() {
        OrderPO pending = paidOrder();
        pending.setStatus(OrderStatusEnum.PENDING.getCode());
        OrderPO cancelled = paidOrder();
        cancelled.setStatus(OrderStatusEnum.CANCELLED.getCode());
        when(orderMapper.selectByOrderNoAndUserIdForUpdate("PENDING", USER_ID)).thenReturn(pending);
        when(orderMapper.selectByOrderNoAndUserIdForUpdate("CANCELLED", USER_ID)).thenReturn(cancelled);
        when(orderMapper.selectByOrderNoAndUserIdForUpdate("OTHER", USER_ID)).thenReturn(null);

        assertThatThrownBy(() -> refundService.refund(USER_ID, "PENDING")).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> refundService.refund(USER_ID, "CANCELLED")).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> refundService.refund(USER_ID, "OTHER")).isInstanceOf(BizException.class);
        verify(userMapper, never()).addPoints(any(), anyInt());
    }

    @Test
    void incompleteTicketInvalidationFailsBeforePointsAndSeatRelease() {
        stubSuccessfulRefund();
        when(electronicTicketMapper.invalidateIssuedByOrderNo(eq(ORDER_NO), any(), anyInt(), anyInt()))
                .thenReturn(1);

        assertThatThrownBy(() -> refundService.refund(USER_ID, ORDER_NO))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("电子票作废不完整");
        verify(userMapper, never()).addPoints(any(), anyInt());
        verify(orderSeatMapper, never()).releaseActiveSalesByOrderNo(anyString());
    }

    @Test
    void eightConcurrentRefundsProduceOneSetOfSideEffects() {
        AtomicInteger status = new AtomicInteger(OrderStatusEnum.PAID.getCode());
        AtomicReference<RefundRecordPO> savedRecord = new AtomicReference<>();
        ReentrantLock orderLock = new ReentrantLock();
        AtomicInteger pointsReturns = new AtomicInteger();
        AtomicInteger stockReturns = new AtomicInteger();
        AtomicInteger invalidations = new AtomicInteger();

        when(orderMapper.selectByOrderNoAndUserIdForUpdate(ORDER_NO, USER_ID)).thenAnswer(invocation -> {
            orderLock.lock();
            OrderPO order = paidOrder();
            order.setStatus(status.get());
            if (status.get() == OrderStatusEnum.REFUNDED.getCode()) {
                order.setRefundTime(savedRecord.get().getRefundedAt());
                orderLock.unlock();
            }
            return order;
        });
        when(electronicTicketMapper.selectByOrderNoForUpdate(ORDER_NO)).thenReturn(issuedTickets());
        when(electronicTicketMapper.selectByOrderNo(ORDER_NO)).thenReturn(invalidatedTickets());
        when(orderSeatMapper.selectByOrderNo(ORDER_NO)).thenReturn(activeSeats());
        when(paymentRecordMapper.selectSuccessfulByOrderNo(ORDER_NO)).thenReturn(payment());
        when(electronicTicketMapper.invalidateIssuedByOrderNo(eq(ORDER_NO), any(), anyInt(), anyInt()))
                .thenAnswer(invocation -> { invalidations.incrementAndGet(); return 2; });
        when(orderMapper.markOrderRefunded(eq(ORDER_NO), eq(USER_ID), any())).thenAnswer(invocation -> {
            return status.compareAndSet(OrderStatusEnum.PAID.getCode(), OrderStatusEnum.REFUNDED.getCode()) ? 1 : 0;
        });
        when(userMapper.addPoints(USER_ID, 66)).thenAnswer(invocation -> { pointsReturns.incrementAndGet(); return 1; });
        when(refundRecordMapper.insert(any())).thenAnswer(invocation -> {
            savedRecord.set(invocation.getArgument(0));
            orderLock.unlock();
            return 1;
        });
        when(refundRecordMapper.selectByOrderNo(ORDER_NO)).thenAnswer(invocation -> savedRecord.get());
        when(orderSeatMapper.releaseActiveSalesByOrderNo(ORDER_NO)).thenReturn(2);
        when(seatLockMapper.releasePurchasedOrderLocks(ORDER_NO)).thenReturn(2);
        when(activitySessionMapper.rollbackStock(40L, 2)).thenAnswer(invocation -> { stockReturns.incrementAndGet(); return 1; });

        CyclicBarrier barrier = new CyclicBarrier(8);
        List<CompletableFuture<RefundResult>> requests = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            requests.add(CompletableFuture.supplyAsync(() -> {
                await(barrier);
                return refundService.refund(USER_ID, ORDER_NO);
            }));
        }
        CompletableFuture.allOf(requests.toArray(CompletableFuture[]::new)).join();

        assertThat(requests).allMatch(request -> request.join().getStatus() == OrderStatusEnum.REFUNDED.getCode());
        assertThat(pointsReturns).hasValue(1);
        assertThat(stockReturns).hasValue(1);
        assertThat(invalidations).hasValue(1);
        verify(refundRecordMapper, times(1)).insert(any());
        verify(orderSeatMapper, times(1)).releaseActiveSalesByOrderNo(ORDER_NO);
    }

    @Test
    void refundAndCheckInNeverProduceRefundedOrderWithUsedTicket() {
        String ticketNo = "ET-RACE";
        long staffId = 2001L;
        AtomicInteger orderStatus = new AtomicInteger(OrderStatusEnum.PAID.getCode());
        AtomicInteger ticketStatus = new AtomicInteger(TicketStatusEnum.ISSUED.getCode());
        ReentrantLock ticketLock = new ReentrantLock();

        UserPO staff = new UserPO();
        staff.setId(staffId);
        staff.setRole(UserRoleEnum.CHECKIN_STAFF.name());
        staff.setDeleted(0);
        when(userMapper.selectById(staffId)).thenReturn(staff);
        when(orderMapper.selectByOrderNoAndUserIdForUpdate(ORDER_NO, USER_ID)).thenAnswer(invocation -> {
            OrderPO order = paidOrder();
            order.setSeatCount(1);
            order.setStatus(orderStatus.get());
            return order;
        });
        when(electronicTicketMapper.selectByOrderNoForUpdate(ORDER_NO)).thenAnswer(invocation -> {
            ticketLock.lock();
            return List.of(raceTicket(ticketNo, ticketStatus.get()));
        });
        when(electronicTicketMapper.selectByTicketNo(ticketNo))
                .thenAnswer(invocation -> raceTicket(ticketNo, ticketStatus.get()));
        lenient().when(electronicTicketMapper.markAsUsed(eq(ticketNo), eq(40L), eq(staffId), any(), anyInt(), anyInt()))
                .thenAnswer(invocation -> {
                    ticketLock.lock();
                    try {
                        return ticketStatus.compareAndSet(TicketStatusEnum.ISSUED.getCode(),
                                TicketStatusEnum.USED.getCode()) ? 1 : 0;
                    } finally {
                        ticketLock.unlock();
                    }
                });
        lenient().when(electronicTicketMapper.selectCheckInResultByTicketNo(ticketNo)).thenAnswer(invocation -> {
            CheckInResult result = new CheckInResult();
            result.setTicketNo(ticketNo);
            result.setSessionId(40L);
            result.setStatus(ticketStatus.get());
            return result;
        });
        when(electronicTicketMapper.invalidateIssuedByOrderNo(eq(ORDER_NO), any(), anyInt(), anyInt()))
                .thenAnswer(invocation -> {
                    try {
                        return ticketStatus.compareAndSet(TicketStatusEnum.ISSUED.getCode(),
                                TicketStatusEnum.INVALIDATED.getCode()) ? 1 : 0;
                    } finally {
                        ticketLock.unlock();
                    }
                });
        when(orderSeatMapper.selectByOrderNo(ORDER_NO)).thenReturn(List.of(activeSeat()));
        when(paymentRecordMapper.selectSuccessfulByOrderNo(ORDER_NO)).thenReturn(paymentFor("65.01"));
        when(orderMapper.markOrderRefunded(eq(ORDER_NO), eq(USER_ID), any())).thenAnswer(invocation -> {
            orderStatus.set(OrderStatusEnum.REFUNDED.getCode());
            return 1;
        });
        when(userMapper.addPoints(USER_ID, 66)).thenReturn(1);
        when(orderSeatMapper.releaseActiveSalesByOrderNo(ORDER_NO)).thenReturn(1);
        when(seatLockMapper.releasePurchasedOrderLocks(ORDER_NO)).thenReturn(1);
        when(activitySessionMapper.rollbackStock(40L, 1)).thenReturn(1);

        CyclicBarrier barrier = new CyclicBarrier(2);
        CheckInService checkInService = new CheckInService(userMapper, electronicTicketMapper);
        CompletableFuture<Boolean> refund = CompletableFuture.supplyAsync(() -> {
            await(barrier);
            try {
                refundService.refund(USER_ID, ORDER_NO);
                return true;
            } catch (BizException e) {
                return false;
            }
        });
        CompletableFuture<Boolean> checkIn = CompletableFuture.supplyAsync(() -> {
            await(barrier);
            try {
                checkInService.checkIn(staffId, ticketNo, 40L);
                return true;
            } catch (BizException e) {
                return false;
            }
        });
        CompletableFuture.allOf(refund, checkIn).join();

        assertThat(orderStatus.get() == OrderStatusEnum.REFUNDED.getCode()
                && ticketStatus.get() == TicketStatusEnum.USED.getCode()).isFalse();
        assertThat(List.of(
                orderStatus.get() == OrderStatusEnum.REFUNDED.getCode()
                        && ticketStatus.get() == TicketStatusEnum.INVALIDATED.getCode(),
                orderStatus.get() == OrderStatusEnum.PAID.getCode()
                        && ticketStatus.get() == TicketStatusEnum.USED.getCode()))
                .contains(true);
    }

    private void stubSuccessfulRefund() {
        when(orderMapper.selectByOrderNoAndUserIdForUpdate(ORDER_NO, USER_ID)).thenReturn(paidOrder());
        when(electronicTicketMapper.selectByOrderNoForUpdate(ORDER_NO)).thenReturn(issuedTickets());
        when(orderSeatMapper.selectByOrderNo(ORDER_NO)).thenReturn(activeSeats());
        when(paymentRecordMapper.selectSuccessfulByOrderNo(ORDER_NO)).thenReturn(payment());
        lenient().when(electronicTicketMapper.invalidateIssuedByOrderNo(eq(ORDER_NO), any(), anyInt(), anyInt())).thenReturn(2);
        lenient().when(orderMapper.markOrderRefunded(eq(ORDER_NO), eq(USER_ID), any())).thenReturn(1);
        lenient().when(userMapper.addPoints(USER_ID, 66)).thenReturn(1);
        lenient().when(orderSeatMapper.releaseActiveSalesByOrderNo(ORDER_NO)).thenReturn(2);
        lenient().when(seatLockMapper.releasePurchasedOrderLocks(ORDER_NO)).thenReturn(2);
        lenient().when(activitySessionMapper.rollbackStock(40L, 2)).thenReturn(1);
    }

    private static OrderPO paidOrder() {
        OrderPO order = new OrderPO();
        order.setId(10L);
        order.setOrderNo(ORDER_NO);
        order.setUserId(USER_ID);
        order.setScheduleId(40L);
        order.setSeatCount(2);
        order.setTotalPrice(new BigDecimal("65.01"));
        order.setStatus(OrderStatusEnum.PAID.getCode());
        return order;
    }

    private static PaymentRecordPO payment() {
        PaymentRecordPO payment = new PaymentRecordPO();
        payment.setPaymentNo("PAY-1");
        payment.setOrderNo(ORDER_NO);
        payment.setUserId(USER_ID);
        payment.setAmount(new BigDecimal("65.01"));
        payment.setStatus("SUCCESS");
        return payment;
    }

    private static List<ElectronicTicketPO> issuedTickets() {
        return List.of(ticket(1L, TicketStatusEnum.ISSUED.getCode()), ticket(2L, TicketStatusEnum.ISSUED.getCode()));
    }

    private static List<ElectronicTicketPO> invalidatedTickets() {
        return List.of(ticket(1L, TicketStatusEnum.INVALIDATED.getCode()), ticket(2L, TicketStatusEnum.INVALIDATED.getCode()));
    }

    private static ElectronicTicketPO ticket(long id, int status) {
        ElectronicTicketPO ticket = new ElectronicTicketPO();
        ticket.setId(id);
        ticket.setOrderNo(ORDER_NO);
        ticket.setStatus(status);
        return ticket;
    }

    private static List<OrderSeatPO> activeSeats() {
        return List.of(activeSeat(), activeSeat());
    }

    private static OrderSeatPO activeSeat() {
        OrderSeatPO seat = new OrderSeatPO();
        seat.setActiveSaleMarker(1);
        return seat;
    }

    private static ElectronicTicketPO raceTicket(String ticketNo, int status) {
        ElectronicTicketPO ticket = ticket(1L, status);
        ticket.setTicketNo(ticketNo);
        ticket.setSessionId(40L);
        return ticket;
    }

    private static PaymentRecordPO paymentFor(String amount) {
        PaymentRecordPO payment = payment();
        payment.setAmount(new BigDecimal(amount));
        return payment;
    }

    private static RefundRecordPO refundRecord(LocalDateTime refundedAt) {
        RefundRecordPO record = new RefundRecordPO();
        record.setOrderNo(ORDER_NO);
        record.setAmount(new BigDecimal("65.01"));
        record.setPoints(66);
        record.setRefundedAt(refundedAt);
        return record;
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
