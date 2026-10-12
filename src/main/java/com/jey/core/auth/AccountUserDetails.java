package com.jey.core.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

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

	// 검증에 쓴 해시가 무엇이었는지의 표시. 해시는 검증이 끝나면 지우므로, 그 뒤에 "그때의 해시가 지금도 그대로인가"를 보려고 둔다.
	// 해시의 해시라서 이것으로 비밀번호를 검증할 수는 없다.
	private final transient String passwordHashStamp;

	AccountUserDetails(User user) {
		this.user = new AuthenticatedUser(user.getId(), user.getLoginId(), user.getName(), user.getRole(),
				user.getCampusId(), user.getPartnerId());
		this.passwordHash = user.getPasswordHash();
		this.passwordHashStamp = stampOf(user.getPasswordHash());
	}

	/**
	 * 비밀번호를 검증한 뒤에 다시 읽은 계정이, 검증할 때 읽은 계정과 권한·비밀번호가 같은지.
	 * 다르면 그 사이에 역할·지점이 바뀌었거나 비밀번호가 바뀐 것이다. 그런 계정의 세션은 변경한 쪽이 끊는데,
	 * 이 로그인의 세션은 그 뒤에 저장되어 옛 권한(또는 옛 비밀번호)으로 살아남는다.
	 */
	boolean isSameAs(User reloaded) {
		return user.role() == reloaded.getRole() && Objects.equals(user.campusId(), reloaded.getCampusId())
				&& Objects.equals(user.partnerId(), reloaded.getPartnerId())
				&& passwordHashStamp.equals(stampOf(reloaded.getPasswordHash()));
	}

	private static String stampOf(String passwordHash) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(passwordHash.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256을 쓸 수 없다", ex);
		}
	}

	AuthenticatedUser toAuthenticatedUser() {
		return user;
	}

	@Override
	public Collection<? extends GrantedAuthority> getAuthorities() {
		return List.of(new SimpleGrantedAuthority(user.role().authority()));
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
