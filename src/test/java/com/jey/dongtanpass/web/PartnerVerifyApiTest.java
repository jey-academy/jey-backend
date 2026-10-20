package com.jey.dongtanpass.web;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import com.jey.TestcontainersConfiguration;
import com.jey.core.auth.api.AuthenticatedUser;
import com.jey.core.auth.api.UserRole;
import com.jey.core.shared.Money;
import com.jey.dongtanpass.application.PassBenefits;
import com.jey.dongtanpass.application.PassQrCodes;
import com.jey.dongtanpass.domain.LinkToken;
import com.jey.dongtanpass.domain.PartnerRedemptionRepository;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import static com.jey.TestCsrf.csrfToken;
import static com.jey.dongtanpass.DongtanpassFixtures.upsertPartner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.securityContext;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 연계 학원이 학부모의 QR이나 패스 번호로 JEY 발급 패스를 확인한다(사용 처리의 첫 단계).
// 연계 학원 발급 패스와 이 학원에서 이미 할인받은 학생은 토큰을 쓰기 전에 거부한다(다른 곳에서 그대로 쓸 수 있게).
// 횟수 한도는 계정마다 세므로 한도를 보는 요청은 테스트마다 새 계정으로 보낸다.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PartnerVerifyApiTest {

	private static final String VERIFY = "/api/v1/dongtanpass/partner-redemptions/verify";

	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

	private static final SecureRandom RANDOM = new SecureRandom();

	private static final long PARTNER = 902_001L;

	private static final long OTHER_PARTNER = 902_002L;

	private static final long INACTIVE = 902_003L;

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	PassRepository passes;

	@Autowired
	PartnerRedemptionRepository partnerRedemptions;

	@Autowired
	PassQrCodes passQrCodes;

	@Autowired
	PassBenefits passBenefits;

	@Autowired
	TransactionTemplate transaction;

	@Autowired
	Clock clock;

	@BeforeEach
	void setUp() {
		upsertPartner(jdbc, PARTNER, true);
		upsertPartner(jdbc, OTHER_PARTNER, true);
		upsertPartner(jdbc, INACTIVE, false);
	}

	@Test
	void QR로_확인하면_패스_ID와_가린_이름과_발급_지점과_할인율을_준다() throws Exception {
		Pass pass = saveCampusPass(newStudentId(), new BigDecimal("0.0725"));

		verify(qrBody(pass), PARTNER).andExpect(status().isOk())
			.andExpect(jsonPath("$.passId").value(pass.getId()))
			.andExpect(jsonPath("$.passNo").value(pass.getPassNo()))
			.andExpect(jsonPath("$.studentName").value("김*윤"))
			.andExpect(jsonPath("$.campusName").value(passBenefits.current().campusName()))
			.andExpect(jsonPath("$.issuedOn").value(LocalDate.now(clock).toString()))
			.andExpect(jsonPath("$.expiresOn").value(LocalDate.now(clock).plusMonths(1).toString()))
			.andExpect(jsonPath("$.discountRate").value(0.0725));
	}

	@Test
	void 패스_번호로도_확인한다() throws Exception {
		Pass pass = saveCampusPass(newStudentId(), new BigDecimal("0.05"));

		verify(passNoBody(pass), PARTNER).andExpect(status().isOk()).andExpect(jsonPath("$.passId").value(pass.getId()));
	}

	@Test
	void 같은_QR은_한_번만_통과한다() throws Exception {
		String qr = qrBody(saveCampusPass(newStudentId(), new BigDecimal("0.05")));

		verify(qr, PARTNER).andExpect(status().isOk());
		verify(qr, PARTNER).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_QR_INVALID"));
	}

	// 연계 학원이 발급한 패스는 JEY 데스크에서 쓴다. 토큰을 쓰지 않으므로 같은 QR로 다시 보내도 같은 409다.
	@Test
	void 연계_학원이_발급한_패스는_409이고_QR을_쓰지_않는다() throws Exception {
		String qr = qrBody(savePartnerPass());

		verify(qr, PARTNER).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_PASS_FOR_CAMPUS_USE"));
		verify(qr, PARTNER).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_PASS_FOR_CAMPUS_USE"));
	}

	@Test
	void 쓸_수_없는_패스는_상태에_맞는_409다() throws Exception {
		Pass canceled = saveCampusPass(newStudentId(), new BigDecimal("0.05"));
		jdbc.update("UPDATE dongtanpass_pass SET status = 'CANCELED' WHERE id = ?", canceled.getId());
		Pass expired = saveCampusPassAt(newStudentId(), clock.instant().minus(Duration.ofDays(40)));

		verify(passNoBody(canceled), PARTNER).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_PASS_CANCELED"));
		verify(passNoBody(expired), PARTNER).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_PASS_EXPIRED"));
	}

	// 할인은 그 학원에 새로 오는 학생에게만 준다. 같은 학생이 다음 달 패스로 같은 학원에 다시 오면 막는다.
	@Test
	void 이_학원에서_이미_할인받은_학생은_409이고_QR을_쓰지_않는다() throws Exception {
		long student = newStudentId();
		redeemDirectly(saveCampusPass(student, new BigDecimal("0.05")), PARTNER);
		String qr = qrBody(saveCampusPass(student, new BigDecimal("0.05")));

		verify(qr, PARTNER).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_STUDENT_ALREADY_USED_AT_PARTNER"));
		// 다른 학원에서는 새 학생이다. 토큰을 쓰지 않았으므로 같은 QR이 통과한다.
		verify(qr, OTHER_PARTNER).andExpect(status().isOk());
	}

	@Test
	void 꺼진_학원은_403이다() throws Exception {
		verify(passNoBody(saveCampusPass(newStudentId(), new BigDecimal("0.05"))), INACTIVE)
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_PARTNER_INACTIVE"));
	}

	@Test
	void 직원과_관리자는_연계_학원의_확인을_쓰지_못한다() throws Exception {
		String body = passNoBody(saveCampusPass(newStudentId(), new BigDecimal("0.05")));

		send(body, user(newAccount(), UserRole.STAFF, 1L, null)).andExpect(status().isForbidden());
		send(body, user(newAccount(), UserRole.ADMIN, null, null)).andExpect(status().isForbidden());
	}

	// 데스크와 같은 한도(계정마다, 맞든 틀리든). QR 확인은 세지 않는다.
	@Test
	void 패스_번호_확인은_계정마다_정한_횟수까지다() throws Exception {
		long account = newAccount();
		Pass pass = saveCampusPass(newStudentId(), new BigDecimal("0.05"));
		for (int i = 0; i < 60; i++) {
			String body = i % 2 == 0 ? passNoBody(pass) : "{\"passNo\":\"" + PassNumber.generate(RANDOM) + "\"}";
			send(body, user(account, UserRole.PARTNER, null, PARTNER)).andExpect(status().is(i % 2 == 0 ? 200 : 404));
		}

		send(passNoBody(pass), user(account, UserRole.PARTNER, null, PARTNER)).andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_PASS_NO_VERIFY_LIMITED"));
		send(qrBody(pass), user(account, UserRole.PARTNER, null, PARTNER)).andExpect(status().isOk());
	}

	private ResultActions verify(String body, long partnerId) throws Exception {
		return send(body, user(newAccount(), UserRole.PARTNER, null, partnerId));
	}

	private ResultActions send(String body, SecurityContext login) throws Exception {
		return mockMvc.perform(post(VERIFY).with(securityContext(login))
			.with(csrfToken(mockMvc))
			.contentType(MediaType.APPLICATION_JSON)
			.content(body));
	}

	private static SecurityContext user(long accountId, UserRole role, Long campusId, Long partnerId) {
		AuthenticatedUser user = new AuthenticatedUser(accountId, "partner-desk", "연계 학원", role, campusId, partnerId);
		SecurityContext login = SecurityContextHolder.createEmptyContext();
		login.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user, null,
				List.of(new SimpleGrantedAuthority(role.authority()))));
		return login;
	}

	private String qrBody(Pass pass) {
		return "{\"qrToken\":\"" + passQrCodes.issue(pass.getId(), clock.instant()).token() + "\"}";
	}

	private static String passNoBody(Pass pass) {
		return "{\"passNo\":\"" + pass.getPassNo() + "\"}";
	}

	private Pass saveCampusPass(long student, BigDecimal rate) {
		return saveCampusPassAt(student, clock.instant(), rate);
	}

	private Pass saveCampusPassAt(long student, Instant issuedAt) {
		return saveCampusPassAt(student, issuedAt, new BigDecimal("0.05"));
	}

	private Pass saveCampusPassAt(long student, Instant issuedAt, BigDecimal rate) {
		return passes.saveAndFlush(Pass.issueAtCampus(passBenefits.current().campusId(), student,
				PassNumber.generate(RANDOM), LinkToken.generate(RANDOM).hash(), "김하윤", "phone-index", issuedAt, SEOUL,
				LocalDate.ofInstant(issuedAt, SEOUL), Money.of(280000), PaymentMethod.CARD, rate));
	}

	private Pass savePartnerPass() {
		return passes.saveAndFlush(Pass.issue(PARTNER, PassNumber.generate(RANDOM), LinkToken.generate(RANDOM).hash(),
				"이연계", "phone-index", clock.instant(), SEOUL, LocalDate.now(clock), Money.of(150000),
				PaymentMethod.CARD));
	}

	// 사용 처리 API는 다음 작업이다. 여기서는 도메인으로 기록을 만든다.
	private void redeemDirectly(Pass pass, long partnerId) {
		transaction.executeWithoutResult(status -> {
			Pass locked = passes.findById(pass.getId()).orElseThrow();
			var redemption = locked.redeemAtPartner(partnerId, Money.of(280000), clock.instant());
			passes.saveAndFlush(locked);
			partnerRedemptions.saveAndFlush(redemption);
		});
	}

	private static long newStudentId() {
		return ThreadLocalRandom.current().nextLong(1_000_000_000L, Long.MAX_VALUE / 2);
	}

	private static long newAccount() {
		return 9_020_000_000L + RANDOM.nextInt(1_000_000_000);
	}

}
