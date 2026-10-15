package com.jey.dongtanpass.web;

import java.util.UUID;

import com.jey.TestcontainersConfiguration;
import com.jey.WithJeyUser;
import com.jey.core.auth.api.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static com.jey.TestCsrf.csrfToken;
import static com.jey.TestIdempotency.idempotencyKey;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 연계 학원은 관리자가 패스 모듈에서 만든다 (ADR-0034).
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PartnerApiTest {

	private static final String PARTNERS = "/api/v1/dongtanpass/partners";

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	JsonMapper objectMapper;

	@Test
	@WithJeyUser(role = UserRole.ADMIN)
	void 관리자가_연계_학원을_만들면_켜진_학원이_되고_만든_사실이_기록된다() throws Exception {
		String name = uniqueName();

		JsonNode created = json(create(" " + name + " ").andExpect(status().isCreated())
			.andExpect(jsonPath("$.id").isNumber())
			.andExpect(jsonPath("$.name").value(name))
			.andExpect(jsonPath("$.type").value("ACADEMY"))
			.andExpect(jsonPath("$.active").value(true))
			.andExpect(jsonPath("$.createdAt").isString()));

		long id = created.get("id").asLong();
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM core_audit_log WHERE action = 'dongtanpass.partner-created' "
				+ "AND target_type = 'partner' AND target_id = ? AND campus_id IS NULL", Integer.class, id))
			.isEqualTo(1);
	}

	// DB는 이름의 대소문자를 가리지 않는다. 대소문자만 다른 이름도 같은 학원이다.
	@Test
	@WithJeyUser(role = UserRole.ADMIN)
	void 이미_있는_이름으로는_만들_수_없다() throws Exception {
		String name = uniqueName();
		create(name).andExpect(status().isCreated());

		create(name).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_PARTNER_NAME_DUPLICATED"));
		create(name.toUpperCase()).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_PARTNER_NAME_DUPLICATED"));
	}

	@Test
	@WithJeyUser(role = UserRole.ADMIN)
	void 비었거나_너무_긴_이름은_입력_오류다() throws Exception {
		create("  ").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"));
		create("가".repeat(51)).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"));
	}

	@Test
	@WithJeyUser(role = UserRole.ADMIN)
	void 목록은_만든_순서다() throws Exception {
		String first = uniqueName();
		String second = uniqueName();
		long firstId = json(create(first).andExpect(status().isCreated())).get("id").asLong();
		long secondId = json(create(second).andExpect(status().isCreated())).get("id").asLong();

		JsonNode list = json(mockMvc.perform(get(PARTNERS)).andExpect(status().isOk()));

		assertThat(list.isArray()).isTrue();
		int firstAt = indexOf(list, firstId);
		int secondAt = indexOf(list, secondId);
		assertThat(firstAt).isNotNegative();
		assertThat(secondAt).isGreaterThan(firstAt);
		assertThat(list.get(firstAt).get("name").asString()).isEqualTo(first);
	}

	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = 1)
	void 직원은_연계_학원을_보거나_만들_수_없다() throws Exception {
		mockMvc.perform(get(PARTNERS)).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("AUTH_ROLE_NOT_ALLOWED"));
		create(uniqueName()).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("AUTH_ROLE_NOT_ALLOWED"));
	}

	@Test
	@WithJeyUser(role = UserRole.PARTNER, partnerId = 1)
	void 연계_학원_계정은_연계_학원을_보거나_만들_수_없다() throws Exception {
		mockMvc.perform(get(PARTNERS)).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("AUTH_ROLE_NOT_ALLOWED"));
		create(uniqueName()).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("AUTH_ROLE_NOT_ALLOWED"));
	}

	private ResultActions create(String name) throws Exception {
		return mockMvc.perform(post(PARTNERS).with(csrfToken(mockMvc))
			.with(idempotencyKey())
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(java.util.Map.of("name", name))));
	}

	private JsonNode json(ResultActions result) throws Exception {
		return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
	}

	private static int indexOf(JsonNode list, long id) {
		for (int i = 0; i < list.size(); i++) {
			if (list.get(i).get("id").asLong() == id) {
				return i;
			}
		}
		return -1;
	}

	private static String uniqueName() {
		return "학원-" + UUID.randomUUID().toString().substring(0, 8);
	}

}
