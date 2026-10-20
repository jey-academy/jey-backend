package com.jey.dongtanpass.web;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.ThreadLocalRandom;

import com.jey.TestcontainersConfiguration;
import com.jey.WithJeyUser;
import com.jey.core.auth.api.UserRole;
import com.jey.core.shared.Money;
import com.jey.dongtanpass.domain.LinkToken;
import com.jey.dongtanpass.domain.Partner;
import com.jey.dongtanpass.domain.PartnerRepository;
import com.jey.dongtanpass.domain.Pass;
import com.jey.dongtanpass.domain.PassNumber;
import com.jey.dongtanpass.domain.PassRepository;
import com.jey.dongtanpass.domain.PassStatus;
import com.jey.dongtanpass.domain.PaymentMethod;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static com.jey.TestCsrf.csrfToken;
import static com.jey.TestIdempotency.idempotencyKey;
import static com.jey.dongtanpass.DongtanpassFixtures.upsertPartner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 관리자가 JEY 발급 패스의 학부모 링크를 새로 만들고, 쓰지 않은 패스를 취소한다.
// 연계 학원은 자기 경로(/passes)로 JEY 발급 패스를 보거나 고치지 못한다.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CampusPassChangeApiTest {

	private static final String PASSES = "/api/v1/dongtanpass/campus-passes";

	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

	private static final SecureRandom RANDOM = new SecureRandom();

	private static final long PROVIDER = 1L;

	private static final long PARTNER = 900_701L;

	@Autowired
	MockMvc mockMvc;

	@Autowired
	PassRepository passes;

	@Autowired
	PartnerRepository partners;

	@Autowired
	JdbcTemplate jdbc;

	@Test
	@WithJeyUser(role = UserRole.ADMIN)
	void 링크를_새로_만들면_새_토큰을_주고_옛_토큰은_쓸_수_없다() throws Exception {
		LinkToken old = LinkToken.generate(RANDOM);
		Pass pass = saveCampusPass(old);
		String oldHash = pass.getLinkTokenHash();

		String token = change(pass.getId(), "link").andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(pass.getId()))
			.andReturn()
			.getResponse()
			.getContentAsString()
			.replaceAll("(?s).*\"linkToken\"\\s*:\\s*\"([^\"]+)\".*", "$1");

		String hash = hashOf(pass);
		assertThat(hash).isNotEqualTo(oldHash).isEqualTo(LinkToken.hashOf(token));
		assertThat(auditCount("dongtanpass.pass-link-renewed", pass.getId())).isEqualTo(1);
		// 학부모 화면으로 본다: 새 토큰은 열리고 옛 토큰은 없는 링크와 같다.
		holderView(token).andExpect(status().isOk());
		holderView(old.raw()).andExpect(status().isNotFound());
	}

	private ResultActions holderView(String token) throws Exception {
		return mockMvc.perform(post("/api/v1/dongtanpass/holder/view").with(csrfToken(mockMvc))
			.contentType(org.springframework.http.MediaType.APPLICATION_JSON)
			.content("{\"token\":\"" + token + "\"}"));
	}

	@Test
	@WithJeyUser(role = UserRole.ADMIN)
	void 취소하면_CANCELED이고_다시_취소하면_409() throws Exception {
		Pass pass = saveCampusPass();

		change(pass.getId(), "cancel").andExpect(status().isOk()).andExpect(jsonPath("$.state").value("CANCELED"));

		assertThat(passes.findById(pass.getId()).orElseThrow().getStatus()).isEqualTo(PassStatus.CANCELED);
		assertThat(auditCount("dongtanpass.pass-canceled", pass.getId())).isEqualTo(1);
		change(pass.getId(), "cancel").andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_PASS_CANCELED"));
		change(pass.getId(), "link").andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_PASS_CANCELED"));
	}

	// 관리자의 JEY 발급 경로로 연계 학원 패스를 고치지 못한다(있는지도 드러내지 않는다).
	@Test
	@WithJeyUser(role = UserRole.ADMIN)
	void 연계_학원_패스_ID로는_404() throws Exception {
		Pass partnerPass = savePartnerPass();

		change(partnerPass.getId(), "link").andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_PASS_NOT_FOUND"));
		change(partnerPass.getId(), "cancel").andExpect(status().isNotFound());
		assertThat(passes.findById(partnerPass.getId()).orElseThrow().getStatus()).isEqualTo(PassStatus.ISSUED);
	}

	@Test
	@WithJeyUser(role = UserRole.PARTNER, partnerId = PARTNER)
	void 연계_학원은_자기_경로로_JEY_발급_패스를_보거나_고치지_못한다() throws Exception {
		upsertPartner(jdbc, PARTNER, true);
		Pass campus = saveCampusPass();

		mockMvc.perform(post("/api/v1/dongtanpass/passes/{id}/link", campus.getId()).with(csrfToken(mockMvc))
			.with(idempotencyKey())).andExpect(status().isNotFound());
		mockMvc.perform(post("/api/v1/dongtanpass/passes/{id}/cancel", campus.getId()).with(csrfToken(mockMvc))
			.with(idempotencyKey())).andExpect(status().isNotFound());
		mockMvc.perform(get("/api/v1/dongtanpass/passes"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[?(@.id == %d)]".formatted(campus.getId())).isEmpty());
		assertThat(passes.findById(campus.getId()).orElseThrow().getStatus()).isEqualTo(PassStatus.ISSUED);
	}

	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = PROVIDER)
	void 직원은_링크도_취소도_못한다() throws Exception {
		Pass pass = saveCampusPass();

		change(pass.getId(), "link").andExpect(status().isForbidden());
		change(pass.getId(), "cancel").andExpect(status().isForbidden());
	}

	private ResultActions change(long passId, String action) throws Exception {
		return mockMvc.perform(post(PASSES + "/{id}/" + action, passId).with(csrfToken(mockMvc))
			.with(idempotencyKey()));
	}

	private String hashOf(Pass pass) {
		return passes.findById(pass.getId()).orElseThrow().getLinkTokenHash();
	}

	private long auditCount(String action, long passId) {
		return jdbc.queryForObject(
				"SELECT COUNT(*) FROM core_audit_log WHERE action = ? AND target_type = 'pass' AND target_id = ?",
				Long.class, action, passId);
	}

	private Pass saveCampusPass() {
		return saveCampusPass(LinkToken.generate(RANDOM));
	}

	private Pass saveCampusPass(LinkToken link) {
		Instant now = Instant.now();
		return passes.saveAndFlush(Pass.issueAtCampus(PROVIDER, ThreadLocalRandom.current().nextLong(1_000_000_000L,
				Long.MAX_VALUE / 2), PassNumber.generate(RANDOM), link.hash(), "김하윤",
				"phone-index", now, SEOUL, LocalDate.ofInstant(now, SEOUL), Money.of(280000), PaymentMethod.CARD,
				new BigDecimal("0.05")));
	}

	private Pass savePartnerPass() {
		Instant now = Instant.now();
		long partnerId = partners.saveAndFlush(Partner.create("JEY변경-" + ThreadLocalRandom.current().nextInt()))
			.getId();
		return passes.saveAndFlush(Pass.issue(partnerId, PassNumber.generate(RANDOM), LinkToken.generate(RANDOM).hash(),
				"이연계", "phone-index", now, SEOUL, LocalDate.ofInstant(now, SEOUL), Money.of(150000),
				PaymentMethod.CARD));
	}

}
