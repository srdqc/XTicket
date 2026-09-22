package com.maoyan.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.maoyan.common.constants.CacheConstants;
import com.maoyan.common.observability.TraceContext;
import com.maoyan.dao.mapper.OrderMapper;
import com.maoyan.dao.mapper.OrderSeatMapper;
import com.maoyan.dao.mapper.ActivitySessionMapper;
import com.maoyan.dao.mapper.SeatLockMapper;
import com.maoyan.domain.enums.OrderStatusEnum;
import com.maoyan.domain.enums.ResponseCodeEnum;
import com.maoyan.domain.exception.BizException;
import com.maoyan.domain.model.dto.CreateOrderDTO;
import com.maoyan.domain.model.dto.LockSeatsDTO;
import com.maoyan.domain.model.dto.OrderSnapshotSourceDTO;
import com.maoyan.domain.model.event.OrderEvent;
import com.maoyan.domain.model.po.OrderPO;
import com.maoyan.domain.model.po.ActivitySessionPO;
import com.maoyan.domain.model.po.SeatLockPO;
import com.maoyan.domain.model.vo.OrderVO;
import com.maoyan.service.infrastructure.DistributedLockService;
import com.maoyan.service.infrastructure.SeatLockClaimService;
import com.maoyan.service.infrastructure.SeatLockKeys;
import com.maoyan.service.infrastructure.StockService;
import com.maoyan.service.event.OrderEventOutboxService;
import com.maoyan.service.observability.BusinessMetrics;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderMapper orderMapper;
    private final ActivitySessionMapper activitySessionMapper;
    private final SeatLockMapper seatLockMapper;
    private final OrderSeatMapper orderSeatMapper;
    private final StockService stockService;
    private final DistributedLockService lockService;
    private final SeatLockClaimService seatLockClaimService;
    private final PlatformTransactionManager transactionManager;
    private final OrderClosureService orderClosureService;
    private final OrderEventOutboxService orderEventOutboxService;
    private final BusinessMetrics businessMetrics;

    private static final DateTimeFormatter ORDER_NO_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final DateTimeFormatter VO_TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public OrderVO createOrder(Long userId, CreateOrderDTO dto) {
        Timer.Sample sample = businessMetrics.startTimer();
        boolean success = false;
        try {
            List<SeatLockKeys.Seat> canonicalSeats = profileOrderCreateStage("request_validation", () -> {
                if (dto.getSeatCount() == null || dto.getSeatCount() <= 0) {
                    throw new BizException(ResponseCodeEnum.BAD_REQUEST.getCode(), "购票数量不合法");
                }
                normalizeRequestSeats(dto, dto.getSeatCount());
                return canonicalRequestSeats(dto);
            });

            Long scheduleId = dto.getScheduleId();
            long lockWaitStarted = System.nanoTime();
            AtomicBoolean lockEntered = new AtomicBoolean(false);
            OrderVO result = lockService.executeWithBoundedLocks(
                    SeatLockKeys.seatKeys(scheduleId, canonicalSeats),
                    SeatLockKeys.WAIT_SECONDS, SeatLockKeys.LEASE_SECONDS,
                    () -> {
                        lockEntered.set(true);
                        businessMetrics.recordOrderCreateStage("redisson_wait",
                                System.nanoTime() - lockWaitStarted);
                        return createOrderWithAtomicStockDeduct(userId, dto, canonicalSeats);
                    });
            if (result == null) {
                if (!lockEntered.get()) {
                    businessMetrics.recordOrderCreateStage("redisson_wait",
                            System.nanoTime() - lockWaitStarted);
                }
                throw new BizException(ResponseCodeEnum.ORDER_CREATE_FAILED.getCode(), "系统繁忙，请重试");
            }
            success = true;
            return result;
        } finally {
            businessMetrics.stopOrderCreate(sample, success);
        }
    }

    private OrderVO createOrderWithAtomicStockDeduct(Long userId, CreateOrderDTO dto,
                                                      List<SeatLockKeys.Seat> canonicalSeats) {
        String lockToken = normalize(dto.getLockToken());
        Set<String> requestedSeats = normalizeRequestSeats(dto, dto.getSeatCount());
        OrderPO existingOrder = profileOrderCreateStage("idempotency_check",
                () -> lockToken == null ? null : orderMapper.selectByLockToken(lockToken));
        if (existingOrder != null) {
            return handleConsumedLockToken(userId, dto, lockToken, requestedSeats, existingOrder);
        }

        AtomicBoolean redisDebitApplied = new AtomicBoolean(false);
        try {
            return executeProfiledTransaction(
                    userId, dto, canonicalSeats, requestedSeats, redisDebitApplied);
        } catch (DuplicateKeyException e) {
            compensateRedisStock(dto.getScheduleId(), dto.getSeatCount(), redisDebitApplied);
            if (lockToken == null) {
                throw new BizException(ResponseCodeEnum.CONFLICT.getCode(), "lockToken 已被消费，请重新查询订单");
            }
            OrderPO concurrentOrder = orderMapper.selectByLockToken(lockToken);
            if (concurrentOrder == null) {
                throw new BizException(ResponseCodeEnum.CONFLICT.getCode(), "lockToken 已被消费，请重新查询订单");
            }
            return handleConsumedLockToken(userId, dto, lockToken, requestedSeats, concurrentOrder);
        } catch (BizException e) {
            compensateRedisStock(dto.getScheduleId(), dto.getSeatCount(), redisDebitApplied);
            throw e;
        } catch (Exception e) {
            compensateRedisStock(dto.getScheduleId(), dto.getSeatCount(), redisDebitApplied);
            log.error("[Order] Create failed after Redis stock debit handling: scheduleId={}, seats={}",
                    dto.getScheduleId(), dto.getSeatCount(), e);
            throw new BizException(ResponseCodeEnum.ORDER_CREATE_FAILED);
        }
    }

    private OrderVO createOrderAttempt(Long userId, CreateOrderDTO dto,
                                       List<SeatLockKeys.Seat> canonicalSeats,
                                       Set<String> requestedSeats,
                                       AtomicBoolean redisDebitApplied) {
        Long scheduleId = dto.getScheduleId();
        int seatCount = dto.getSeatCount();
        LocalDateTime now = LocalDateTime.now();

        ActivitySessionPO schedule = profileOrderCreateStage("load_session",
                () -> activitySessionMapper.selectById(scheduleId));
        if (schedule == null || schedule.getDeleted() == 1 || schedule.getStatus() != 1) {
            throw new BizException(ResponseCodeEnum.NOT_FOUND.getCode(), "场次不存在或已停售");
        }

        String lockToken = normalize(dto.getLockToken());
        if (lockToken == null) {
            lockToken = profileOrderCreateStage("create_missing_lock",
                    () -> lockSeatsForOrder(userId, schedule, canonicalSeats, now));
        }

        String effectiveLockToken = lockToken;
        OrderPO existingOrder = profileOrderCreateStage("idempotency_check",
                () -> orderMapper.selectByLockToken(effectiveLockToken));
        if (existingOrder != null) {
            return handleConsumedLockToken(userId, dto, lockToken, requestedSeats, existingOrder);
        }

        List<SeatLockPO> lockedSeats = profileOrderCreateStage("verify_locks", () -> {
            List<SeatLockPO> locks = seatLockMapper.selectActiveLocksByTokenOnly(effectiveLockToken, now);
            validateUsableLockToken(userId, scheduleId, effectiveLockToken, requestedSeats, locks, seatCount);
            return locks;
        });

        OrderSnapshotSourceDTO snapshot = profileOrderCreateStage("load_snapshot",
                () -> loadOrderSnapshotSource(scheduleId));
        String seatsInfo = formatSeatsInfo(lockedSeats);

        long remaining = profileOrderCreateStage("redis_stock",
                () -> stockService.preDeduct(scheduleId, seatCount));
        if (remaining < 0) {
            throw new BizException(ResponseCodeEnum.STOCK_NOT_ENOUGH);
        }
        redisDebitApplied.set(true);

        int affected = profileOrderCreateStage("db_stock_update",
                () -> activitySessionMapper.deductStock(scheduleId, seatCount));
        if (affected == 0) {
            throw new BizException(ResponseCodeEnum.STOCK_NOT_ENOUGH);
        }

        String orderNo = generateOrderNo(userId);
        OrderPO order = buildPendingOrder(userId, snapshot, lockToken, orderNo, now, seatCount, seatsInfo);
        profileOrderCreateStage("order_insert", () -> orderMapper.insert(order));

        int bound = profileOrderCreateStage("bind_locks",
                () -> seatLockMapper.bindLocksToOrder(scheduleId, userId, effectiveLockToken, orderNo,
                        order.getExpireTime(), now));
        if (bound != seatCount) {
            throw new BizException(ResponseCodeEnum.CONFLICT.getCode(), "锁座绑定数量不匹配，请重新选座");
        }

        profileOrderCreateStage("refresh_cache", () -> refreshScheduleDetailCache(scheduleId));
        profileOrderCreateStage("outbox_insert",
                () -> orderEventOutboxService.append(OrderEvent.Type.CREATED, order));
        OrderVO result = profileOrderCreateStage("response_mapping", () -> toVO(order));
        businessMetrics.orderCreated();
        log.info("[Order] Created: orderNo={}, userId={}, scheduleId={}, seats={}, total={}",
                orderNo, userId, scheduleId, seatCount, order.getTotalPrice());
        return result;
    }

    private String lockSeatsForOrder(Long userId, ActivitySessionPO schedule,
                                     List<SeatLockKeys.Seat> seats, LocalDateTime now) {
        Long scheduleId = schedule.getId();
        Set<String> soldSeats = orderSeatMapper.selectPurchasedSeats(scheduleId).stream()
                .map(os -> os.getRowNum() + "," + os.getColNum())
                .collect(Collectors.toSet());
        for (SeatLockKeys.Seat seat : seats) {
            if (soldSeats.contains(seat.row() + "," + seat.col())) {
                throw new BizException(ResponseCodeEnum.SEAT_LOCKED);
            }
        }

        String lockToken = UUID.randomUUID().toString().replace("-", "");
        LocalDateTime lockUntil = now.plusMinutes(CacheConstants.SEAT_LOCK_MINUTES);
        for (SeatLockKeys.Seat seat : seats) {
            seatLockClaimService.claim(scheduleId, seat.row(), seat.col(), userId, lockToken,
                    lockUntil, now, seats.size());
        }
        return lockToken;
    }

    private OrderVO handleConsumedLockToken(Long userId, CreateOrderDTO dto, String lockToken,
                                            Set<String> requestedSeats, OrderPO existingOrder) {
        if (!existingOrder.getUserId().equals(userId)) {
            throw new BizException(ResponseCodeEnum.CONFLICT.getCode(), "lockToken 已被其他用户使用");
        }
        if (!existingOrder.getScheduleId().equals(dto.getScheduleId())) {
            throw new BizException(ResponseCodeEnum.CONFLICT.getCode(), "lockToken 对应场次与请求不一致");
        }
        if (!existingOrder.getSeatCount().equals(dto.getSeatCount())) {
            throw new BizException(ResponseCodeEnum.CONFLICT.getCode(), "lockToken 对应座位数量与请求不一致");
        }

        List<SeatLockPO> tokenLocks = seatLockMapper.selectLocksByToken(lockToken);
        if (!tokenLocks.isEmpty()) {
            Set<String> lockedSeats = normalizeLockedSeats(tokenLocks);
            if (!requestedSeats.equals(lockedSeats)) {
                throw new BizException(ResponseCodeEnum.CONFLICT.getCode(), "lockToken 对应座位与请求不一致");
            }
        }

        log.info("[Order] Idempotent create hit: lockToken={}, orderNo={}",
                maskLockToken(lockToken), existingOrder.getOrderNo());
        return toVO(existingOrder);
    }

    private void validateUsableLockToken(Long userId, Long scheduleId, String lockToken,
                                         Set<String> requestedSeats, List<SeatLockPO> lockedSeats,
                                         int seatCount) {
        if (lockedSeats == null || lockedSeats.isEmpty()) {
            throw new BizException(ResponseCodeEnum.SEAT_LOCK_EXPIRED.getCode(),
                    "lockToken 已失效或不存在，请重新选座");
        }
        if (lockedSeats.size() != seatCount) {
            throw new BizException(ResponseCodeEnum.CONFLICT.getCode(), "lockToken 对应座位数量与请求不一致");
        }

        for (SeatLockPO lock : lockedSeats) {
            if (!lock.getUserId().equals(userId)) {
                throw new BizException(ResponseCodeEnum.CONFLICT.getCode(), "lockToken 属于其他用户");
            }
            if (!lock.getScheduleId().equals(scheduleId)) {
                throw new BizException(ResponseCodeEnum.CONFLICT.getCode(), "lockToken 属于其他场次");
            }
            if (lock.getOrderNo() != null && !lock.getOrderNo().isBlank()) {
                throw new BizException(ResponseCodeEnum.CONFLICT.getCode(), "lockToken 已绑定其他订单");
            }
        }

        Set<String> locked = normalizeLockedSeats(lockedSeats);
        if (!requestedSeats.equals(locked)) {
            throw new BizException(ResponseCodeEnum.CONFLICT.getCode(), "lockToken 对应座位与请求不一致");
        }
    }

    private Set<String> normalizeRequestSeats(CreateOrderDTO dto, int seatCount) {
        List<LockSeatsDTO.SeatPos> seats = dto.getSeats();
        if (seats == null || seats.isEmpty()) {
            throw new BizException(ResponseCodeEnum.BAD_REQUEST.getCode(), "请选择座位");
        }
        Set<String> requestSeats = new LinkedHashSet<>();
        for (LockSeatsDTO.SeatPos seat : seats) {
            String seatKey = seat.getRow() + "," + seat.getCol();
            if (!requestSeats.add(seatKey)) {
                throw new BizException(ResponseCodeEnum.BAD_REQUEST.getCode(), "不能重复选择同一座位");
            }
        }
        if (requestSeats.size() != seatCount) {
            throw new BizException(ResponseCodeEnum.BAD_REQUEST.getCode(), "座位数量与购票数量不一致");
        }
        return requestSeats;
    }

    private List<SeatLockKeys.Seat> canonicalRequestSeats(CreateOrderDTO dto) {
        return SeatLockKeys.canonicalize(dto.getSeats().stream()
                .map(seat -> new SeatLockKeys.Seat(seat.getRow(), seat.getCol()))
                .toList());
    }

    private Set<String> normalizeLockedSeats(List<SeatLockPO> lockedSeats) {
        return lockedSeats.stream()
                .map(s -> s.getRowNum() + "," + s.getColNum())
                .collect(Collectors.toSet());
    }

    private OrderSnapshotSourceDTO loadOrderSnapshotSource(Long scheduleId) {
        OrderSnapshotSourceDTO snapshot = activitySessionMapper.selectOrderSnapshotSource(scheduleId);
        if (snapshot == null || snapshot.getStatus() == null || snapshot.getStatus() != 1) {
            throw new BizException(ResponseCodeEnum.NOT_FOUND.getCode(), "场次不存在或已停售");
        }
        if (isBlank(snapshot.getMovieName()) || isBlank(snapshot.getCinemaName()) ||
                isBlank(snapshot.getHallName()) || isBlank(snapshot.getShowDate()) ||
                isBlank(snapshot.getShowTime()) || snapshot.getUnitPrice() == null ||
                snapshot.getVersion() == null) {
            throw new BizException(ResponseCodeEnum.ORDER_CREATE_FAILED.getCode(),
                    "场次信息不完整，暂时无法创建订单");
        }
        return snapshot;
    }

    private String formatSeatsInfo(List<SeatLockPO> lockedSeats) {
        return lockedSeats.stream()
                .sorted((left, right) -> {
                    int rowCompare = Integer.compare(left.getRowNum(), right.getRowNum());
                    if (rowCompare != 0) {
                        return rowCompare;
                    }
                    return Integer.compare(left.getColNum(), right.getColNum());
                })
                .map(seat -> seat.getRowNum() + "排" + seat.getColNum() + "座")
                .collect(Collectors.joining(","));
    }

    private OrderPO buildPendingOrder(Long userId, OrderSnapshotSourceDTO snapshot,
                                      String lockToken, String orderNo, LocalDateTime now,
                                      int seatCount, String seatsInfo) {
        OrderPO order = new OrderPO();
        order.setOrderNo(orderNo);
        order.setUserId(userId);
        order.setScheduleId(snapshot.getScheduleId());
        order.setLockToken(lockToken);
        order.setMovieName(snapshot.getMovieName());
        order.setCinemaName(snapshot.getCinemaName());
        order.setHallName(snapshot.getHallName());
        order.setShowTime(snapshot.getShowDate() + " " + snapshot.getShowTime());
        order.setSeatCount(seatCount);
        order.setSeatsInfo(seatsInfo);
        order.setUnitPrice(snapshot.getUnitPrice());
        order.setTotalPrice(snapshot.getUnitPrice().multiply(BigDecimal.valueOf(seatCount)));
        order.setStatus(OrderStatusEnum.PENDING.getCode());
        order.setExpireTime(now.plusMinutes(CacheConstants.ORDER_PAY_TIMEOUT_MINUTES));
        return order;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    @Transactional(rollbackFor = Exception.class, timeout = 8)
    public void cancelOrder(Long userId, String orderNo) {
        LambdaQueryWrapper<OrderPO> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(OrderPO::getOrderNo, orderNo)
                .eq(OrderPO::getUserId, userId);
        OrderPO order = orderMapper.selectOne(wrapper);

        if (order == null) {
            throw new BizException(ResponseCodeEnum.NOT_FOUND.getCode(), "订单不存在");
        }
        if (order.getStatus() != OrderStatusEnum.PENDING.getCode()) {
            throw new BizException(ResponseCodeEnum.BAD_REQUEST.getCode(), "当前订单状态不允许取消");
        }

        orderClosureService.closePendingOrder(orderNo, "USER_CANCEL");
    }

    @Scheduled(fixedDelay = 60000)
    public void cancelExpiredOrders() {
        TraceContext.setOrGenerate(null);
        try {
            LocalDateTime now = LocalDateTime.now();
            List<OrderPO> expired = orderMapper.selectExpiredPendingOrders(now, 100);
            if (expired.isEmpty()) {
                return;
            }
            for (OrderPO order : expired) {
                try {
                    orderClosureService.closeExpiredOrder(order.getOrderNo(), "TIMEOUT_SCHEDULER");
                } catch (Exception e) {
                    log.error("[Order] Failed to close expired order: orderNo={}", order.getOrderNo(), e);
                }
            }
        } finally {
            TraceContext.clear();
        }
    }

    private OrderVO executeProfiledTransaction(Long userId, CreateOrderDTO dto,
                                                List<SeatLockKeys.Seat> canonicalSeats,
                                                Set<String> requestedSeats,
                                                AtomicBoolean redisDebitApplied) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setTimeout(8);
        long transactionStarted = System.nanoTime();
        AtomicLong callbackNanos = new AtomicLong();
        try {
            return template.execute(status -> {
                long callbackStarted = System.nanoTime();
                try {
                    return createOrderAttempt(userId, dto, canonicalSeats, requestedSeats,
                            redisDebitApplied);
                } finally {
                    callbackNanos.set(System.nanoTime() - callbackStarted);
                }
            });
        } finally {
            long completionNanos = System.nanoTime() - transactionStarted - callbackNanos.get();
            businessMetrics.recordOrderCreateStage("tx_completion", completionNanos);
        }
    }

    private void compensateRedisStock(Long scheduleId, int seatCount, AtomicBoolean redisDebitApplied) {
        if (redisDebitApplied.compareAndSet(true, false)) {
            stockService.rollback(scheduleId, seatCount);
        }
    }

    public List<OrderVO> getUserOrders(Long userId, int page, int size) {
        int offset = (page - 1) * size;
        return orderMapper.selectByUserIdWithPage(userId, offset, size).stream()
                .map(this::toVO)
                .toList();
    }

    private void refreshScheduleDetailCache(Long scheduleId) {
        try {
            ActivitySessionPO schedule = activitySessionMapper.selectById(scheduleId);
            if (schedule != null) {
                stockService.initScheduleDetail(schedule);
            }
        } catch (Exception e) {
            log.warn("[Order] Failed to refresh schedule cache: scheduleId={}", scheduleId, e);
        }
    }

    private String generateOrderNo(Long userId) {
        String time = LocalDateTime.now().format(ORDER_NO_FMT);
        String userSuffix = String.format("%04d", userId % 10000);
        String random = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        return "MO" + time + userSuffix + random;
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
            vo.setCreateTime(po.getCreateTime().format(VO_TIME_FMT));
        }
        if (po.getPayTime() != null) {
            vo.setPayTime(po.getPayTime().format(VO_TIME_FMT));
        }
        if (po.getExpireTime() != null) {
            vo.setExpireTime(po.getExpireTime().format(VO_TIME_FMT));
        }
        return vo;
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private String maskLockToken(String lockToken) {
        if (lockToken == null || lockToken.length() <= 12) {
            return "***";
        }
        return lockToken.substring(0, 6) + "..." + lockToken.substring(lockToken.length() - 4);
    }

    private <T> T profileOrderCreateStage(String stage, Supplier<T> action) {
        long started = System.nanoTime();
        try {
            return action.get();
        } finally {
            businessMetrics.recordOrderCreateStage(stage, System.nanoTime() - started);
        }
    }

    private void profileOrderCreateStage(String stage, Runnable action) {
        profileOrderCreateStage(stage, () -> {
            action.run();
            return null;
        });
    }
}
