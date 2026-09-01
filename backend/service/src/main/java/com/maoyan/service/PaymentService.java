package com.maoyan.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.maoyan.common.constants.MQConstants;
import com.maoyan.dao.mapper.OrderMapper;
import com.maoyan.dao.mapper.OrderSeatMapper;
import com.maoyan.dao.mapper.SeatLockMapper;
import com.maoyan.dao.mapper.UserMapper;
import com.maoyan.domain.enums.OrderStatusEnum;
import com.maoyan.domain.enums.ResponseCodeEnum;
import com.maoyan.domain.exception.BizException;
import com.maoyan.domain.model.event.OrderEvent;
import com.maoyan.domain.model.po.OrderPO;
import com.maoyan.domain.model.po.OrderSeatPO;
import com.maoyan.domain.model.po.SeatLockPO;
import com.maoyan.domain.model.po.UserPO;
import com.maoyan.domain.model.vo.OrderVO;
import com.maoyan.service.infrastructure.DistributedLockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.RoundingMode;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final OrderMapper orderMapper;
    private final SeatLockMapper seatLockMapper;
    private final OrderSeatMapper orderSeatMapper;
    private final UserMapper userMapper;
    private final DistributedLockService lockService;
    private final OrderClosureService orderClosureService;

    @Autowired(required = false)
    private RocketMQTemplate rocketMQTemplate;

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Transactional(rollbackFor = Exception.class, timeout = 8)
    public OrderVO payOrder(Long userId, String orderNo) {
        OrderVO result = lockService.executeWithBoundedLock("pay:" + orderNo, 3, 12,
                () -> payOrderInLock(userId, orderNo));
        if (result == null) {
            throw new BizException(ResponseCodeEnum.ORDER_CREATE_FAILED.getCode(), "支付处理中，请稍后重试");
        }
        return result;
    }

    private OrderVO payOrderInLock(Long userId, String orderNo) {
        OrderPO order = selectUserOrder(userId, orderNo);
        if (order == null) {
            throw new BizException(ResponseCodeEnum.NOT_FOUND.getCode(), "订单不存在");
        }
        if (order.getStatus() != OrderStatusEnum.PENDING.getCode()) {
            throw nonPayableStatusException(order.getStatus());
        }

        LocalDateTime now = LocalDateTime.now();
        if (order.getExpireTime() != null && now.isAfter(order.getExpireTime())) {
            OrderClosureService.CloseResult closeResult =
                    orderClosureService.closeExpiredOrder(orderNo, "PAYMENT_LAZY_EXPIRE");
            log.info("[Payment] Expired payment rejected: orderNo={}, closeSource=PAYMENT_LAZY_EXPIRE, closed={}, statusAfter={}",
                    orderNo, closeResult.isClosed(), closeResult.getCurrentStatus());
            if (closeResult.isClosed() || closeResult.isCancelled()) {
                throw new BizException(ResponseCodeEnum.CONFLICT.getCode(), "订单已过期并自动取消，无法支付");
            }
            if (closeResult.isPaid()) {
                throw new BizException(ResponseCodeEnum.CONFLICT.getCode(), "订单已支付，不能重复支付");
            }
            throw new BizException(ResponseCodeEnum.SEAT_LOCK_EXPIRED);
        }

        List<SeatLockPO> locks = seatLockMapper.selectLocksByOrderNo(orderNo);
        if (locks.size() != order.getSeatCount() || locks.stream().anyMatch(l -> l.getStatus() != 1)) {
            throw new BizException(ResponseCodeEnum.SEAT_LOCK_EXPIRED);
        }

        int pointsCost = order.getTotalPrice().setScale(0, RoundingMode.UP).intValue();
        UserPO user = userMapper.selectById(userId);
        if (user == null || user.getPoints() == null || user.getPoints() < pointsCost) {
            throw new BizException(ResponseCodeEnum.BAD_REQUEST.getCode(),
                    "积分不足，需要" + pointsCost + "积分，当前" + (user != null ? user.getPoints() : 0) + "积分");
        }
        int pointAffected = userMapper.deductPoints(userId, pointsCost);
        if (pointAffected == 0) {
            throw new BizException(ResponseCodeEnum.BAD_REQUEST.getCode(), "积分扣减失败，请重试");
        }

        int paid = orderMapper.markOrderPaid(orderNo, now);
        if (paid == 0) {
            throw new BizException(ResponseCodeEnum.BAD_REQUEST.getCode(), "订单状态已变化，请刷新后重试");
        }

        confirmOrderSeats(order, locks, now);
        seatLockMapper.markAsPurchased(orderNo, now);

        order.setStatus(OrderStatusEnum.PAID.getCode());
        order.setPayTime(now);
        sendPaidEvent(order);

        log.info("[Payment] Order paid: orderNo={}, total={}", orderNo, order.getTotalPrice());
        OrderVO vo = toVO(order);
        UserPO updatedUser = userMapper.selectById(userId);
        vo.setRemainingPoints(updatedUser != null ? updatedUser.getPoints() : 0);
        return vo;
    }

    private void confirmOrderSeats(OrderPO order, List<SeatLockPO> locks, LocalDateTime now) {
        try {
            for (SeatLockPO lock : locks) {
                OrderSeatPO seat = new OrderSeatPO();
                seat.setOrderId(order.getId());
                seat.setOrderNo(order.getOrderNo());
                seat.setScheduleId(order.getScheduleId());
                seat.setRowNum(lock.getRowNum());
                seat.setColNum(lock.getColNum());
                seat.setSeatLabel(lock.getRowNum() + "排" + lock.getColNum() + "座");
                seat.setCreateTime(now);
                orderSeatMapper.insert(seat);
            }
        } catch (DuplicateKeyException e) {
            SQLException sqlException = findSqlException(e);
            log.warn("[Payment] Seat confirmation conflict: orderNo={}, scheduleId={}, conflictType=ORDER_SEAT_UNIQUE, springException={}, sqlErrorCode={}, sqlState={}, seatCount={}",
                    order.getOrderNo(), order.getScheduleId(), e.getClass().getSimpleName(),
                    sqlException != null ? sqlException.getErrorCode() : null,
                    sqlException != null ? sqlException.getSQLState() : null,
                    locks.size());
            throw new BizException(ResponseCodeEnum.CONFLICT.getCode(), "所选座位已被其他订单确认，支付失败，请重新选座");
        }
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

    private BizException nonPayableStatusException(Integer status) {
        if (status != null && status == OrderStatusEnum.CANCELLED.getCode()) {
            return new BizException(ResponseCodeEnum.CONFLICT.getCode(), "订单已取消，无法支付");
        }
        if (status != null && status == OrderStatusEnum.PAID.getCode()) {
            return new BizException(ResponseCodeEnum.CONFLICT.getCode(), "订单已支付，不能重复支付");
        }
        return new BizException(ResponseCodeEnum.BAD_REQUEST.getCode(), "订单状态不允许支付");
    }

    public OrderVO getOrderDetail(Long userId, String orderNo) {
        OrderPO order = selectUserOrder(userId, orderNo);
        if (order == null) {
            throw new BizException(ResponseCodeEnum.NOT_FOUND.getCode(), "订单不存在");
        }
        return toVO(order);
    }

    private OrderPO selectUserOrder(Long userId, String orderNo) {
        LambdaQueryWrapper<OrderPO> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(OrderPO::getOrderNo, orderNo)
                .eq(OrderPO::getUserId, userId);
        return orderMapper.selectOne(wrapper);
    }

    private void sendPaidEvent(OrderPO order) {
        if (rocketMQTemplate == null) {
            return;
        }
        try {
            OrderEvent event = new OrderEvent(
                    OrderEvent.Type.PAID, order.getOrderNo(), order.getUserId(),
                    order.getScheduleId(), null, null,
                    order.getSeatCount(), order.getTotalPrice(), System.currentTimeMillis()
            );
            rocketMQTemplate.syncSend(MQConstants.ORDER_TOPIC + ":" + MQConstants.TAG_ORDER_PAID, event, 1000);
        } catch (Exception e) {
            log.error("[Payment] MQ notify failed", e);
        }
    }

    private OrderVO toVO(OrderPO po) {
        OrderVO vo = new OrderVO();
        vo.setId(po.getId());
        vo.setOrderNo(po.getOrderNo());
        vo.setLockToken(po.getLockToken());
        vo.setMovieName(po.getMovieName());
        vo.setCinemaName(po.getCinemaName());
        vo.setHallName(po.getHallName());
        vo.setShowTime(po.getShowTime());
        vo.setSeatCount(po.getSeatCount());
        vo.setSeatsInfo(po.getSeatsInfo());
        vo.setUnitPrice(po.getUnitPrice());
        vo.setTotalPrice(po.getTotalPrice());
        vo.setStatus(po.getStatus());
        vo.setStatusDesc(OrderStatusEnum.of(po.getStatus()).getDesc());
        vo.setScheduleId(po.getScheduleId());
        if (po.getCreateTime() != null) {
            vo.setCreateTime(po.getCreateTime().format(FMT));
        }
        if (po.getPayTime() != null) {
            vo.setPayTime(po.getPayTime().format(FMT));
        }
        if (po.getExpireTime() != null) {
            vo.setExpireTime(po.getExpireTime().format(FMT));
        }
        return vo;
    }
}
