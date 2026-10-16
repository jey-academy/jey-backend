package com.jey.dongtanpass.web;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.stream.Stream;

import com.jey.TestcontainersConfiguration;
import com.jey.WithJeyUser;
import com.jey.core.auth.api.UserRole;
import com.jey.core.shared.Money;
import com.jey.dongtanpass.application.ConsentNotice;
import com.jey.dongtanpass.application.PassBenefits;
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
import org.junit.jupiter.params.provider.MethodSource;
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

import static com.jey.TestCsrf.csrfToken;
import static com.jey.dongtanpass.DongtanpassFixtures.upsertPartner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 학부모는 로그인하지 않고 링크 토큰으로 패스를 연다. 토큰은 본문으로 받는다.
// 없는 토큰, 모양이 틀린 토큰, 링크를 새로 만들어 막힌 토큰은 모두 같은 404다(어느 쪽인지 드러내지 않는다).
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class HolderViewApiTest {

	private static final String VIEW = "/api/v1/dongtanpass/holder/view";

	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

	private static final long PARTNER = 901_001L;

	private static final long INACTIVE = 901_002L;

	private static final SecureRandom RANDOM = new SecureRandom();

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	PassRepository passes;

	@Autowired
	PassBenefits passBenefits;

	@Autowired
	Clock clock;

	@BeforeEach
	void setUp() {
		// 앞 테스트가 스레드에 남긴 로그인 정보를 MockMvc가 집어 들지 않게 한다(로그인 없는 요청을 본다).
		TestSecurityContextHolder.clearContext();
		upsertPartner(jdbc, PARTNER, true);
		upsertPartner(jdbc, INACTIVE, false);
	}

	@Test
	void 링크_토큰으로_열면_가린_이름과_상태와_할인과_고지_문구를_준다() throws Exception {
		LinkToken link = LinkToken.generate(RANDOM);
		Pass pass = save(PARTNER, link, "김하윤");

		view(link.raw()).andExpect(status().isOk())
			.andExpect(jsonPath("$.passNo").value(pass.getPassNo()))
			.andExpect(jsonPath("$.partnerName").value("테스트학원-" + PARTNER))
			.andExpect(jsonPath("$.studentName").value("김*윤"))
			.andExpect(jsonPath("$.expiresOn").value(today().plusMonths(1).toString()))
			.andExpect(jsonPath("$.state").value("ISSUED"))
			.andExpect(jsonPath("$.discountRate").value(0.05))
			.andExpect(jsonPath("$.campusName").value(passBenefits.current().campusName()))
			.andExpect(jsonPath("$.noticeVersion").value(ConsentNotice.CURRENT.version()))
			.andExpect(jsonPath("$.noticeText").value(ConsentNotice.CURRENT.text()))
			.andExpect(content().string(not(containsString("김하윤"))))
			.andExpect(content().string(not(containsString(link.raw()))))
			// 로그인 없는 요청은 세션을 만들지 않는다
			.andExpect(header().doesNotExist("Set-Cookie"));
	}

	// 만료는 저장하지 않고 오늘로 계산한다. 화면은 넷을 따로 그린다.
	@ParameterizedTest(name = "{0}, 만료일 오늘{1}일 → {2}")
	@CsvSource({ "ISSUED,0,ISSUED", "ISSUED,-1,EXPIRED", "REDEEMED,-1,REDEEMED", "CANCELED,10,CANCELED" })
	void 보이는_상태_넷을_구분한다(String stored, int expiresInDays, String shown) throws Exception {
		LinkToken link = LinkToken.generate(RANDOM);
		Pass pass = save(PARTNER, link, "김하윤");
		jdbc.update("UPDATE dongtanpass_pass SET status = ?, expires_on = ? WHERE id = ?", stored,
				today().plusDays(expiresInDays), pass.getId());

		view(link.raw()).andExpect(status().isOk()).andExpect(jsonPath("$.state").value(shown));
	}

	@Test
	void 링크를_새로_만든_뒤의_옛_토큰은_404다() throws Exception {
		LinkToken old = LinkToken.generate(RANDOM);
		Pass pass = save(PARTNER, old, "김하윤");
		jdbc.update("UPDATE dongtanpass_pass SET link_token_hash = ? WHERE id = ?",
				LinkToken.generate(RANDOM).hash(), pass.getId());

		view(old.raw()).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_PASS_NOT_FOUND"));
	}

	static Stream<String> malformedTokens() {
		String a42 = "A".repeat(42);
		return Stream.of("x", a42, a42 + "AA", a42 + "=", " " + a42 + "A", a42 + "+",
				"A".repeat(10_000), LinkToken.generate(RANDOM).raw().substring(1) + "가");
	}

	@ParameterizedTest
	@MethodSource("malformedTokens")
	void 모양이_틀린_토큰은_없는_토큰과_같은_404다(String token) throws Exception {
		view(token).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_PASS_NOT_FOUND"));
	}

	@Test
	void 없는_토큰은_404다() throws Exception {
		view(LinkToken.generate(RANDOM).raw()).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_PASS_NOT_FOUND"));
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = { "{}", "{\"token\":null}", "{\"token\":\"\"}", "{\"token\":1}" })
	void 토큰이_없거나_글자가_아니면_그_칸의_400이다(String body) throws Exception {
		send(body).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"))
			.andExpect(jsonPath("$.errors[0].field").value("token"));
	}

	@Test
	void 모르는_칸이_있으면_400이다() throws Exception {
		send("{\"token\":\"" + LinkToken.generate(RANDOM).raw() + "\",\"tokn\":\"x\"}")
			.andExpect(status().isBadRequest());
	}

	// 발급된 패스는 학원을 꺼도 쓸 수 있다(ADR-0034).
	@Test
	void 꺼진_학원이_발급한_패스도_열린다() throws Exception {
		LinkToken link = LinkToken.generate(RANDOM);
		save(INACTIVE, link, "김하윤");

		view(link.raw()).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("ISSUED"));
	}

	// 로그인하지 않아도 CSRF는 본다. 화면은 먼저 CSRF 토큰을 받는다.
	@Test
	void CSRF_토큰이_없으면_403이다() throws Exception {
		mockMvc.perform(post(VIEW).contentType(MediaType.APPLICATION_JSON)
			.content("{\"token\":\"" + LinkToken.generate(RANDOM).raw() + "\"}"))
			.andExpect(status().isForbidden());
	}

	// 공개 경로라 로그인한 계정이 열어도 된다(역할과 무관).
	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = 1)
	void 로그인한_계정도_열_수_있다() throws Exception {
		LinkToken link = LinkToken.generate(RANDOM);
		save(PARTNER, link, "김하윤");

		view(link.raw()).andExpect(status().isOk());
	}

	@Test
	void 로그에_토큰_원문과_실명이_없다(CapturedOutput output) throws Exception {
		LinkToken link = LinkToken.generate(RANDOM);
		save(PARTNER, link, "로그확인이름");
		LinkToken missing = LinkToken.generate(RANDOM);

		view(link.raw()).andExpect(status().isOk());
		view(missing.raw()).andExpect(status().isNotFound());

		assertThat(output.getAll()).doesNotContain(link.raw()).doesNotContain(missing.raw()).doesNotContain("로그확인이름");
	}

	private ResultActions view(String token) throws Exception {
		return send("{\"token\":\"" + token + "\"}");
	}

	private ResultActions send(String body) throws Exception {
		return mockMvc.perform(
				post(VIEW).with(csrfToken(mockMvc)).contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private Pass save(long partnerId, LinkToken link, String name) {
		return passes.saveAndFlush(Pass.issue(partnerId, PassNumber.generate(RANDOM), link.hash(), name, "phone-index",
				clock.instant(), SEOUL, today(), Money.of(150000), PaymentMethod.CARD));
	}

	private LocalDate today() {
		return LocalDate.now(clock);
	}

}
