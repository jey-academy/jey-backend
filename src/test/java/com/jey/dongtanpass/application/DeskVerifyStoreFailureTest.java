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
import com.jey.core.shared.BusinessException;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static com.jey.dongtanpass.application.QrSigningPropertiesTest.KEY_1;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.InstanceOfAssertFactories.STRING;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// 쓴 토큰을 적지 못하면(저장소 장애) 통과시키지 않는다. 통과시키면 같은 QR이 장애 동안 몇 번이고 통과한다.
// 패스를 읽지 못하면(DB 장애, 패스 없음) 토큰을 쓰지 않는다. 쓰면 같은 QR로 다시 할 수 없고 로그에는 재사용으로 남는다.
// 저장소를 일부러 실패시켜야 해서 Spring 없이 만든다(컨텍스트를 늘리지 않는다).
@ExtendWith(OutputCaptureExtension.class)
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

	@Test
	void 패스를_읽지_못하면_토큰을_쓰지_않는다() {
		when(passes.findById(11L)).thenThrow(new IllegalStateException("DB에 닿지 못했다"));
		when(usedQrTokens.markUsed(any(), any())).thenReturn(true);

		assertThatThrownBy(() -> service.verifyQr(token, STAFF)).isInstanceOf(IllegalStateException.class)
			.hasMessage("DB에 닿지 못했다");
		verify(usedQrTokens, never()).markUsed(any(), any());
	}

	@Test
	void 연계_학원을_읽지_못하면_토큰을_쓰지_않는다() {
		when(partners.findById(7L)).thenThrow(new IllegalStateException("DB에 닿지 못했다"));
		when(usedQrTokens.markUsed(any(), any())).thenReturn(true);

		assertThatThrownBy(() -> service.verifyQr(token, STAFF)).isInstanceOf(IllegalStateException.class);
		verify(usedQrTokens, never()).markUsed(any(), any());
	}

	// 서명은 맞는데 패스가 없다(지금은 패스를 지우지 않아 생기지 않아야 한다). 데스크에는 같은 400, 운영자에게는 경고.
	@Test
	void 서명은_맞는데_패스가_없으면_400이고_경고를_남기고_토큰을_쓰지_않는다(CapturedOutput output) {
		when(passes.findById(11L)).thenReturn(Optional.empty());
		when(usedQrTokens.markUsed(any(), any())).thenReturn(true);

		assertThatThrownBy(() -> service.verifyQr(token, STAFF)).isInstanceOfSatisfying(BusinessException.class,
				ex -> assertThat(ex.getErrorCode()).isEqualTo(DongtanpassErrorCode.QR_INVALID));
		assertThat(output.getAll().lines().filter(line -> line.contains("reason=pass_missing")))
			.singleElement(STRING)
			.contains("WARN")
			.contains("passId=11");
		verify(usedQrTokens, never()).markUsed(any(), any());
	}

	// 대조군: 같은 준비에서 적기가 되면 통과한다.
	@Test
	void 적으면_통과한다() {
		when(usedQrTokens.markUsed(any(), any())).thenReturn(true);

		assertThat(service.verifyQr(token, STAFF).passId()).isEqualTo(11L);
	}

}
