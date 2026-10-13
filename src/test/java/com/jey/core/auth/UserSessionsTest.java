package com.jey.core.auth;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.MapSession;
import org.springframework.session.Session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

// 실제 세션 저장소에서 끊기는지는 API 테스트가 본다. 여기서는 어느 세션을 끊고 어느 세션을 남기는지를 본다.
@ExtendWith(OutputCaptureExtension.class)
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

	// 부르는 쪽은 계정을 이미 바꾸고 커밋했다. 여기서 던지면 성공한 변경이 실패로 답해진다.
	// 남은 세션은 요청마다의 확인이 막는다 (ADR-0031). 저장소 장애는 알아야 하므로 원인과 함께 오류로 남긴다.
	@Test
	void 끊다가_실패해도_던지지_않고_그때까지_끊은_수를_돌려주며_오류를_남긴다(CapturedOutput output) {
		loggedIn("first", "second", "third");
		doThrow(new IllegalStateException("저장소 장애")).when(sessions).deleteById("second");

		assertThat(userSessions.terminateAll(USER_ID)).isEqualTo(1);

		verify(sessions, never()).deleteById("third");
		assertThat(output).contains("세션 강제 종료 실패: userId=7 끊은 수=1").contains("저장소 장애");
	}

	@Test
	void 그_계정의_세션을_찾다가_실패해도_던지지_않는다(CapturedOutput output) {
		when(sessions.findByPrincipalName(anyString())).thenThrow(new IllegalStateException("저장소 장애"));

		assertThat(userSessions.terminateOthers(USER_ID, "here")).isZero();

		assertThat(output).contains("세션 강제 종료 실패: userId=7 끊은 수=0");
	}

	private void loggedIn(String... sessionIds) {
		Map<String, Session> found = new LinkedHashMap<>();
		for (String sessionId : sessionIds) {
			found.put(sessionId, new MapSession(sessionId));
		}
		when(sessions.findByPrincipalName(String.valueOf(USER_ID))).thenReturn(found);
	}

}
