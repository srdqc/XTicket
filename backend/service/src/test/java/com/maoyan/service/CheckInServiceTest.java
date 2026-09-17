package com.maoyan.service;

import com.maoyan.dao.mapper.ElectronicTicketMapper;
import com.maoyan.dao.mapper.UserMapper;
import com.maoyan.domain.enums.TicketStatusEnum;
import com.maoyan.domain.enums.UserRoleEnum;
import com.maoyan.domain.exception.BizException;
import com.maoyan.domain.model.po.ElectronicTicketPO;
import com.maoyan.domain.model.po.UserPO;
import com.maoyan.domain.model.vo.CheckInResult;
import com.maoyan.service.observability.BusinessMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CheckInServiceTest {

    private static final long STAFF_ID = 2001L;
    private static final long SESSION_ID = 19L;
    private static final String TICKET_NO = "ET-CHECKIN-1";

    @Mock
    private UserMapper userMapper;
    @Mock
    private ElectronicTicketMapper electronicTicketMapper;
    @Mock
    private BusinessMetrics businessMetrics;

    private final AtomicReference<Integer> status = new AtomicReference<>();
    private final AtomicReference<LocalDateTime> usedAt = new AtomicReference<>();
    private final AtomicLong checkedBy = new AtomicLong();
    private final AtomicInteger successfulTransitions = new AtomicInteger();
    private CheckInService checkInService;

    @BeforeEach
    void setUp() {
        checkInService = new CheckInService(userMapper, electronicTicketMapper, businessMetrics);
        status.set(TicketStatusEnum.ISSUED.getCode());
        usedAt.set(null);
        checkedBy.set(0L);
        successfulTransitions.set(0);
        configureStaff(STAFF_ID, UserRoleEnum.CHECKIN_STAFF);
        configureTicketStore();
    }

    @Test
    void staffChecksInIssuedTicketAndWritesAuditFields() {
        CheckInResult result = checkInService.checkIn(STAFF_ID, TICKET_NO, SESSION_ID);

        assertThat(result.isFirstCheckIn()).isTrue();
        assertThat(result.isAlreadyUsed()).isFalse();
        assertThat(result.getStatus()).isEqualTo(TicketStatusEnum.USED.getCode());
        assertThat(result.getUsedAt()).isNotBlank();
        assertThat(checkedBy.get()).isEqualTo(STAFF_ID);
        assertThat(successfulTransitions).hasValue(1);
        verify(businessMetrics).checkInSuccess();
    }

    @Test
    void repeatedCheckInKeepsOriginalUsedAtAndCheckedBy() {
        CheckInResult first = checkInService.checkIn(STAFF_ID, TICKET_NO, SESSION_ID);
        configureStaff(2002L, UserRoleEnum.CHECKIN_STAFF);

        CheckInResult repeated = checkInService.checkIn(2002L, TICKET_NO, SESSION_ID);

        assertThat(repeated.isFirstCheckIn()).isFalse();
        assertThat(repeated.isAlreadyUsed()).isTrue();
        assertThat(repeated.getUsedAt()).isEqualTo(first.getUsedAt());
        assertThat(checkedBy.get()).isEqualTo(STAFF_ID);
        assertThat(successfulTransitions).hasValue(1);
        verify(businessMetrics).checkInSuccess();
        verify(businessMetrics).checkInDuplicate();
    }

    @Test
    void eightConcurrentRequestsProduceOneStateTransition() {
        CyclicBarrier barrier = new CyclicBarrier(8);
        List<CompletableFuture<CheckInResult>> calls = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            calls.add(CompletableFuture.supplyAsync(() -> {
                await(barrier);
                return checkInService.checkIn(STAFF_ID, TICKET_NO, SESSION_ID);
            }));
        }
        CompletableFuture.allOf(calls.toArray(CompletableFuture[]::new)).join();

        List<CheckInResult> results = calls.stream().map(CompletableFuture::join).toList();
        assertThat(results).filteredOn(CheckInResult::isFirstCheckIn).hasSize(1);
        assertThat(results).filteredOn(CheckInResult::isAlreadyUsed).hasSize(7);
        assertThat(status).hasValue(TicketStatusEnum.USED.getCode());
        assertThat(usedAt.get()).isNotNull();
        assertThat(checkedBy.get()).isEqualTo(STAFF_ID);
        assertThat(successfulTransitions).hasValue(1);
    }

    @Test
    void invalidatedTicketCannotBeCheckedIn() {
        status.set(TicketStatusEnum.INVALIDATED.getCode());

        assertThatThrownBy(() -> checkInService.checkIn(STAFF_ID, TICKET_NO, SESSION_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已失效");
        verify(electronicTicketMapper, never()).markAsUsed(any(), any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void missingTicketIsRejected() {
        when(electronicTicketMapper.selectByTicketNo("MISSING")).thenReturn(null);

        assertThatThrownBy(() -> checkInService.checkIn(STAFF_ID, "MISSING", SESSION_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不存在");
    }

    @Test
    void wrongSessionIsRejectedWithoutStateChange() {
        assertThatThrownBy(() -> checkInService.checkIn(STAFF_ID, TICKET_NO, 20L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不属于当前核销场次");
        assertThat(status).hasValue(TicketStatusEnum.ISSUED.getCode());
        verify(electronicTicketMapper, never()).markAsUsed(any(), any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void ordinaryUserCannotCheckIn() {
        configureStaff(1001L, UserRoleEnum.USER);

        assertThatThrownBy(() -> checkInService.checkIn(1001L, TICKET_NO, SESSION_ID))
                .isInstanceOf(BizException.class)
                .extracting("code").isEqualTo(403);
        verify(electronicTicketMapper, never()).selectByTicketNo(any());
    }

    @Test
    void schemasDeclareRoleAndCheckInAuditColumn() throws Exception {
        String mysql = Files.readString(Path.of("..", "..", "docker", "mysql", "init", "00-init.sql"));
        String h2 = Files.readString(Path.of("..", "provider", "src", "main", "resources", "schema.sql"));

        assertThat(mysql).contains("role           VARCHAR(32) NOT NULL DEFAULT 'USER'")
                .contains("checked_by      BIGINT      NULL");
        assertThat(h2).contains("role           VARCHAR(32) NOT NULL DEFAULT 'USER'")
                .contains("checked_by      BIGINT      NULL");
    }

    private void configureStaff(long userId, UserRoleEnum role) {
        UserPO user = new UserPO();
        user.setId(userId);
        user.setRole(role.name());
        user.setDeleted(0);
        lenient().when(userMapper.selectById(userId)).thenReturn(user);
    }

    private void configureTicketStore() {
        lenient().when(electronicTicketMapper.selectByTicketNo(TICKET_NO)).thenAnswer(invocation -> currentTicket());
        lenient().when(electronicTicketMapper.markAsUsed(eq(TICKET_NO), eq(SESSION_ID), any(), any(),
                eq(TicketStatusEnum.ISSUED.getCode()), eq(TicketStatusEnum.USED.getCode())))
                .thenAnswer(invocation -> {
                    if (!status.compareAndSet(TicketStatusEnum.ISSUED.getCode(), TicketStatusEnum.USED.getCode())) {
                        return 0;
                    }
                    usedAt.set(invocation.getArgument(3));
                    checkedBy.set(invocation.getArgument(2));
                    successfulTransitions.incrementAndGet();
                    return 1;
                });
        lenient().when(electronicTicketMapper.selectCheckInResultByTicketNo(TICKET_NO)).thenAnswer(invocation -> {
            CheckInResult result = new CheckInResult();
            result.setTicketNo(TICKET_NO);
            result.setSessionId(SESSION_ID);
            result.setStatus(status.get());
            result.setUsedAt(usedAt.get() == null ? null : usedAt.get().toString());
            result.setActivityName("高校迎新晚会");
            result.setVenueName("大学生活动中心");
            result.setHallName("主会场");
            result.setShowTime("2026-09-20 19:00");
            result.setSeatLabel("1排1座");
            return result;
        });
    }

    private ElectronicTicketPO currentTicket() {
        ElectronicTicketPO ticket = new ElectronicTicketPO();
        ticket.setTicketNo(TICKET_NO);
        ticket.setSessionId(SESSION_ID);
        ticket.setStatus(status.get());
        ticket.setUsedAt(usedAt.get());
        ticket.setCheckedBy(checkedBy.get() == 0 ? null : checkedBy.get());
        return ticket;
    }

    private void await(CyclicBarrier barrier) {
        try {
            barrier.await();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
