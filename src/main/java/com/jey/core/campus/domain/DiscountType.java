package com.jey.core.campus.domain;

import java.math.BigDecimal;
import java.util.regex.Pattern;

import com.jey.core.shared.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 할인 유형과 그 할인율. 지점과 무관하게 학원 전체가 같이 쓴다.
 * 어떤 할인을 적용할지는 등록할 때 직원이 고른다. 여러 할인이 겹칠 때의 계산은 여기서 정하지 않는다.
 */
@Entity
@Table(name = "core_discount_type")
public class DiscountType extends BaseEntity {

	/** 대문자로 시작하는 대문자·숫자·밑줄 2~30자. 요청 검증에서도 같은 식을 쓴다. */
	public static final String CODE_PATTERN = "[A-Z][A-Z0-9_]{1,29}";

	public static final int MAX_NAME_LENGTH = 50;

	/** 할인율의 소수 자릿수. 0.0001(0.01%) 단위까지 저장한다. */
	public static final int RATE_SCALE = 4;

	private static final Pattern CODE = Pattern.compile(CODE_PATTERN);

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	// 청구에 남는 값이라 만든 뒤 바꾸지 않는다.
	@Column(nullable = false, updatable = false, length = 30)
	private String code;

	@Column(nullable = false, length = MAX_NAME_LENGTH)
	private String name;

	// 5%는 0.05
	@Column(nullable = false, precision = 5, scale = RATE_SCALE)
	private BigDecimal rate;

	// 꺼진 할인은 새로 고를 수 없다. 이미 만든 청구는 그대로 남는다.
	@Column(nullable = false)
	private boolean active;

	protected DiscountType() {
	}

	private DiscountType(String code, String name, BigDecimal rate) {
		this.code = code;
		this.name = name;
		this.rate = rate;
		this.active = true;
	}

	/**
	 * @param name 앞뒤 공백을 떼고 저장한다
	 * @param rate 할인율. 0보다 크고 1 이하, 소수 넷째 자리까지
	 * @throws IllegalArgumentException 코드 형식이 틀리거나, 이름이 비었거나 길거나, 할인율이 범위 밖일 때
	 */
	public static DiscountType create(String code, String name, BigDecimal rate) {
		if (code == null || !CODE.matcher(code).matches()) {
			throw new IllegalArgumentException("code는 대문자로 시작하는 대문자·숫자·밑줄 2~30자여야 한다");
		}
		return new DiscountType(code, validName(name), validRate(rate));
	}

	/**
	 * 이미 만든 청구의 할인율은 바뀌지 않는다. 청구는 만들 때의 할인율을 따로 갖는다.
	 *
	 * @throws IllegalArgumentException {@link #create}와 같은 조건
	 */
	public void update(String name, BigDecimal rate, boolean active) {
		String validName = validName(name);
		BigDecimal validRate = validRate(rate);
		this.name = validName;
		this.rate = validRate;
		this.active = active;
	}

	public Long getId() {
		return id;
	}

	public String getCode() {
		return code;
	}

	public String getName() {
		return name;
	}

	/** 할인율. DB는 0.0500으로 돌려주지만 0.05로 맞춰서 준다. 표현이 달라도 같은 할인율이면 같게 보이도록 한다. */
	public BigDecimal getRate() {
		BigDecimal stripped = rate.stripTrailingZeros();
		return (stripped.scale() < 0) ? stripped.setScale(0) : stripped;
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

	private static BigDecimal validRate(BigDecimal rate) {
		if (rate == null || rate.signum() <= 0 || rate.compareTo(BigDecimal.ONE) > 0) {
			throw new IllegalArgumentException("할인율은 0보다 크고 1 이하여야 한다");
		}
		// 컬럼보다 긴 소수는 DB가 반올림한다. 입력한 할인율과 저장된 할인율이 달라지지 않게 막는다.
		if (rate.stripTrailingZeros().scale() > RATE_SCALE) {
			throw new IllegalArgumentException("할인율은 소수 " + RATE_SCALE + "자리까지다");
		}
		return rate;
	}

}
