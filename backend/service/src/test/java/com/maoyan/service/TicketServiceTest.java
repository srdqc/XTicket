package com.maoyan.service;

import com.maoyan.dao.mapper.ElectronicTicketMapper;
import com.maoyan.dao.mapper.OrderMapper;
import com.maoyan.dao.mapper.OrderSeatMapper;
import com.maoyan.domain.enums.OrderStatusEnum;
import com.maoyan.domain.enums.TicketStatusEnum;
import com.maoyan.domain.exception.BizException;
import com.maoyan.domain.model.po.ElectronicTicketPO;
import com.maoyan.domain.model.po.OrderPO;
import com.maoyan.domain.model.po.OrderSeatPO;
import com.maoyan.domain.model.vo.TicketVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TicketServiceTest {

    @Mock
    private OrderMapper orderMapper;
    @Mock
    private OrderSeatMapper orderSeatMapper;
    @Mock
    private ElectronicTicketMapper electronicTicketMapper;

    private final ConcurrentHashMap<Long, ElectronicTicketPO> ticketsBySeat = new ConcurrentHashMap<>();
    private final Set<String> ticketNumbers = ConcurrentHashMap.newKeySet();
    private final AtomicLong ticketIds = new AtomicLong();
    private TicketService ticketService;

    @BeforeEach
    void setUp() {
        ticketService = new TicketService(orderMapper, orderSeatMapper, electronicTicketMapper);
        configureTicketStore();
    }

    @Test
    void oneSeatIssuesOneTicketAndRepeatDoesNotIncreaseCount() {
        prepareOrder(OrderStatusEnum.PAID, 1);

        List<TicketVO> first = ticketService.issueTickets("ORDER-1");
        List<TicketVO> second = ticketService.issueTickets("ORDER-1");

        assertThat(first).hasSize(1);
        assertThat(second).hasSize(1);
        assertThat(ticketsBySeat).hasSize(1);
        assertThat(first.get(0).getStatus()).isEqualTo(TicketStatusEnum.ISSUED.getCode());
        assertThat(first.get(0).getTicketNo()).startsWith("ET").hasSize(34);
    }

    @Test
    void twoSeatsIssueTwoDifferentTickets() {
        prepareOrder(OrderStatusEnum.PAID, 2);

        List<TicketVO> tickets = ticketService.issueTickets("ORDER-1");

        assertThat(tickets).hasSize(2);
        assertThat(tickets).extracting(TicketVO::getTicketNo).doesNotHaveDuplicates();
        assertThat(ticketsBySeat.keySet()).containsExactlyInAnyOrder(101L, 102L);
    }

    @Test
    void concurrentIssueConvergesToOneTicketPerOrderSeat() {
        prepareOrder(OrderStatusEnum.PAID, 2);

        List<CompletableFuture<List<TicketVO>>> calls = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            calls.add(CompletableFuture.supplyAsync(() -> ticketService.issueTickets("ORDER-1")));
        }
        CompletableFuture.allOf(calls.toArray(CompletableFuture[]::new)).join();

        assertThat(ticketsBySeat).hasSize(2);
        assertThat(ticketNumbers).hasSize(2);
        assertThat(calls).allSatisfy(call -> assertThat(call.join()).hasSize(2));
    }

    @Test
    void pendingOrderCannotIssueTickets() {
        prepareOrder(OrderStatusEnum.PENDING, 1);

        assertThatThrownBy(() -> ticketService.issueTickets("ORDER-1"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("仅已支付订单");
        verify(electronicTicketMapper, never()).insert(any());
    }

    @Test
    void cancelledOrderCannotIssueTickets() {
        prepareOrder(OrderStatusEnum.CANCELLED, 1);

        assertThatThrownBy(() -> ticketService.issueTickets("ORDER-1"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("仅已支付订单");
        verify(electronicTicketMapper, never()).insert(any());
    }

    @Test
    void ticketLookupAlwaysIncludesAuthenticatedUser() {
        TicketVO own = new TicketVO();
        own.setTicketNo("ET-OWN");
        when(electronicTicketMapper.selectViewByTicketNoAndUserId("ET-OWN", 1001L)).thenReturn(own);

        assertThat(ticketService.getUserTicket(1001L, "ET-OWN")).isSameAs(own);
        assertThatThrownBy(() -> ticketService.getUserTicket(2002L, "ET-OWN"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("电子票不存在");
        verify(electronicTicketMapper).selectViewByTicketNoAndUserId("ET-OWN", 1001L);
        verify(electronicTicketMapper).selectViewByTicketNoAndUserId("ET-OWN", 2002L);
    }

    @Test
    void ticketOwnerSeesUsedStatusAfterCheckIn() {
        TicketVO used = new TicketVO();
        used.setTicketNo("ET-USED");
        used.setStatus(TicketStatusEnum.USED.getCode());
        used.setUsedAt("2026-09-15T10:30:00");
        when(electronicTicketMapper.selectViewByTicketNoAndUserId("ET-USED", 1001L)).thenReturn(used);

        TicketVO result = ticketService.getUserTicket(1001L, "ET-USED");

        assertThat(result.getStatusDesc()).isEqualTo("已核销");
        assertThat(result.getUsedAt()).isEqualTo("2026-09-15T10:30:00");
    }

    @Test
    void schemasDeclareBothTicketUniquenessConstraints() throws Exception {
        String mysql = Files.readString(Path.of("..", "..", "docker", "mysql", "init", "00-init.sql"));
        String h2 = Files.readString(Path.of("..", "provider", "src", "main", "resources", "schema.sql"));

        assertThat(mysql).contains("UNIQUE INDEX uq_electronic_ticket_no (ticket_no)")
                .contains("UNIQUE INDEX uq_electronic_ticket_order_seat (order_seat_id)");
        assertThat(h2).contains("uq_electronic_ticket_no ON electronic_ticket(ticket_no)")
                .contains("uq_electronic_ticket_order_seat ON electronic_ticket(order_seat_id)");
    }

    private void prepareOrder(OrderStatusEnum status, int seatCount) {
        OrderPO order = new OrderPO();
        order.setId(10L);
        order.setOrderNo("ORDER-1");
        order.setUserId(1001L);
        order.setScheduleId(40L);
        order.setSeatCount(seatCount);
        order.setStatus(status.getCode());
        when(orderMapper.selectByOrderNoForUpdate("ORDER-1")).thenReturn(order);

        List<OrderSeatPO> seats = new ArrayList<>();
        for (int i = 1; i <= seatCount; i++) {
            OrderSeatPO seat = new OrderSeatPO();
            seat.setId(100L + i);
            seat.setOrderId(10L);
            seat.setOrderNo("ORDER-1");
            seat.setScheduleId(40L);
            seat.setRowNum(1);
            seat.setColNum(i);
            seat.setSeatLabel("1排" + i + "座");
            seats.add(seat);
        }
        if (status == OrderStatusEnum.PAID) {
            when(orderSeatMapper.selectByOrderNo("ORDER-1")).thenReturn(seats);
        }
    }

    private void configureTicketStore() {
        lenient().when(electronicTicketMapper.selectByOrderNo(anyString()))
                .thenAnswer(invocation -> ticketsBySeat.values().stream()
                        .filter(ticket -> invocation.getArgument(0).equals(ticket.getOrderNo()))
                        .sorted((left, right) -> Long.compare(left.getOrderSeatId(), right.getOrderSeatId()))
                        .toList());
        lenient().when(electronicTicketMapper.selectByOrderSeatId(anyLong()))
                .thenAnswer(invocation -> ticketsBySeat.get(invocation.getArgument(0, Long.class)));
        lenient().when(electronicTicketMapper.insert(any(ElectronicTicketPO.class))).thenAnswer(invocation -> {
            ElectronicTicketPO ticket = invocation.getArgument(0);
            if (!ticketNumbers.add(ticket.getTicketNo())) {
                throw new DuplicateKeyException("ticket_no duplicate");
            }
            ElectronicTicketPO previous = ticketsBySeat.putIfAbsent(ticket.getOrderSeatId(), ticket);
            if (previous != null) {
                ticketNumbers.remove(ticket.getTicketNo());
                throw new DuplicateKeyException("order_seat_id duplicate");
            }
            ticket.setId(ticketIds.incrementAndGet());
            return 1;
        });
        lenient().when(electronicTicketMapper.selectViewsByUserIdAndOrderNo(eq(1001L), anyString()))
                .thenAnswer(invocation -> ticketsBySeat.values().stream()
                        .filter(ticket -> invocation.getArgument(1).equals(ticket.getOrderNo()))
                        .sorted((left, right) -> Long.compare(left.getOrderSeatId(), right.getOrderSeatId()))
                        .map(this::toView)
                        .toList());
    }

    private TicketVO toView(ElectronicTicketPO ticket) {
        TicketVO view = new TicketVO();
        view.setTicketNo(ticket.getTicketNo());
        view.setOrderNo(ticket.getOrderNo());
        view.setSessionId(ticket.getSessionId());
        view.setStatus(ticket.getStatus());
        view.setIssuedAt(LocalDateTime.now().toString());
        return view;
    }
}
