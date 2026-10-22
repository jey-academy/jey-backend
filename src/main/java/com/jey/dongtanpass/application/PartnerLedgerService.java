package com.jey.dongtanpass.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.jey.core.audit.api.AuditEntry;
import com.jey.core.audit.api.AuditLog;
import com.jey.core.campus.api.CampusCatalog;
import com.jey.core.campus.api.CampusInfo;
import com.jey.core.shared.BusinessException;
import com.jey.core.shared.UniqueKeys;
import com.jey.core.sheets.api.SheetsRejected;
import com.jey.core.sheets.api.SheetsRejection;
import com.jey.core.sheets.api.SheetsUnavailable;
import com.jey.core.sheets.api.Spreadsheets;
import com.jey.dongtanpass.api.PassRefs;
import com.jey.dongtanpass.domain.Partner;
import com.jey.dongtanpass.domain.PartnerLedgerKind;
import com.jey.dongtanpass.domain.PartnerLedgerReason;
import com.jey.dongtanpass.domain.PartnerLedgerStatus;
import com.jey.dongtanpass.domain.PartnerLedgerSync;
import com.jey.dongtanpass.domain.PartnerLedgerSyncRepository;
import com.jey.dongtanpass.domain.PartnerLedgerSyncStateException;
import com.jey.dongtanpass.domain.PartnerRedemption;
import com.jey.dongtanpass.domain.PartnerRedemptionRepository;
import com.jey.dongtanpass.domain.PartnerRepository;
import com.jey.dongtanpass.domain.Pass;
import com.jey.dongtanpass.domain.PassRepository;
import com.jey.dongtanpass.domain.Redemption;
import com.jey.dongtanpass.domain.RedemptionRepository;

/**
 * 패스 사용을 연계 학원의 장부(구글 시트)에 한 줄씩 붙인다(ADR-0048). 사용 처리는 장부와 무관하게 성공해 있다.
 *
 * <p>한 번의 시도: 행을 잠가 맡는다 → (트랜잭션 밖) 탭을 읽어 머리글을 보고, 그 패스의 앱 표시가 있으면 그 줄로 끝내고, 없으면 붙인다 →
 * 결과를 적는다. 붙이기 전에 읽는 것은 결과를 모르는 붙이기(시간 초과) 뒤에 두 번 붙이지 않기 위해서다. 잠금은 없다 — 구글이 줄을 끼워
 * 넣으므로 다른 기록과 겹치지 않고, 같은 기록은 행 잠금으로 한 쪽만 맡는다.
 *
 * <p>구글 장애는 간격을 두고 다시 하고(스케줄러), 사람이 고칠 이유는 미반영으로 둔다(관리자가 다시 반영).
 * 줄의 값은 시도할 때 사용 기록과 패스에서 읽는다(사용 기록은 고치지 않는다).
 */
@Service
public class PartnerLedgerService {

	private static final Logger log = LoggerFactory.getLogger(PartnerLedgerService.class);

	/** 스케줄러가 한 번에 집는 줄 수. 남은 줄은 다음 주기에 집는다. */
	static final int DUE_BATCH = 50;

	private final PartnerLedgerSyncRepository syncs;

	private final PassRepository passes;

	private final PartnerRedemptionRepository partnerRedemptions;

	private final RedemptionRepository redemptions;

	private final PartnerRepository partners;

	private final Spreadsheets sheets;

	private final PartnerLedgerProperties properties;

	private final CampusCatalog catalog;

	private final AuditLog auditLog;

	private final TransactionTemplate transaction;

	private final Clock clock;

	PartnerLedgerService(PartnerLedgerSyncRepository syncs, PassRepository passes,
			PartnerRedemptionRepository partnerRedemptions, RedemptionRepository redemptions, PartnerRepository partners,
			Spreadsheets sheets,
			PartnerLedgerProperties properties, CampusCatalog catalog, AuditLog auditLog, TransactionTemplate transaction,
			Clock clock) {
		this.syncs = syncs;
		this.passes = passes;
		this.partnerRedemptions = partnerRedemptions;
		this.redemptions = redemptions;
		this.partners = partners;
		this.sheets = sheets;
		this.properties = properties;
		this.catalog = catalog;
		this.auditLog = auditLog;
		this.transaction = transaction;
		this.clock = clock;
	}

	/**
	 * 패스를 썼다. 반영 상태 줄을 만들고(있으면 그대로) 바로 한 번 시도한다. 같은 패스로 다시 불러도 결과가 같다(리스너가 다시 돌아도).
	 * 트랜잭션 밖에서 부른다.
	 *
	 * @param partnerId 장부의 주인(할인해 준 학원, 또는 패스를 발급한 학원)
	 */
	public void record(long passId, long partnerId, PartnerLedgerKind kind) {
		attempt(ensureRow(passId, partnerId, kind));
	}

	/**
	 * 관리자가 장부(탭, 머리글, 권한, 설정)를 고친 뒤 미반영 줄을 다시 반영한다. 바로 한 번 시도하고 그 뒤의 상태를 돌려준다:
	 * 반영됨, 또 미반영, 대기 중(구글 장애라 잠시 뒤 다시 한다), 시도 중(그 순간 다른 쪽이 맡았다).
	 *
	 * @throws BusinessException 줄이 없을 때(404), 미반영이 아닐 때(409)
	 */
	public PartnerLedgerSyncView retryUnsynced(long id) {
		transaction.executeWithoutResult(status -> {
			PartnerLedgerSync sync = syncs.findByIdForUpdate(id)
				.orElseThrow(() -> new BusinessException(DongtanpassErrorCode.PARTNER_LEDGER_SYNC_NOT_FOUND));
			try {
				sync.retryByAdmin(clock.instant());
			} catch (PartnerLedgerSyncStateException e) {
				throw new BusinessException(DongtanpassErrorCode.PARTNER_LEDGER_SYNC_NOT_RETRYABLE);
			}
		});
		attempt(id);
		return viewsOf(List.of(syncs.findById(id).orElseThrow())).getFirst();
	}

	/** 미반영 줄, 최근 것부터. 학원을 주지 않으면 전 학원이다(관리자 전용). */
	public Page<PartnerLedgerSyncView> listUnsynced(Long partnerId, int page, int size) {
		PageRequest request = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id"));
		Page<PartnerLedgerSync> found = partnerId == null ? syncs.findByStatus(PartnerLedgerStatus.UNSYNCED, request)
				: syncs.findByStatusAndPartnerId(PartnerLedgerStatus.UNSYNCED, partnerId, request);
		return new PageImpl<>(viewsOf(found.getContent()), found.getPageable(), found.getTotalElements());
	}

	// 학원 이름과 패스의 학생 이름을 한 번씩 모아 읽는다(줄마다 읽지 않는다).
	private List<PartnerLedgerSyncView> viewsOf(List<PartnerLedgerSync> found) {
		Map<Long, String> partnerNames = partners
			.findAllById(found.stream().map(PartnerLedgerSync::getPartnerId).distinct().toList())
			.stream()
			.collect(Collectors.toMap(Partner::getId, Partner::getName));
		Map<Long, Pass> passById = passes.findAllById(found.stream().map(PartnerLedgerSync::getPassId).toList())
			.stream()
			.collect(Collectors.toMap(Pass::getId, Function.identity()));
		return found.stream().map(sync -> {
			Pass pass = passById.get(sync.getPassId());
			return PartnerLedgerSyncView.of(sync, partnerNames.get(sync.getPartnerId()),
					pass == null ? null : pass.getStudentName());
		}).toList();
	}

	/** 다시 시도할 때가 된 줄(구글 장애 뒤, 맡은 서버가 내려간 것)을 집어 시도한다. 스케줄러가 부른다. */
	public int retryDue() {
		Instant now = clock.instant();
		List<Long> ids = syncs.findDueIds(now, now.minus(PartnerLedgerSync.STALE_AFTER), PageRequest.of(0, DUE_BATCH));
		for (long id : ids) {
			try {
				attempt(id);
			} catch (RuntimeException e) {
				log.error("연계 학원 장부 반영을 다시 시도하지 못했다: 반영 {}", id, e);
			}
		}
		return ids.size();
	}

	/** 시도한다. 다른 쪽이 맡고 있거나 때가 아니면 아무것도 하지 않는다. */
	void attempt(long id) {
		Instant now = clock.instant();
		Claimed claimed = transaction.execute(status -> {
			PartnerLedgerSync sync = syncs.findByIdForUpdate(id).orElseThrow();
			return sync.claim(now) ? Claimed.of(sync) : null;
		});
		if (claimed == null) {
			return;
		}
		Outcome outcome = process(claimed);
		transaction.executeWithoutResult(status -> apply(claimed, outcome));
	}

	private long ensureRow(long passId, long partnerId, PartnerLedgerKind kind) {
		Optional<PartnerLedgerSync> existing = syncs.findByPassId(passId);
		if (existing.isPresent()) {
			return existing.get().getId();
		}
		try {
			return transaction.execute(status -> syncs
				.saveAndFlush(PartnerLedgerSync.pending(passId, partnerId, kind, clock.instant()))
				.getId());
		} catch (DataIntegrityViolationException e) {
			// 같은 이벤트가 동시에 두 번 왔다(재전송). 먼저 만든 줄을 쓴다.
			if (UniqueKeys.isViolationOf(e, PartnerLedgerSync.PASS_UNIQUE_KEY)) {
				return syncs.findByPassId(passId).orElseThrow().getId();
			}
			throw e;
		}
	}

	// 트랜잭션 밖. 구글을 부르고 결과만 돌려준다(상태는 apply가 바꾼다).
	private Outcome process(Claimed claimed) {
		try {
			Optional<String> spreadsheet = properties.spreadsheetFor(claimed.partnerId());
			if (spreadsheet.isEmpty()) {
				return new Unsynced(PartnerLedgerReason.NOT_CONFIGURED);
			}
			String tab = properties.tabName();
			List<List<String>> values = sheets.read(spreadsheet.get(), tab);
			if (!PartnerLedgerRow.headerMatches(values)) {
				return new Unsynced(PartnerLedgerReason.HEADER_MISMATCH);
			}
			Optional<Integer> written = PartnerLedgerRow.rowOf(values, claimed.passId());
			if (written.isPresent()) {
				return new Written(written.get());
			}
			return new Written(sheets.append(spreadsheet.get(), tab, rowOf(claimed).cells()));
		} catch (SheetsUnavailable e) {
			log.warn("구글 시트 장애로 연계 학원 장부 반영을 미룬다: 반영 {}, 시도 {}, {}", claimed.id(), claimed.attempts(),
					e.getMessage());
			return new RetryLater(e.retryAfter());
		} catch (SheetsRejected e) {
			return new Unsynced(reasonOf(e.rejection()));
		} catch (RuntimeException e) {
			log.error("연계 학원 장부 반영 중 예상하지 못한 오류: 반영 {}", claimed.id(), e);
			return new Unsynced(PartnerLedgerReason.APP_ERROR);
		}
	}

	// 사용 기록과 패스에서 줄을 만든다. 없으면(있어서는 안 되는 자료) 앱 오류로 미반영이 된다.
	private PartnerLedgerRow rowOf(Claimed claimed) {
		Pass pass = passes.findById(claimed.passId())
			.orElseThrow(() -> new IllegalStateException("장부에 쓸 패스가 없다: " + claimed.passId()));
		return switch (claimed.kind()) {
			case PARTNER_DISCOUNT -> {
				PartnerRedemption used = partnerRedemptions.findByPassId(claimed.passId())
					.orElseThrow(() -> new IllegalStateException("연계 학원 사용 기록이 없다: 패스 " + claimed.passId()));
				yield PartnerLedgerRow.partnerDiscount(claimed.passId(), dateOf(used.getRedeemedAt()),
						pass.getStudentName(), pass.getPassNo(), used.getOriginalFee(), used.getDiscountRate(),
						used.getDiscountAmount(), used.getPayableAmount(), campusName(pass.getIssuerCampusId()));
			}
			case USED_AT_JEY -> {
				Redemption used = redemptions.findByPassId(claimed.passId())
					.orElseThrow(() -> new IllegalStateException("데스크 사용 기록이 없다: 패스 " + claimed.passId()));
				yield PartnerLedgerRow.usedAtJey(claimed.passId(), dateOf(used.getRedeemedAt()), pass.getStudentName(),
						pass.getPassNo(), campusName(used.getCampusId()));
			}
		};
	}

	private LocalDate dateOf(Instant at) {
		return LocalDate.ofInstant(at, clock.getZone());
	}

	private String campusName(Long campusId) {
		if (campusId == null) {
			throw new IllegalStateException("장부에 쓸 지점이 없다");
		}
		return catalog.findCampus(campusId)
			.map(CampusInfo::name)
			.orElseThrow(() -> new IllegalStateException("장부에 쓸 지점이 없다: " + campusId));
	}

	// 구글이 거절한 이유를 장부의 미반영 이유로 바꾼다. 파일이 없는 것은 설정이 틀린 것이다.
	private static PartnerLedgerReason reasonOf(SheetsRejection rejection) {
		return switch (rejection) {
			case TAB_NOT_FOUND -> PartnerLedgerReason.TAB_NOT_FOUND;
			case ACCESS_DENIED -> PartnerLedgerReason.ACCESS_DENIED;
			case SPREADSHEET_NOT_FOUND -> PartnerLedgerReason.NOT_CONFIGURED;
			case REJECTED -> PartnerLedgerReason.GOOGLE_REJECTED;
		};
	}

	// 트랜잭션 안. 그사이 다른 쪽이 다시 맡았으면(이 시도가 5분을 넘겼다) 결과를 적지 않는다.
	private void apply(Claimed claimed, Outcome outcome) {
		PartnerLedgerSync sync = syncs.findByIdForUpdate(claimed.id()).orElseThrow();
		if (sync.getStatus() != PartnerLedgerStatus.IN_PROGRESS
				|| !sync.getLastAttemptAt().equals(claimed.lastAttemptAt())) {
			log.warn("연계 학원 장부 반영의 결과({})를 적지 않는다(다른 시도가 맡았다): 반영 {}", outcome.getClass().getSimpleName(),
					claimed.id());
			return;
		}
		Instant now = clock.instant();
		switch (outcome) {
			case Written written -> {
				sync.written(written.row(), now);
				auditLog.record(AuditEntry.of(DongtanpassAuditAction.PARTNER_LEDGER_WRITTEN)
					.target(PassRefs.pass(sync.getPassId()))
					.with("partnerId", sync.getPartnerId())
					.with("kind", sync.getKind())
					.with("row", written.row()));
			}
			case RetryLater retry -> sync.retryLater(now, retry.retryAfter());
			case Unsynced unsynced -> {
				log.info("연계 학원 장부에 반영하지 못했다: 반영 {}, 이유 {}", claimed.id(), unsynced.reason());
				sync.unsynced(unsynced.reason());
			}
		}
		if (sync.getStatus() == PartnerLedgerStatus.UNSYNCED
				&& sync.getReason() == PartnerLedgerReason.GOOGLE_UNAVAILABLE) {
			log.warn("구글 장애가 이어져 연계 학원 장부 반영을 그만둔다(관리자가 다시 반영한다): 반영 {}", claimed.id());
		}
	}

	/** 맡은 순간의 값. 트랜잭션 밖에서 쓴다. */
	private record Claimed(long id, long passId, long partnerId, PartnerLedgerKind kind, int attempts,
			Instant lastAttemptAt) {

		static Claimed of(PartnerLedgerSync sync) {
			return new Claimed(sync.getId(), sync.getPassId(), sync.getPartnerId(), sync.getKind(), sync.getAttempts(),
					sync.getLastAttemptAt());
		}

	}

	private sealed interface Outcome {
	}

	private record Written(int row) implements Outcome {
	}

	private record RetryLater(Duration retryAfter) implements Outcome {
	}

	private record Unsynced(PartnerLedgerReason reason) implements Outcome {
	}

}
