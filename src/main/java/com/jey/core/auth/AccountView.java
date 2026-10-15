package com.jey.core.auth;

import java.time.Instant;

import com.jey.core.auth.api.UserRole;
import com.jey.core.auth.domain.User;
import com.jey.core.auth.domain.UserStatus;

/**
 * 계정 하나의 내용. 비밀번호와 그 해시는 싣지 않는다.
 *
 * @param campusId 소속 지점. 직원만 값이 있다
 * @param partnerId 연계 학원 식별자. 연계 학원 계정만 값이 있다
 */
public record AccountView(long id, String loginId, String name, UserRole role, Long campusId, Long partnerId,
		UserStatus status, Instant createdAt) {

	static AccountView from(User user) {
		return new AccountView(user.getId(), user.getLoginId(), user.getName(), user.getRole(), user.getCampusId(),
				user.getPartnerId(), user.getStatus(), user.getCreatedAt());
	}

}
