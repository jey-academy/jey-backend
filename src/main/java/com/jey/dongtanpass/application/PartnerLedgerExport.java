package com.jey.dongtanpass.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.jey.core.auth.api.AuthenticatedUser;
import com.jey.core.campus.api.CampusCatalog;
import com.jey.core.shared.BusinessException;
import com.jey.dongtanpass.domain.PartnerDiscountLine;
import com.jey.dongtanpass.domain.PartnerRedemptionRepository;
import com.jey.dongtanpass.domain.RedemptionRepository;
import com.jey.dongtanpass.domain.UsedAtJeyLine;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 연계 학원 장부를 CSV로 준다. 시트 장부(ADR-0048)와 같은 칸에서 앱 표시만 뺀다. 시트를 쓰든 안 쓰든 모든 연계 학원이 받는다.
 *
 * <p>두 종류를 섞는다: 그 학원이 할인해 준 기록, 그 학원이 발급한 패스가 JEY 지점에서 쓰인 기록. 사용 시각 최신 순이고, 같으면 패스 ID가 큰 것이
 * 먼저다. 기간은 연계 학원 사용 내역과 같은 규칙이다(사용일로 자르고 양끝 포함, 1년까지). 나누지 않는다(학원당 1년에 수백 건 규모).
 */
@Service
public class PartnerLedgerExport {

	private static final Comparator<Line> NEWEST_FIRST = Comparator.comparing(Line::usedAt)
		.thenComparingLong(Line::passId)
		.reversed();

	private final PartnerRedemptionRepository partnerRedemptions;

	private final RedemptionRepository redemptions;

	private final CampusCatalog catalog;

	private final Clock clock;

	PartnerLedgerExport(PartnerRedemptionRepository partnerRedemptions, RedemptionRepository redemptions,
			CampusCatalog catalog, Clock clock) {
		this.partnerRedemptions = partnerRedemptions;
		this.redemptions = redemptions;
		this.catalog = catalog;
		this.clock = clock;
	}

	/**
	 * 로그인한 연계 학원의 장부 CSV(UTF-8 BOM).
	 *
	 * @throws BusinessException 기간이 틀렸거나 1년을 넘을 때(400)
	 */
	@Transactional(readOnly = true)
	public byte[] csv(AuthenticatedUser user, LocalDate from, LocalDate to) {
		long partnerId = PartnerPassService.partnerIdOf(user);
		PartnerRedemptionLedger.requirePeriod(from, to);
		Instant start = startOf(from);
		Instant before = startOf(to.plusDays(1));
		Map<Long, String> campusNames = new HashMap<>();
		List<Line> lines = new ArrayList<>();
		for (PartnerDiscountLine used : partnerRedemptions.discountLines(partnerId, start, before)) {
			lines.add(new Line(used.redeemedAt(), used.passId(),
					PartnerLedgerRow.partnerDiscount(used.passId(), dateOf(used.redeemedAt()), used.studentName(),
							used.passNo(), used.originalFee(), used.discountRate(), used.discountAmount(),
							used.payableAmount(), campusName(campusNames, used.issuerCampusId()))));
		}
		for (UsedAtJeyLine used : redemptions.usedAtJeyLines(partnerId, start, before)) {
			lines.add(new Line(used.redeemedAt(), used.passId(), PartnerLedgerRow.usedAtJey(used.passId(),
					dateOf(used.redeemedAt()), used.studentName(), used.passNo(), campusName(campusNames, used.campusId()))));
		}
		lines.sort(NEWEST_FIRST);
		return LedgerCsv.write(PartnerLedgerRow.CSV_HEADER, lines.stream().map(line -> line.row().csvCells()).toList());
	}

	// 한 파일 안에서 같은 지점은 한 번만 읽는다.
	private String campusName(Map<Long, String> names, Long campusId) {
		String known = campusId == null ? null : names.get(campusId);
		if (known != null) {
			return known;
		}
		String name = PartnerLedgerService.campusName(catalog, campusId);
		names.put(campusId, name);
		return name;
	}

	private LocalDate dateOf(Instant at) {
		return LocalDate.ofInstant(at, clock.getZone());
	}

	// 그 날짜가 시작되는 시각(한국 0시).
	private Instant startOf(LocalDate date) {
		return date.atStartOfDay(clock.getZone()).toInstant();
	}

	private record Line(Instant usedAt, long passId, PartnerLedgerRow row) {

	}

}
