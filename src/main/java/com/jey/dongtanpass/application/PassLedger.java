package com.jey.dongtanpass.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;

import com.jey.core.shared.BusinessException;
import com.jey.dongtanpass.domain.PassRepository;
import com.jey.dongtanpass.domain.PassState;
import com.jey.dongtanpass.domain.PassStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 연계 학원의 발급 장부. 그 학원이 발급한 패스만 보인다.
 *
 * <p>기간은 발급일(한국 날짜)로 자르고 양끝을 포함한다. 상태는 오늘 기준으로 보이는 상태({@link PassState})로 거른다.
 */
@Service
public class PassLedger {

	// 최신 발급 순. 같은 시각이면 나중에 만든 것이 먼저다.
	private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("issuedAt"), Sort.Order.desc("id"));

	private final PassRepository passes;

	private final Clock clock;

	PassLedger(PassRepository passes, Clock clock) {
		this.passes = passes;
		this.clock = clock;
	}

	/**
	 * @param from 이 발급일부터(포함). 없으면 처음부터
	 * @param to 이 발급일까지(포함). 없으면 끝까지
	 * @param state 이 상태만. 없으면 모두
	 * @throws BusinessException 시작일이 종료일보다 뒤일 때
	 */
	@Transactional(readOnly = true)
	public Page<LedgerRow> page(long partnerId, LocalDate from, LocalDate to, PassState state, int page, int size) {
		if (from != null && to != null && from.isAfter(to)) {
			throw new BusinessException(DongtanpassErrorCode.INVALID_PERIOD);
		}
		LocalDate today = LocalDate.now(clock);
		Filter filter = Filter.of(state, today);
		return passes
			.ledger(partnerId, startOf(from), startOf(to == null ? null : to.plusDays(1)), filter.status(),
					filter.expiresOnOrAfter(), filter.expiresBefore(), PageRequest.of(page, size, NEWEST_FIRST))
			.map(pass -> LedgerRow.of(pass, today, clock.getZone()));
	}

	// 그 날짜가 시작되는 시각(한국 0시).
	private Instant startOf(LocalDate date) {
		return date == null ? null : date.atStartOfDay(clock.getZone()).toInstant();
	}

	// 보이는 상태를 저장 상태와 만료일 조건으로 바꾼다. 쓸 수 있음은 만료일이 오늘 이후, 만료는 만료일이 오늘 전이다.
	private record Filter(PassStatus status, LocalDate expiresOnOrAfter, LocalDate expiresBefore) {

		static Filter of(PassState state, LocalDate today) {
			if (state == null) {
				return new Filter(null, null, null);
			}
			return switch (state) {
				case ISSUED -> new Filter(PassStatus.ISSUED, today, null);
				case EXPIRED -> new Filter(PassStatus.ISSUED, null, today);
				case REDEEMED -> new Filter(PassStatus.REDEEMED, null, null);
				case CANCELED -> new Filter(PassStatus.CANCELED, null, null);
			};
		}

	}

}
