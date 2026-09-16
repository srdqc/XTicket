package com.maoyan.service;

import com.maoyan.dao.mapper.OrderMapper;
import com.maoyan.dao.mapper.OrderSeatMapper;
import com.maoyan.dao.mapper.ActivitySessionMapper;
import com.maoyan.dao.mapper.SeatLockMapper;
import com.maoyan.domain.model.po.OrderPO;
import com.maoyan.service.infrastructure.DistributedLockService;
import com.maoyan.service.infrastructure.StockService;
import com.maoyan.service.event.OrderEventOutboxService;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderExpiredScanTest {

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
    private PlatformTransactionManager transactionManager;
    @Mock
    private OrderClosureService orderClosureService;
    @Mock
    private OrderEventOutboxService orderEventOutboxService;

    @Test
    void expiredOrderScanSqlKeepsPendingExpiredDeletedAndLimitPredicates() throws Exception {
        Select select = OrderMapper.class
                .getMethod("selectExpiredPendingOrders", LocalDateTime.class, int.class)
                .getAnnotation(Select.class);

        String sql = String.join(" ", select.value())
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);

        assertThat(sql).contains("from ticket_order");
        assertThat(sql).contains("where status = 0");
        assertThat(sql).contains("expire_time <= #{now}");
        assertThat(sql).contains("deleted = 0");
        assertThat(sql).contains("limit #{limit}");
        assertThat(sql).doesNotContain("order by");
    }

    @Test
    void schedulerScansAtMostOneHundredExpiredPendingOrders() {
        OrderService orderService = new OrderService(orderMapper, activitySessionMapper, seatLockMapper, orderSeatMapper,
                stockService, lockService, transactionManager, orderClosureService, orderEventOutboxService);
        OrderPO order = new OrderPO();
        order.setOrderNo("MO_EXPIRED_001");
        when(orderMapper.selectExpiredPendingOrders(any(LocalDateTime.class), eq(100)))
                .thenReturn(List.of(order));

        orderService.cancelExpiredOrders();

        verify(orderMapper).selectExpiredPendingOrders(any(LocalDateTime.class), eq(100));
        verify(orderClosureService).closeExpiredOrder("MO_EXPIRED_001", "TIMEOUT_SCHEDULER");
    }

    @Test
    void schedulerDoesNothingWhenNoExpiredPendingOrdersFound() {
        OrderService orderService = new OrderService(orderMapper, activitySessionMapper, seatLockMapper, orderSeatMapper,
                stockService, lockService, transactionManager, orderClosureService, orderEventOutboxService);
        when(orderMapper.selectExpiredPendingOrders(any(LocalDateTime.class), eq(100))).thenReturn(List.of());

        orderService.cancelExpiredOrders();

        verify(orderMapper).selectExpiredPendingOrders(any(LocalDateTime.class), eq(100));
        verify(orderClosureService, never()).closeExpiredOrder(any(), any());
    }
}
