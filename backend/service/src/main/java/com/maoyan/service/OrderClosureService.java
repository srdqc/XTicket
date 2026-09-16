package com.maoyan.service;

import com.maoyan.dao.mapper.OrderMapper;
import com.maoyan.dao.mapper.ActivitySessionMapper;
import com.maoyan.dao.mapper.SeatLockMapper;
import com.maoyan.domain.enums.OrderStatusEnum;
import com.maoyan.domain.model.event.OrderEvent;
import com.maoyan.domain.model.po.OrderPO;
import com.maoyan.domain.model.po.ActivitySessionPO;
import com.maoyan.service.infrastructure.StockService;
import com.maoyan.service.event.OrderEventOutboxService;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderClosureService {

    private final OrderMapper orderMapper;
    private final ActivitySessionMapper activitySessionMapper;
    private final SeatLockMapper seatLockMapper;
    private final StockService stockService;
    private final PlatformTransactionManager transactionManager;
    private final OrderEventOutboxService orderEventOutboxService;

    public CloseResult closeExpiredOrder(String orderNo, String source) {
        LocalDateTime now = LocalDateTime.now();
        CloseResult result = closeInNewTransaction(orderNo, source, now, true);
        afterDatabaseCloseCommitted(result, source);
        return result;
    }

    public CloseResult closePendingOrder(String orderNo, String source) {
        LocalDateTime now = LocalDateTime.now();
        CloseResult result = closeInNewTransaction(orderNo, source, now, false);
        afterDatabaseCloseCommitted(result, source);
        return result;
    }

    private CloseResult closeInNewTransaction(String orderNo, String source, LocalDateTime now, boolean onlyExpired) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setTimeout(8);
        return template.execute(status -> {
            OrderPO order = orderMapper.selectByOrderNo(orderNo);
            if (order == null) {
                log.info("[OrderClosure] Order not found: orderNo={}, source={}", orderNo, source);
                return CloseResult.notFound(orderNo);
            }

            int closed = onlyExpired
                    ? orderMapper.closeExpiredPendingOrder(orderNo, now)
                    : orderMapper.closePendingOrder(orderNo, now);
            if (closed == 0) {
                OrderPO latest = orderMapper.selectByOrderNo(orderNo);
                log.info("[OrderClosure] Close skipped: orderNo={}, source={}, onlyExpired={}, status={}, expireTime={}",
                        orderNo, source, onlyExpired, latest != null ? latest.getStatus() : null,
                        latest != null ? latest.getExpireTime() : null);
                return CloseResult.skipped(latest != null ? latest : order);
            }

            int dbStockRows = activitySessionMapper.rollbackStock(order.getScheduleId(), order.getSeatCount());
            int releasedLocks = seatLockMapper.releaseOrderLocks(orderNo);
            order.setStatus(OrderStatusEnum.CANCELLED.getCode());
            order.setCancelTime(now);
            orderEventOutboxService.append(OrderEvent.Type.CANCELLED, order);
            log.info("[OrderClosure] DB closed: orderNo={}, source={}, casAffectedRows={}, seatCount={}, dbStockRows={}, releasedLocks={}",
                    orderNo, source, closed, order.getSeatCount(), dbStockRows, releasedLocks);
            return CloseResult.closed(order, dbStockRows, releasedLocks);
        });
    }

    private void afterDatabaseCloseCommitted(CloseResult result, String source) {
        if (result == null || !result.isClosed()) {
            return;
        }
        OrderPO order = result.getOrder();
        stockService.rollback(order.getScheduleId(), order.getSeatCount());
        refreshScheduleDetailCache(order.getScheduleId());
        log.info("[OrderClosure] Closed committed: orderNo={}, source={}, seatCount={}, dbStockRows={}, releasedLocks={}",
                order.getOrderNo(), source, order.getSeatCount(), result.getDbStockRows(), result.getReleasedLocks());
    }

    private void refreshScheduleDetailCache(Long scheduleId) {
        try {
            ActivitySessionPO schedule = activitySessionMapper.selectById(scheduleId);
            if (schedule != null) {
                stockService.initScheduleDetail(schedule);
            }
        } catch (Exception e) {
            log.warn("[OrderClosure] Failed to refresh schedule cache: scheduleId={}", scheduleId, e);
        }
    }

    @Getter
    public static class CloseResult {
        private final String orderNo;
        private final boolean closed;
        private final boolean found;
        private final OrderPO order;
        private final int dbStockRows;
        private final int releasedLocks;

        private CloseResult(String orderNo, boolean closed, boolean found, OrderPO order,
                            int dbStockRows, int releasedLocks) {
            this.orderNo = orderNo;
            this.closed = closed;
            this.found = found;
            this.order = order;
            this.dbStockRows = dbStockRows;
            this.releasedLocks = releasedLocks;
        }

        static CloseResult closed(OrderPO order, int dbStockRows, int releasedLocks) {
            return new CloseResult(order.getOrderNo(), true, true, order, dbStockRows, releasedLocks);
        }

        static CloseResult skipped(OrderPO order) {
            return new CloseResult(order.getOrderNo(), false, true, order, 0, 0);
        }

        static CloseResult notFound(String orderNo) {
            return new CloseResult(orderNo, false, false, null, 0, 0);
        }

        public Integer getCurrentStatus() {
            return order != null ? order.getStatus() : null;
        }

        public boolean isCancelled() {
            return getCurrentStatus() != null && getCurrentStatus() == OrderStatusEnum.CANCELLED.getCode();
        }

        public boolean isPaid() {
            return getCurrentStatus() != null && getCurrentStatus() == OrderStatusEnum.PAID.getCode();
        }
    }
}
