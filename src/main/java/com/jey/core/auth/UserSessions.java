package com.jey.core.auth;

import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Service;

/**
 * 계정에 딸린 로그인 세션을 다룬다.
 * 세션은 계정 ID로 찾는다({@code AuthenticatedUser#getName}이 계정 ID를 돌려주고, Spring Session이 그 이름으로 색인을 만든다).
 *
 * <p>여기서 끊는 것은 미리 치워 두는 일이다. 차단됐거나 바뀐 계정의 옛 세션을 막는 것은 요청마다의 확인이 한다
 * ({@code SessionAccountFilter}, ADR-0031). 그래서 끊다가 실패해도 옛 세션이 계속 일하지는 못한다.
 *
 * <p><b>끊지 못해도 예외를 던지지 않는다</b>(세션 저장소 장애). 오류 로그만 남기고 그때까지 끊은 수를 돌려준다.
 * 부르는 쪽은 계정을 이미 바꾸고 커밋했다. 여기서 던지면 성공한 변경이 실패로 답해진다.
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
	 * @return 끊은 세션 수. 끊다가 실패했으면 그때까지 끊은 수
	 */
	public int terminateAll(long userId) {
		return terminate(userId, session -> true);
	}

	/**
	 * 그 계정으로 로그인된 세션 가운데 하나만 남기고 모두 끊는다. 본인이 비밀번호를 바꿨을 때 쓴다.
	 * 바꾼 사람이 쓰고 있는 세션은 남기고, 옛 비밀번호로 로그인된 다른 세션(다른 기기, 훔친 세션)을 끊는다.
	 *
	 * @param keepSessionId 남길 세션의 ID
	 * @return 끊은 세션 수. 끊다가 실패했으면 그때까지 끊은 수
	 * @throws NullPointerException 남길 세션의 ID가 없을 때(부르는 쪽의 버그다)
	 */
	public int terminateOthers(long userId, String keepSessionId) {
		// 남길 세션을 모르는 채로 부르면 전부 끊는 것과 같아진다. 그럴 때는 terminateAll을 쓴다.
		Objects.requireNonNull(keepSessionId, "keepSessionId");
		return terminate(userId, session -> !session.getId().equals(keepSessionId));
	}

	private int terminate(long userId, Predicate<Session> shouldTerminate) {
		int terminated = 0;
		try {
			Map<String, ? extends Session> found = sessions.findByPrincipalName(String.valueOf(userId));
			for (Session session : found.values()) {
				if (!shouldTerminate.test(session)) {
					continue;
				}
				sessions.deleteById(session.getId());
				terminated++;
			}
		}
		catch (RuntimeException ex) {
			// 계정 상태는 이미 바뀌었는데 세션이 남아 있을 수 있다. 남은 세션은 다음 요청에서 막힌다(권한 판 번호가 다르다).
			// 던지지 않는다. 다만 저장소 장애는 알아야 하므로 오류로 남긴다.
			log.error("세션 강제 종료 실패: userId={} 끊은 수={}. 남은 세션은 다음 요청에서 막힌다", userId, terminated, ex);
			return terminated;
		}
		log.info("세션 강제 종료: userId={} count={}", userId, terminated);
		return terminated;
	}

}
