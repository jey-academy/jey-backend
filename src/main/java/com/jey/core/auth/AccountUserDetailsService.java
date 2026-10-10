package com.jey.core.auth;

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
		return users.findByLoginId(loginId)
				.map(AccountUserDetails::new)
				.orElseThrow(() -> new UsernameNotFoundException("계정 없음"));
	}

}
