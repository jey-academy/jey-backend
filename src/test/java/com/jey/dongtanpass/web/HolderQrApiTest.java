package com.jey.dongtanpass.web;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

import com.jey.TestcontainersConfiguration;
import com.jey.core.shared.Money;
import com.jey.dongtanpass.application.PassQrCodes;
import com.jey.dongtanpass.domain.LinkToken;
import com.jey.dongtanpass.domain.Pass;
import com.jey.dongtanpass.domain.PassNumber;
import com.jey.dongtanpass.domain.PassRepository;
import com.jey.dongtanpass.domain.PaymentMethod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.TestSecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static com.jey.TestCsrf.csrfToken;
import static com.jey.dongtanpass.DongtanpassFixtures.upsertPartner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 학부모는 데스크에 보여 줄 서명 QR 토큰을 받는다(ADR-0010). 쓸 수 있는 패스에만 준다.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class HolderQrApiTest {

	private static final String QR = "/api/v1/dongtanpass/holder/qr";

	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

	private static final long PARTNER = 901_101L;

	private static final long INACTIVE = 901_102L;

	private static final SecureRandom RANDOM = new SecureRandom();

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	PassRepository passes;

	@Autowired
	PassQrCodes passQrCodes;

	@Autowired
	JsonMapper jsonMapper;

	@Autowired
	Clock clock;

	@BeforeEach
	void setUp() {
		TestSecurityContextHolder.clearContext();
		upsertPartner(jdbc, PARTNER, true);
		upsertPartner(jdbc, INACTIVE, false);
	}

	@Test
	void 쓸_수_있는_패스는_60초짜리_QR_토큰을_받고_그_토큰은_그_패스로_확인된다() throws Exception {
		LinkToken link = LinkToken.generate(RANDOM);
		Pass pass = save(PARTNER, link);
		Instant before = clock.instant();

		JsonNode response = json(qr(link.raw()).andExpect(status().isOk()));
		Instant expiresAt = Instant.parse(response.get("expiresAt").asString());

		assertThat(expiresAt).isBetween(before.truncatedTo(ChronoUnit.SECONDS).plus(PassQrCodes.TTL),
				clock.instant().plus(PassQrCodes.TTL));
		assertThat(passQrCodes.verify(response.get("qrToken").asString(), clock.instant()))
			.hasValueSatisfying(verified -> {
				assertThat(verified.passId()).isEqualTo(pass.getId());
				assertThat(verified.expiresAt()).isEqualTo(expiresAt);
			});
	}

	@Test
	void 부를_때마다_다른_토큰이다() throws Exception {
		LinkToken link = LinkToken.generate(RANDOM);
		save(PARTNER, link);

		String first = json(qr(link.raw()).andExpect(status().isOk())).get("qrToken").asString();
		String second = json(qr(link.raw()).andExpect(status().isOk())).get("qrToken").asString();

		assertThat(first).isNotEqualTo(second);
	}

	// 열람은 상태와 무관하게 되지만 QR은 쓸 수 있는 패스에만 준다. 만료일 당일은 쓸 수 있다.
	@ParameterizedTest(name = "{0}, 만료일 오늘{1}일 → {2}")
	@CsvSource({ "ISSUED,0,200", "ISSUED,-1,DONGTANPASS_PASS_EXPIRED", "CANCELED,10,DONGTANPASS_PASS_CANCELED",
			"REDEEMED,10,DONGTANPASS_PASS_ALREADY_REDEEMED" })
	void 쓸_수_없는_패스는_상태에_맞는_409다(String stored, int expiresInDays, String expected) throws Exception {
		LinkToken link = LinkToken.generate(RANDOM);
		Pass pass = save(PARTNER, link);
		jdbc.update("UPDATE dongtanpass_pass SET status = ?, expires_on = ? WHERE id = ?", stored,
				today().plusDays(expiresInDays), pass.getId());

		if ("200".equals(expected)) {
			qr(link.raw()).andExpect(status().isOk());
			return;
		}
		qr(link.raw()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(expected));
	}

	@Test
	void 없거나_모양이_틀린_토큰은_404다() throws Exception {
		qr(LinkToken.generate(RANDOM).raw()).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_PASS_NOT_FOUND"));
		qr("x").andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("DONGTANPASS_PASS_NOT_FOUND"));
	}

	// 공개 경로가 세션을 만들면 로그인 없이 세션 저장소를 채울 수 있다.
	@Test
	void 로그인_없는_요청은_성공이든_404든_세션을_만들지_않는다() throws Exception {
		LinkToken link = LinkToken.generate(RANDOM);
		save(PARTNER, link);

		qr(link.raw()).andExpect(status().isOk()).andExpect(header().doesNotExist("Set-Cookie"));
		qr(LinkToken.generate(RANDOM).raw()).andExpect(status().isNotFound())
			.andExpect(header().doesNotExist("Set-Cookie"));
	}

	@Test
	void 링크를_새로_만든_뒤의_옛_토큰은_404다() throws Exception {
		LinkToken old = LinkToken.generate(RANDOM);
		Pass pass = save(PARTNER, old);
		jdbc.update("UPDATE dongtanpass_pass SET link_token_hash = ? WHERE id = ?",
				LinkToken.generate(RANDOM).hash(), pass.getId());

		qr(old.raw()).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_PASS_NOT_FOUND"));
	}

	@Test
	void 꺼진_학원이_발급한_패스도_QR을_받는다() throws Exception {
		LinkToken link = LinkToken.generate(RANDOM);
		save(INACTIVE, link);

		qr(link.raw()).andExpect(status().isOk());
	}

	@Test
	void CSRF_토큰이_없으면_403이다() throws Exception {
		mockMvc.perform(post(QR).contentType(MediaType.APPLICATION_JSON)
			.content("{\"token\":\"" + LinkToken.generate(RANDOM).raw() + "\"}"))
			.andExpect(status().isForbidden());
	}

	@Test
	void 로그에_링크_토큰과_QR_토큰이_없다(CapturedOutput output) throws Exception {
		LinkToken link = LinkToken.generate(RANDOM);
		save(PARTNER, link);

		String qrToken = json(qr(link.raw()).andExpect(status().isOk())).get("qrToken").asString();

		assertThat(output.getAll()).doesNotContain(link.raw()).doesNotContain(qrToken);
	}

	private ResultActions qr(String token) throws Exception {
		return mockMvc.perform(post(QR).with(csrfToken(mockMvc))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"token\":\"" + token + "\"}"));
	}

	private Pass save(long partnerId, LinkToken link) {
		return passes.saveAndFlush(Pass.issue(partnerId, PassNumber.generate(RANDOM), link.hash(), "김하윤", "phone-index",
				clock.instant(), SEOUL, today(), Money.of(150000), PaymentMethod.CARD));
	}

	private LocalDate today() {
		return LocalDate.now(clock);
	}

	private JsonNode json(ResultActions result) throws Exception {
		return jsonMapper.readTree(result.andReturn().getResponse().getContentAsString());
	}

}
