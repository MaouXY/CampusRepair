package com.maou.apptemplateapi.common.security;

import com.maou.apptemplateapi.common.enums.UserRole;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class CurrentUser {

    private final Long principalId;
    private final String subject;
    private final LoginType loginType;
    private final String roleCode;
    private final Long organizationId;

    public Long getId() {
        return principalId;
    }

    public UserRole getRole() {
        return UserRole.fromCode(roleCode);
    }
}

