package com.jey.dongtanpass.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import com.jey.core.shared.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 패스 사용 하나를 연계 학원 장부(구글 시트)에 쓰는 일의 상태({@link PartnerLedgerStatus}). {@link #pending}으로 만든다.
 * 패스는 한 번만 쓰이므로 패스 하나에 한 줄이다.
 *
 * <p>한 번의 시도는 "맡기({@link #claim}) → 시트 읽고 붙이기(트랜잭션 밖) → 결과 적기"다. 맡기는 행을 잠가 읽은 뒤에 부른다
 * ({@code PartnerLedgerSyncRepository#findByIdForUpdate}) — 두 쪽이 같은 기록을 동시에 쓰지 않게.
 * 결과는 {@link #written}, {@link #retryLater}(구글 장애), {@link #unsynced}(사람이 고칠 이유) 중 하나다.
 *
 * <p>수납 모듈의 장부 반영(ADR-0044)과 같은 규칙에서 취소와 미루기를 뺐다(패스 사용은 되돌리지 않고, 줄을 붙이므로 잠금이 없다 — ADR-0048).
 */
@Entity
@Table(name = "dongtanpass_partner_ledger_sync")
public class PartnerLedgerSync extends BaseEntity {

	public static final String PASS_UNIQUE_KEY = "uk_dongtanpass_partner_ledger_sync_pass";

	/** 이만큼 넘게 시도 중이면 맡은 서버가 내려간 것으로 보고 다시 맡는다. 한 번의 시도(구글 요청 둘)보다 훨씬 길다. */
	public static final Duration STALE_AFTER = Duration.ofMinutes(5);

	/** 구글 장애 뒤 다시 시도하기까지의 간격. n번째 시도가 실패하면 n번째 간격 뒤에 다시 한다. 다 쓰면 미반영. */
	public static final List<Duration> RETRY_DELAYS = List.of(Duration.ofMinutes(1), Duration.ofMinutes(5),
			Duration.ofMinutes(15), Duration.ofHours(1), Duration.ofHours(3));

	/** 한 줄의 시도 한도(간격을 다 쓴 마지막 시도까지). 오래 멈춘 시도를 다시 맡는 것도 여기까지다. */
	static final int MAX_ATTEMPTS = RETRY_DELAYS.size() + 1;

	/** 구글이 알려 준 기다릴 시간({@code Retry-After})은 이것까지만 따른다(정한 간격의 가장 긴 값). */
	static final Duration MAX_RETRY_AFTER = RETRY_DELAYS.getLast();

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "pass_id", nullable = false, updatable = false)
	private Long passId;

	@Column(name = "partner_id", nullable = false, updatable = false)
	private Long partnerId;

	@Enumerated(EnumType.STRING)
	@Column(name = "kind", nullable = false, updatable = false, length = 20)
	private PartnerLedgerKind kind;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private PartnerLedgerStatus status;

	@Enumerated(EnumType.STRING)
	@Column(name = "reason", length = 40)
	private PartnerLedgerReason reason;

	@Column(name = "attempts", nullable = false)
	private int attempts;

	@Column(name = "next_attempt_at")
	private Instant nextAttemptAt;

	@Column(name = "last_attempt_at")
	private Instant lastAttemptAt;

	@Column(name = "sheet_row")
	private Integer sheetRow;

	@Column(name = "synced_at")
	private Instant syncedAt;

	protected PartnerLedgerSync() {
	}

	private PartnerLedgerSync(long passId, long partnerId, PartnerLedgerKind kind, Instant now) {
		this.passId = passId;
		this.partnerId = partnerId;
		this.kind = kind;
		this.status = PartnerLedgerStatus.PENDING;
		this.nextAttemptAt = micros(now);
	}

	/**
	 * 패스를 썼다. 바로 시도할 수 있다.
	 *
	 * @param partnerId 장부의 주인인 연계 학원(할인해 준 학원, 또는 패스를 발급한 학원)
	 */
	public static PartnerLedgerSync pending(long passId, long partnerId, PartnerLedgerKind kind, Instant now) {
		if (kind == null || now == null) {
			throw new IllegalArgumentException("kind, now는 비울 수 없다");
		}
		return new PartnerLedgerSync(passId, partnerId, kind, now);
	}

	/**
	 * 이번 시도를 맡는다. 대기 중이고 다음 시도 시각이 됐거나, 시도 중인 채로 {@link #STALE_AFTER}를 넘겼을 때만 맡는다.
	 * 행을 잠가 읽은 뒤에 부른다. 오래 멈춘 시도가 시도 한도까지 거듭됐으면 맡지 않고 {@link PartnerLedgerReason#APP_ERROR}로 미반영한다.
	 *
	 * @return 맡았는지. 맡지 못했으면 위의 경우 말고는 아무것도 바꾸지 않는다
	 */
	public boolean claim(Instant now) {
		boolean due = status == PartnerLedgerStatus.PENDING && !nextAttemptAt.isAfter(now);
		boolean stale = status == PartnerLedgerStatus.IN_PROGRESS && lastAttemptAt.plus(STALE_AFTER).isBefore(now);
		if (stale && attempts >= MAX_ATTEMPTS) {
			unsynced(PartnerLedgerReason.APP_ERROR);
			return false;
		}
		if (!due && !stale) {
			return false;
		}
		this.status = PartnerLedgerStatus.IN_PROGRESS;
		this.attempts++;
		this.lastAttemptAt = micros(now);
		this.nextAttemptAt = null;
		return true;
	}

	/** 장부의 그 줄에 썼다(또는 앞선 시도가 쓴 줄을 찾았다). */
	public void written(int row, Instant now) {
		requireInProgress();
		this.sheetRow = row;
		this.syncedAt = micros(now);
		this.status = PartnerLedgerStatus.SYNCED;
	}

	/**
	 * 구글 장애(시간 초과, 연결 끊김, 408, 5xx, 429). 시도 횟수에 맞는 간격 뒤에 다시 한다. 구글이 알려 준 기다릴 시간이 더 길면
	 * 그것을 따른다({@link #MAX_RETRY_AFTER}까지). 간격을 다 썼으면 {@link PartnerLedgerReason#GOOGLE_UNAVAILABLE}로 미반영.
	 *
	 * @param retryAfter 구글이 알려 준 기다릴 시간. 없으면 {@code null}
	 */
	public void retryLater(Instant now, Duration retryAfter) {
		requireInProgress();
		if (attempts > RETRY_DELAYS.size()) {
			unsynced(PartnerLedgerReason.GOOGLE_UNAVAILABLE);
			return;
		}
		Duration delay = RETRY_DELAYS.get(attempts - 1);
		if (retryAfter != null && retryAfter.compareTo(delay) > 0) {
			delay = retryAfter.compareTo(MAX_RETRY_AFTER) > 0 ? MAX_RETRY_AFTER : retryAfter;
		}
		this.status = PartnerLedgerStatus.PENDING;
		this.nextAttemptAt = micros(now.plus(delay));
	}

	/** 사람이 장부나 설정을 고쳐야 하는 이유로 쓰지 못했다. 자동으로 다시 하지 않는다. */
	public void unsynced(PartnerLedgerReason reason) {
		requireInProgress();
		if (reason == null) {
			throw new IllegalArgumentException("reason은 비울 수 없다");
		}
		this.status = PartnerLedgerStatus.UNSYNCED;
		this.reason = reason;
		this.nextAttemptAt = null;
	}

	/**
	 * 관리자가 장부를 고친 뒤 다시 반영한다. 시도 횟수를 처음부터 센다.
	 *
	 * @throws PartnerLedgerSyncStateException 미반영이 아닐 때
	 */
	public void retryByAdmin(Instant now) {
		if (status != PartnerLedgerStatus.UNSYNCED) {
			throw new PartnerLedgerSyncStateException(status);
		}
		this.status = PartnerLedgerStatus.PENDING;
		this.reason = null;
		this.attempts = 0;
		this.nextAttemptAt = micros(now);
	}

	private void requireInProgress() {
		if (status != PartnerLedgerStatus.IN_PROGRESS) {
			throw new IllegalStateException("시도 중이 아닌 연계 학원 장부 반영이다: " + status);
		}
	}

	// DB는 마이크로초까지 저장한다. 만든 직후의 값과 나중에 읽은 값이 같도록 미리 자른다.
	private static Instant micros(Instant at) {
		return at.truncatedTo(ChronoUnit.MICROS);
	}

	public Long getId() {
		return id;
	}

	public long getPassId() {
		return passId;
	}

	public long getPartnerId() {
		return partnerId;
	}

	public PartnerLedgerKind getKind() {
		return kind;
	}

	public PartnerLedgerStatus getStatus() {
		return status;
	}

	public PartnerLedgerReason getReason() {
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

	public Integer getSheetRow() {
		return sheetRow;
	}

	public Instant getSyncedAt() {
		return syncedAt;
	}

}
