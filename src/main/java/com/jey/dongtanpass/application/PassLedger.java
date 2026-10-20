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
import org.springframework.data.domain.Pageable;
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
	/** 기간으로 받는 날짜의 범위. 아주 먼 날짜는 하루를 더하다 넘치거나 DB의 시각 범위를 벗어난다. */
	public static final LocalDate MIN_DATE = LocalDate.of(2000, 1, 1);

	public static final LocalDate MAX_DATE = LocalDate.of(2099, 12, 31);

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
	 * @throws BusinessException 시작일이 종료일보다 뒤이거나, 날짜가 {@link #MIN_DATE}~{@link #MAX_DATE} 밖일 때
	 */
	@Transactional(readOnly = true)
	public Page<LedgerRow> page(long partnerId, LocalDate from, LocalDate to, PassState state, int page, int size) {
		return rows(partnerId, from, to, state, PageRequest.of(page, size, NEWEST_FIRST));
	}

	/**
	 * 장부를 CSV 파일로 준다(UTF-8 BOM). 상태로 거르지 않고 나누지 않는다. 학원당 발급은 수백 건 규모다.
	 *
	 * @throws BusinessException 시작일이 종료일보다 뒤이거나, 날짜가 {@link #MIN_DATE}~{@link #MAX_DATE} 밖일 때
	 */
	@Transactional(readOnly = true)
	public byte[] csv(long partnerId, LocalDate from, LocalDate to) {
		return LedgerCsv.write(rows(partnerId, from, to, null, Pageable.unpaged(NEWEST_FIRST)).getContent());
	}

	/**
	 * JEY 지점이 발급한 패스. 연계 학원 장부와 같은 기간·상태 조건에 학생을 더한다. 최신 발급 순이다.
	 *
	 * @param studentId 이 학생의 패스만. 없으면 모두
	 * @throws BusinessException 시작일이 종료일보다 뒤이거나, 날짜가 {@link #MIN_DATE}~{@link #MAX_DATE} 밖일 때
	 */
	@Transactional(readOnly = true)
	public Page<CampusLedgerRow> campusPage(long campusId, Long studentId, LocalDate from, LocalDate to,
			PassState state, int page, int size) {
		requirePeriod(from, to);
		LocalDate today = LocalDate.now(clock);
		Filter filter = Filter.of(state, today);
		return passes
			.campusLedger(campusId, studentId, startOf(from), startOf(to == null ? null : to.plusDays(1)),
					filter.status(), filter.expiresOnOrAfter(), filter.expiresBefore(),
					PageRequest.of(page, size, NEWEST_FIRST))
			.map(pass -> CampusLedgerRow.of(pass, today, clock.getZone()));
	}

	private Page<LedgerRow> rows(long partnerId, LocalDate from, LocalDate to, PassState state, Pageable pageable) {
		requirePeriod(from, to);
		LocalDate today = LocalDate.now(clock);
		Filter filter = Filter.of(state, today);
		return passes
			.ledger(partnerId, startOf(from), startOf(to == null ? null : to.plusDays(1)), filter.status(),
					filter.expiresOnOrAfter(), filter.expiresBefore(), pageable)
			.map(pass -> LedgerRow.of(pass, today, clock.getZone()));
	}

	private static void requirePeriod(LocalDate from, LocalDate to) {
		if (outOfRange(from) || outOfRange(to) || (from != null && to != null && from.isAfter(to))) {
			throw new BusinessException(DongtanpassErrorCode.INVALID_PERIOD);
		}
	}

	private static boolean outOfRange(LocalDate date) {
		return date != null && (date.isBefore(MIN_DATE) || date.isAfter(MAX_DATE));
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
