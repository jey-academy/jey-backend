package com.jey.core.auth;

import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

import com.jey.core.auth.api.AuthenticatedUser;
import com.jey.core.auth.api.UserRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
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
		return terminate(userId, session -> true);
	}

	/**
	 * 그 계정으로 로그인된 세션 가운데 역할이나 지점이 지금과 다른 것을 끊는다. 계정의 역할·지점을 고친 뒤에 쓴다.
	 * 세션에는 로그인할 때의 역할과 지점이 들어 있어, 끊지 않으면 옛 권한으로 계속 일한다.
	 * 로그인 정보를 읽을 수 없는 세션도 끊는다(어떤 권한인지 알 수 없다).
	 *
	 * <p>지금과 같은 세션은 남기므로 부를 때마다 결과가 같다. 고친 것이 없어도 불러도 되고,
	 * 끊다가 실패한 뒤 같은 요청을 다시 보내면 남은 옛 세션이 끊긴다.
	 *
	 * @param role 지금의 역할
	 * @param campusId 지금의 소속 지점. 없으면 {@code null}
	 * @return 끊은 세션 수
	 */
	public int terminateOutdated(long userId, UserRole role, Long campusId) {
		Objects.requireNonNull(role, "role");
		return terminate(userId, session -> !loggedInAs(session, role, campusId));
	}

	/**
	 * 그 계정으로 로그인된 세션 가운데 하나만 남기고 모두 끊는다. 본인이 비밀번호를 바꿨을 때 쓴다.
	 * 바꾼 사람이 쓰고 있는 세션은 남기고, 옛 비밀번호로 로그인된 다른 세션(다른 기기, 훔친 세션)을 끊는다.
	 *
	 * @param keepSessionId 남길 세션의 ID
	 * @return 끊은 세션 수
	 */
	public int terminateOthers(long userId, String keepSessionId) {
		// 남길 세션을 모르는 채로 부르면 전부 끊는 것과 같아진다. 그럴 때는 terminateAll을 쓴다.
		Objects.requireNonNull(keepSessionId, "keepSessionId");
		return terminate(userId, session -> !session.getId().equals(keepSessionId));
	}

	private static boolean loggedInAs(Session session, UserRole role, Long campusId) {
		Object context = session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
		if (!(context instanceof SecurityContext securityContext) || securityContext.getAuthentication() == null
				|| !(securityContext.getAuthentication().getPrincipal() instanceof AuthenticatedUser user)) {
			return false;
		}
		return user.role() == role && Objects.equals(user.campusId(), campusId);
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
			// 계정 상태는 이미 바뀌었는데 세션이 남아 있을 수 있다. 여러 번 실행해도 안전하므로 다시 시도하면 된다.
			log.error("세션 강제 종료 실패: userId={} 끊은 수={}. 남은 세션이 있을 수 있으니 다시 시도해야 한다", userId, terminated, ex);
			throw ex;
		}
		log.info("세션 강제 종료: userId={} count={}", userId, terminated);
		return terminated;
	}

}
