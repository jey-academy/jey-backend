package com.jey.dongtanpass.web;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import com.jey.TestcontainersConfiguration;
import com.jey.WithJeyUser;
import com.jey.core.auth.api.UserRole;
import com.jey.core.shared.Money;
import com.jey.dongtanpass.domain.LinkToken;
import com.jey.dongtanpass.domain.Pass;
import com.jey.dongtanpass.domain.PassNumber;
import com.jey.dongtanpass.domain.PassRepository;
import com.jey.dongtanpass.domain.PaymentMethod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static com.jey.TestCsrf.csrfToken;
import static com.jey.TestIdempotency.idempotencyKey;
import static com.jey.dongtanpass.DongtanpassFixtures.upsertPartner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 연계 학원은 학부모 링크를 새로 만들 수 있다(잃어버렸거나 잘못 보냈을 때). 예전 링크는 바로 쓸 수 없게 된다.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PassLinkApiTest {

	private static final long PARTNER = 900_701L;

	private static final long OTHER = 900_702L;

	private static final long INACTIVE = 900_703L;

	private static final long ACCOUNT = 900_711L;

	private static final SecureRandom RANDOM = new SecureRandom();

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	PassRepository passes;

	@Autowired
	JsonMapper jsonMapper;

	@BeforeEach
	void setUp() {
		upsertPartner(jdbc, PARTNER, true);
		upsertPartner(jdbc, OTHER, true);
		upsertPartner(jdbc, INACTIVE, false);
	}

	@Test
	@WithJeyUser(role = UserRole.PARTNER, id = ACCOUNT, partnerId = PARTNER)
	void 링크를_새로_만들면_새_토큰을_주고_예전_토큰의_해시는_남지_않는다() throws Exception {
		LinkToken old = LinkToken.generate(RANDOM);
		Pass pass = save(PARTNER, old, LocalDate.of(2026, 10, 14));

		JsonNode response = json(renew(pass.getId()).andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(pass.getId()))
			.andExpect(jsonPath("$.linkToken").value(matchesPattern("[A-Za-z0-9_-]{43}"))));
		String renewed = response.get("linkToken").asString();

		assertThat(renewed).isNotEqualTo(old.raw());
		assertThat(hashOf(pass)).isEqualTo(LinkToken.hashOf(renewed));
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dongtanpass_pass WHERE link_token_hash = ?", Integer.class,
				old.hash()))
			.isZero();
	}

	@Test
	@WithJeyUser(role = UserRole.PARTNER, id = ACCOUNT, partnerId = PARTNER)
	void 링크를_새로_만든_일은_감사_로그에_남는다() throws Exception {
		Pass pass = save(PARTNER, LinkToken.generate(RANDOM), LocalDate.of(2026, 10, 14));

		renew(pass.getId()).andExpect(status().isOk());

		assertThat(jdbc.queryForList(
				"SELECT actor_id, outcome FROM core_audit_log WHERE action = 'dongtanpass.pass-link-renewed' AND target_type = 'pass' AND target_id = ?",
				pass.getId()))
			.singleElement()
			.satisfies(row -> assertThat(row).containsEntry("actor_id", ACCOUNT).containsEntry("outcome", "DONE"));
	}

	// 학부모가 만료를 확인하는 데 쓸 수 있다.
	@Test
	@WithJeyUser(role = UserRole.PARTNER, id = ACCOUNT, partnerId = PARTNER)
	void 기간이_지난_패스도_링크를_새로_만들_수_있다() throws Exception {
		Pass pass = save(PARTNER, LinkToken.generate(RANDOM), LocalDate.of(2025, 1, 1));

		renew(pass.getId()).andExpect(status().isOk());
	}

	@Test
	@WithJeyUser(role = UserRole.PARTNER, id = ACCOUNT, partnerId = PARTNER)
	void 취소된_패스는_409이고_링크는_그대로다() throws Exception {
		LinkToken old = LinkToken.generate(RANDOM);
		Pass pass = save(PARTNER, old, LocalDate.of(2026, 10, 14));
		jdbc.update("UPDATE dongtanpass_pass SET status = 'CANCELED' WHERE id = ?", pass.getId());

		renew(pass.getId()).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_PASS_CANCELED"));

		assertThat(hashOf(pass)).isEqualTo(old.hash());
	}

	@Test
	@WithJeyUser(role = UserRole.PARTNER, id = ACCOUNT, partnerId = PARTNER)
	void 사용된_패스는_409이고_링크는_그대로다() throws Exception {
		LinkToken old = LinkToken.generate(RANDOM);
		Pass pass = save(PARTNER, old, LocalDate.of(2026, 10, 14));
		jdbc.update("UPDATE dongtanpass_pass SET status = 'REDEEMED' WHERE id = ?", pass.getId());

		renew(pass.getId()).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_PASS_ALREADY_REDEEMED"));

		assertThat(hashOf(pass)).isEqualTo(old.hash());
	}

	// 다른 학원의 패스는 없는 것과 같다. 있는지도 드러내지 않는다.
	@Test
	@WithJeyUser(role = UserRole.PARTNER, id = ACCOUNT, partnerId = PARTNER)
	void 다른_학원의_패스는_404이고_그대로다() throws Exception {
		LinkToken old = LinkToken.generate(RANDOM);
		Pass others = save(OTHER, old, LocalDate.of(2026, 10, 14));

		renew(others.getId()).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_PASS_NOT_FOUND"));

		assertThat(hashOf(others)).isEqualTo(old.hash());
		assertThat(jdbc.queryForObject(
				"SELECT COUNT(*) FROM core_audit_log WHERE action = 'dongtanpass.pass-link-renewed' AND target_id = ?",
				Integer.class, others.getId()))
			.isZero();
	}

	@Test
	@WithJeyUser(role = UserRole.PARTNER, id = ACCOUNT, partnerId = PARTNER)
	void 없는_패스는_404다() throws Exception {
		renew(Long.MAX_VALUE).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_PASS_NOT_FOUND"));
	}

	// 응답을 받지 못해 같은 키로 다시 보내면 처음의 토큰이 다시 나간다. 링크가 또 바뀌지 않는다.
	@Test
	@WithJeyUser(role = UserRole.PARTNER, id = ACCOUNT, partnerId = PARTNER)
	void 같은_멱등_키로_다시_보내면_처음의_토큰이_나가고_링크는_한_번만_바뀐다() throws Exception {
		Pass pass = save(PARTNER, LinkToken.generate(RANDOM), LocalDate.of(2026, 10, 14));
		String key = UUID.randomUUID().toString();

		String first = json(renew(pass.getId(), key).andExpect(status().isOk())).get("linkToken").asString();
		String second = json(renew(pass.getId(), key).andExpect(status().isOk())
			.andExpect(header().string("Idempotency-Replayed", "true"))).get("linkToken").asString();

		assertThat(second).isEqualTo(first);
		assertThat(hashOf(pass)).isEqualTo(LinkToken.hashOf(first));
	}

	@Test
	@WithJeyUser(role = UserRole.PARTNER, id = ACCOUNT, partnerId = INACTIVE)
	void 꺼진_학원은_링크를_새로_만들_수_없다() throws Exception {
		renew(1L).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_PARTNER_INACTIVE"));
	}

	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = 1)
	void 직원은_링크를_새로_만들_수_없다() throws Exception {
		renew(1L).andExpect(status().isForbidden());
	}

	private ResultActions renew(long passId) throws Exception {
		return renew(passId, UUID.randomUUID().toString());
	}

	private ResultActions renew(long passId, String key) throws Exception {
		return mockMvc.perform(post("/api/v1/dongtanpass/passes/{id}/link", passId).with(csrfToken(mockMvc))
			.with(idempotencyKey(key)));
	}

	private Pass save(long partnerId, LinkToken link, LocalDate issuedOn) {
		Instant issuedAt = issuedOn.atTime(10, 0).atZone(ZoneId.of("Asia/Seoul")).toInstant();
		return passes.saveAndFlush(Pass.issue(partnerId, PassNumber.generate(RANDOM), link.hash(), "김하윤",
				"phone-index", issuedAt, issuedOn, issuedOn, Money.of(150000), PaymentMethod.CARD));
	}

	private String hashOf(Pass pass) {
		return jdbc.queryForObject("SELECT link_token_hash FROM dongtanpass_pass WHERE id = ?", String.class,
				pass.getId());
	}

	private JsonNode json(ResultActions result) throws Exception {
		return jsonMapper.readTree(result.andReturn().getResponse().getContentAsString());
	}

}
