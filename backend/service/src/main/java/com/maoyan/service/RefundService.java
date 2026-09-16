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
import com.maoyan.domain.enums.ResponseCodeEnum;
import com.maoyan.domain.enums.TicketStatusEnum;
import com.maoyan.domain.exception.BizException;
import com.maoyan.domain.model.po.ActivitySessionPO;
import com.maoyan.domain.model.po.ElectronicTicketPO;
import com.maoyan.domain.model.po.OrderPO;
import com.maoyan.domain.model.po.OrderSeatPO;
import com.maoyan.domain.model.po.PaymentRecordPO;
import com.maoyan.domain.model.po.RefundRecordPO;
import com.maoyan.domain.model.vo.RefundResult;
import com.maoyan.domain.model.event.OrderEvent;
import com.maoyan.service.event.OrderEventOutboxService;
import com.maoyan.service.infrastructure.StockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class RefundService {

    private static final String REFUND_STATUS_SUCCESS = "SUCCESS";
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final OrderMapper orderMapper;
    private final PaymentRecordMapper paymentRecordMapper;
    private final RefundRecordMapper refundRecordMapper;
    private final ElectronicTicketMapper electronicTicketMapper;
    private final OrderSeatMapper orderSeatMapper;
    private final SeatLockMapper seatLockMapper;
    private final UserMapper userMapper;
    private final ActivitySessionMapper activitySessionMapper;
    private final StockService stockService;
    private final OrderEventOutboxService orderEventOutboxService;

    @Transactional(rollbackFor = Exception.class, timeout = 8, isolation = Isolation.READ_COMMITTED)
    public RefundResult refund(Long userId, String orderNo) {
        String normalizedOrderNo = normalizeOrderNo(orderNo);
        OrderPO order = orderMapper.selectByOrderNoAndUserIdForUpdate(normalizedOrderNo, userId);
        if (order == null) {
            throw new BizException(ResponseCodeEnum.NOT_FOUND.getCode(), "订单不存在");
        }
        if (order.getStatus() == OrderStatusEnum.REFUNDED.getCode()) {
            return existingRefundResult(order);
        }
        requirePaidOrder(order);

        List<ElectronicTicketPO> tickets = electronicTicketMapper.selectByOrderNoForUpdate(normalizedOrderNo);
        validateRefundableTickets(order, tickets);
        List<OrderSeatPO> orderSeats = orderSeatMapper.selectByOrderNo(normalizedOrderNo);
        validateActiveOrderSeats(order, orderSeats);

        PaymentRecordPO payment = paymentRecordMapper.selectSuccessfulByOrderNo(normalizedOrderNo);
        validatePayment(order, payment);

        LocalDateTime now = LocalDateTime.now();
        int refundedPoints = payment.getAmount().setScale(0, RoundingMode.UP).intValue();
        requireAffected(electronicTicketMapper.invalidateIssuedByOrderNo(
                normalizedOrderNo, now, TicketStatusEnum.ISSUED.getCode(),
                TicketStatusEnum.INVALIDATED.getCode()), tickets.size(), "电子票作废不完整");
        requireAffected(orderMapper.markOrderRefunded(normalizedOrderNo, userId, now), 1, "订单退款状态更新失败");
        requireAffected(userMapper.addPoints(userId, refundedPoints), 1, "退款积分返还失败");

        RefundRecordPO refundRecord = buildRefundRecord(order, payment, refundedPoints, now);
        refundRecordMapper.insert(refundRecord);
        requireAffected(orderSeatMapper.releaseActiveSalesByOrderNo(normalizedOrderNo),
                order.getSeatCount(), "有效售座释放不完整");
        requireAffected(seatLockMapper.releasePurchasedOrderLocks(normalizedOrderNo),
                order.getSeatCount(), "已购买座位锁释放不完整");
        requireAffected(activitySessionMapper.rollbackStock(order.getScheduleId(), order.getSeatCount()),
                1, "数据库库存恢复失败");

        order.setStatus(OrderStatusEnum.REFUNDED.getCode());
        order.setRefundTime(now);
        orderEventOutboxService.append(OrderEvent.Type.REFUNDED, order);
        registerRedisRestoreAfterCommit(order);
        log.info("[Refund] Order refunded: orderNo={}, amount={}, points={}, tickets={}",
                normalizedOrderNo, payment.getAmount(), refundedPoints, tickets.size());
        return toResult(order, refundRecord, tickets.size());
    }

    private void requirePaidOrder(OrderPO order) {
        if (order.getStatus() == OrderStatusEnum.PENDING.getCode()) {
            throw new BizException(ResponseCodeEnum.CONFLICT.getCode(), "待支付订单不能退款，请取消订单");
        }
        if (order.getStatus() == OrderStatusEnum.CANCELLED.getCode()) {
            throw new BizException(ResponseCodeEnum.CONFLICT.getCode(), "已取消订单不能退款");
        }
        if (order.getStatus() != OrderStatusEnum.PAID.getCode()) {
            throw new BizException(ResponseCodeEnum.CONFLICT.getCode(), "订单状态不允许退款");
        }
    }

    private void validateRefundableTickets(OrderPO order, List<ElectronicTicketPO> tickets) {
        if (tickets == null || tickets.isEmpty() || tickets.size() != order.getSeatCount()) {
            throw new BizException(ResponseCodeEnum.CONFLICT.getCode(), "订单电子票数量不完整，不能退款");
        }
        if (tickets.stream().anyMatch(ticket -> ticket.getStatus() == TicketStatusEnum.USED.getCode())) {
            throw new BizException(ResponseCodeEnum.CONFLICT.getCode(), "订单包含已核销电子票，不能退款");
        }
        if (tickets.stream().anyMatch(ticket -> ticket.getStatus() != TicketStatusEnum.ISSUED.getCode())) {
            throw new BizException(ResponseCodeEnum.CONFLICT.getCode(), "订单电子票状态异常，不能退款");
        }
    }

    private void validateActiveOrderSeats(OrderPO order, List<OrderSeatPO> orderSeats) {
        long activeSeats = orderSeats == null ? 0 : orderSeats.stream()
                .filter(seat -> Integer.valueOf(1).equals(seat.getActiveSaleMarker()))
                .count();
        if (activeSeats != order.getSeatCount()) {
            throw new BizException(ResponseCodeEnum.CONFLICT.getCode(), "订单有效售座数量异常，不能退款");
        }
    }

    private void validatePayment(OrderPO order, PaymentRecordPO payment) {
        if (payment == null || !order.getUserId().equals(payment.getUserId())
                || payment.getAmount() == null || order.getTotalPrice() == null
                || payment.getAmount().compareTo(order.getTotalPrice()) != 0) {
            throw new BizException(ResponseCodeEnum.CONFLICT.getCode(), "订单支付事实不完整，不能退款");
        }
    }

    private RefundRecordPO buildRefundRecord(OrderPO order, PaymentRecordPO payment,
                                              int refundedPoints, LocalDateTime now) {
        RefundRecordPO record = new RefundRecordPO();
        record.setRefundNo("RF" + UUID.randomUUID().toString().replace("-", ""));
        record.setOrderNo(order.getOrderNo());
        record.setPaymentNo(payment.getPaymentNo());
        record.setUserId(order.getUserId());
        record.setAmount(payment.getAmount());
        record.setPoints(refundedPoints);
        record.setStatus(REFUND_STATUS_SUCCESS);
        record.setRefundedAt(now);
        record.setCreateTime(now);
        record.setUpdateTime(now);
        return record;
    }

    private RefundResult existingRefundResult(OrderPO order) {
        RefundRecordPO record = refundRecordMapper.selectByOrderNo(order.getOrderNo());
        if (record == null) {
            throw new BizException(ResponseCodeEnum.INTERNAL_ERROR.getCode(), "退款订单缺少退款记录");
        }
        int invalidatedCount = (int) electronicTicketMapper.selectByOrderNo(order.getOrderNo()).stream()
                .filter(ticket -> ticket.getStatus() == TicketStatusEnum.INVALIDATED.getCode())
                .count();
        return toResult(order, record, invalidatedCount);
    }

    private RefundResult toResult(OrderPO order, RefundRecordPO record, int invalidatedTicketCount) {
        RefundResult result = new RefundResult();
        result.setOrderNo(order.getOrderNo());
        result.setStatus(OrderStatusEnum.REFUNDED.getCode());
        result.setRefundedAmount(record.getAmount());
        result.setRefundedPoints(record.getPoints());
        LocalDateTime refundTime = order.getRefundTime() != null ? order.getRefundTime() : record.getRefundedAt();
        result.setRefundTime(refundTime == null ? null : refundTime.format(FMT));
        result.setInvalidatedTicketCount(invalidatedTicketCount);
        return result;
    }

    private void registerRedisRestoreAfterCommit(OrderPO order) {
        Runnable restore = () -> {
            stockService.rollback(order.getScheduleId(), order.getSeatCount());
            try {
                ActivitySessionPO session = activitySessionMapper.selectById(order.getScheduleId());
                if (session != null) {
                    stockService.initScheduleDetail(session);
                }
            } catch (Exception e) {
                log.warn("[Refund] Failed to refresh session cache: sessionId={}", order.getScheduleId(), e);
            }
        };
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            restore.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                restore.run();
            }
        });
    }

    private void requireAffected(int actual, int expected, String message) {
        if (actual != expected) {
            throw new BizException(ResponseCodeEnum.CONFLICT.getCode(), message);
        }
    }

    private String normalizeOrderNo(String orderNo) {
        if (orderNo == null || orderNo.isBlank()) {
            throw new BizException(ResponseCodeEnum.BAD_REQUEST.getCode(), "订单号不能为空");
        }
        return orderNo.trim();
    }
}
