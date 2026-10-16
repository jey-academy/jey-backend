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

import static com.jey.TestCsrf.csrfToken;
import static com.jey.TestIdempotency.idempotencyKey;
import static com.jey.dongtanpass.DongtanpassFixtures.upsertPartner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 연계 학원은 쓰지 않은 패스를 취소한다. 결제 환불은 연계 학원이 따로 한다.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PassCancelApiTest {

	private static final long PARTNER = 900_801L;

	private static final long OTHER = 900_802L;

	private static final long INACTIVE = 900_803L;

	private static final long ACCOUNT = 900_811L;

	private static final SecureRandom RANDOM = new SecureRandom();

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	PassRepository passes;

	@BeforeEach
	void setUp() {
		upsertPartner(jdbc, PARTNER, true);
		upsertPartner(jdbc, OTHER, true);
		upsertPartner(jdbc, INACTIVE, false);
	}

	@Test
	@WithJeyUser(role = UserRole.PARTNER, id = ACCOUNT, partnerId = PARTNER)
	void 취소하면_취소_상태가_되고_장부에도_취소로_나온다() throws Exception {
		Pass pass = save(PARTNER, LocalDate.of(2026, 10, 14));

		cancel(pass.getId()).andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(pass.getId()))
			.andExpect(jsonPath("$.state").value("CANCELED"));

		assertThat(statusOf(pass)).isEqualTo("CANCELED");
		mockMvc.perform(get("/api/v1/dongtanpass/passes").param("status", "CANCELED"))
			.andExpect(jsonPath("$.items[?(@.id == " + pass.getId() + ")].state").value("CANCELED"));
	}

	@Test
	@WithJeyUser(role = UserRole.PARTNER, id = ACCOUNT, partnerId = PARTNER)
	void 취소한_일은_감사_로그에_남는다() throws Exception {
		Pass pass = save(PARTNER, LocalDate.of(2026, 10, 14));

		cancel(pass.getId()).andExpect(status().isOk());

		assertThat(jdbc.queryForList(
				"SELECT actor_id, outcome FROM core_audit_log WHERE action = 'dongtanpass.pass-canceled' AND target_type = 'pass' AND target_id = ?",
				pass.getId()))
			.singleElement()
			.satisfies(row -> assertThat(row).containsEntry("actor_id", ACCOUNT).containsEntry("outcome", "DONE"));
	}

	// 기간이 지난 패스의 환불을 정리할 수 있다.
	@Test
	@WithJeyUser(role = UserRole.PARTNER, id = ACCOUNT, partnerId = PARTNER)
	void 기간이_지난_패스도_취소할_수_있다() throws Exception {
		Pass pass = save(PARTNER, LocalDate.of(2025, 1, 1));

		cancel(pass.getId()).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("CANCELED"));
	}

	// 새 키로 다시 누른 취소다. 같은 키의 재전송은 아래 테스트처럼 처음의 응답을 받는다.
	@Test
	@WithJeyUser(role = UserRole.PARTNER, id = ACCOUNT, partnerId = PARTNER)
	void 취소한_패스를_다시_취소하면_409이고_링크도_새로_만들_수_없다() throws Exception {
		Pass pass = save(PARTNER, LocalDate.of(2026, 10, 14));
		cancel(pass.getId()).andExpect(status().isOk());

		cancel(pass.getId()).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_PASS_CANCELED"));
		mockMvc.perform(post("/api/v1/dongtanpass/passes/{id}/link", pass.getId()).with(csrfToken(mockMvc))
			.with(idempotencyKey()))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_PASS_CANCELED"));
	}

	@Test
	@WithJeyUser(role = UserRole.PARTNER, id = ACCOUNT, partnerId = PARTNER)
	void 사용된_패스는_취소할_수_없다() throws Exception {
		Pass pass = save(PARTNER, LocalDate.of(2026, 10, 14));
		jdbc.update("UPDATE dongtanpass_pass SET status = 'REDEEMED' WHERE id = ?", pass.getId());

		cancel(pass.getId()).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_PASS_ALREADY_REDEEMED"));

		assertThat(statusOf(pass)).isEqualTo("REDEEMED");
	}

	@Test
	@WithJeyUser(role = UserRole.PARTNER, id = ACCOUNT, partnerId = PARTNER)
	void 다른_학원의_패스는_404이고_그대로다() throws Exception {
		Pass others = save(OTHER, LocalDate.of(2026, 10, 14));

		cancel(others.getId()).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_PASS_NOT_FOUND"));

		assertThat(statusOf(others)).isEqualTo("ISSUED");
		assertThat(jdbc.queryForObject(
				"SELECT COUNT(*) FROM core_audit_log WHERE action = 'dongtanpass.pass-canceled' AND target_id = ?",
				Integer.class, others.getId()))
			.isZero();
	}

	@Test
	@WithJeyUser(role = UserRole.PARTNER, id = ACCOUNT, partnerId = PARTNER)
	void 없는_패스는_404다() throws Exception {
		cancel(Long.MAX_VALUE).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_PASS_NOT_FOUND"));
	}

	// 응답을 받지 못해 같은 키로 다시 보낸 취소는 409가 아니라 처음의 응답을 받는다.
	@Test
	@WithJeyUser(role = UserRole.PARTNER, id = ACCOUNT, partnerId = PARTNER)
	void 같은_멱등_키로_다시_보낸_취소는_처음의_응답을_받는다() throws Exception {
		Pass pass = save(PARTNER, LocalDate.of(2026, 10, 14));
		String key = UUID.randomUUID().toString();

		cancel(pass.getId(), key).andExpect(status().isOk());
		cancel(pass.getId(), key).andExpect(status().isOk())
			.andExpect(header().string("Idempotency-Replayed", "true"))
			.andExpect(jsonPath("$.state").value("CANCELED"));
	}

	@Test
	@WithJeyUser(role = UserRole.PARTNER, id = ACCOUNT, partnerId = INACTIVE)
	void 꺼진_학원은_취소할_수_없다() throws Exception {
		cancel(1L).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_PARTNER_INACTIVE"));
	}

	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = 1)
	void 직원은_취소할_수_없다() throws Exception {
		cancel(1L).andExpect(status().isForbidden());
	}

	private ResultActions cancel(long passId) throws Exception {
		return cancel(passId, UUID.randomUUID().toString());
	}

	private ResultActions cancel(long passId, String key) throws Exception {
		return mockMvc.perform(post("/api/v1/dongtanpass/passes/{id}/cancel", passId).with(csrfToken(mockMvc))
			.with(idempotencyKey(key)));
	}

	private Pass save(long partnerId, LocalDate issuedOn) {
		Instant issuedAt = issuedOn.atTime(10, 0).atZone(ZoneId.of("Asia/Seoul")).toInstant();
		return passes.saveAndFlush(Pass.issue(partnerId, PassNumber.generate(RANDOM), LinkToken.generate(RANDOM).hash(),
				"김하윤", "phone-index", issuedAt, ZoneId.of("Asia/Seoul"), issuedOn, Money.of(150000), PaymentMethod.CARD));
	}

	private String statusOf(Pass pass) {
		return jdbc.queryForObject("SELECT status FROM dongtanpass_pass WHERE id = ?", String.class, pass.getId());
	}

}
