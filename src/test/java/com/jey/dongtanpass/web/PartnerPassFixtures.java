package com.jey.dongtanpass.web;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import com.jey.core.auth.api.AuthenticatedUser;
import com.jey.core.auth.api.UserRole;
import com.jey.core.shared.Money;
import com.jey.dongtanpass.domain.LinkToken;
import com.jey.dongtanpass.domain.Pass;
import com.jey.dongtanpass.domain.PassNumber;
import com.jey.dongtanpass.domain.PaymentMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static com.jey.TestCsrf.csrfToken;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.securityContext;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 연계 학원 쪽 사용 처리 시험이 같이 쓰는 것. 학생 ID는 학생 자료와 잇지 않는 시험이라 겹치지 않게 크게 고른다(외래 키 없음).
final class PartnerPassFixtures {

	static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

	private static final SecureRandom RANDOM = new SecureRandom();

	private PartnerPassFixtures() {
	}

	static Pass campusPass(long campusId, long studentId, String name, String phoneIndex, Instant issuedAt,
			BigDecimal rate) {
		return Pass.issueAtCampus(campusId, studentId, PassNumber.generate(RANDOM), LinkToken.generate(RANDOM).hash(),
				name, phoneIndex, issuedAt, SEOUL, LocalDate.ofInstant(issuedAt, SEOUL), Money.of(280000),
				PaymentMethod.CARD, rate);
	}

	static Pass partnerPass(long partnerId, String name, String phoneIndex, Instant issuedAt) {
		return Pass.issue(partnerId, PassNumber.generate(RANDOM), LinkToken.generate(RANDOM).hash(), name, phoneIndex,
				issuedAt, SEOUL, LocalDate.ofInstant(issuedAt, SEOUL), Money.of(150000), PaymentMethod.CARD);
	}

	static long newStudentId() {
		return ThreadLocalRandom.current().nextLong(1_000_000_000L, Long.MAX_VALUE / 2);
	}

	// 한도는 계정마다 세고 Redis의 횟수는 한 번 실행 안에서 테스트끼리 남는다. 한도를 보는 요청은 새 계정으로 보낸다.
	static long newAccount() {
		return 9_030_000_000L + RANDOM.nextInt(1_000_000_000);
	}

	static SecurityContext login(long accountId, UserRole role, Long campusId, Long partnerId) {
		AuthenticatedUser user = new AuthenticatedUser(accountId, "partner-desk", "연계 학원", role, campusId, partnerId);
		SecurityContext context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user, null,
				List.of(new SimpleGrantedAuthority(role.authority()))));
		return context;
	}

	static SecurityContext partner(long partnerId) {
		return login(newAccount(), UserRole.PARTNER, null, partnerId);
	}

	// 다음 단계(본인 확인, 금액, 사용 처리)는 그 학원이 확인한 패스에만 된다. 패스 번호로 확인해 둔다(새 계정이라 번호 확인 한도와 무관하다).
	static void verifyAt(MockMvc mockMvc, Pass pass, long partnerId) throws Exception {
		mockMvc.perform(post("/api/v1/dongtanpass/partner-redemptions/verify").with(securityContext(partner(partnerId)))
			.with(csrfToken(mockMvc))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"passNo\":\"" + pass.getPassNo() + "\"}"))
			.andExpect(status().isOk());
	}

}
