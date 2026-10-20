package com.jey.dongtanpass.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.regex.Pattern;

import com.jey.core.shared.BaseEntity;
import com.jey.core.shared.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * 동탄고 패스. 방향이 둘이다({@link PassDirection}). 연계 학원이 결제를 확인하고 발급해 JEY 지점에서 쓰거나(ADR-0001),
 * JEY 지점이 다니는 학생의 결제를 확인하고 발급해 연계 학원에서 쓴다. 방향마다 채우는 칸이 다르고 DB의 CHECK가 그 조합을 막는다:
 * 연계 학원 발급분은 {@code partnerId}만, JEY 발급분은 {@code issuerCampusId}·{@code studentId}·{@code discountRate}만 있다.
 * JEY 발급분은 발급 때의 할인율을 남긴다(설정을 바꿔도 이미 준 패스는 그대로). 연계 학원 발급분의 할인율은 받는 지점의 할인 유형에서 읽는다.
 *
 * <p>학부모 번호는 전체 번호 색인만 가진다(ADR-0035). 번호를 꺼낼 수 없고, 데스크가 받은 번호의 색인과 비교하는 데만 쓴다.
 * 만료 여부는 저장하지 않고 만료일과 오늘로 계산한다. 결제 기록은 발급한 뒤 고치지 않는다.
 * 동시에 고치는 요청(사용 처리와 취소 등)은 버전으로 막는다.
 */
@Entity
@Table(name = "dongtanpass_pass")
public class Pass extends BaseEntity {

	/** 요청을 받는 쪽의 검증도 이 값을 쓴다. */
	public static final int MAX_STUDENT_NAME_LENGTH = 50;

	/** JEY 발급분 할인율의 소수 자리(컬럼 DECIMAL(5,4), core의 할인 유형과 같다). */
	public static final int DISCOUNT_RATE_SCALE = 4;

	private static final Pattern LINK_TOKEN_HASH = Pattern.compile("[0-9a-f]{64}");

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "pass_no", nullable = false, updatable = false, length = 9)
	private String passNo;

	@Column(name = "link_token_hash", nullable = false, length = 64)
	private String linkTokenHash;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, updatable = false, length = 20)
	private PassDirection direction;

	@Column(name = "partner_id", updatable = false)
	private Long partnerId;

	@Column(name = "issuer_campus_id", updatable = false)
	private Long issuerCampusId;

	@Column(name = "student_id", updatable = false)
	private Long studentId;

	@Column(name = "student_name", nullable = false, updatable = false, length = MAX_STUDENT_NAME_LENGTH)
	private String studentName;

	@Column(name = "guardian_phone_bidx", nullable = false, updatable = false, length = 64)
	private String guardianPhoneIndex;

	@Column(name = "issued_at", nullable = false, updatable = false)
	private Instant issuedAt;

	@Column(name = "expires_on", nullable = false, updatable = false)
	private LocalDate expiresOn;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private PassStatus status;

	@Column(name = "paid_on", nullable = false, updatable = false)
	private LocalDate paidOn;

	@Column(name = "paid_amount", nullable = false, updatable = false)
	private Money paidAmount;

	@Enumerated(EnumType.STRING)
	@Column(name = "payment_method", nullable = false, updatable = false, length = 20)
	private PaymentMethod paymentMethod;

	@Column(name = "discount_rate", updatable = false, precision = 5, scale = DISCOUNT_RATE_SCALE)
	private BigDecimal discountRate;

	@Version
	@Column(nullable = false)
	private Long version;

	protected Pass() {
	}

	private Pass(PassDirection direction, Long partnerId, Long issuerCampusId, Long studentId, BigDecimal discountRate,
			String passNo, String linkTokenHash, String studentName, String guardianPhoneIndex, Instant issuedAt,
			LocalDate expiresOn, LocalDate paidOn, Money paidAmount, PaymentMethod paymentMethod) {
		this.direction = direction;
		this.partnerId = partnerId;
		this.issuerCampusId = issuerCampusId;
		this.studentId = studentId;
		this.discountRate = discountRate;
		this.passNo = passNo;
		this.linkTokenHash = linkTokenHash;
		this.studentName = studentName;
		this.guardianPhoneIndex = guardianPhoneIndex;
		this.issuedAt = issuedAt;
		this.expiresOn = expiresOn;
		this.status = PassStatus.ISSUED;
		this.paidOn = paidOn;
		this.paidAmount = paidAmount;
		this.paymentMethod = paymentMethod;
	}

	/**
	 * 연계 학원이 발급한 패스(PARTNER_ISSUED)를 만든다. 결제일과 금액이 업무 규칙에 맞는지(미래 날짜, 0원)는 부르는 쪽이 먼저 보고 업무 오류로 답한다.
	 * 여기서는 저장할 수 없는 금액(0원, 1원 미만)을 다시 막는다(다른 길로 만들어도 DB가 조용히 반올림하지 않게).
	 *
	 * @param studentName 앞뒤 공백만 떼고 저장한다(가운데 띄어쓰기는 그대로. 비교는 사용 처리 때 따로 정리해서 한다)
	 * @param guardianPhoneIndex 학부모 번호의 전체 번호 색인
	 * @param zone 발급일을 정하는 시간대. 발급일은 발급 시각을 이 시간대의 날짜로 바꾼 것이고 만료일을 정한다
	 * (발급일을 따로 받지 않는다. 시계를 두 번 읽으면 자정을 사이에 두고 둘이 어긋난다)
	 * @throws IllegalArgumentException 빈 값, 형식에 맞지 않는 패스 번호·링크 해시, 비었거나 긴 이름, 0원이거나 1원 단위가 아닌 금액
	 */
	public static Pass issue(long partnerId, String passNo, String linkTokenHash, String studentName,
			String guardianPhoneIndex, Instant issuedAt, ZoneId zone, LocalDate paidOn, Money paidAmount,
			PaymentMethod paymentMethod) {
		requireIssueFields(passNo, linkTokenHash, guardianPhoneIndex, issuedAt, zone, paidOn, paidAmount,
				paymentMethod);
		return new Pass(PassDirection.PARTNER_ISSUED, partnerId, null, null, null, passNo, linkTokenHash,
				validName(studentName), guardianPhoneIndex, issuedAt, expiryOf(LocalDate.ofInstant(issuedAt, zone)),
				paidOn, paidAmount, paymentMethod);
	}

	/**
	 * JEY 지점이 다니는 학생에게 발급한 패스를 만든다. 검증은 {@link #issue}와 같고 할인율 규칙을 더한다.
	 *
	 * @param issuerCampusId 발급한 JEY 지점
	 * @param studentId 받은 학생. 연계 학원에서 같은 학생의 두 번째 사용을 가려낼 근거로 남긴다(그 확인은 연계 학원의 사용 처리에서 한다 — 아직 없다)
	 * @param discountRate 발급 때의 할인율. 0보다 크고 1 이하, 소수 {@value #DISCOUNT_RATE_SCALE}자리까지
	 * @throws IllegalArgumentException {@link #issue}의 경우와, 할인율이 비었거나 규칙 밖일 때
	 */
	public static Pass issueAtCampus(long issuerCampusId, long studentId, String passNo, String linkTokenHash,
			String studentName, String guardianPhoneIndex, Instant issuedAt, ZoneId zone, LocalDate paidOn,
			Money paidAmount, PaymentMethod paymentMethod, BigDecimal discountRate) {
		requireIssueFields(passNo, linkTokenHash, guardianPhoneIndex, issuedAt, zone, paidOn, paidAmount,
				paymentMethod);
		return new Pass(PassDirection.CAMPUS_ISSUED, null, issuerCampusId, studentId, validRate(discountRate), passNo,
				linkTokenHash, validName(studentName), guardianPhoneIndex, issuedAt,
				expiryOf(LocalDate.ofInstant(issuedAt, zone)), paidOn, paidAmount, paymentMethod);
	}

	/**
	 * 할인율 규칙(core의 할인 유형과 같다): 0보다 크고 1 이하, 소수 {@value #DISCOUNT_RATE_SCALE}자리까지.
	 * 컬럼보다 긴 소수는 DB가 반올림해 입력과 저장이 달라지므로 막는다. 설정과 요청도 이 규칙을 쓴다.
	 *
	 * @throws IllegalArgumentException 비었거나 규칙 밖일 때
	 */
	public static BigDecimal validRate(BigDecimal rate) {
		if (rate == null || rate.signum() <= 0 || rate.compareTo(BigDecimal.ONE) > 0) {
			throw new IllegalArgumentException("할인율은 0보다 크고 1 이하여야 한다");
		}
		if (rate.stripTrailingZeros().scale() > DISCOUNT_RATE_SCALE) {
			throw new IllegalArgumentException("할인율은 소수 " + DISCOUNT_RATE_SCALE + "자리까지다");
		}
		return rate;
	}

	private static void requireIssueFields(String passNo, String linkTokenHash, String guardianPhoneIndex,
			Instant issuedAt, ZoneId zone, LocalDate paidOn, Money paidAmount, PaymentMethod paymentMethod) {
		if (!PassNumber.isValid(passNo)) {
			throw new IllegalArgumentException("passNo의 형식이 틀렸다");
		}
		requireLinkTokenHash(linkTokenHash);
		if (guardianPhoneIndex == null || guardianPhoneIndex.isBlank()) {
			throw new IllegalArgumentException("guardianPhoneIndex는 비울 수 없다");
		}
		if (issuedAt == null || zone == null || paidOn == null || paidAmount == null || paymentMethod == null) {
			throw new IllegalArgumentException("발급 시각, 시간대, 결제일, 결제 금액, 결제 수단은 비울 수 없다");
		}
		if (paidAmount.isZero() || !paidAmount.isWholeWon()) {
			throw new IllegalArgumentException("paidAmount는 0원보다 크고 1원 단위여야 한다");
		}
	}

	/** 발급일에 한 달을 더한 날. 그날까지 쓸 수 있다. 다음 달에 그날이 없으면 그 달의 마지막 날이다(10-31 → 11-30). */
	public static LocalDate expiryOf(LocalDate issuedOn) {
		return issuedOn.plusMonths(1);
	}

	/** 화면에 보일 상태. 쓰지 않은 채 만료일이 지났으면 만료다. */
	public PassState stateOn(LocalDate today) {
		return switch (status) {
			case REDEEMED -> PassState.REDEEMED;
			case CANCELED -> PassState.CANCELED;
			case ISSUED -> isExpiredOn(today) ? PassState.EXPIRED : PassState.ISSUED;
		};
	}

	/** 만료일이 지났는지. 만료일 당일은 지나지 않은 것이다. 상태와 무관하게 날짜만 본다. */
	public boolean isExpiredOn(LocalDate today) {
		return today.isAfter(expiresOn);
	}

	/** 발급일. 발급 시각을 그 시간대의 날짜로 바꾼 것이다(만료일을 셀 때 쓴 시간대를 넘긴다). */
	public LocalDate issuedOn(ZoneId zone) {
		return LocalDate.ofInstant(issuedAt, zone);
	}

	/** 오늘을 포함해 쓸 수 있는 날 수. 만료일 당일이면 1이다. 보이는 상태가 발급됨이 아니면(만료, 사용됨, 취소됨) 0이다. */
	public int daysLeftOn(LocalDate today) {
		if (stateOn(today) != PassState.ISSUED) {
			return 0;
		}
		return Math.toIntExact(ChronoUnit.DAYS.between(today, expiresOn) + 1);
	}

	/**
	 * 링크를 새 토큰의 해시로 바꾼다. 예전 링크는 더 쓸 수 없다. 기간이 지난 패스도 바꿀 수 있다.
	 *
	 * @throws PassStatusException 발급 상태가 아닐 때(사용됨, 취소됨)
	 * @throws IllegalArgumentException 해시의 형식이 틀렸을 때
	 */
	public void renewLink(String newLinkTokenHash) {
		requireLinkTokenHash(newLinkTokenHash);
		requireIssued();
		this.linkTokenHash = newLinkTokenHash;
	}

	/**
	 * 쓰지 않은 패스를 취소한다. 기간이 지난 패스도 취소할 수 있다. 결제 환불은 발급한 쪽이 따로 한다.
	 *
	 * @throws PassStatusException 발급 상태가 아닐 때(사용됨, 이미 취소됨)
	 */
	public void cancel() {
		requireIssued();
		this.status = PassStatus.CANCELED;
	}

	/**
	 * 패스를 쓴 것으로 표시하고 그 사용 기록을 만든다. 사용 기록은 이 메서드로만 만든다(사용됨인 패스에는 기록이 하나 있다).
	 * 기간은 보지 않는다(오늘을 아는 사용 처리가 먼저 본다). 저장은 부르는 쪽이 한다(패스를 먼저, 기록을 나중에).
	 *
	 * @param campusId 쓴 지점(패스를 받는 지점)
	 * @throws PassStatusException 발급 상태가 아닐 때(이미 사용됨, 취소됨)
	 * @throws IllegalStateException 저장하지 않은 패스일 때(기록이 가리킬 ID가 없다)
	 * @throws IllegalArgumentException 사용 시각이 없을 때
	 */
	public Redemption redeem(long studentId, long campusId, Instant redeemedAt) {
		if (id == null) {
			throw new IllegalStateException("저장한 패스만 사용 처리할 수 있다");
		}
		// JEY 지점에서 쓴 기록은 연계 학원 발급분에만 생긴다. 부르는 쪽(데스크)이 먼저 409로 막는다 — 여기 닿으면 코드의 버그다.
		if (direction != PassDirection.PARTNER_ISSUED) {
			throw new IllegalStateException("JEY 발급 패스는 JEY 지점에서 사용 처리하지 않는다: passId=" + id);
		}
		requireIssued();
		Redemption redemption = Redemption.of(id, studentId, campusId, redeemedAt);
		this.status = PassStatus.REDEEMED;
		return redemption;
	}

	/**
	 * JEY 발급 패스를 연계 학원에서 쓴 것으로 표시하고 그 사용 기록을 만든다. 연계 학원 사용 기록은 이 메서드로만 만든다.
	 * 기간·본인·신규는 보지 않는다(부르는 쪽이 먼저 본다). 저장은 부르는 쪽이 한다(패스를 먼저, 기록을 나중에).
	 *
	 * @param partnerId 쓴 연계 학원
	 * @param discount 이 패스의 할인율로 계산한 금액
	 * @throws IllegalStateException 저장하지 않은 패스이거나 연계 학원이 발급한 패스일 때(부르는 쪽이 먼저 409로 막는다 — 코드의 버그)
	 * @throws PassStatusException 발급 상태가 아닐 때(이미 사용됨, 취소됨)
	 * @throws IllegalArgumentException 금액이나 사용 시각이 없을 때
	 */
	public PartnerRedemption redeemAtPartner(long partnerId, PartnerDiscount discount, Instant redeemedAt) {
		if (id == null) {
			throw new IllegalStateException("저장한 패스만 사용 처리할 수 있다");
		}
		if (direction != PassDirection.CAMPUS_ISSUED) {
			throw new IllegalStateException("연계 학원이 발급한 패스는 연계 학원에서 사용 처리하지 않는다: passId=" + id);
		}
		requireIssued();
		PartnerRedemption redemption = PartnerRedemption.of(id, partnerId, studentId, discount, redeemedAt);
		this.status = PassStatus.REDEEMED;
		return redemption;
	}

	private void requireIssued() {
		if (status != PassStatus.ISSUED) {
			throw new PassStatusException(status);
		}
	}

	private static void requireLinkTokenHash(String hash) {
		if (hash == null || !LINK_TOKEN_HASH.matcher(hash).matches()) {
			throw new IllegalArgumentException("linkTokenHash는 64자 소문자 16진수여야 한다");
		}
	}

	// strip()은 전각 공백(U+3000)도 공백으로 본다.
	private static String validName(String name) {
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("studentName은 비울 수 없다");
		}
		String stripped = name.strip();
		if (stripped.length() > MAX_STUDENT_NAME_LENGTH) {
			throw new IllegalArgumentException("studentName은 " + MAX_STUDENT_NAME_LENGTH + "자를 넘을 수 없다");
		}
		return stripped;
	}

	public Long getId() {
		return id;
	}

	public String getPassNo() {
		return passNo;
	}

	public String getLinkTokenHash() {
		return linkTokenHash;
	}

	public PassDirection getDirection() {
		return direction;
	}

	/** 발급한 연계 학원. JEY 발급분은 null이다. */
	public Long getPartnerId() {
		return partnerId;
	}

	/** 발급한 JEY 지점. 연계 학원 발급분은 null이다. */
	public Long getIssuerCampusId() {
		return issuerCampusId;
	}

	/** 받은 학생. 연계 학원 발급분은 null이다(연계 학원의 학생은 JEY 학생 자료에 없다). */
	public Long getStudentId() {
		return studentId;
	}

	/** 발급 때의 할인율. 연계 학원 발급분은 null이다(받는 지점의 할인 유형에서 읽는다). */
	public BigDecimal getDiscountRate() {
		return discountRate;
	}

	public String getStudentName() {
		return studentName;
	}

	public String getGuardianPhoneIndex() {
		return guardianPhoneIndex;
	}

	public Instant getIssuedAt() {
		return issuedAt;
	}

	public LocalDate getExpiresOn() {
		return expiresOn;
	}

	public PassStatus getStatus() {
		return status;
	}

	public LocalDate getPaidOn() {
		return paidOn;
	}

	public Money getPaidAmount() {
		return paidAmount;
	}

	public PaymentMethod getPaymentMethod() {
		return paymentMethod;
	}

}
