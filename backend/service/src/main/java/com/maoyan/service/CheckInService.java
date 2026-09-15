package com.maoyan.service;

import com.maoyan.dao.mapper.ElectronicTicketMapper;
import com.maoyan.dao.mapper.UserMapper;
import com.maoyan.domain.enums.ResponseCodeEnum;
import com.maoyan.domain.enums.TicketStatusEnum;
import com.maoyan.domain.enums.UserRoleEnum;
import com.maoyan.domain.exception.BizException;
import com.maoyan.domain.model.po.ElectronicTicketPO;
import com.maoyan.domain.model.po.UserPO;
import com.maoyan.domain.model.vo.CheckInResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class CheckInService {

    private final UserMapper userMapper;
    private final ElectronicTicketMapper electronicTicketMapper;

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public CheckInResult checkIn(Long operatorUserId, String ticketNo, Long sessionId) {
        verifyOperator(operatorUserId);
        if (ticketNo == null || ticketNo.isBlank()) {
            throw new BizException(ResponseCodeEnum.BAD_REQUEST.getCode(), "电子票号不能为空");
        }

        ElectronicTicketPO ticket = requireTicket(ticketNo.trim());
        verifySession(ticket, sessionId);

        if (ticket.getStatus() == TicketStatusEnum.USED.getCode()) {
            return buildResult(ticket.getTicketNo(), false);
        }
        if (ticket.getStatus() == TicketStatusEnum.INVALIDATED.getCode()) {
            throw new BizException(ResponseCodeEnum.CONFLICT.getCode(), "电子票已失效，不能核销");
        }
        if (ticket.getStatus() != TicketStatusEnum.ISSUED.getCode()) {
            throw new BizException(ResponseCodeEnum.CONFLICT.getCode(), "电子票状态不允许核销");
        }

        int affected = electronicTicketMapper.markAsUsed(
                ticket.getTicketNo(), sessionId, operatorUserId, LocalDateTime.now(),
                TicketStatusEnum.ISSUED.getCode(), TicketStatusEnum.USED.getCode());
        if (affected == 1) {
            return buildResult(ticket.getTicketNo(), true);
        }

        ElectronicTicketPO current = requireTicket(ticket.getTicketNo());
        verifySession(current, sessionId);
        if (current.getStatus() == TicketStatusEnum.USED.getCode()) {
            return buildResult(current.getTicketNo(), false);
        }
        if (current.getStatus() == TicketStatusEnum.INVALIDATED.getCode()) {
            throw new BizException(ResponseCodeEnum.CONFLICT.getCode(), "电子票已失效，不能核销");
        }
        throw new BizException(ResponseCodeEnum.CONFLICT.getCode(), "电子票状态更新失败，请重试");
    }

    private void verifyOperator(Long operatorUserId) {
        UserPO operator = operatorUserId == null ? null : userMapper.selectById(operatorUserId);
        if (operator == null || operator.getDeleted() == null || operator.getDeleted() != 0
                || !UserRoleEnum.CHECKIN_STAFF.matches(operator.getRole())) {
            throw new BizException(ResponseCodeEnum.FORBIDDEN.getCode(), "当前用户无核销权限");
        }
    }

    private ElectronicTicketPO requireTicket(String ticketNo) {
        ElectronicTicketPO ticket = electronicTicketMapper.selectByTicketNo(ticketNo);
        if (ticket == null) {
            throw new BizException(ResponseCodeEnum.NOT_FOUND.getCode(), "电子票不存在");
        }
        return ticket;
    }

    private void verifySession(ElectronicTicketPO ticket, Long sessionId) {
        if (sessionId == null || !sessionId.equals(ticket.getSessionId())) {
            throw new BizException(ResponseCodeEnum.CONFLICT.getCode(), "电子票不属于当前核销场次");
        }
    }

    private CheckInResult buildResult(String ticketNo, boolean firstCheckIn) {
        CheckInResult result = electronicTicketMapper.selectCheckInResultByTicketNo(ticketNo);
        if (result == null) {
            throw new BizException(ResponseCodeEnum.INTERNAL_ERROR.getCode(), "核销结果读取失败");
        }
        result.setFirstCheckIn(firstCheckIn);
        result.setAlreadyUsed(!firstCheckIn);
        return result;
    }
}
