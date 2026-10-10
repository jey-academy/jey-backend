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
		String normalized = User.normalizeLoginId(loginId);
		// 아이디로 쓸 수 없는 문자가 섞였으면 DB에 묻지 않는다. DB는 악센트나 전각 문자를 같은 글자로 봐서,
		// 그런 변형으로도 남의 계정이 찾아지기 때문이다.
		// 예외 메시지에는 입력한 아이디를 넣지 않는다. 아이디 칸에 비밀번호를 잘못 넣는 일이 흔하다.
		if (!User.isValidLoginId(normalized)) {
			throw new UsernameNotFoundException("계정 없음");
		}
		return users.findByLoginId(normalized)
				.map(AccountUserDetails::new)
				.orElseThrow(() -> new UsernameNotFoundException("계정 없음"));
	}

}
