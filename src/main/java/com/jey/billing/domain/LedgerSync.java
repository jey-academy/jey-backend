package com.jey.billing.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.List;

import com.jey.core.shared.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 결제 하나를 장부에 쓰는 일의 상태({@link LedgerSyncStatus}). {@link #pending}으로 만든다.
 *
 * <p>한 번의 시도는 "맡기({@link #claim}) → 시트 읽고 쓰기(트랜잭션 밖) → 결과 적기"다. 맡기는 행을 잠가 읽은 뒤에 부른다
 * ({@code LedgerSyncRepository#findByIdForUpdate}) — 두 쪽이 같은 결제를 동시에 쓰지 않게.
 * 결과는 {@link #written}, {@link #retryLater}(구글 장애), {@link #unsynced}(사람이 고칠 이유), {@link #cancelDone} 중 하나다.
 *
 * <p>결제를 취소하면 {@link #requestCancel}로 다시 대기시키고, 다음 시도가 시트에서 이 결제의 표시를 찾아 취소 표시를 남긴다.
 * 쓰기 전이라도 시트를 본다 — 앞선 시도가 쓰고 응답을 잃었을 수 있다.
 */
@Entity
@Table(name = "billing_ledger_sync")
public class LedgerSync extends BaseEntity {

	public static final String PAYMENT_UNIQUE_KEY = "uk_billing_ledger_sync_payment";

	/** 이만큼 넘게 시도 중이면 맡은 서버가 내려간 것으로 보고 다시 맡는다. 한 번의 시도(구글 요청 몇 번)보다 훨씬 길다. */
	public static final Duration STALE_AFTER = Duration.ofMinutes(5);

	/** 구글 장애 뒤 다시 시도하기까지의 간격. n번째 시도가 실패하면 n번째 간격 뒤에 다시 한다. 다 쓰면 미반영. */
	public static final List<Duration> RETRY_DELAYS = List.of(Duration.ofMinutes(1), Duration.ofMinutes(5),
			Duration.ofMinutes(15), Duration.ofHours(1), Duration.ofHours(3));

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "payment_id", nullable = false, updatable = false)
	private Long paymentId;

	@Column(name = "campus_id", nullable = false, updatable = false)
	private Long campusId;

	@Column(name = "student_id", nullable = false, updatable = false)
	private Long studentId;

	@Convert(converter = YearMonthConverter.class)
	@Column(name = "target_month", nullable = false, updatable = false, length = 7)
	private YearMonth targetMonth;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private LedgerSyncStatus status;

	@Enumerated(EnumType.STRING)
	@Column(name = "reason", length = 40)
	private LedgerSyncReason reason;

	@Column(name = "attempts", nullable = false)
	private int attempts;

	@Column(name = "next_attempt_at")
	private Instant nextAttemptAt;

	@Column(name = "last_attempt_at")
	private Instant lastAttemptAt;

	@Column(name = "sheet_tab", length = 20)
	private String sheetTab;

	@Column(name = "sheet_row")
	private Integer sheetRow;

	@Column(name = "synced_at")
	private Instant syncedAt;

	@Column(name = "canceled_on")
	private LocalDate canceledOn;

	@Column(name = "cancel_marked", nullable = false)
	private boolean cancelMarked;

	protected LedgerSync() {
	}

	private LedgerSync(long paymentId, long campusId, long studentId, YearMonth targetMonth, Instant now) {
		this.paymentId = paymentId;
		this.campusId = campusId;
		this.studentId = studentId;
		this.targetMonth = targetMonth;
		this.status = LedgerSyncStatus.PENDING;
		this.nextAttemptAt = micros(now);
	}

	/** 결제를 받았다. 바로 시도할 수 있다. */
	public static LedgerSync pending(long paymentId, long campusId, long studentId, YearMonth targetMonth, Instant now) {
		if (targetMonth == null || now == null) {
			throw new IllegalArgumentException("targetMonth, now는 비울 수 없다");
		}
		return new LedgerSync(paymentId, campusId, studentId, targetMonth, now);
	}

	/**
	 * 이번 시도를 맡는다. 대기 중이고 다음 시도 시각이 됐거나, 시도 중인 채로 {@link #STALE_AFTER}를 넘겼을 때만 맡는다.
	 * 행을 잠가 읽은 뒤에 부른다.
	 *
	 * @return 맡았는지. 맡지 못했으면 아무것도 바꾸지 않는다
	 */
	public boolean claim(Instant now) {
		boolean due = status == LedgerSyncStatus.PENDING && !nextAttemptAt.isAfter(now);
		boolean stale = status == LedgerSyncStatus.IN_PROGRESS && lastAttemptAt.plus(STALE_AFTER).isBefore(now);
		if (!due && !stale) {
			return false;
		}
		this.status = LedgerSyncStatus.IN_PROGRESS;
		this.attempts++;
		this.lastAttemptAt = micros(now);
		this.nextAttemptAt = null;
		return true;
	}

	/**
	 * 장부의 그 줄에 썼다(또는 앞선 시도가 쓴 줄을 찾았다). 쓰는 사이 결제가 취소됐으면 취소 표시를 하러 바로 다시 대기한다.
	 */
	public void written(String tab, int row, Instant now) {
		requireInProgress();
		this.sheetTab = tab;
		this.sheetRow = row;
		this.syncedAt = micros(now);
		if (canceledOn == null) {
			this.status = LedgerSyncStatus.SYNCED;
		} else {
			waitFrom(now);
		}
	}

	/**
	 * 구글 장애(시간 초과, 연결 끊김, 5xx, 429). 시도 횟수에 맞는 간격 뒤에 다시 한다. 구글이 알려 준 기다릴 시간이 더 길면 그것을 따른다.
	 * 간격을 다 썼으면 {@link LedgerSyncReason#GOOGLE_UNAVAILABLE}로 미반영.
	 *
	 * @param retryAfter 구글이 알려 준 기다릴 시간. 없으면 {@code null}
	 */
	public void retryLater(Instant now, Duration retryAfter) {
		requireInProgress();
		if (attempts > RETRY_DELAYS.size()) {
			unsynced(LedgerSyncReason.GOOGLE_UNAVAILABLE);
			return;
		}
		Duration delay = RETRY_DELAYS.get(attempts - 1);
		if (retryAfter != null && retryAfter.compareTo(delay) > 0) {
			delay = retryAfter;
		}
		this.status = LedgerSyncStatus.PENDING;
		this.nextAttemptAt = micros(now.plus(delay));
	}

	/** 사람이 장부나 설정을 고쳐야 하는 이유로 쓰지 못했다. 자동으로 다시 하지 않는다. */
	public void unsynced(LedgerSyncReason reason) {
		requireInProgress();
		if (reason == null) {
			throw new IllegalArgumentException("reason은 비울 수 없다");
		}
		this.status = LedgerSyncStatus.UNSYNCED;
		this.reason = reason;
		this.nextAttemptAt = null;
	}

	/**
	 * 관리자가 장부를 고친 뒤 다시 반영한다. 시도 횟수를 처음부터 센다.
	 *
	 * @throws LedgerSyncStateException 미반영이 아닐 때
	 */
	public void retryByAdmin(Instant now) {
		if (status != LedgerSyncStatus.UNSYNCED) {
			throw new LedgerSyncStateException(status);
		}
		waitFrom(now);
	}

	/**
	 * 결제를 취소했다. 시도 중이면 그 시도가 끝난 뒤({@link #written} 등) 다시 대기하고, 아니면 바로 다시 대기한다.
	 * 이미 취소를 받았으면 아무것도 하지 않는다(같은 이벤트가 다시 와도 같다).
	 *
	 * @param canceledOn 취소한 날(한국 날짜). 취소 표시에 쓴다
	 */
	public void requestCancel(LocalDate canceledOn, Instant now) {
		if (canceledOn == null || now == null) {
			throw new IllegalArgumentException("canceledOn, now는 비울 수 없다");
		}
		if (this.canceledOn != null) {
			return;
		}
		this.canceledOn = canceledOn;
		if (status != LedgerSyncStatus.IN_PROGRESS) {
			waitFrom(now);
		}
	}

	/**
	 * 취소 처리를 마쳤다.
	 *
	 * @param marked 장부에서 이 결제의 줄을 찾아 비고에 취소 표시를 했는지(쓴 줄이 없었으면 {@code false})
	 */
	public void cancelDone(boolean marked, Instant now) {
		requireInProgress();
		if (canceledOn == null) {
			throw new IllegalStateException("취소 요청이 없는 장부 반영이다");
		}
		this.status = LedgerSyncStatus.CANCELED;
		this.cancelMarked = marked;
		this.nextAttemptAt = null;
	}

	public boolean isCancelRequested() {
		return canceledOn != null;
	}

	private void waitFrom(Instant now) {
		this.status = LedgerSyncStatus.PENDING;
		this.reason = null;
		this.attempts = 0;
		this.nextAttemptAt = micros(now);
	}

	private void requireInProgress() {
		if (status != LedgerSyncStatus.IN_PROGRESS) {
			throw new IllegalStateException("시도 중이 아닌 장부 반영이다: " + status);
		}
	}

	// DB는 마이크로초까지 저장한다. 만든 직후의 값과 나중에 읽은 값이 같도록 미리 자른다.
	private static Instant micros(Instant at) {
		return at.truncatedTo(ChronoUnit.MICROS);
	}

	public Long getId() {
		return id;
	}

	public long getPaymentId() {
		return paymentId;
	}

	public long getCampusId() {
		return campusId;
	}

	public long getStudentId() {
		return studentId;
	}

	public YearMonth getTargetMonth() {
		return targetMonth;
	}

	public LedgerSyncStatus getStatus() {
		return status;
	}

	public LedgerSyncReason getReason() {
		return reason;
	}

	public int getAttempts() {
		return attempts;
	}

	public Instant getNextAttemptAt() {
		return nextAttemptAt;
	}

	public Instant getLastAttemptAt() {
		return lastAttemptAt;
	}

	public String getSheetTab() {
		return sheetTab;
	}

	public Integer getSheetRow() {
		return sheetRow;
	}

	public Instant getSyncedAt() {
		return syncedAt;
	}

	public LocalDate getCanceledOn() {
		return canceledOn;
	}

	public boolean isCancelMarked() {
		return cancelMarked;
	}

}
