package com.maoyan.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maoyan.domain.model.po.ElectronicTicketPO;
import com.maoyan.domain.model.vo.TicketVO;
import com.maoyan.domain.model.vo.CheckInResult;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

import java.util.List;

@Mapper
public interface ElectronicTicketMapper extends BaseMapper<ElectronicTicketPO> {

    @Select("SELECT * FROM electronic_ticket WHERE order_seat_id = #{orderSeatId} AND deleted = 0 LIMIT 1")
    ElectronicTicketPO selectByOrderSeatId(@Param("orderSeatId") Long orderSeatId);

    @Select("SELECT * FROM electronic_ticket WHERE ticket_no = #{ticketNo} AND deleted = 0 LIMIT 1")
    ElectronicTicketPO selectByTicketNo(@Param("ticketNo") String ticketNo);

    @Update("""
        UPDATE electronic_ticket
        SET status = #{usedStatus}, used_at = #{usedAt}, checked_by = #{operatorUserId},
            update_time = CURRENT_TIMESTAMP
        WHERE ticket_no = #{ticketNo} AND session_id = #{sessionId}
          AND status = #{issuedStatus} AND deleted = 0
        """)
    int markAsUsed(@Param("ticketNo") String ticketNo,
                   @Param("sessionId") Long sessionId,
                   @Param("operatorUserId") Long operatorUserId,
                   @Param("usedAt") LocalDateTime usedAt,
                   @Param("issuedStatus") int issuedStatus,
                   @Param("usedStatus") int usedStatus);

    @Select("""
        SELECT et.ticket_no AS ticket_no, et.session_id AS session_id, et.status AS status,
               et.used_at AS used_at, o.movie_name AS activity_name,
               o.cinema_name AS venue_name, o.hall_name AS hall_name,
               o.show_time AS show_time, os.seat_label AS seat_label
        FROM electronic_ticket et
        INNER JOIN ticket_order o ON o.order_no = et.order_no AND o.deleted = 0
        INNER JOIN order_seat os ON os.id = et.order_seat_id
        WHERE et.ticket_no = #{ticketNo} AND et.deleted = 0
        LIMIT 1
        """)
    CheckInResult selectCheckInResultByTicketNo(@Param("ticketNo") String ticketNo);

    @Select("SELECT * FROM electronic_ticket WHERE order_no = #{orderNo} AND deleted = 0 ORDER BY order_seat_id")
    List<ElectronicTicketPO> selectByOrderNo(@Param("orderNo") String orderNo);

    @Select("""
        <script>
        SELECT et.ticket_no AS ticket_no, et.order_no AS order_no, et.session_id AS session_id,
               et.status AS status, et.issued_at AS issued_at, et.used_at AS used_at,
               o.movie_name AS activity_name, o.cinema_name AS venue_name,
               o.hall_name AS hall_name, o.show_time AS show_time,
               os.seat_label AS seat_label, os.row_num AS row_num, os.col_num AS col_num
        FROM electronic_ticket et
        INNER JOIN ticket_order o ON o.order_no = et.order_no AND o.deleted = 0
        INNER JOIN order_seat os ON os.id = et.order_seat_id
        WHERE et.user_id = #{userId} AND et.deleted = 0
        <if test="orderNo != null and orderNo != ''">
          AND et.order_no = #{orderNo}
        </if>
        ORDER BY et.issued_at DESC, et.order_seat_id ASC
        </script>
        """)
    List<TicketVO> selectViewsByUserIdAndOrderNo(@Param("userId") Long userId,
                                                  @Param("orderNo") String orderNo);

    @Select("""
        SELECT et.ticket_no AS ticket_no, et.order_no AS order_no, et.session_id AS session_id,
               et.status AS status, et.issued_at AS issued_at, et.used_at AS used_at,
               o.movie_name AS activity_name, o.cinema_name AS venue_name,
               o.hall_name AS hall_name, o.show_time AS show_time,
               os.seat_label AS seat_label, os.row_num AS row_num, os.col_num AS col_num
        FROM electronic_ticket et
        INNER JOIN ticket_order o ON o.order_no = et.order_no AND o.deleted = 0
        INNER JOIN order_seat os ON os.id = et.order_seat_id
        WHERE et.ticket_no = #{ticketNo} AND et.user_id = #{userId} AND et.deleted = 0
        LIMIT 1
        """)
    TicketVO selectViewByTicketNoAndUserId(@Param("ticketNo") String ticketNo,
                                           @Param("userId") Long userId);
}
