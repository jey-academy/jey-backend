package com.jey.dongtanpass;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.transaction.support.TransactionTemplate;

import com.jey.core.shared.Money;
import com.jey.dongtanpass.domain.LinkToken;
import com.jey.dongtanpass.domain.PartnerRedemption;
import com.jey.dongtanpass.domain.PartnerRedemptionRepository;
import com.jey.dongtanpass.domain.Pass;
import com.jey.dongtanpass.domain.PassNumber;
import com.jey.dongtanpass.domain.PassRepository;
import com.jey.dongtanpass.domain.PaymentMethod;
import com.jey.dongtanpass.domain.Redemption;
import com.jey.dongtanpass.domain.RedemptionRepository;

/**
 * 연계 학원 장부 시험의 준비: 쓴 패스와 사용 기록을 저장소로 바로 넣는다(이벤트는 나가지 않는다). 지점 1은 동탄관이다.
 * 패스를 먼저(사용됨으로), 기록을 나중에 저장한다(사용 처리와 같은 순서).
 */
public final class PartnerLedgerFixtures {

	/** 한국 날짜로 2026-11-03. */
	public static final Instant USED_AT = Instant.parse("2026-11-02T16:30:00Z");

	private static final SecureRandom RANDOM = new SecureRandom();

	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

	private final PassRepository passes;

	private final PartnerRedemptionRepository partnerRedemptions;

	private final RedemptionRepository redemptions;

	private final TransactionTemplate transaction;

	public PartnerLedgerFixtures(PassRepository passes, PartnerRedemptionRepository partnerRedemptions,
			RedemptionRepository redemptions, TransactionTemplate transaction) {
		this.passes = passes;
		this.partnerRedemptions = partnerRedemptions;
		this.redemptions = redemptions;
		this.transaction = transaction;
	}

	/** 동탄관(지점 1)이 발급한 패스를 그 학원이 280,000원에 5% 할인으로 썼다. 패스 ID를 준다. */
	public long campusPassRedeemedAt(long partnerId, String studentName) {
		return transaction.execute(status -> {
			Pass pass = passes.saveAndFlush(Pass.issueAtCampus(1L, newStudentId(), PassNumber.generate(RANDOM),
					LinkToken.generate(RANDOM).hash(), studentName, "phone-index", USED_AT.minus(Duration.ofDays(3)),
					SEOUL, LocalDate.of(2026, 10, 31), Money.of(280_000), PaymentMethod.CARD, new BigDecimal("0.05")));
			PartnerRedemption redemption = pass.redeemAtPartner(partnerId, Money.of(280_000), USED_AT);
			passes.saveAndFlush(pass);
			partnerRedemptions.saveAndFlush(redemption);
			return pass.getId();
		});
	}

	/** 그 학원이 발급한 패스를 동탄관(지점 1) 데스크가 썼다. 패스 ID를 준다. */
	public long partnerPassRedeemedAtDesk(long partnerId, String studentName) {
		return transaction.execute(status -> {
			Pass pass = passes.saveAndFlush(Pass.issue(partnerId, PassNumber.generate(RANDOM),
					LinkToken.generate(RANDOM).hash(), studentName, "phone-index", USED_AT.minus(Duration.ofDays(3)),
					SEOUL, LocalDate.of(2026, 10, 31), Money.of(150_000), PaymentMethod.CARD));
			Redemption redemption = pass.redeem(newStudentId(), 1L, USED_AT);
			passes.saveAndFlush(pass);
			redemptions.saveAndFlush(redemption);
			return pass.getId();
		});
	}

	public static long newStudentId() {
		return ThreadLocalRandom.current().nextLong(1_000_000_000L, Long.MAX_VALUE / 2);
	}

}
