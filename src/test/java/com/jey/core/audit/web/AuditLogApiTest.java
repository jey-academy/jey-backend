package com.jey.core.audit.web;

import java.util.Map;
import java.util.UUID;

import com.jey.TestcontainersConfiguration;
import com.jey.WithJeyUser;
import com.jey.core.audit.api.AuditLog;
import com.jey.core.audit.TestAuditAction;
import com.jey.core.audit.api.AuditEntry;
import com.jey.core.audit.api.AuditOutcome;
import com.jey.core.auth.api.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 테스트끼리 DB를 같이 쓴다. 행위 이름을 매번 새로 만들어 그 이름으로 걸러서 본다.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuditLogApiTest {

	private static final String AUDIT_LOGS = "/api/v1/audit-logs";

	private static final long ADMIN = 9001;

	@Autowired
	MockMvc mockMvc;

	@Autowired
	AuditLog auditLog;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	TransactionTemplate transaction;

	@Test
	@WithJeyUser(role = UserRole.ADMIN, id = ADMIN)
	void 관리자가_기록을_최신순으로_본다() throws Exception {
		String action = newAction();
		transaction.executeWithoutResult(status -> auditLog.record(AuditEntry.of(done(action))
				.target("student", 11)
				.campus(2)
				.with("guardianId", 5)
				.with("linkedToExisting", true)));
		record(action);

		mockMvc.perform(get(AUDIT_LOGS).param("action", action))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(2))
				.andExpect(jsonPath("$.page").value(0))
				.andExpect(jsonPath("$.size").value(20))
				.andExpect(jsonPath("$.totalElements").value(2))
				.andExpect(jsonPath("$.totalPages").value(1))
				// 나중에 적은 것이 먼저 나온다.
				.andExpect(jsonPath("$.items[0].targetType").isEmpty())
				.andExpect(jsonPath("$.items[0].targetId").isEmpty())
				.andExpect(jsonPath("$.items[0].detail").isEmpty())
				.andExpect(jsonPath("$.items[0].campusId").isEmpty())
				.andExpect(jsonPath("$.items[1].id").isNumber())
				.andExpect(jsonPath("$.items[1].occurredAt").isString())
				.andExpect(jsonPath("$.items[1].actorId").value(ADMIN))
				.andExpect(jsonPath("$.items[1].action").value(action))
				.andExpect(jsonPath("$.items[1].outcome").value("DONE"))
				.andExpect(jsonPath("$.items[1].campusId").value(2))
				.andExpect(jsonPath("$.items[1].targetType").value("student"))
				.andExpect(jsonPath("$.items[1].targetId").value(11))
				// 상세는 문자열이 아니라 JSON 객체로 나간다.
				.andExpect(jsonPath("$.items[1].detail.guardianId").value(5))
				.andExpect(jsonPath("$.items[1].detail.linkedToExisting").value(true));
	}

	@Test
	@WithJeyUser(role = UserRole.ADMIN, id = ADMIN)
	void 행위자와_대상으로_거른다() throws Exception {
		String action = newAction();
		long otherActor = 9002;
		record(action, "student", 21);
		record(action, "student", 22);
		record(action, "guardian", 21);
		jdbc.update("UPDATE core_audit_log SET actor_id = ? WHERE action = ? AND target_id = 22", otherActor, action);

		mockMvc.perform(get(AUDIT_LOGS).param("action", action).param("actorId", String.valueOf(otherActor)))
				.andExpect(jsonPath("$.items[*].targetId", contains(22)));
		mockMvc.perform(get(AUDIT_LOGS).param("action", action).param("targetType", "student").param("targetId", "21"))
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].targetType").value("student"));
		// 대상 ID만 주면 학생 21번과 학부모 21번의 기록이 섞인다. 종류를 같이 주게 한다.
		mockMvc.perform(get(AUDIT_LOGS).param("action", action).param("targetId", "21"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("AUDIT_TARGET_TYPE_REQUIRED"));
		mockMvc.perform(get(AUDIT_LOGS).param("action", action).param("targetType", "guardian"))
				.andExpect(jsonPath("$.items[*].targetType", everyItem(is("guardian"))))
				.andExpect(jsonPath("$.totalElements").value(1));
		// 행위 이름은 정확히 일치해야 한다. 앞부분만 같은 것은 나오지 않는다.
		mockMvc.perform(get(AUDIT_LOGS).param("action", action.substring(0, action.length() - 1)))
				.andExpect(jsonPath("$.items").isEmpty());
	}

	// 가린 전화번호를 대입으로 알아내려는 시도는 거부된 기록으로 쌓인다. 거부만 골라 볼 수 있어야 한다.
	@Test
	@WithJeyUser(role = UserRole.ADMIN, id = ADMIN)
	void 한_일과_거부된_시도를_골라_본다() throws Exception {
		String action = newAction();
		record(action, "student", 1);
		auditLog.record(AuditEntry.of(new TestAuditAction(action, AuditOutcome.DENIED)).target("student", 2));
		auditLog.record(AuditEntry.of(new TestAuditAction(action, AuditOutcome.DENIED)).target("student", 3));

		mockMvc.perform(get(AUDIT_LOGS).param("action", action).param("outcome", "DENIED"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[*].targetId", contains(3, 2)))
				.andExpect(jsonPath("$.items[*].outcome", everyItem(is("DENIED"))))
				.andExpect(jsonPath("$.totalElements").value(2));
		mockMvc.perform(get(AUDIT_LOGS).param("action", action).param("outcome", "DONE"))
				.andExpect(jsonPath("$.items[*].targetId", contains(1)));
		mockMvc.perform(get(AUDIT_LOGS).param("action", action))
				.andExpect(jsonPath("$.totalElements").value(3));
		mockMvc.perform(get(AUDIT_LOGS).param("outcome", "FAILED")).andExpect(status().isBadRequest());
	}

	// 한 계정이 같은 응답을 되풀이해 받았는지는 기간을 좁혀 전체 건수로 본다.
	@Test
	@WithJeyUser(role = UserRole.ADMIN, id = ADMIN)
	void 기간으로_거른다_시작은_포함하고_끝은_제외한다() throws Exception {
		String action = newAction();
		record(action, "student", 1);
		record(action, "student", 2);
		record(action, "student", 3);
		setOccurredAt(action, 1, "2026-11-01 00:00:00.000000");
		setOccurredAt(action, 2, "2026-11-15 12:00:00.000000");
		setOccurredAt(action, 3, "2026-12-01 00:00:00.000000");

		// DB에는 한국 시각으로 저장된다. 11월 1일 0시(한국)부터 12월 1일 0시(한국) 전까지.
		mockMvc.perform(get(AUDIT_LOGS).param("action", action)
				.param("from", "2026-11-01T00:00:00+09:00")
				.param("to", "2026-12-01T00:00:00+09:00"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[*].targetId", contains(2, 1)))
				.andExpect(jsonPath("$.items[1].occurredAt").value("2026-10-31T15:00:00Z"));
		// 같은 시각을 UTC로 적어도 같다.
		mockMvc.perform(get(AUDIT_LOGS).param("action", action).param("from", "2026-11-30T15:00:00Z"))
				.andExpect(jsonPath("$.items[*].targetId", contains(3)));
		mockMvc.perform(get(AUDIT_LOGS).param("action", action).param("to", "2026-10-31T15:00:00Z"))
				.andExpect(jsonPath("$.items").isEmpty());
	}

	@Test
	@WithJeyUser(role = UserRole.ADMIN, id = ADMIN)
	void 페이지로_나눈다() throws Exception {
		String action = newAction();
		for (int i = 1; i <= 5; i++) {
			record(action, "student", i);
		}

		mockMvc.perform(get(AUDIT_LOGS).param("action", action).param("size", "2"))
				.andExpect(jsonPath("$.items[*].targetId", contains(5, 4)))
				.andExpect(jsonPath("$.totalElements").value(5))
				.andExpect(jsonPath("$.totalPages").value(3));
		mockMvc.perform(get(AUDIT_LOGS).param("action", action).param("size", "2").param("page", "2"))
				.andExpect(jsonPath("$.items[*].targetId", contains(1)));
	}

	@Test
	@WithJeyUser(role = UserRole.ADMIN, id = ADMIN)
	void 조건이_틀리면_400이다() throws Exception {
		mockMvc.perform(get(AUDIT_LOGS).param("from", "2026-12-01T00:00:00Z").param("to", "2026-11-01T00:00:00Z"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("AUDIT_INVALID_PERIOD"));
		mockMvc.perform(get(AUDIT_LOGS).param("from", "2026-11-01")).andExpect(status().isBadRequest());
		mockMvc.perform(get(AUDIT_LOGS).param("from", "어제")).andExpect(status().isBadRequest());
		mockMvc.perform(get(AUDIT_LOGS).param("size", "101"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[?(@.field == 'size')]").isNotEmpty());
		mockMvc.perform(get(AUDIT_LOGS).param("page", "-1")).andExpect(status().isBadRequest());
		mockMvc.perform(get(AUDIT_LOGS).param("action", "a".repeat(81))).andExpect(status().isBadRequest());
		mockMvc.perform(get(AUDIT_LOGS).param("actorId", "abc")).andExpect(status().isBadRequest());
		mockMvc.perform(get(AUDIT_LOGS).param("size", "100")).andExpect(status().isOk());
		// 같은 시각으로 시작과 끝을 주면 빈 결과다(끝은 제외).
		mockMvc.perform(get(AUDIT_LOGS).param("from", "2026-11-01T00:00:00Z").param("to", "2026-11-01T00:00:00Z"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items").isEmpty());
	}

	// 직원이 자기 기록을 볼 수 있으면 무엇이 남는지 지켜보며 움직일 수 있다.
	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = 1)
	void 직원은_보지_못한다() throws Exception {
		mockMvc.perform(get(AUDIT_LOGS))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("AUTH_ROLE_NOT_ALLOWED"));
		mockMvc.perform(get(AUDIT_LOGS).param("actorId", "1")).andExpect(status().isForbidden());
	}

	@Test
	@WithJeyUser(role = UserRole.PARTNER, partnerId = 7)
	void 연계_학원은_보지_못한다() throws Exception {
		mockMvc.perform(get(AUDIT_LOGS))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("AUTH_ROLE_NOT_ALLOWED"));
	}

	@Test
	void 로그인하지_않으면_401이다() throws Exception {
		mockMvc.perform(get(AUDIT_LOGS)).andExpect(status().isUnauthorized());
	}

	// 기록은 추가만 한다.
	@Test
	@WithJeyUser(role = UserRole.ADMIN, id = ADMIN)
	void 기록을_지우는_경로는_없다() throws Exception {
		mockMvc.perform(delete(AUDIT_LOGS + "/1").with(com.jey.TestCsrf.csrfToken(mockMvc)))
				.andExpect(status().is4xxClientError());
	}

	private void setOccurredAt(String action, long targetId, String koreanTime) {
		jdbc.update("UPDATE core_audit_log SET occurred_at = ? WHERE action = ? AND target_id = ?", koreanTime, action,
				targetId);
	}

	private void record(String code) {
		transaction.executeWithoutResult(status -> auditLog.record(AuditEntry.of(done(code))));
	}

	private void record(String code, String targetType, long targetId) {
		transaction.executeWithoutResult(
				status -> auditLog.record(AuditEntry.of(done(code)).target(targetType, targetId)));
	}

	private static TestAuditAction done(String code) {
		return new TestAuditAction(code, AuditOutcome.DONE);
	}

	private static String newAction() {
		return "audittest.api-" + UUID.randomUUID().toString().substring(0, 8);
	}

}
