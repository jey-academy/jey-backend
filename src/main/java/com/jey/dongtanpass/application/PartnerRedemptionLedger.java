package com.jey.dongtanpass.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;

import com.jey.core.auth.api.AuthenticatedUser;
import com.jey.core.auth.api.UserRole;
import com.jey.core.shared.BusinessException;
import com.jey.dongtanpass.domain.PartnerRedemptionEntry;
import com.jey.dongtanpass.domain.PartnerRedemptionRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 연계 학원의 제이G패스 사용 내역. 연계 학원은 자기 학원 것만, 관리자는 모든 학원 것(또는 고른 학원)을 본다.
 * 기간 규칙은 JEY 지점 데스크의 사용 내역과 같다(사용일로 자르고 양끝 포함, 1년까지).
 */
@Service
public class PartnerRedemptionLedger {

	// 최신 사용 순. 같은 시각이면 나중에 넣은 것이 먼저다.
	private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("redeemedAt"), Sort.Order.desc("id"));

	private final PartnerRedemptionRepository redemptions;

	private final Clock clock;

	PartnerRedemptionLedger(PartnerRedemptionRepository redemptions, Clock clock) {
		this.redemptions = redemptions;
		this.clock = clock;
	}

	/**
	 * @param partnerId 고른 학원. 연계 학원 계정은 안 주거나 자기 학원만, 관리자는 안 주면 모든 학원
	 * @throws BusinessException 연계 학원이 다른 학원을 골랐을 때(403), 기간이 틀렸거나 1년을 넘을 때(400)
	 */
	@Transactional(readOnly = true)
	public Page<PartnerRedemptionRow> page(AuthenticatedUser user, Long partnerId, LocalDate from, LocalDate to,
			int page, int size) {
		Long scope = scopeOf(user, partnerId);
		if (outOfRange(from) || outOfRange(to) || from.isAfter(to)) {
			throw new BusinessException(DongtanpassErrorCode.INVALID_PERIOD);
		}
		if (to.isAfter(from.plusYears(RedemptionLedger.MAX_PERIOD_YEARS))) {
			throw new BusinessException(DongtanpassErrorCode.PERIOD_TOO_LONG);
		}
		return redemptions.list(scope, startOf(from), startOf(to.plusDays(1)), PageRequest.of(page, size, NEWEST_FIRST))
			.map(this::rowOf);
	}

	// 연계 학원은 자기 학원으로만 본다. 다른 학원을 고르면 조용히 바꾸지 않고 거부한다(화면의 실수가 드러나게).
	private static Long scopeOf(AuthenticatedUser user, Long partnerId) {
		if (user.role() != UserRole.PARTNER) {
			return partnerId;
		}
		long own = PartnerPassService.partnerIdOf(user);
		if (partnerId != null && partnerId != own) {
			throw new BusinessException(DongtanpassErrorCode.OTHER_PARTNER_NOT_ALLOWED);
		}
		return own;
	}

	private PartnerRedemptionRow rowOf(PartnerRedemptionEntry entry) {
		return new PartnerRedemptionRow(entry.id(), entry.redeemedAt(),
				LocalDate.ofInstant(entry.redeemedAt(), clock.getZone()), entry.partnerId(), entry.partnerName(),
				entry.passId(), entry.passNo(), entry.studentName(), entry.originalFee(), entry.discountRate(),
				entry.discountAmount(), entry.payableAmount());
	}

	private static boolean outOfRange(LocalDate date) {
		return date.isBefore(PassLedger.MIN_DATE) || date.isAfter(PassLedger.MAX_DATE);
	}

	// 그 날짜가 시작되는 시각(한국 0시).
	private Instant startOf(LocalDate date) {
		return date.atStartOfDay(clock.getZone()).toInstant();
	}

}
