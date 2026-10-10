package com.jey.core.auth;

import com.jey.core.auth.AuthProperties.InitialAdmin;
import com.jey.core.auth.api.UserRole;
import com.jey.core.auth.domain.User;
import com.jey.core.auth.domain.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 계정이 하나도 없을 때 설정값({@code jey.auth.initial-admin})으로 첫 관리자를 만든다.
 * 계정을 만드는 API도 로그인이 필요하므로, 맨 처음 한 명은 이렇게 만들 수밖에 없다.
 * 비밀번호를 저장소나 마이그레이션 파일에 두지 않으려고 실행 환경의 설정값으로 받는다.
 */
@Component
class InitialAdminInitializer implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(InitialAdminInitializer.class);

	private static final String DEFAULT_NAME = "관리자";

	private final UserRepository users;

	private final PasswordEncoder passwordEncoder;

	private final AuthProperties properties;

	InitialAdminInitializer(UserRepository users, PasswordEncoder passwordEncoder, AuthProperties properties) {
		this.users = users;
		this.passwordEncoder = passwordEncoder;
		this.properties = properties;
	}

	@Override
	public void run(ApplicationArguments args) {
		createIfNoAccounts();
	}

	// 계정이 이미 있으면 아무것도 하지 않는다. 서버를 다시 띄워도 관리자가 또 생기거나 비밀번호가 바뀌지 않는다.
	void createIfNoAccounts() {
		if (users.count() > 0) {
			return;
		}
		InitialAdmin initialAdmin = properties.initialAdmin();
		if (initialAdmin == null || !initialAdmin.isConfigured()) {
			log.warn("계정이 하나도 없는데 첫 관리자 설정(jey.auth.initial-admin)이 없다. 지금은 아무도 로그인할 수 없다.");
			return;
		}
		String name = (initialAdmin.name() == null || initialAdmin.name().isBlank()) ? DEFAULT_NAME
				: initialAdmin.name();
		users.save(User.create(initialAdmin.loginId(), passwordEncoder.encode(initialAdmin.password()), name,
				UserRole.ADMIN, null));
		log.info("첫 관리자 계정을 만들었다: loginId={}", initialAdmin.loginId());
	}

}
