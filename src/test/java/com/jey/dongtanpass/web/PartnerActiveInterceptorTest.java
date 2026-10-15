package com.jey.dongtanpass.web;

import com.jey.TestcontainersConfiguration;
import com.jey.WithJeyUser;
import com.jey.core.auth.api.UserRole;
import com.jey.dongtanpass.domain.PartnerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 끈 연계 학원의 계정은 패스 모듈의 API를 쓰지 못한다(ADR-0034). 계정 차단이 늦거나 실패해도 이 확인이 막는다.
// 세션 없이 로그인 정보만 넣어(@WithJeyUser) 장치 하나만 본다. 실제 쿠키로 끄기와 이어지는 흐름은 PartnerDeactivationTest가 본다.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PartnerActiveInterceptorTest {

	private static final String AREA = "/api/v1/dongtanpass/test-partner-area";

	// 로그인 정보의 연계 학원 식별자는 표시에 적는 상수라 행의 ID를 고정해 넣는다. 다른 테스트가 만드는 ID와 겹치지 않게 크게 잡는다.
	private static final long ACTIVE = 900_001L;

	private static final long INACTIVE = 900_002L;

	private static final long MISSING = 900_003L;

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbc;

	@MockitoSpyBean
	PartnerRepository partners;

	@BeforeEach
	void setUp() {
		upsert(ACTIVE, true);
		upsert(INACTIVE, false);
		jdbc.update("DELETE FROM dongtanpass_partner WHERE id = ?", MISSING);
	}

	@Test
	@WithJeyUser(role = UserRole.PARTNER, partnerId = ACTIVE)
	void 켜진_학원의_계정은_지나간다() throws Exception {
		mockMvc.perform(get(AREA)).andExpect(status().isOk()).andExpect(content().string("ok"));
	}

	@Test
	@WithJeyUser(role = UserRole.PARTNER, partnerId = INACTIVE)
	void 꺼진_학원의_계정은_막힌다() throws Exception {
		mockMvc.perform(get(AREA)).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_PARTNER_INACTIVE"));
	}

	// DB를 손으로 고쳐 계정이 없는 학원을 가리키게 된 경우. 서버 오류가 아니라 꺼진 학원과 같이 막는다.
	@Test
	@WithJeyUser(role = UserRole.PARTNER, partnerId = MISSING)
	void 없는_학원의_계정도_막힌다() throws Exception {
		mockMvc.perform(get(AREA)).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_PARTNER_INACTIVE"));
	}

	// 학원 상태를 읽지 못하면 통과시키지 않는다. 확인하지 못한 요청이 꺼진 학원의 것일 수 있다.
	@Test
	@WithJeyUser(role = UserRole.PARTNER, partnerId = ACTIVE)
	void 학원을_읽지_못하면_통과시키지_않는다() throws Exception {
		doThrow(new DataAccessResourceFailureException("DB 대신 일부러 낸 장애")).when(partners).findById(ACTIVE);

		mockMvc.perform(get(AREA)).andExpect(status().isInternalServerError())
			.andExpect(jsonPath("$.code").value("COMMON_INTERNAL_ERROR"));
	}

	// 막는 범위는 패스 모듈의 API뿐이다. 꺼진 학원의 계정도 로그인 상태 확인이나 본인 비밀번호 변경 같은 기초 기능은 막히지 않는다(ADR-0034).
	@Test
	@WithJeyUser(role = UserRole.PARTNER, partnerId = INACTIVE)
	void 패스_모듈_밖의_API는_꺼진_학원의_계정도_학원을_읽지_않고_지나간다() throws Exception {
		mockMvc.perform(get("/api/v1/auth/me")).andExpect(status().isOk())
			.andExpect(jsonPath("$.role").value("PARTNER"));

		verify(partners, never()).findById(any());
	}

	// 역할 확인이 먼저다. 역할에 맞지 않는 요청은 학원 상태를 읽지 않고 끝난다.
	@Test
	@WithJeyUser(role = UserRole.PARTNER, partnerId = ACTIVE)
	void 연계_학원_계정이_관리자_전용_API를_부르면_학원을_읽지_않고_역할로_막힌다() throws Exception {
		mockMvc.perform(get(AREA + "/admin")).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("AUTH_ROLE_NOT_ALLOWED"));

		verify(partners, never()).findById(any());
	}

	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = 1)
	void 직원은_학원을_읽지_않고_지나간다() throws Exception {
		mockMvc.perform(get(AREA + "/desk")).andExpect(status().isOk());

		verify(partners, never()).findById(any());
	}

	@Test
	@WithJeyUser(role = UserRole.ADMIN)
	void 관리자는_학원을_읽지_않고_지나간다() throws Exception {
		mockMvc.perform(get(AREA + "/admin")).andExpect(status().isOk());

		verify(partners, never()).findById(any());
	}

	private void upsert(long id, boolean active) {
		jdbc.update("""
				INSERT INTO dongtanpass_partner (id, name, type, active, created_at, updated_at)
				VALUES (?, ?, 'ACADEMY', ?, NOW(6), NOW(6))
				ON DUPLICATE KEY UPDATE active = VALUES(active)
				""", id, "장치확인-" + id, active);
	}

}
