package com.jey.dongtanpass.domain;

import java.time.Instant;

import com.jey.core.shared.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 패스 사용 기록. 데스크가 등록된 학생에게 패스를 쓴 사실이다(ADR-0033). 고치거나 지우지 않는다(저장소에 지우는 메서드가 없다).
 * {@link Pass#redeem}으로만 만든다(패스의 상태와 기록이 짝을 이룬다).
 * 패스 하나와 학생 한 명은 한 번만 쓰인다(DB의 유니크 키가 막는다). 처리한 직원은 {@code createdBy}다.
 * 금액과 청구는 다루지 않는다(수납 모듈과 이을 때 정한다).
 */
@Entity
@Table(name = "dongtanpass_redemption")
public class Redemption extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "pass_id", nullable = false, updatable = false)
	private Long passId;

	@Column(name = "student_id", nullable = false, updatable = false)
	private Long studentId;

	@Column(name = "campus_id", nullable = false, updatable = false)
	private Long campusId;

	@Column(name = "redeemed_at", nullable = false, updatable = false)
	private Instant redeemedAt;

	protected Redemption() {
	}

	private Redemption(long passId, long studentId, long campusId, Instant redeemedAt) {
		this.passId = passId;
		this.studentId = studentId;
		this.campusId = campusId;
		this.redeemedAt = redeemedAt;
	}

	/**
	 * @param campusId 쓴 지점(패스를 받는 지점)
	 * @throws IllegalArgumentException 사용 시각이 없을 때
	 */
	static Redemption of(long passId, long studentId, long campusId, Instant redeemedAt) {
		if (redeemedAt == null) {
			throw new IllegalArgumentException("redeemedAt은 비울 수 없다");
		}
		return new Redemption(passId, studentId, campusId, redeemedAt);
	}

	public Long getId() {
		return id;
	}

	public Long getPassId() {
		return passId;
	}

	public Long getStudentId() {
		return studentId;
	}

	public Long getCampusId() {
		return campusId;
	}

	public Instant getRedeemedAt() {
		return redeemedAt;
	}

}
