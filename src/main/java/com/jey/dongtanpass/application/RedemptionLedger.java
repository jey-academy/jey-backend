package com.jey.dongtanpass.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.jey.core.auth.api.CampusScope;
import com.jey.billing.api.InvoiceIssuing;
import com.jey.core.member.api.MemberDirectory;
import com.jey.core.member.api.StudentInfo;
import com.jey.core.shared.BusinessException;
import com.jey.dongtanpass.domain.RedemptionEntry;
import com.jey.dongtanpass.domain.RedemptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 지점별 패스 사용 내역(데스크). 소속 지점으로 묶은 자료라 지점 범위는 부르는 쪽이 정해 넘긴다(ADR-0021).
 *
 * <p>기간은 사용일(한국 날짜)로 자르고 양끝을 포함한다. 시작일과 종료일은 모두 받아야 하고 1년까지다(아주 넓은 조회를 막는다).
 * 학생 이름은 한 페이지의 학생을 한 번에 조회해 붙인다.
 */
@Service
public class RedemptionLedger {

	/** 한 번에 볼 수 있는 기간. 시작일에 이만큼 더한 날까지 받는다. */
	static final int MAX_PERIOD_YEARS = 1;

	// 최신 사용 순. 같은 시각이면 나중에 넣은 것이 먼저다.
	private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("redeemedAt"), Sort.Order.desc("id"));

	private static final Logger log = LoggerFactory.getLogger(RedemptionLedger.class);

	private final RedemptionRepository redemptions;

	private final MemberDirectory members;

	private final InvoiceIssuing invoices;

	private final Clock clock;

	RedemptionLedger(RedemptionRepository redemptions, MemberDirectory members, InvoiceIssuing invoices, Clock clock) {
		this.redemptions = redemptions;
		this.members = members;
		this.invoices = invoices;
		this.clock = clock;
	}

	/**
	 * @param scope 볼 수 있는 지점(관리자는 전 지점 또는 고른 지점, 직원은 소속 지점)
	 * @param from 이 사용일부터(포함)
	 * @param to 이 사용일까지(포함)
	 * @throws BusinessException 시작일이 종료일보다 뒤이거나 날짜가 {@link PassLedger#MIN_DATE}~{@link PassLedger#MAX_DATE} 밖일 때,
	 * 기간이 1년을 넘을 때
	 */
	@Transactional(readOnly = true)
	public Page<RedemptionRow> page(CampusScope scope, LocalDate from, LocalDate to, int page, int size) {
		if (outOfRange(from) || outOfRange(to) || from.isAfter(to)) {
			throw new BusinessException(DongtanpassErrorCode.INVALID_PERIOD);
		}
		if (to.isAfter(from.plusYears(MAX_PERIOD_YEARS))) {
			throw new BusinessException(DongtanpassErrorCode.PERIOD_TOO_LONG);
		}
		Long campusId = switch (scope) {
			case CampusScope.All all -> null;
			case CampusScope.Only only -> only.campusId();
		};
		Page<RedemptionEntry> entries = redemptions.list(campusId, startOf(from), startOf(to.plusDays(1)),
				PageRequest.of(page, size, NEWEST_FIRST));
		Map<Long, StudentInfo> students = members
			.findStudents(entries.getContent().stream().map(RedemptionEntry::studentId).distinct().toList())
			.stream()
			.collect(Collectors.toMap(StudentInfo::studentId, Function.identity()));
		// 사용 기록에 적힌 청구는 마지막으로 만든 것이다. 취소했을 수 있어 열린 것만 싣는다(한 페이지를 한 번에 묻는다).
		Set<Long> openInvoices = invoices.openAmong(
				entries.getContent().stream().map(RedemptionEntry::invoiceId).filter(Objects::nonNull).toList());
		return new PageImpl<>(entries.getContent().stream().map(entry -> rowOf(entry, students, openInvoices)).toList(),
				entries.getPageable(), entries.getTotalElements());
	}

	private RedemptionRow rowOf(RedemptionEntry entry, Map<Long, StudentInfo> students, Set<Long> openInvoices) {
		StudentInfo student = students.get(entry.studentId());
		if (student == null) {
			// 학생은 지우지 않는다. 생기면 자료를 봐야 한다. 한 줄 때문에 목록이 500이 되지 않게 이름만 비운다.
			log.warn("사용 내역의 학생을 찾지 못했다: redemptionId={} studentId={}", entry.redemptionId(),
					entry.studentId());
		}
		return new RedemptionRow(entry.redemptionId(), entry.redeemedAt(),
				LocalDate.ofInstant(entry.redeemedAt(), clock.getZone()), entry.campusId(), entry.passId(),
				entry.passNo(), entry.partnerName(), entry.studentId(), student == null ? null : student.name(),
				entry.staffId(), isOpen(entry.invoiceId(), openInvoices) ? entry.invoiceId() : null);
	}

	// 불변 집합은 null을 물으면 예외를 던진다. 청구를 만들지 않은 기록은 먼저 거른다.
	private static boolean isOpen(Long invoiceId, Set<Long> openInvoices) {
		return invoiceId != null && openInvoices.contains(invoiceId);
	}

	private static boolean outOfRange(LocalDate date) {
		return date.isBefore(PassLedger.MIN_DATE) || date.isAfter(PassLedger.MAX_DATE);
	}

	// 그 날짜가 시작되는 시각(한국 0시).
	private Instant startOf(LocalDate date) {
		return date.atStartOfDay(clock.getZone()).toInstant();
	}

}
