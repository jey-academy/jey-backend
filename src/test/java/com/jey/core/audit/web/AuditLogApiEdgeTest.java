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
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuditLogApiEdgeTest {

	private static final String AUDIT_LOGS = "/api/v1/audit-logs";

	@Autowired
	MockMvc mockMvc;

	@Autowired
	AuditLog auditLog;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	TransactionTemplate transaction;

	// 이벤트가 한꺼번에 들어오면 같은 시각의 기록이 여럿 생긴다. 순서가 정해져 있지 않으면 페이지 사이에서 겹치거나 빠진다.
	@Test
	@WithJeyUser(role = UserRole.ADMIN)
	void 시각이_같은_기록은_나중에_적힌_것이_먼저이고_페이지_사이에서_겹치지_않는다() throws Exception {
		String action = newAction();
		for (int i = 1; i <= 5; i++) {
			record(action, "student", i);
		}
		jdbc.update("UPDATE core_audit_log SET occurred_at = '2026-11-01 09:00:00.000000' WHERE action = ?", action);

		mockMvc.perform(get(AUDIT_LOGS).param("action", action).param("size", "2"))
				.andExpect(jsonPath("$.items[*].targetId", contains(5, 4)));
		mockMvc.perform(get(AUDIT_LOGS).param("action", action).param("size", "2").param("page", "1"))
				.andExpect(jsonPath("$.items[*].targetId", contains(3, 2)));
		mockMvc.perform(get(AUDIT_LOGS).param("action", action).param("size", "2").param("page", "2"))
				.andExpect(jsonPath("$.items[*].targetId", contains(1)));
	}

	// 없는 값은 빈 객체나 빈 문자열이 아니라 null로 나간다.
	@Test
	@WithJeyUser(role = UserRole.ADMIN)
	void 없는_값은_null이다() throws Exception {
		String action = newAction();
		record(action);

		mockMvc.perform(get(AUDIT_LOGS).param("action", action))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].detail").value(nullValue()))
				.andExpect(jsonPath("$.items[0].targetType").value(nullValue()))
				.andExpect(jsonPath("$.items[0].targetId").value(nullValue()))
				.andExpect(jsonPath("$.items[0].campusId").value(nullValue()));
	}

	@Test
	@WithJeyUser(role = UserRole.ADMIN)
	void 조건의_경계가_틀리면_400이다() throws Exception {
		mockMvc.perform(get(AUDIT_LOGS).param("size", "0"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[?(@.field == 'size')]").isNotEmpty());
		// 시간대가 없는 시각은 받지 않는다. 서버의 시간대로 짐작해서 읽지 않는다.
		mockMvc.perform(get(AUDIT_LOGS).param("from", "2026-11-01T00:00:00")).andExpect(status().isBadRequest());
		mockMvc.perform(get(AUDIT_LOGS).param("targetType", "a".repeat(41)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[?(@.field == 'targetType')]").isNotEmpty());
		mockMvc.perform(get(AUDIT_LOGS).param("targetId", "abc")).andExpect(status().isBadRequest());
	}

	// 저장된 값이 깨져 있으면(DB를 직접 고친 경우 등) 그 줄만 읽을 수 없다고 표시한다.
	// 한 줄 때문에 페이지 전체가 실패하면, 기록이 고쳐졌는지 살펴야 할 때 조회가 막힌다.
	@Test
	@WithJeyUser(role = UserRole.ADMIN)
	void 상세가_깨진_줄이_있어도_나머지는_보인다() throws Exception {
		String action = newAction();
		record(action, "student", 1);
		record(action, "student", 2);
		record(action, "student", 3);
		jdbc.update("UPDATE core_audit_log SET detail_json = ? WHERE action = ? AND target_id = 1", "{broken", action);
		jdbc.update("UPDATE core_audit_log SET detail_json = ? WHERE action = ? AND target_id = 2", "[1]", action);

		mockMvc.perform(get(AUDIT_LOGS).param("action", action))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[*].targetId", contains(3, 2, 1)))
				.andExpect(jsonPath("$.items[0].detail").value(nullValue()))
				.andExpect(jsonPath("$.items[1].detail.corrupted").value(true))
				.andExpect(jsonPath("$.items[2].detail.corrupted").value(true));
	}

	// 빈 조건은 조건을 주지 않은 것과 같다. 앞뒤 공백은 뗀다.
	@Test
	@WithJeyUser(role = UserRole.ADMIN)
	void 빈_조건은_없는_것으로_보고_앞뒤_공백은_뗀다() throws Exception {
		String action = newAction();
		record(action, "student", 1);

		mockMvc.perform(get(AUDIT_LOGS).param("action", " " + action + " ").param("targetType", ""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(1));
	}

	// 직원은 조건을 어떻게 바꿔도 보지 못한다.
	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = 1, id = 77)
	void 직원은_자기_기록도_보지_못한다() throws Exception {
		mockMvc.perform(get(AUDIT_LOGS).param("actorId", "77"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("AUTH_ROLE_NOT_ALLOWED"));
		mockMvc.perform(get(AUDIT_LOGS).param("size", "1").param("page", "0")).andExpect(status().isForbidden());
		// 조건이 틀려도 "조건이 틀렸다"가 아니라 권한 없음이다.
		mockMvc.perform(get(AUDIT_LOGS).param("size", "101")).andExpect(status().isForbidden());
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
		return "audittest.edge-" + UUID.randomUUID().toString().substring(0, 8);
	}

}
