package com.jey.dongtanpass.web;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import com.jey.TestcontainersConfiguration;
import com.jey.WithJeyUser;
import com.jey.core.auth.api.UserRole;
import com.jey.core.shared.Money;
import com.jey.dongtanpass.application.PassQrCodes;
import com.jey.dongtanpass.domain.LinkToken;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static com.jey.TestCsrf.csrfToken;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// JEY가 발급한 패스는 연계 학원에서 쓴다. 동탄관 데스크(확인·본인 확인·사용 처리)는 받지 않고 정해진 409로 답한다.
// 데스크의 한도는 계정마다 세므로 계정 ID를 테스트마다 바꾼다(id 값).
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CampusPassAtDeskApiTest {

	private static final String PASSES = "/api/v1/dongtanpass/passes";

	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

	private static final SecureRandom RANDOM = new SecureRandom();

	private static final long PROVIDER = 1L;

	private static final String WRONG_DIRECTION = "DONGTANPASS_PASS_FOR_PARTNER_USE";

	@Autowired
	MockMvc mockMvc;

	@Autowired
	PassRepository passes;

	@Autowired
	PassQrCodes passQrCodes;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	Clock clock;

	@Test
	@WithJeyUser(id = 9_020_000_001L, role = UserRole.STAFF, campusId = PROVIDER)
	void 패스_번호로_확인하면_409() throws Exception {
		Pass pass = saveCampusPass();

		send("/verify", "{\"passNo\":\"" + pass.getPassNo() + "\"}").andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value(WRONG_DIRECTION));
	}

	@Test
	@WithJeyUser(id = 9_020_000_002L, role = UserRole.STAFF, campusId = PROVIDER)
	void QR로_확인하면_409() throws Exception {
		Pass pass = saveCampusPass();
		String qr = passQrCodes.issue(pass.getId(), clock.instant()).token();

		send("/verify", "{\"qrToken\":\"" + qr + "\"}").andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value(WRONG_DIRECTION));
	}

	@Test
	@WithJeyUser(id = 9_020_000_003L, role = UserRole.STAFF, campusId = PROVIDER)
	void 본인_확인은_409() throws Exception {
		Pass pass = saveCampusPass();

		send("/" + pass.getId() + "/holder-check", "{\"studentName\":\"김하윤\",\"guardianPhones\":[\"010-1234-5678\"]}")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value(WRONG_DIRECTION));
	}

	@Test
	@WithJeyUser(id = 9_020_000_004L, role = UserRole.ADMIN)
	void 사용_처리는_409이고_사용_기록이_생기지_않는다() throws Exception {
		Pass pass = saveCampusPass();

		send("/" + pass.getId() + "/redeem", "{\"studentId\":" + pass.getStudentId() + "}",
				UUID.randomUUID().toString())
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value(WRONG_DIRECTION));

		assertThat(passes.findById(pass.getId()).orElseThrow().getStatus()).isEqualTo(PassStatus.ISSUED);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dongtanpass_redemption WHERE pass_id = ?", Long.class,
				pass.getId()))
			.isZero();
	}

	private ResultActions send(String path, String body) throws Exception {
		return mockMvc.perform(post(PASSES + path).with(csrfToken(mockMvc))
			.contentType(MediaType.APPLICATION_JSON)
			.content(body));
	}

	private ResultActions send(String path, String body, String idempotencyKey) throws Exception {
		return mockMvc.perform(post(PASSES + path).with(csrfToken(mockMvc))
			.header("Idempotency-Key", idempotencyKey)
			.contentType(MediaType.APPLICATION_JSON)
			.content(body));
	}

	private Pass saveCampusPass() {
		return passes.saveAndFlush(Pass.issueAtCampus(PROVIDER,
				ThreadLocalRandom.current().nextLong(1_000_000_000L, Long.MAX_VALUE / 2), PassNumber.generate(RANDOM),
				LinkToken.generate(RANDOM).hash(), "김하윤", "phone-index", clock.instant(), SEOUL,
				LocalDate.now(clock), Money.of(280000), PaymentMethod.CARD, new BigDecimal("0.05")));
	}

}
