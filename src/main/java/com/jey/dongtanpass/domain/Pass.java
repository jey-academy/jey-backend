package com.jey.dongtanpass.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
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
 * 동탄고 패스. 연계 학원이 결제를 확인하고 발급한다(ADR-0001). 지점과 무관하다.
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

	private static final Pattern LINK_TOKEN_HASH = Pattern.compile("[0-9a-f]{64}");

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "pass_no", nullable = false, updatable = false, length = 9)
	private String passNo;

	@Column(name = "link_token_hash", nullable = false, length = 64)
	private String linkTokenHash;

	@Column(name = "partner_id", nullable = false, updatable = false)
	private Long partnerId;

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

	@Version
	@Column(nullable = false)
	private Long version;

	protected Pass() {
	}

	private Pass(long partnerId, String passNo, String linkTokenHash, String studentName, String guardianPhoneIndex,
			Instant issuedAt, LocalDate expiresOn, LocalDate paidOn, Money paidAmount, PaymentMethod paymentMethod) {
		this.partnerId = partnerId;
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
	 * 발급한 패스를 만든다. 결제일과 금액이 업무 규칙에 맞는지(미래 날짜, 0원)는 부르는 쪽이 먼저 본다.
	 *
	 * @param studentName 앞뒤 공백만 떼고 저장한다(가운데 띄어쓰기는 그대로. 비교는 사용 처리 때 따로 정리해서 한다)
	 * @param guardianPhoneIndex 학부모 번호의 전체 번호 색인
	 * @param zone 발급일을 정하는 시간대. 발급일은 발급 시각을 이 시간대의 날짜로 바꾼 것이고 만료일을 정한다
	 * (발급일을 따로 받지 않는다. 시계를 두 번 읽으면 자정을 사이에 두고 둘이 어긋난다)
	 * @throws IllegalArgumentException 빈 값, 형식에 맞지 않는 패스 번호·링크 해시, 비었거나 긴 이름
	 */
	public static Pass issue(long partnerId, String passNo, String linkTokenHash, String studentName,
			String guardianPhoneIndex, Instant issuedAt, ZoneId zone, LocalDate paidOn, Money paidAmount,
			PaymentMethod paymentMethod) {
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
		return new Pass(partnerId, passNo, linkTokenHash, validName(studentName), guardianPhoneIndex, issuedAt,
				expiryOf(LocalDate.ofInstant(issuedAt, zone)), paidOn, paidAmount, paymentMethod);
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
	 * 쓰지 않은 패스를 취소한다. 기간이 지난 패스도 취소할 수 있다. 결제 환불은 연계 학원이 따로 한다.
	 *
	 * @throws PassStatusException 발급 상태가 아닐 때(사용됨, 이미 취소됨)
	 */
	public void cancel() {
		requireIssued();
		this.status = PassStatus.CANCELED;
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

	public Long getPartnerId() {
		return partnerId;
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
