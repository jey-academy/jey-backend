package com.jey.billing.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.jey.billing.domain.InvoiceRepository;
import com.jey.billing.domain.LedgerSync;
import com.jey.billing.domain.LedgerSyncReason;
import com.jey.billing.domain.LedgerSyncRepository;
import com.jey.billing.domain.LedgerSyncStateException;
import com.jey.billing.domain.LedgerSyncStatus;
import com.jey.billing.domain.Payment;
import com.jey.billing.domain.PaymentRepository;
import com.jey.billing.domain.ledger.LedgerFormats;
import com.jey.billing.domain.ledger.LedgerLayout;
import com.jey.billing.domain.ledger.LedgerLayoutException;
import com.jey.billing.domain.ledger.LedgerRow;
import com.jey.billing.domain.ledger.LedgerTarget;
import com.jey.core.audit.api.AuditEntry;
import com.jey.core.audit.api.AuditLog;
import com.jey.core.member.api.MemberRefs;
import com.jey.core.shared.BusinessException;
import com.jey.core.shared.UniqueKeys;

/**
 * 결제를 지점의 장부(구글 시트)에 쓴다(ADR-0044). 결제마다 {@link LedgerSync} 한 줄이 상태를 갖는다.
 *
 * <p>한 번의 시도: (트랜잭션) 잠가 읽고 맡기 → (트랜잭션 밖) 값 모으기, 장부 잠금, 탭 읽기, 쓸 줄 찾기, 쓰기 → (트랜잭션) 결과 적기와 감사 로그.
 * 구글을 부르는 동안 DB 트랜잭션을 쥐지 않는다. 같은 결제를 두 번 쓰지 않는 것은 비고의 표시({@code 앱:결제-123})로 본다
 * — 앞선 시도가 쓰고 응답을 잃었으면 다음 시도가 그 표시를 찾아 반영됨으로 적는다.
 *
 * <p>이 클래스의 메서드는 트랜잭션 밖에서 부른다(결제 이벤트 리스너, 스케줄러, 관리자 요청).
 */
@Service
public class LedgerSyncService {

	private static final Logger log = LoggerFactory.getLogger(LedgerSyncService.class);

	/** 다른 결제가 같은 장부를 쓰는 중이면 이만큼 기다려 본다. 한 번의 쓰기는 보통 1~2초다. */
	static final Duration LOCK_WAIT = Duration.ofSeconds(5);

	/** 기다려도 잠금을 못 잡으면 이만큼 뒤로 미룬다(시도로 세지 않는다). 스케줄러가 집는다. */
	static final Duration POSTPONE = Duration.ofSeconds(30);

	/** 한 번 부를 때 이어서 하는 시도의 수(쓴 뒤 바로 취소 처리까지). */
	private static final int MAX_ROUNDS = 2;

	/** 스케줄러가 한 번에 집는 줄 수. 남은 줄은 다음 주기에 집는다. */
	static final int DUE_BATCH = 50;

	private final LedgerSyncRepository syncs;

	private final PaymentRepository payments;

	private final InvoiceRepository invoices;

	private final LedgerEntryAssembler assembler;

	private final LedgerSheets sheets;

	private final LedgerLock lock;

	private final LedgerProperties properties;

	private final AuditLog auditLog;

	private final TransactionTemplate transaction;

	private final Clock clock;

	LedgerSyncService(LedgerSyncRepository syncs, PaymentRepository payments, InvoiceRepository invoices,
			LedgerEntryAssembler assembler, LedgerSheets sheets, LedgerLock lock, LedgerProperties properties,
			AuditLog auditLog, TransactionTemplate transaction, Clock clock) {
		this.syncs = syncs;
		this.payments = payments;
		this.invoices = invoices;
		this.assembler = assembler;
		this.sheets = sheets;
		this.lock = lock;
		this.properties = properties;
		this.auditLog = auditLog;
		this.transaction = transaction;
		this.clock = clock;
	}

	/** 결제를 받았다. 반영 상태 줄을 만들고(이미 있으면 그대로) 바로 한 번 써 본다. 같은 결제로 다시 불러도 결과가 같다. */
	public void recordPayment(long paymentId) {
		attempt(ensureRow(paymentId));
	}

	/**
	 * 결제를 취소했다. 장부에서 이 결제의 표시를 찾으면 비고에 취소 표시를 하고, 쓴 적이 없으면 쓰지 않은 채 끝낸다.
	 * 앱이 쓴 줄이 있는데 표시를 하지 못하면(표시가 사라짐, 탭·머리글을 읽지 못함, 장부 연결이 꺼짐) 미반영으로 남긴다. 다시 불러도 결과가 같다.
	 */
	public void recordCancel(long paymentId) {
		long id = ensureRow(paymentId);
		transaction.executeWithoutResult(status -> {
			LedgerSync sync = syncs.findByIdForUpdate(id).orElseThrow();
			Payment payment = payments.findById(paymentId).orElseThrow();
			if (payment.getCanceledAt() == null) {
				throw new IllegalStateException("취소하지 않은 결제다: " + paymentId);
			}
			sync.requestCancel(LocalDate.ofInstant(payment.getCanceledAt(), clock.getZone()), clock.instant());
		});
		attempt(id);
	}

	/**
	 * 관리자가 장부(탭, 반 블록, 빈 줄, 권한)를 고친 뒤 미반영 줄을 다시 반영한다. 바로 한 번 시도하고 그 뒤의 상태를 돌려준다:
	 * 반영됨, 또 미반영, 대기 중(구글 장애나 다른 결제의 쓰기 때문에 잠시 뒤 다시 한다), 시도 중(그 순간 다른 쪽이 맡았다),
	 * 취소됨(취소한 결제라 취소 처리를 마쳤다).
	 *
	 * @throws BusinessException 줄이 없을 때(404), 미반영이 아닐 때(409)
	 */
	public LedgerSyncView retryUnsynced(long id) {
		transaction.executeWithoutResult(status -> {
			LedgerSync sync = syncs.findByIdForUpdate(id)
				.orElseThrow(() -> new BusinessException(BillingErrorCode.LEDGER_SYNC_NOT_FOUND));
			try {
				sync.retryByAdmin(clock.instant());
			} catch (LedgerSyncStateException e) {
				throw new BusinessException(BillingErrorCode.LEDGER_SYNC_NOT_RETRYABLE);
			}
		});
		attempt(id);
		return LedgerSyncView.of(syncs.findById(id).orElseThrow());
	}

	/** 미반영 줄, 최근 것부터. 지점을 주지 않으면 전 지점이다(관리자 전용 — 지점 범위를 걸지 않는다). */
	public Page<LedgerSyncView> listUnsynced(Long campusId, int page, int size) {
		PageRequest request = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id"));
		Page<LedgerSync> found = campusId == null ? syncs.findByStatus(LedgerSyncStatus.UNSYNCED, request)
				: syncs.findByStatusAndCampusId(LedgerSyncStatus.UNSYNCED, campusId, request);
		return found.map(LedgerSyncView::of);
	}

	/** 다시 시도할 때가 된 줄(구글 장애 뒤, 미뤄 둔 것, 맡은 서버가 내려간 것)을 집어 시도한다. 스케줄러가 부른다. */
	public int retryDue() {
		Instant now = clock.instant();
		List<Long> ids = syncs.findDueIds(now, now.minus(LedgerSync.STALE_AFTER), PageRequest.of(0, DUE_BATCH));
		for (long id : ids) {
			try {
				attempt(id);
			} catch (RuntimeException e) {
				log.error("장부 반영을 다시 시도하지 못했다: 반영 {}", id, e);
			}
		}
		return ids.size();
	}

	/**
	 * 시도한다. 다른 쪽이 맡고 있거나 때가 아니면 아무것도 하지 않는다. 결과를 적은 뒤 바로 다시 할 일이 생겼으면(쓰는 사이 결제가 취소됐다)
	 * 한 번 더 이어서 한다 — 취소 처리가 스케줄러를 기다리지 않게.
	 */
	void attempt(long id) {
		for (int round = 0; round < MAX_ROUNDS; round++) {
			Instant now = clock.instant();
			Claimed claimed = transaction.execute(status -> {
				LedgerSync sync = syncs.findByIdForUpdate(id).orElseThrow();
				return sync.claim(now) ? Claimed.of(sync) : null;
			});
			if (claimed == null) {
				return;
			}
			Outcome outcome = process(claimed);
			Boolean dueAgain = transaction.execute(status -> apply(claimed, outcome));
			if (!Boolean.TRUE.equals(dueAgain)) {
				return;
			}
		}
	}

	private long ensureRow(long paymentId) {
		Optional<LedgerSync> existing = syncs.findByPaymentId(paymentId);
		if (existing.isPresent()) {
			return existing.get().getId();
		}
		try {
			return transaction.execute(status -> {
				Payment payment = payments.findById(paymentId)
					.orElseThrow(() -> new IllegalStateException("결제가 없다: " + paymentId));
				YearMonth month = invoices.findById(payment.getInvoiceId()).orElseThrow().getTargetMonth();
				return syncs.saveAndFlush(LedgerSync.pending(paymentId, payment.getCampusId(), payment.getStudentId(),
						month, clock.instant())).getId();
			});
		} catch (DataIntegrityViolationException e) {
			// 결제와 결제 취소의 리스너가 동시에 처음 만들었다. 먼저 만든 줄을 쓴다.
			if (UniqueKeys.isViolationOf(e, LedgerSync.PAYMENT_UNIQUE_KEY)) {
				return syncs.findByPaymentId(paymentId).orElseThrow().getId();
			}
			throw e;
		}
	}

	// 트랜잭션 밖. 구글을 부르고 결과만 돌려준다(상태는 apply가 바꾼다).
	private Outcome process(Claimed claimed) {
		boolean cancel = claimed.cancelAttempt();
		try {
			Optional<String> spreadsheet = properties.spreadsheetFor(claimed.campusId());
			if (spreadsheet.isEmpty()) {
				return cancel ? cannotMark(claimed, LedgerSyncReason.NOT_CONFIGURED)
						: new Unsynced(LedgerSyncReason.NOT_CONFIGURED);
			}
			LedgerEntryAssembler.Assembled assembled = cancel ? null : assembler.assemble(claimed.paymentId());
			if (assembled instanceof LedgerEntryAssembler.Assembled.Rejected rejected) {
				return new Unsynced(rejected.reason());
			}
			Optional<LedgerLock.Held> held = lock.tryAcquire(spreadsheet.get(), LOCK_WAIT);
			if (held.isEmpty()) {
				return new Postpone();
			}
			try (LedgerLock.Held hold = held.get()) {
				return cancel ? markCanceled(spreadsheet.get(), claimed, hold)
						: write(spreadsheet.get(), claimed, (LedgerEntryAssembler.Assembled.Ready) assembled, hold);
			}
		} catch (LedgerSheetsUnavailable e) {
			log.warn("구글 시트 장애로 장부 반영을 미룬다: 반영 {}, 시도 {}, {}", claimed.id(), claimed.attempts(), e.getMessage());
			return new RetryLater(e.retryAfter());
		} catch (LedgerLockUnavailable e) {
			log.warn("장부 잠금 저장소 장애로 장부 반영을 미룬다: 반영 {}, 시도 {}", claimed.id(), claimed.attempts(), e);
			return new RetryLater(null);
		} catch (LedgerSheetsRejected e) {
			return cancel && e.reason() == LedgerSyncReason.TAB_NOT_FOUND ? cannotMark(claimed, e.reason())
					: new Unsynced(e.reason());
		} catch (LedgerLayoutException e) {
			return cancel ? cannotMark(claimed, e.reason()) : new Unsynced(e.reason());
		} catch (RuntimeException e) {
			log.error("장부 반영 중 예상하지 못한 오류: 반영 {}", claimed.id(), e);
			return new Unsynced(LedgerSyncReason.APP_ERROR);
		}
	}

	// 취소 표시를 할 수 없다. 앱이 쓴 줄이 없으면 쓰지 않은 채 끝내고, 있으면 미반영으로 남긴다(장부에 납부한 줄이 남는다).
	private static Outcome cannotMark(Claimed claimed, LedgerSyncReason reason) {
		return claimed.hasWrittenRow() ? new Unsynced(reason) : new CancelUnwritten();
	}

	private Outcome write(String spreadsheet, Claimed claimed, LedgerEntryAssembler.Assembled.Ready ready,
			LedgerLock.Held hold) {
		String tab = claimed.tab();
		LedgerLayout layout = LedgerLayout.parse(sheets.read(spreadsheet, tab), claimed.targetMonth());
		return switch (layout.find(ready.className(), LedgerFormats.marker(claimed.paymentId()))) {
			case LedgerTarget.AlreadyWritten written -> new Written(tab, written.row());
			case LedgerTarget.Missing missing -> new Unsynced(missing.reason());
			case LedgerTarget.EmptyRow empty -> {
				writeCells(spreadsheet, tab, layout.cells(empty.row(), LedgerRow.of(ready.entry())), hold);
				yield new Written(tab, empty.row());
			}
		};
	}

	// 쓰기 전이라도 표시를 찾는다 — 앞선 시도가 쓰고 응답을 잃었을 수 있다.
	private Outcome markCanceled(String spreadsheet, Claimed claimed, LedgerLock.Held hold) {
		String tab = claimed.tab();
		LedgerLayout layout = LedgerLayout.parse(sheets.read(spreadsheet, tab), claimed.targetMonth());
		Optional<Integer> row = layout.rowOf(LedgerFormats.marker(claimed.paymentId()));
		if (row.isEmpty()) {
			return cannotMark(claimed, LedgerSyncReason.MARKER_NOT_FOUND);
		}
		writeCells(spreadsheet, tab, Map.of(layout.noteCell(row.get()),
				LedgerFormats.canceledNote(claimed.paymentId(), claimed.canceledOn())), hold);
		return new CancelMarked(tab, row.get());
	}

	// 쓰기의 결과를 모르면(시간 초과, 연결 끊김, 5xx) 잠금을 시간이 다할 때까지 남긴다. 구글이 늦게 반영할 수 있어서, 바로 풀면
	// 다음 결제가 같은 빈 줄을 집고 늦게 들어온 이 쓰기에 덮인다.
	private void writeCells(String spreadsheet, String tab, Map<String, Object> cells, LedgerLock.Held hold) {
		try {
			sheets.write(spreadsheet, tab, cells);
		} catch (LedgerSheetsUnavailable e) {
			hold.keepUntilExpiry();
			throw e;
		}
	}

	// 트랜잭션 안. 그사이 다른 쪽이 다시 맡았으면(이 시도가 5분을 넘겼다) 결과를 적지 않는다.
	// 돌려주는 값은 바로 다시 할 일이 생겼는지(쓰는 사이 결제가 취소됐다).
	private boolean apply(Claimed claimed, Outcome outcome) {
		LedgerSync sync = syncs.findByIdForUpdate(claimed.id()).orElseThrow();
		if (sync.getStatus() != LedgerSyncStatus.IN_PROGRESS || !sync.getLastAttemptAt().equals(claimed.lastAttemptAt())) {
			log.warn("장부 반영의 결과({})를 적지 않는다(다른 시도가 맡았다): 반영 {}", outcome.getClass().getSimpleName(),
					claimed.id());
			return false;
		}
		Instant now = clock.instant();
		// 쓰기 시도가 쓰지 못하고 끝났는데 그사이 결제가 취소됐다. 취소된 결제를 "장부를 고칠 결제"로 남기지 않는다.
		if (!claimed.cancelAttempt() && sync.isCancelRequested() && !(outcome instanceof Written)) {
			sync.requeueForCancel(now);
			return true;
		}
		switch (outcome) {
			case Written written -> {
				sync.written(written.tab(), written.row(), now);
				audit(BillingAuditAction.LEDGER_WRITTEN, sync, written.tab(), written.row());
			}
			case CancelMarked marked -> {
				sync.cancelMarked(marked.row(), now);
				audit(BillingAuditAction.LEDGER_CANCEL_MARKED, sync, marked.tab(), marked.row());
			}
			case CancelUnwritten unwritten -> sync.cancelUnwritten(now);
			case RetryLater retry -> sync.retryLater(now, retry.retryAfter());
			case Unsynced unsynced -> {
				log.info("장부에 반영하지 못했다: 반영 {}, 이유 {}", claimed.id(), unsynced.reason());
				sync.unsynced(unsynced.reason());
			}
			case Postpone postpone -> sync.postpone(now.plus(POSTPONE));
		}
		if (sync.getStatus() == LedgerSyncStatus.UNSYNCED && sync.getReason() == LedgerSyncReason.GOOGLE_UNAVAILABLE) {
			log.warn("구글 장애가 이어져 다시 시도를 그만둔다(관리자가 다시 반영한다): 반영 {}", claimed.id());
		}
		return sync.getStatus() == LedgerSyncStatus.PENDING && !sync.getNextAttemptAt().isAfter(now);
	}

	private void audit(BillingAuditAction action, LedgerSync sync, String tab, int row) {
		auditLog.record(AuditEntry.of(action)
			.target(MemberRefs.student(sync.getStudentId()))
			.campus(sync.getCampusId())
			.with("paymentId", sync.getPaymentId())
			.with("tab", tab)
			.with("row", row));
	}

	/** 맡은 순간의 값. 트랜잭션 밖에서 쓴다. */
	private record Claimed(long id, long paymentId, long campusId, YearMonth targetMonth, LocalDate canceledOn,
			boolean hasWrittenRow, int attempts, Instant lastAttemptAt) {

		static Claimed of(LedgerSync sync) {
			return new Claimed(sync.getId(), sync.getPaymentId(), sync.getCampusId(), sync.getTargetMonth(),
					sync.getCanceledOn(), sync.hasWrittenRow(), sync.getAttempts(), sync.getLastAttemptAt());
		}

		/** 이번 시도가 취소 처리인지(맡을 때 취소 요청이 와 있었다). */
		boolean cancelAttempt() {
			return canceledOn != null;
		}

		/** 대상 월의 탭 이름({@code 2026-11}). */
		String tab() {
			return targetMonth.toString();
		}

	}

	private sealed interface Outcome {
	}

	private record Written(String tab, int row) implements Outcome {
	}

	private record CancelMarked(String tab, int row) implements Outcome {
	}

	private record CancelUnwritten() implements Outcome {
	}

	private record RetryLater(Duration retryAfter) implements Outcome {
	}

	private record Unsynced(LedgerSyncReason reason) implements Outcome {
	}

	private record Postpone() implements Outcome {
	}

}
