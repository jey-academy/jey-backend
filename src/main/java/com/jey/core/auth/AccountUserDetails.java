package com.jey.core.auth;

import java.util.Collection;
import java.util.List;

import com.jey.core.auth.api.AuthenticatedUser;
import com.jey.core.auth.domain.User;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

// 비밀번호 검증에만 쓴다. 해시를 들고 있으므로 세션에는 이 객체가 아니라 AuthenticatedUser를 저장한다.
final class AccountUserDetails implements UserDetails {

	private final AuthenticatedUser user;

	private final String passwordHash;

	private final boolean active;

	AccountUserDetails(User user) {
		this.user = new AuthenticatedUser(user.getId(), user.getLoginId(), user.getName(), user.getRole(),
				user.getCampusId());
		this.passwordHash = user.getPasswordHash();
		this.active = user.isActive();
	}

	AuthenticatedUser toAuthenticatedUser() {
		return user;
	}

	// 역할을 Spring Security 권한 이름(ROLE_ADMIN 등)으로 바꾼다.
	@Override
	public Collection<? extends GrantedAuthority> getAuthorities() {
		return List.of(new SimpleGrantedAuthority("ROLE_" + user.role().name()));
	}

	@Override
	public String getPassword() {
		return passwordHash;
	}

	@Override
	public String getUsername() {
		return user.loginId();
	}

	@Override
	public boolean isEnabled() {
		return active;
	}

}
