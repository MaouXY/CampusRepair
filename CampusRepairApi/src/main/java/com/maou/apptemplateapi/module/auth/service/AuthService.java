package com.maou.apptemplateapi.module.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.maou.apptemplateapi.common.exception.BusinessException;
import com.maou.apptemplateapi.common.exception.ErrorCode;
import com.maou.apptemplateapi.common.security.CurrentUser;
import com.maou.apptemplateapi.common.security.CurrentUserProvider;
import com.maou.apptemplateapi.common.security.JwtTokenService;
import com.maou.apptemplateapi.common.security.LoginType;
import com.maou.apptemplateapi.module.auth.dto.CurrentUserResponse;
import com.maou.apptemplateapi.module.auth.dto.LoginRequest;
import com.maou.apptemplateapi.module.auth.dto.LoginResponse;
import com.maou.apptemplateapi.module.user.entity.UserAccount;
import com.maou.apptemplateapi.module.user.mapper.UserAccountMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserAccountMapper userAccountMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService jwtTokenService;

    @Transactional
    public LoginResponse login(LoginRequest request) {
        UserAccount user = userAccountMapper.selectOne(new LambdaQueryWrapper<UserAccount>()
                .eq(UserAccount::getUsername, request.username())
                .eq(UserAccount::getDeleted, 0)
                .last("limit 1"));
        if (user == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            log.warn("login rejected, scenario=password-login, username={}, reason=bad-credentials", request.username());
            throw new BusinessException(ErrorCode.AUTH_LOGIN_FAILED);
        }
        if (user.getEnabled() == null || user.getEnabled() != 1) {
            log.warn("login rejected, scenario=password-login, userId={}, username={}, reason=account-disabled",
                    user.getId(), user.getUsername());
            throw new BusinessException(ErrorCode.AUTH_ACCOUNT_DISABLED);
        }

        CurrentUser currentUser = CurrentUser.builder()
                .principalId(user.getId())
                .subject(user.getUsername())
                .loginType(LoginType.PASSWORD)
                .roleCode(user.getRoleCode())
                .organizationId(null)
                .build();
        user.setLastLoginAt(LocalDateTime.now());
        userAccountMapper.updateById(user);

        return new LoginResponse(
                jwtTokenService.generateToken(currentUser),
                jwtTokenService.getExpirationSeconds(),
                user.getId(),
                user.getUsername(),
                user.getRealName(),
                user.getRoleCode()
        );
    }

    public CurrentUserResponse currentUser() {
        CurrentUser currentUser = CurrentUserProvider.require();
        UserAccount user = userAccountMapper.selectById(currentUser.getId());
        if (user == null || user.getDeleted() != 0 || user.getEnabled() == null || user.getEnabled() != 1) {
            log.warn("current user not found, scenario=current-user, userId={}", currentUser.getId());
            throw new BusinessException(ErrorCode.AUTH_CURRENT_USER_NOT_FOUND);
        }
        return new CurrentUserResponse(user.getId(), user.getUsername(), user.getRealName(), user.getRoleCode());
    }
}
