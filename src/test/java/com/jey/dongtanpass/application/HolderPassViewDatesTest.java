package com.jey.dongtanpass.application;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;

import com.jey.core.shared.Money;
import com.jey.dongtanpass.domain.LinkToken;
import com.jey.dongtanpass.domain.Partner;
import com.jey.dongtanpass.domain.PartnerRepository;
import com.jey.dongtanpass.domain.Pass;
import com.jey.dongtanpass.domain.PassNumber;
import com.jey.dongtanpass.domain.PassRepository;
import com.jey.dongtanpass.domain.PassState;
import com.jey.dongtanpass.domain.PaymentMethod;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// 열람의 발급일·상태·남은 일수가 서버 시계의 시간대(서울)와 하나의 오늘로 정해지는지 본다.
// 시각은 모두 서울 00:30(UTC로는 전날)이다. UTC로 고르거나 남은 일수에만 다른 오늘을 쓰면 하루가 어긋난다.
// API 테스트는 시스템 시계를 써서 낮에 돌면 이 차이가 드러나지 않으므로, 시계를 고정하려고 Spring 없이 만든다.
class HolderPassViewDatesTest {

	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

	private static final SecureRandom RANDOM = new SecureRandom();

	// 서울 2026-10-14 00:30, UTC로는 2026-10-13
	private static final Instant ISSUED_AT = Instant.parse("2026-10-13T15:30:00Z");

	private final PassRepository passes = mock(PassRepository.class);

	private final PartnerRepository partners = mock(PartnerRepository.class);

	private final PassBenefits passBenefits = mock(PassBenefits.class);

	@ParameterizedTest(name = "{0} 열람 → {1}, {2}일 남음")
	@CsvSource({ "2026-10-13T15:30:00Z,ISSUED,32", "2026-11-13T15:30:00Z,ISSUED,1", "2026-11-14T15:30:00Z,EXPIRED,0" })
	void 발급일과_상태와_남은_일수는_서울_날짜의_같은_오늘로_정한다(Instant now, PassState state, int daysLeft) {
		LinkToken link = LinkToken.generate(RANDOM);
		Pass pass = Pass.issue(7L, PassNumber.generate(RANDOM), link.hash(), "김하윤", "phone-index", ISSUED_AT, SEOUL,
				LocalDate.of(2026, 10, 14), Money.of(150000), PaymentMethod.CARD);
		Partner partner = mock(Partner.class);
		when(partner.getName()).thenReturn("테스트학원");
		when(passes.findByLinkTokenHash(link.hash())).thenReturn(Optional.of(pass));
		when(partners.findById(7L)).thenReturn(Optional.of(partner));
		when(passBenefits.current()).thenReturn(new PassBenefit(1L, "테스트지점", "DONGTAN_PASS", new BigDecimal("0.05"), true));
		HolderPassService service = new HolderPassService(passes, partners, passBenefits, mock(PassQrCodes.class),
				Clock.fixed(now, SEOUL));

		HolderPassView view = service.view(link.raw());

		assertThat(view.issuedOn()).isEqualTo(LocalDate.of(2026, 10, 14));
		assertThat(view.expiresOn()).isEqualTo(LocalDate.of(2026, 11, 14));
		assertThat(view.state()).isEqualTo(state);
		assertThat(view.daysLeft()).isEqualTo(daysLeft);
	}

}
