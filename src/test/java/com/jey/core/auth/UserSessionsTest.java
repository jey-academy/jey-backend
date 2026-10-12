package com.jey.core.auth;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.jey.core.auth.api.AuthenticatedUser;
import com.jey.core.auth.api.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.MapSession;
import org.springframework.session.Session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

// 실제 세션 저장소에서 끊기는지는 API 테스트가 본다. 여기서는 어느 세션을 끊고 어느 세션을 남기는지를 본다.
class UserSessionsTest {

	private static final long USER_ID = 7L;

	@SuppressWarnings("unchecked")
	private final FindByIndexNameSessionRepository<Session> sessions = mock(FindByIndexNameSessionRepository.class);

	private final UserSessions userSessions = new UserSessions(sessions);

	@Test
	void 그_계정의_세션을_모두_끊는다() {
		loggedIn("here", "other-device", "stolen");

		assertThat(userSessions.terminateAll(USER_ID)).isEqualTo(3);

		verify(sessions).deleteById("here");
		verify(sessions).deleteById("other-device");
		verify(sessions).deleteById("stolen");
	}

	// 본인이 비밀번호를 바꿨을 때다. 바꾼 사람의 세션은 남기고 옛 비밀번호로 로그인된 다른 세션을 끊는다.
	@Test
	void 하나만_남기고_끊는다() {
		loggedIn("here", "other-device", "stolen");

		assertThat(userSessions.terminateOthers(USER_ID, "here")).isEqualTo(2);

		verify(sessions, never()).deleteById("here");
		verify(sessions).deleteById("other-device");
		verify(sessions).deleteById("stolen");
	}

	@Test
	void 남길_세션이_그_계정의_것이_아니면_모두_끊긴다() {
		loggedIn("other-device", "stolen");

		assertThat(userSessions.terminateOthers(USER_ID, "someone-else")).isEqualTo(2);
	}

	// 남길 세션을 모르는 채로 부르면 조용히 전부 끊는 것이 된다. 그렇게 되지 않게 받지 않는다.
	@Test
	void 남길_세션_없이는_하나만_남기고_끊기를_부를_수_없다() {
		assertThatNullPointerException().isThrownBy(() -> userSessions.terminateOthers(USER_ID, null));

		verifyNoInteractions(sessions);
	}

	@Test
	void 끊다가_실패하면_예외를_그대로_던진다() {
		loggedIn("here", "other-device");
		doThrow(new IllegalStateException("저장소 장애")).when(sessions).deleteById(anyString());

		assertThatThrownBy(() -> userSessions.terminateOthers(USER_ID, "here")).isInstanceOf(IllegalStateException.class)
			.hasMessage("저장소 장애");
	}

	// 세션에는 로그인할 때의 역할과 지점이 들어 있다. 계정의 지점을 옮기면 옛 지점으로 로그인된 세션만 끊는다.
	@Test
	void 역할이나_지점이_지금과_다른_세션만_끊는다() {
		Map<String, Session> found = new LinkedHashMap<>();
		found.put("dongtan", sessionOf("dongtan", UserRole.STAFF, 1L));
		found.put("neungdong", sessionOf("neungdong", UserRole.STAFF, 2L));
		found.put("was-admin", sessionOf("was-admin", UserRole.ADMIN, null));
		when(sessions.findByPrincipalName(String.valueOf(USER_ID))).thenReturn(found);

		assertThat(userSessions.terminateOutdated(USER_ID, UserRole.STAFF, 2L)).isEqualTo(2);

		verify(sessions).deleteById("dongtan");
		verify(sessions).deleteById("was-admin");
		verify(sessions, never()).deleteById("neungdong");
	}

	// 어떤 권한으로 로그인된 세션인지 알 수 없으면 남기지 않는다.
	@Test
	void 로그인_정보를_읽을_수_없는_세션은_옛_세션으로_보고_끊는다() {
		MapSession broken = new MapSession("broken");
		broken.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, "not-a-security-context");
		Map<String, Session> found = new LinkedHashMap<>();
		found.put("empty", new MapSession("empty"));
		found.put("broken", broken);
		when(sessions.findByPrincipalName(String.valueOf(USER_ID))).thenReturn(found);

		assertThat(userSessions.terminateOutdated(USER_ID, UserRole.STAFF, 1L)).isEqualTo(2);
	}

	private static Session sessionOf(String sessionId, UserRole role, Long campusId) {
		var principal = new AuthenticatedUser(USER_ID, "desk", "김직원", role, campusId, null);
		SecurityContext context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null,
				List.of(new SimpleGrantedAuthority(role.authority()))));
		MapSession session = new MapSession(sessionId);
		session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
		return session;
	}

	private void loggedIn(String... sessionIds) {
		Map<String, Session> found = new LinkedHashMap<>();
		for (String sessionId : sessionIds) {
			found.put(sessionId, new MapSession(sessionId));
		}
		when(sessions.findByPrincipalName(String.valueOf(USER_ID))).thenReturn(found);
	}

}
