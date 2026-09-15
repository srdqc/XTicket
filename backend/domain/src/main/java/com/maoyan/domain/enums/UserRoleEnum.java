package com.maoyan.domain.enums;

public enum UserRoleEnum {

    USER,
    CHECKIN_STAFF;

    public boolean matches(String role) {
        return name().equals(role);
    }
}
