package com.jey.core.auth;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Service;

/**
 * 계정에 딸린 로그인 세션을 다룬다.
 * 세션은 계정 ID로 찾는다({@code AuthenticatedUser#getName}이 계정 ID를 돌려주고, Spring Session이 그 이름으로 색인을 만든다).
 */
@Service
public class UserSessions {

	private static final Logger log = LoggerFactory.getLogger(UserSessions.class);

	private final FindByIndexNameSessionRepository<? extends Session> sessions;

	UserSessions(FindByIndexNameSessionRepository<? extends Session> sessions) {
		this.sessions = sessions;
	}

	/**
	 * 그 계정으로 로그인된 세션을 모두 끊는다. 계정을 차단하거나 역할·지점을 바꿨을 때 쓴다.
	 * 끊긴 세션의 다음 요청은 401을 받는다.
	 *
	 * @return 끊은 세션 수
	 */
	public int terminateAll(long userId) {
		Map<String, ? extends Session> found = sessions.findByPrincipalName(String.valueOf(userId));
		found.keySet().forEach(sessions::deleteById);
		log.info("세션 강제 종료: userId={} count={}", userId, found.size());
		return found.size();
	}

}
