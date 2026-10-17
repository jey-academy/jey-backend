package com.jey.dongtanpass.application;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;

import com.jey.core.audit.api.AuditLog;
import com.jey.core.auth.api.AccountRateLimiter;
import com.jey.core.auth.api.AuthenticatedUser;
import com.jey.core.auth.api.UserRole;
import com.jey.core.shared.Money;
import com.jey.dongtanpass.domain.LinkToken;
import com.jey.dongtanpass.domain.Partner;
import com.jey.dongtanpass.domain.PartnerRepository;
import com.jey.dongtanpass.domain.Pass;
import com.jey.dongtanpass.domain.PassNumber;
import com.jey.dongtanpass.domain.PassRepository;
import com.jey.dongtanpass.domain.PaymentMethod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static com.jey.dongtanpass.application.QrSigningPropertiesTest.KEY_1;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// 쓴 토큰을 적지 못하면(저장소 장애) 통과시키지 않는다. 통과시키면 같은 QR이 장애 동안 몇 번이고 통과한다.
// 저장소를 일부러 실패시켜야 해서 Spring 없이 만든다(컨텍스트를 늘리지 않는다).
class DeskVerifyStoreFailureTest {

	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-17T03:00:00.123Z"), SEOUL);

	private static final SecureRandom RANDOM = new SecureRandom();

	private static final AuthenticatedUser STAFF = new AuthenticatedUser(1L, "desk", "데스크", UserRole.STAFF, 1L, null);

	private final PassRepository passes = mock(PassRepository.class);

	private final PartnerRepository partners = mock(PartnerRepository.class);

	private final PassBenefits passBenefits = mock(PassBenefits.class);

	private final UsedQrTokens usedQrTokens = mock(UsedQrTokens.class);

	private final PassQrCodes passQrCodes = new PassQrCodes(new QrSigningProperties(1, Map.of(1, KEY_1)));

	private DeskPassService service;

	private String token;

	@BeforeEach
	void setUp() {
		Pass pass = Pass.issue(7L, PassNumber.generate(RANDOM), LinkToken.generate(RANDOM).hash(), "김하윤", "idx",
				CLOCK.instant(), SEOUL, LocalDate.now(CLOCK), Money.of(150000), PaymentMethod.CARD);
		ReflectionTestUtils.setField(pass, "id", 11L);
		Partner partner = mock(Partner.class);
		when(partner.getName()).thenReturn("테스트학원");
		when(passes.findById(11L)).thenReturn(Optional.of(pass));
		when(partners.findById(7L)).thenReturn(Optional.of(partner));
		when(passBenefits.current()).thenReturn(new PassBenefit(1L, "테스트지점", "DONGTAN_PASS", new BigDecimal("0.05")));
		service = new DeskPassService(passes, partners, passBenefits, passQrCodes, usedQrTokens,
				mock(AccountRateLimiter.class), new PassNoVerifyProperties(60, Duration.ofHours(1)), mock(AuditLog.class),
				CLOCK);
		token = passQrCodes.issue(11L, CLOCK.instant()).token();
	}

	@Test
	void 쓴_토큰을_적지_못하면_예외가_그대로_나가고_패스를_돌려주지_않는다() {
		when(usedQrTokens.markUsed(any(), any())).thenThrow(new IllegalStateException("저장소에 닿지 못했다"));

		assertThatThrownBy(() -> service.verifyQr(token, STAFF)).isInstanceOf(IllegalStateException.class)
			.hasMessage("저장소에 닿지 못했다");
	}

	// 대조군: 같은 준비에서 적기가 되면 통과한다.
	@Test
	void 적으면_통과한다() {
		when(usedQrTokens.markUsed(any(), any())).thenReturn(true);

		assertThat(service.verifyQr(token, STAFF).passId()).isEqualTo(11L);
	}

}
