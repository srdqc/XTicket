package com.maoyan.service;

import com.maoyan.common.utils.JwtUtil;
import com.maoyan.dao.mapper.UserMapper;
import com.maoyan.domain.enums.UserRoleEnum;
import com.maoyan.domain.model.dto.UserRegisterDTO;
import com.maoyan.domain.model.po.UserPO;
import com.maoyan.domain.model.vo.UserVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceRoleTest {

    @Mock
    private UserMapper userMapper;
    @Mock
    private JwtUtil jwtUtil;
    @InjectMocks
    private UserService userService;

    @Test
    void registrationAlwaysCreatesOrdinaryUserAndDtoHasNoRoleField() {
        UserRegisterDTO dto = new UserRegisterDTO();
        dto.setAccount("phase4b_user");
        dto.setPassword("secret123");
        dto.setUserNick("Phase 4B User");
        dto.setInviteCode("lpf");
        when(userMapper.insert(any(UserPO.class))).thenAnswer(invocation -> {
            invocation.<UserPO>getArgument(0).setId(1001L);
            return 1;
        });
        when(jwtUtil.generateToken(1001L, "phase4b_user")).thenReturn("token");

        UserVO result = userService.register(dto);

        ArgumentCaptor<UserPO> captor = ArgumentCaptor.forClass(UserPO.class);
        verify(userMapper).insert(captor.capture());
        assertThat(captor.getValue().getRole()).isEqualTo(UserRoleEnum.USER.name());
        assertThat(result.getRole()).isEqualTo(UserRoleEnum.USER.name());
        assertThat(Arrays.stream(UserRegisterDTO.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getName)).doesNotContain("role");
    }

    @Test
    void currentUserExposesPersistedCheckInStaffRole() {
        UserPO staff = new UserPO();
        staff.setId(2001L);
        staff.setAccount("staff");
        staff.setRole(UserRoleEnum.CHECKIN_STAFF.name());
        staff.setDeleted(0);
        when(userMapper.selectById(2001L)).thenReturn(staff);

        UserVO result = userService.getUserInfo(2001L);

        assertThat(result.getRole()).isEqualTo(UserRoleEnum.CHECKIN_STAFF.name());
    }
}
