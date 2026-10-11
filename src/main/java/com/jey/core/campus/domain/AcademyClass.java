package com.jey.core.campus.domain;

import com.jey.core.shared.BaseEntity;
import com.jey.core.shared.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 반과 그 반의 수강료. 수강료표의 한 줄이다.
 * 수강료는 관리자만 본다. 이 엔티티를 직원에게 가는 응답으로 바꿀 때는 수강료가 없는 응답 타입을 쓴다.
 */
@Entity
@Table(name = "core_class")
public class AcademyClass extends BaseEntity {

	public static final int MAX_NAME_LENGTH = 50;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	// 반은 지점을 옮기지 않는다.
	@Column(name = "campus_id", nullable = false, updatable = false)
	private Long campusId;

	@Column(nullable = false, length = MAX_NAME_LENGTH)
	private String name;

	// 8회 수업 기준 수강료. 청구액은 이 금액에서 계산한다.
	@Column(name = "base_fee_8", nullable = false)
	private Money baseFee8;

	@Column(name = "book_fee", nullable = false)
	private Money bookFee;

	// 꺼진 반은 새로 고를 수 없다. 이미 만든 청구는 그대로 남는다.
	@Column(nullable = false)
	private boolean active;

	protected AcademyClass() {
	}

	private AcademyClass(Long campusId, String name, Money baseFee8, Money bookFee) {
		this.campusId = campusId;
		this.name = name;
		this.baseFee8 = baseFee8;
		this.bookFee = bookFee;
		this.active = true;
	}

	/**
	 * @param name 앞뒤 공백을 떼고 저장한다
	 * @throws IllegalArgumentException 필수값이 비었거나, 이름이 길거나, 금액이 허용 범위 밖일 때({@link #isValidBaseFee},
	 * {@link #isValidBookFee})
	 */
	public static AcademyClass create(Long campusId, String name, Money baseFee8, Money bookFee) {
		if (campusId == null) {
			throw new IllegalArgumentException("campusId는 비울 수 없다");
		}
		return new AcademyClass(campusId, validName(name), validBaseFee(baseFee8), validBookFee(bookFee));
	}

	/**
	 * 이미 만든 청구의 금액은 바뀌지 않는다. 청구는 만들 때의 수강료를 따로 갖는다.
	 *
	 * @throws IllegalArgumentException {@link #create}와 같은 조건
	 */
	public void update(String name, Money baseFee8, Money bookFee, boolean active) {
		String validName = validName(name);
		Money validBaseFee = validBaseFee(baseFee8);
		Money validBookFee = validBookFee(bookFee);
		this.name = validName;
		this.baseFee8 = validBaseFee;
		this.bookFee = validBookFee;
		this.active = active;
	}

	/** 수강료로 저장할 수 있는 금액인지. 1원 단위이고 0원보다 커야 한다. */
	public static boolean isValidBaseFee(Money fee) {
		return fee != null && fee.isWholeWon() && !fee.isZero();
	}

	/** 교재비로 저장할 수 있는 금액인지. 1원 단위다. 교재가 없는 반은 0원이다. */
	public static boolean isValidBookFee(Money fee) {
		return fee != null && fee.isWholeWon();
	}

	public Long getId() {
		return id;
	}

	public Long getCampusId() {
		return campusId;
	}

	public String getName() {
		return name;
	}

	public Money getBaseFee8() {
		return baseFee8;
	}

	public Money getBookFee() {
		return bookFee;
	}

	public boolean isActive() {
		return active;
	}

	private static String validName(String name) {
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("name은 비울 수 없다");
		}
		String trimmed = name.strip();
		if (trimmed.length() > MAX_NAME_LENGTH) {
			throw new IllegalArgumentException("name은 " + MAX_NAME_LENGTH + "자를 넘을 수 없다");
		}
		return trimmed;
	}

	private static Money validBaseFee(Money fee) {
		if (!isValidBaseFee(fee)) {
			throw new IllegalArgumentException("수강료는 1원 단위의 0원보다 큰 금액이어야 한다");
		}
		return fee;
	}

	private static Money validBookFee(Money fee) {
		if (!isValidBookFee(fee)) {
			throw new IllegalArgumentException("교재비는 1원 단위의 금액이어야 한다");
		}
		return fee;
	}

}
