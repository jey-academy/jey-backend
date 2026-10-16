package com.jey.dongtanpass.web;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.ThreadLocalRandom;

import com.jey.TestcontainersConfiguration;
import com.jey.core.shared.Money;
import com.jey.core.shared.PhoneNumber;
import com.jey.core.shared.PhoneProtector;
import com.jey.dongtanpass.domain.LinkToken;
import com.jey.dongtanpass.domain.Pass;
import com.jey.dongtanpass.domain.PassNumber;
import com.jey.dongtanpass.domain.PassRepository;
import com.jey.dongtanpass.domain.PaymentMethod;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

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

// 학부모가 학생 이름과 전체 번호로 링크를 다시 받는다(ADR-0035 개정). 로그인하지 않고, 이름과 번호는 본문으로만 받는다.
// 틀린 시도는 "번호 + 접속 주소"(5분 10번)와 "접속 주소"(5분 60번)로 센다. 테스트마다 다른 번호와 접속 주소를 쓴다.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class HolderRelinkApiTest {

	private static final String RELINK = "/api/v1/dongtanpass/holder/relink";

	private static final String VIEW = "/api/v1/dongtanpass/holder/view";

	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

	private static final long PARTNER = 901_201L;

	private static final String NAME = "김하윤";

	private static final SecureRandom RANDOM = new SecureRandom();

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	PassRepository passes;

	@Autowired
	PhoneProtector phoneProtector;

	@Autowired
	Clock clock;

	private String phone;

	private String address;

	@BeforeEach
	void setUp() {
		// 앞 테스트가 스레드에 남긴 로그인 정보를 MockMvc가 집어 들지 않게 한다(로그인 없는 요청을 본다).
		TestSecurityContextHolder.clearContext();
		upsertPartner(jdbc, PARTNER, true);
		phone = newPhone();
		address = newAddress();
	}

	@Test
	void 맞는_이름과_번호면_새_링크_토큰을_주고_세션을_만들지_않는다() throws Exception {
		LinkToken old = LinkToken.generate(RANDOM);
		Pass pass = save(old, NAME);

		String body = relink(NAME, phone, address).andExpect(status().isOk())
			.andExpect(jsonPath("$.linkToken").isString())
			.andExpect(content().string(not(containsString(NAME))))
			.andExpect(content().string(not(containsString(pass.getPassNo()))))
			.andExpect(header().doesNotExist("Set-Cookie"))
			.andReturn()
			.getResponse()
			.getContentAsString();
		String token = JsonPath.read(body, "$.linkToken");

		assertThat(LinkToken.isWellFormed(token)).isTrue();
		view(token).andExpect(status().isOk()).andExpect(jsonPath("$.passNo").value(pass.getPassNo()));
		view(old.raw()).andExpect(status().isNotFound());
	}

	// 번호는 하이픈이나 공백이 섞여도 같은 번호다.
	@Test
	void 번호는_구분자와_무관하다() throws Exception {
		save(LinkToken.generate(RANDOM), NAME);
		String dashed = phone.substring(0, 3) + "-" + phone.substring(3, 7) + "-" + phone.substring(7);

		relink("김 하윤", dashed, address).andExpect(status().isOk());
	}

	// 코드만 같고 문구나 다른 칸이 다르면 어느 쪽이 틀렸는지 드러난다. 요청 ID를 맞춰 본문 전체를 비교한다.
	@Test
	void 이름만_틀림과_번호만_틀림과_둘_다_틀림의_404_본문이_같다() throws Exception {
		save(LinkToken.generate(RANDOM), NAME);

		String wrongName = notFoundBody("김하은", phone);

		assertThat(notFoundBody(NAME, newPhone())).isEqualTo(wrongName);
		assertThat(notFoundBody("김하은", newPhone())).isEqualTo(wrongName);
		// 요청 검증을 지나오는 공백뿐인 이름(줄바꿈하지 않는 공백, 전각 공백)도 같은 404다
		assertThat(notFoundBody("\u00A0\u3000", phone)).isEqualTo(wrongName);
		assertThat(wrongName).contains("DONGTANPASS_PASS_NOT_FOUND");
	}

	@ParameterizedTest(name = "{0} → {1}")
	@CsvSource({ "REDEEMED,0,DONGTANPASS_PASS_ALREADY_REDEEMED", "CANCELED,0,DONGTANPASS_PASS_CANCELED",
			"ISSUED,-1,DONGTANPASS_PASS_EXPIRED" })
	void 맞았지만_쓸_수_없는_패스는_그_사정을_409로_알린다(String status, int expiresInDays, String code) throws Exception {
		Pass pass = save(LinkToken.generate(RANDOM), NAME);
		jdbc.update("UPDATE dongtanpass_pass SET status = ?, expires_on = ? WHERE id = ?", status,
				today().plusDays(expiresInDays), pass.getId());

		relink(NAME, phone, address).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value(code))
			.andExpect(header().doesNotExist("Set-Cookie"));
	}

	// 막힌 동안은 맞는 이름도 확인하지 않는다. 같은 와이파이의 다른 학부모(다른 번호)는 막히지 않는다.
	@Test
	void 한_번호로_10번_틀리면_5분_동안_막고_다시_할_시간을_알린다() throws Exception {
		save(LinkToken.generate(RANDOM), NAME);
		for (int i = 0; i < 10; i++) {
			relink("틀린이름", phone, address).andExpect(status().isNotFound());
		}

		relink(NAME, phone, address).andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_HOLDER_RELINK_LIMITED"))
			.andExpect(header().exists("Retry-After"));
		relink("틀린이름", newPhone(), address).andExpect(status().isNotFound());
		relink(NAME, phone, newAddress()).andExpect(status().isOk());
	}

	@Test
	void 한_주소에서_번호를_바꿔_60번_틀리면_다른_번호도_막는다() throws Exception {
		for (int i = 0; i < 60; i++) {
			relink("틀린이름", newPhone(), address).andExpect(status().isNotFound());
		}

		save(LinkToken.generate(RANDOM), NAME);
		relink(NAME, phone, address).andExpect(status().isTooManyRequests());
	}

	@ParameterizedTest
	@ValueSource(strings = { "{\"guardianPhone\":\"01012345678\"}", "{\"studentName\":\"\",\"guardianPhone\":\"01012345678\"}",
			"{\"studentName\":\"김하윤\"}", "{\"studentName\":\"김하윤\",\"guardianPhone\":\"02-123-4567\"}",
			"{\"studentName\":\"김하윤\",\"guardianPhone\":1012345678}" })
	void 칸이_없거나_틀리면_400이다(String body) throws Exception {
		send(body, address).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"));
	}

	// 형식이 틀린 요청은 패스를 찾아보지 않았으므로 틀린 시도로 세지 않는다.
	@Test
	void 형식이_틀린_요청은_세지_않는다() throws Exception {
		for (int i = 0; i < 12; i++) {
			send("{\"studentName\":\"김하윤\",\"guardianPhone\":\"02-123-4567\"}", address)
				.andExpect(status().isBadRequest());
		}

		relink("틀린이름", phone, address).andExpect(status().isNotFound());
	}

	// 조건의 오타가 엉뚱한 응답으로 이어지지 않게 한다.
	@Test
	void 모르는_칸이_있으면_400이다() throws Exception {
		send("{\"studentName\":\"김하윤\",\"guardianPhone\":\"" + phone + "\",\"name\":\"x\"}", address)
			.andExpect(status().isBadRequest());
	}

	// 이름과 번호를 주소에 실으면 이미 앞단의 접근 로그에 남았다. 읽지 않고 거부해서 드러나게 한다.
	@Test
	void 주소에_쿼리_문자열이_있으면_400이다() throws Exception {
		save(LinkToken.generate(RANDOM), NAME);

		mockMvc
			.perform(withAddress(post(RELINK + "?studentName=x"), address).with(csrfToken(mockMvc))
				.contentType(MediaType.APPLICATION_JSON)
				.content(body(NAME, phone)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("COMMON_BAD_REQUEST"));
	}

	// 로그인하지 않아도 CSRF는 본다. 화면은 먼저 CSRF 토큰을 받는다.
	@Test
	void CSRF_토큰이_없으면_403이다() throws Exception {
		mockMvc
			.perform(withAddress(post(RELINK), address).contentType(MediaType.APPLICATION_JSON)
				.content(body(NAME, phone)))
			.andExpect(status().isForbidden());
	}

	@Test
	void 로그에_이름과_번호와_토큰_원문이_없다(CapturedOutput output) throws Exception {
		save(LinkToken.generate(RANDOM), "로그확인이름");

		String body = relink("로그확인이름", phone, address).andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		relink("로그확인틀린이름", phone, address).andExpect(status().isNotFound());

		String token = JsonPath.read(body, "$.linkToken");
		assertThat(output.getAll()).doesNotContain(token)
			.doesNotContain("로그확인")
			.doesNotContain(phone)
			.doesNotContain(phone.substring(3));
	}

	private String notFoundBody(String name, String guardianPhone) throws Exception {
		return mockMvc
			.perform(withAddress(post(RELINK), address).with(csrfToken(mockMvc))
				.header("X-Request-Id", "holder-relink-404")
				.contentType(MediaType.APPLICATION_JSON)
				.content(body(name, guardianPhone)))
			.andExpect(status().isNotFound())
			.andReturn()
			.getResponse()
			.getContentAsString();
	}

	private ResultActions relink(String name, String guardianPhone, String from) throws Exception {
		return send(body(name, guardianPhone), from);
	}

	private ResultActions send(String body, String from) throws Exception {
		return mockMvc.perform(withAddress(post(RELINK), from).with(csrfToken(mockMvc))
			.contentType(MediaType.APPLICATION_JSON)
			.content(body));
	}

	private ResultActions view(String token) throws Exception {
		return mockMvc.perform(post(VIEW).with(csrfToken(mockMvc))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"token\":\"" + token + "\"}"));
	}

	private static MockHttpServletRequestBuilder withAddress(MockHttpServletRequestBuilder request, String from) {
		return request.with(r -> {
			r.setRemoteAddr(from);
			return r;
		});
	}

	private static String body(String name, String guardianPhone) {
		return "{\"studentName\":\"" + name + "\",\"guardianPhone\":\"" + guardianPhone + "\"}";
	}

	private Pass save(LinkToken link, String name) {
		return passes.saveAndFlush(Pass.issue(PARTNER, PassNumber.generate(RANDOM), link.hash(), name,
				phoneProtector.fullIndex(PhoneNumber.of(phone)), clock.instant(), SEOUL, today(), Money.of(150000),
				PaymentMethod.CARD));
	}

	private LocalDate today() {
		return LocalDate.now(clock);
	}

	// 010-9로 시작하는 무작위 번호. 테스트마다 다른 번호라 다른 테스트의 패스가 후보에 섞이지 않는다.
	private static String newPhone() {
		return "0109" + String.format("%07d", ThreadLocalRandom.current().nextInt(10_000_000));
	}

	private static String newAddress() {
		ThreadLocalRandom random = ThreadLocalRandom.current();
		return "10." + random.nextInt(256) + "." + random.nextInt(256) + "." + random.nextInt(1, 255);
	}

}
