package com.jey.dongtanpass.domain;

import java.time.Instant;

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
 * 학부모의 동의를 확인했다는 기록(ADR-0035). 어느 고지 문구를 보여 줬는지 버전으로 남긴다.
 * 문구가 바뀌어도 이미 받은 동의는 그때의 버전으로 남는다. 고치지 않는다.
 */
@Entity
@Table(name = "dongtanpass_consent")
public class Consent extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "pass_id", nullable = false, updatable = false)
	private Long passId;

	@Enumerated(EnumType.STRING)
	@Column(name = "consent_type", nullable = false, updatable = false, length = 40)
	private ConsentType consentType;

	@Column(name = "notice_version", nullable = false, updatable = false, length = 20)
	private String noticeVersion;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, updatable = false, length = 30)
	private ConsentChannel channel;

	@Column(name = "agreed_at", nullable = false, updatable = false)
	private Instant agreedAt;

	protected Consent() {
	}

	private Consent(long passId, ConsentType consentType, String noticeVersion, ConsentChannel channel,
			Instant agreedAt) {
		this.passId = passId;
		this.consentType = consentType;
		this.noticeVersion = noticeVersion;
		this.channel = channel;
		this.agreedAt = agreedAt;
	}

	/**
	 * 연계 학원이 고지하고 확인한 제3자 제공 동의.
	 *
	 * @throws IllegalArgumentException 버전이나 시각이 비었을 때
	 */
	public static Consent thirdPartyProvision(long passId, String noticeVersion, Instant agreedAt) {
		if (noticeVersion == null || noticeVersion.isBlank() || agreedAt == null) {
			throw new IllegalArgumentException("noticeVersion과 agreedAt은 비울 수 없다");
		}
		return new Consent(passId, ConsentType.THIRD_PARTY_PROVISION, noticeVersion, ConsentChannel.PARTNER_CONFIRMED,
				agreedAt);
	}

	public Long getId() {
		return id;
	}

	public Long getPassId() {
		return passId;
	}

	public ConsentType getConsentType() {
		return consentType;
	}

	public String getNoticeVersion() {
		return noticeVersion;
	}

	public ConsentChannel getChannel() {
		return channel;
	}

	public Instant getAgreedAt() {
		return agreedAt;
	}

}
