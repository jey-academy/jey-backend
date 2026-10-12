package com.jey.core.auth;

import com.jey.core.auth.api.UserRole;

/**
 * 계정을 고칠 내용. 셋 모두 고친 뒤의 값이다(그대로 둘 값도 지금 값을 넣는다).
 *
 * @param campusId 소속 지점. 직원이면 있어야 하고 그 밖의 역할이면 없어야 한다
 */
public record AccountChange(String name, UserRole role, Long campusId) {
}
