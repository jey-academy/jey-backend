package com.jey.dongtanpass.web;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import com.jey.TestcontainersConfiguration;
import com.jey.core.auth.api.AuthenticatedUser;
import com.jey.core.auth.api.UserRole;
import com.jey.core.shared.Money;
import com.jey.dongtanpass.domain.LinkToken;
import com.jey.dongtanpass.domain.Pass;
import com.jey.dongtanpass.domain.PassNumber;
import com.jey.dongtanpass.domain.PassRepository;
import com.jey.dongtanpass.domain.PaymentMethod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
import org.springframework.security.test.context.TestSecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static com.jey.TestCsrf.csrfToken;
import static com.jey.TestIdempotency.idempotencyKey;
import static com.jey.dongtanpass.DongtanpassFixtures.upsertPartner;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.securityContext;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 지점별 패스 사용 내역. 소속 지점으로 묶은 자료라 직원은 자기 지점만, 관리자는 전 지점을 본다(ADR-0021).
// 기간은 사용일(한국 날짜)로 자르고 양끝을 포함한다. 기록은 먼 미래 날짜로 직접 넣어 다른 테스트의 기록과 섞이지 않게 한다.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RedemptionLedgerApiTest {

	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

	private static final long DONGTAN = 1L;

	private static final long NEUNGDONG = 2L;

	private static final long PARTNER = 901_901L;

	private static final long STAFF = 901_911L;

	private static final SecureRandom RANDOM = new SecureRandom();

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	PassRepository passes;

	@BeforeEach
	void setUp() {
		TestSecurityContextHolder.clearContext();
		upsertPartner(jdbc, PARTNER, true);
		jdbc.update("DELETE FROM dongtanpass_redemption WHERE redeemed_at >= '2031-01-01'");
	}

	// 사용일의 0시와 끝 시각이 경계다. 하루 앞뒤는 빠진다.
	@Test
	void 기간은_사용일로_자르고_양끝을_포함한다() throws Exception {
		insert(DONGTAN, "2031-02-28 23:59:59.999999");
		long first = insert(DONGTAN, "2031-03-01 00:00:00");
		long last = insert(DONGTAN, "2031-03-31 23:59:59.999999");
		insert(DONGTAN, "2031-04-01 00:00:00");

		list(UserRole.STAFF, DONGTAN, "from=2031-03-01&to=2031-03-31").andExpect(status().isOk())
			.andExpect(jsonPath("$.items[*].redemptionId", contains((int) last, (int) first)))
			.andExpect(jsonPath("$.totalElements").value(2))
			.andExpect(jsonPath("$.items[0].redeemedOn").value("2031-03-31"))
			.andExpect(jsonPath("$.items[1].redeemedOn").value("2031-03-01"))
			.andExpect(jsonPath("$.items[1].redeemedAt").value("2031-02-28T15:00:00Z"))
			// 학생을 찾지 못한 줄(있어서는 안 되는 자료)도 목록은 나온다. 이름만 비운다.
			.andExpect(jsonPath("$.items[1].studentName").value(nullValue()));
	}

	@Test
	void 한_줄에_패스_연계_학원_학생_처리한_직원이_있다() throws Exception {
		String name = "내역" + Integer.toHexString(RANDOM.nextInt());
		long studentId = register(name);
		Pass pass = save();
		long id = insert(DONGTAN, "2031-05-10 10:00:00", pass, studentId);

		list(UserRole.STAFF, DONGTAN, "from=2031-05-10&to=2031-05-10").andExpect(status().isOk())
			.andExpect(jsonPath("$.items", hasSize(1)))
			.andExpect(jsonPath("$.items[0].redemptionId").value(id))
			.andExpect(jsonPath("$.items[0].campusId").value(DONGTAN))
			.andExpect(jsonPath("$.items[0].passId").value(pass.getId()))
			.andExpect(jsonPath("$.items[0].passNo").value(pass.getPassNo()))
			.andExpect(jsonPath("$.items[0].partnerName").value("테스트학원-" + PARTNER))
			.andExpect(jsonPath("$.items[0].studentId").value(studentId))
			.andExpect(jsonPath("$.items[0].studentName").value(name))
			.andExpect(jsonPath("$.items[0].staffId").value(STAFF));
	}

	// 같은 시각이면 나중에 넣은 기록(ID가 큰 것)이 먼저다.
	@Test
	void 최신_사용_순이고_페이지로_나눈다() throws Exception {
		long a = insert(DONGTAN, "2031-06-01 09:00:00");
		long b = insert(DONGTAN, "2031-06-02 09:00:00");
		long c = insert(DONGTAN, "2031-06-02 09:00:00");

		list(UserRole.STAFF, DONGTAN, "from=2031-06-01&to=2031-06-30&size=2").andExpect(status().isOk())
			.andExpect(jsonPath("$.items[*].redemptionId", contains((int) c, (int) b)))
			.andExpect(jsonPath("$.totalElements").value(3))
			.andExpect(jsonPath("$.totalPages").value(2));
		list(UserRole.STAFF, DONGTAN, "from=2031-06-01&to=2031-06-30&size=2&page=1").andExpect(status().isOk())
			.andExpect(jsonPath("$.items[*].redemptionId", contains((int) a)));
	}

	// 직원은 지점을 고르지 않으면 자기 지점이고, 다른 지점을 고르면 403이다(바꿔 주지 않는다).
	@Test
	void 직원은_자기_지점만_본다() throws Exception {
		long mine = insert(DONGTAN, "2031-07-01 10:00:00");
		insert(NEUNGDONG, "2031-07-01 11:00:00");

		// 건수도 자기 지점만 센다(다른 지점 건수가 새거나 빈 페이지가 생기지 않게). 첫 페이지가 차 있어야 건수 쿼리가 돈다(size=1).
		list(UserRole.STAFF, DONGTAN, "from=2031-07-01&to=2031-07-01&size=1").andExpect(status().isOk())
			.andExpect(jsonPath("$.items[*].redemptionId", contains((int) mine)))
			.andExpect(jsonPath("$.totalElements").value(1))
			.andExpect(jsonPath("$.totalPages").value(1));
		list(UserRole.STAFF, DONGTAN, "campusId=2&from=2031-07-01&to=2031-07-01").andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("AUTH_CAMPUS_NOT_ALLOWED"));
	}

	@Test
	void 관리자는_전_지점을_보고_지점을_고를_수_있다() throws Exception {
		long dongtan = insert(DONGTAN, "2031-07-02 10:00:00");
		long neungdong = insert(NEUNGDONG, "2031-07-02 11:00:00");

		list(UserRole.ADMIN, null, "from=2031-07-02&to=2031-07-02").andExpect(status().isOk())
			.andExpect(jsonPath("$.items[*].redemptionId", contains((int) neungdong, (int) dongtan)))
			.andExpect(jsonPath("$.totalElements").value(2));
		list(UserRole.ADMIN, null, "campusId=1&from=2031-07-02&to=2031-07-02").andExpect(status().isOk())
			.andExpect(jsonPath("$.items[*].redemptionId", contains((int) dongtan)))
			.andExpect(jsonPath("$.totalElements").value(1));
	}

	@Test
	void 연계_학원_계정은_403이다() throws Exception {
		list(UserRole.PARTNER, null, "from=2031-07-01&to=2031-07-01").andExpect(status().isForbidden());
	}

	// 정확히 1년(from + 1년 = to)까지 받는다.
	@Test
	void 기간은_1년까지다() throws Exception {
		list(UserRole.STAFF, DONGTAN, "from=2031-01-01&to=2032-01-01").andExpect(status().isOk());
		list(UserRole.STAFF, DONGTAN, "from=2031-01-01&to=2032-01-02").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_PERIOD_TOO_LONG"));
	}

	@ParameterizedTest(name = "{0}")
	@CsvSource(delimiter = '|', value = { "from=2031-03-02&to=2031-03-01|DONGTANPASS_INVALID_PERIOD",
			"from=1999-12-31&to=2000-01-01|DONGTANPASS_INVALID_PERIOD",
			"from=2099-12-31&to=2100-01-01|DONGTANPASS_INVALID_PERIOD" })
	void 기간이_틀리면_400이다(String query, String code) throws Exception {
		list(UserRole.STAFF, DONGTAN, query).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value(code));
	}

	@ParameterizedTest(name = "{0}")
	@CsvSource({ "to=2031-03-01", "from=2031-03-01", "''", "from=2031-03-01&to=2031-03-02&size=101",
			"from=2031-03-01&to=2031-03-02&size=0", "from=2031-03-01&to=2031-03-02&page=10001",
			"from=2031-03-01&to=2031-03-02&page=-1", "from=2031-3-1&to=2031-03-02" })
	void 날짜가_없거나_페이지가_범위_밖이면_400이다(String query) throws Exception {
		list(UserRole.STAFF, DONGTAN, query).andExpect(status().isBadRequest());
	}

	private ResultActions list(UserRole role, Long campusId, String query) throws Exception {
		return mockMvc.perform(get("/api/v1/dongtanpass/redemptions?" + query).with(securityContext(login(role, campusId))));
	}

	private static SecurityContext login(UserRole role, Long campusId) {
		AuthenticatedUser user = new AuthenticatedUser(STAFF, "desk-staff", "데스크", role, campusId,
				role == UserRole.PARTNER ? PARTNER : null);
		SecurityContext login = SecurityContextHolder.createEmptyContext();
		login.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user, null,
				List.of(new SimpleGrantedAuthority(role.authority()))));
		return login;
	}

	// 사용 기록을 직접 넣는다(시각과 지점을 정하려고). 학생은 다른 기록과 겹치지 않는 큰 번호를 쓴다(내역은 이름을 못 찾으면 null로 둔다).
	private long insert(long campusId, String seoulTime) {
		return insert(campusId, seoulTime, save(), 9_019_000_000L + ThreadLocalRandom.current().nextInt(1_000_000_000));
	}

	private long insert(long campusId, String seoulTime, Pass pass, long studentId) {
		jdbc.update("""
				INSERT INTO dongtanpass_redemption (pass_id, student_id, campus_id, redeemed_at, created_at, updated_at, created_by)
				VALUES (?, ?, ?, ?, NOW(6), NOW(6), ?)
				""", pass.getId(), studentId, campusId, seoulTime, STAFF);
		return jdbc.queryForObject("SELECT id FROM dongtanpass_redemption WHERE pass_id = ?", Long.class, pass.getId());
	}

	private Pass save() {
		return passes.saveAndFlush(Pass.issue(PARTNER, PassNumber.generate(RANDOM), LinkToken.generate(RANDOM).hash(),
				"김하윤", "phone-index", Instant.parse("2031-01-01T01:00:00Z"), SEOUL, LocalDate.of(2031, 1, 1),
				Money.of(150000), PaymentMethod.CARD));
	}

	private long register(String name) throws Exception {
		String phone = "0109" + String.format("%07d", ThreadLocalRandom.current().nextInt(10_000_000));
		String json = mockMvc
			.perform(post("/api/v1/students").with(securityContext(login(UserRole.STAFF, DONGTAN)))
				.with(csrfToken(mockMvc))
				.with(idempotencyKey())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"campusId": 1, "name": "%s", "grade": "HIGH_1",
						 "guardians": [{"name": "보호자", "relation": "MOTHER", "phone": "%s"}]}
						""".formatted(name, phone)))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return Long.parseLong(json.replaceAll("(?s).*?\"id\"\\s*:\\s*(\\d+).*", "$1"));
	}

}
