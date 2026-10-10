package com.jey.core.auth;

import com.jey.core.auth.domain.User;
import com.jey.core.auth.domain.UserRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
class AccountUserDetailsService implements UserDetailsService {

	private final UserRepository users;

	AccountUserDetailsService(UserRepository users) {
		this.users = users;
	}

	@Override
	public UserDetails loadUserByUsername(String loginId) {
		// 저장할 때와 같은 규칙(공백 제거, 소문자)으로 맞춰서 찾는다.
		// 예외 메시지에는 입력한 아이디를 넣지 않는다. 아이디 칸에 비밀번호를 잘못 넣는 일이 흔하다.
		return users.findByLoginId(User.normalizeLoginId(loginId))
				.map(AccountUserDetails::new)
				.orElseThrow(() -> new UsernameNotFoundException("계정 없음"));
	}

}
