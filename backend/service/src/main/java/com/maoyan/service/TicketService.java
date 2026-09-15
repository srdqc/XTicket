package com.maoyan.service;

import com.maoyan.dao.mapper.ElectronicTicketMapper;
import com.maoyan.dao.mapper.OrderMapper;
import com.maoyan.dao.mapper.OrderSeatMapper;
import com.maoyan.domain.enums.OrderStatusEnum;
import com.maoyan.domain.enums.ResponseCodeEnum;
import com.maoyan.domain.enums.TicketStatusEnum;
import com.maoyan.domain.exception.BizException;
import com.maoyan.domain.model.po.ElectronicTicketPO;
import com.maoyan.domain.model.po.OrderPO;
import com.maoyan.domain.model.po.OrderSeatPO;
import com.maoyan.domain.model.vo.TicketVO;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TicketService {

    private static final int TICKET_NO_MAX_ATTEMPTS = 3;

    private final OrderMapper orderMapper;
    private final OrderSeatMapper orderSeatMapper;
    private final ElectronicTicketMapper electronicTicketMapper;

    @Transactional(rollbackFor = Exception.class)
    public List<TicketVO> issueTickets(String orderNo) {
        if (orderNo == null || orderNo.isBlank()) {
            throw new BizException(ResponseCodeEnum.BAD_REQUEST.getCode(), "订单号不能为空");
        }

        OrderPO order = orderMapper.selectByOrderNoForUpdate(orderNo);
        if (order == null) {
            throw new BizException(ResponseCodeEnum.NOT_FOUND.getCode(), "订单不存在");
        }
        if (order.getStatus() == null || order.getStatus() != OrderStatusEnum.PAID.getCode()) {
            throw new BizException(ResponseCodeEnum.CONFLICT.getCode(), "仅已支付订单可以签发电子票");
        }

        List<OrderSeatPO> orderSeats = orderSeatMapper.selectByOrderNo(orderNo);
        validateOrderSeats(order, orderSeats);

        Map<Long, ElectronicTicketPO> existingByOrderSeatId = new HashMap<>();
        for (ElectronicTicketPO ticket : electronicTicketMapper.selectByOrderNo(orderNo)) {
            existingByOrderSeatId.put(ticket.getOrderSeatId(), ticket);
        }

        LocalDateTime issuedAt = LocalDateTime.now();
        for (OrderSeatPO orderSeat : orderSeats) {
            if (!existingByOrderSeatId.containsKey(orderSeat.getId())) {
                insertMissingTicket(order, orderSeat, issuedAt);
            }
        }

        List<TicketVO> tickets = electronicTicketMapper
                .selectViewsByUserIdAndOrderNo(order.getUserId(), orderNo);
        if (tickets.size() != orderSeats.size()) {
            throw new BizException(ResponseCodeEnum.INTERNAL_ERROR.getCode(), "电子票签发结果不完整");
        }
        enrichStatusDescriptions(tickets);
        return tickets;
    }

    public List<TicketVO> getUserTickets(Long userId, String orderNo) {
        List<TicketVO> tickets = electronicTicketMapper
                .selectViewsByUserIdAndOrderNo(userId, normalize(orderNo));
        enrichStatusDescriptions(tickets);
        return tickets;
    }

    public TicketVO getUserTicket(Long userId, String ticketNo) {
        TicketVO ticket = electronicTicketMapper.selectViewByTicketNoAndUserId(ticketNo, userId);
        if (ticket == null) {
            throw new BizException(ResponseCodeEnum.NOT_FOUND.getCode(), "电子票不存在");
        }
        enrichStatusDescription(ticket);
        return ticket;
    }

    private void validateOrderSeats(OrderPO order, List<OrderSeatPO> orderSeats) {
        if (orderSeats == null || orderSeats.isEmpty()) {
            throw new BizException(ResponseCodeEnum.CONFLICT.getCode(), "已支付订单缺少确认座位，无法签发电子票");
        }
        if (order.getSeatCount() == null || orderSeats.size() != order.getSeatCount()) {
            throw new BizException(ResponseCodeEnum.CONFLICT.getCode(), "订单座位数量不完整，无法签发电子票");
        }
        boolean invalidOwnership = orderSeats.stream().anyMatch(seat ->
                !order.getId().equals(seat.getOrderId())
                        || !order.getOrderNo().equals(seat.getOrderNo())
                        || !order.getScheduleId().equals(seat.getScheduleId()));
        if (invalidOwnership) {
            throw new BizException(ResponseCodeEnum.CONFLICT.getCode(), "订单座位归属不一致，无法签发电子票");
        }
    }

    private void insertMissingTicket(OrderPO order, OrderSeatPO orderSeat, LocalDateTime issuedAt) {
        for (int attempt = 1; attempt <= TICKET_NO_MAX_ATTEMPTS; attempt++) {
            ElectronicTicketPO existing = electronicTicketMapper.selectByOrderSeatId(orderSeat.getId());
            if (existing != null) {
                return;
            }

            ElectronicTicketPO ticket = new ElectronicTicketPO();
            ticket.setTicketNo(generateTicketNo());
            ticket.setOrderSeatId(orderSeat.getId());
            ticket.setOrderNo(order.getOrderNo());
            ticket.setUserId(order.getUserId());
            ticket.setSessionId(orderSeat.getScheduleId());
            ticket.setStatus(TicketStatusEnum.ISSUED.getCode());
            ticket.setIssuedAt(issuedAt);

            try {
                electronicTicketMapper.insert(ticket);
                return;
            } catch (DuplicateKeyException e) {
                if (electronicTicketMapper.selectByOrderSeatId(orderSeat.getId()) != null) {
                    return;
                }
                if (attempt == TICKET_NO_MAX_ATTEMPTS) {
                    throw e;
                }
            }
        }
    }

    private String generateTicketNo() {
        return "ET" + UUID.randomUUID().toString().replace("-", "");
    }

    private void enrichStatusDescriptions(List<TicketVO> tickets) {
        tickets.forEach(this::enrichStatusDescription);
    }

    private void enrichStatusDescription(TicketVO ticket) {
        if (ticket.getStatus() != null) {
            ticket.setStatusDesc(TicketStatusEnum.of(ticket.getStatus()).getDesc());
        }
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
