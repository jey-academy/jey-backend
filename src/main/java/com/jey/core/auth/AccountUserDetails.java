package com.jey.core.auth;

import java.util.Collection;
import java.util.List;

import com.jey.core.auth.api.AuthenticatedUser;
import com.jey.core.auth.domain.User;
import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

// 비밀번호 검증에만 쓴다. 해시를 들고 있으므로 세션에는 이 객체가 아니라 AuthenticatedUser를 저장한다.
// 실수로 세션에 들어가더라도 해시가 남지 않게 두 겹으로 막는다: 검증이 끝나면 해시를 지우고(CredentialsContainer),
// 직렬화 대상에서도 뺀다(transient).
final class AccountUserDetails implements UserDetails, CredentialsContainer {

	private final AuthenticatedUser user;

	private transient String passwordHash;

	AccountUserDetails(User user) {
		this.user = new AuthenticatedUser(user.getId(), user.getLoginId(), user.getName(), user.getRole(),
				user.getCampusId());
		this.passwordHash = user.getPasswordHash();
	}

	AuthenticatedUser toAuthenticatedUser() {
		return user;
	}

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

	// 비활성 여부는 여기서 알리지 않는다. Spring Security는 이 값을 비밀번호 검증보다 먼저 확인해서,
	// 비활성 계정만 해시 계산 없이 빨리 실패하게 된다. 그 시간 차이로 "존재하지만 비활성인 아이디"를 가려낼 수 있다.
	// 비활성 여부는 비밀번호가 맞은 뒤에 LoginService가 DB에서 다시 읽어 확인한다.
	@Override
	public boolean isEnabled() {
		return true;
	}

	// 인증 관리자가 검증을 마친 뒤 호출한다.
	@Override
	public void eraseCredentials() {
		this.passwordHash = null;
	}

}
