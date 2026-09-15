package com.maoyan.domain.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum TicketStatusEnum {

    ISSUED(0, "已签发"),
    USED(1, "已核销"),
    INVALIDATED(2, "已作废");

    private final int code;
    private final String desc;

    public static TicketStatusEnum of(int code) {
        for (TicketStatusEnum status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知电子票状态: " + code);
    }
}
