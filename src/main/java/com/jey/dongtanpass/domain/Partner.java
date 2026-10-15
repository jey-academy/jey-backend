package com.jey.dongtanpass.domain;

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
 * 연계 학원. 패스를 발급한다(ADR-0034). 지점과 무관하다.
 * 끄면 새 발급을 막고 그 학원의 계정이 패스 API를 쓰지 못한다. 이미 발급한 패스는 그대로 쓸 수 있다.
 */
@Entity
@Table(name = "dongtanpass_partner")
public class Partner extends BaseEntity {

	/** 요청을 받는 쪽의 검증도 이 값을 쓴다. */
	public static final int MAX_NAME_LENGTH = 50;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = MAX_NAME_LENGTH)
	private String name;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private PartnerType type;

	@Column(nullable = false)
	private boolean active;

	protected Partner() {
	}

	private Partner(String name, PartnerType type) {
		this.name = name;
		this.type = type;
		this.active = true;
	}

	/**
	 * 켜진 학원으로 만든다. 종류는 학원이다.
	 *
	 * @param name 앞뒤 공백을 떼고 저장한다
	 * @throws IllegalArgumentException 이름이 비었거나 길 때
	 */
	public static Partner create(String name) {
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("name은 비울 수 없다");
		}
		String stripped = name.strip();
		if (stripped.length() > MAX_NAME_LENGTH) {
			throw new IllegalArgumentException("name은 " + MAX_NAME_LENGTH + "자를 넘을 수 없다");
		}
		return new Partner(stripped, PartnerType.ACADEMY);
	}

	/**
	 * 끈다. 그 학원의 계정을 막는 것은 부르는 쪽이 한다(계정 차단은 다른 모듈의 일이다).
	 *
	 * @return 실제로 바뀌었는지. 이미 꺼져 있으면 {@code false}
	 */
	public boolean deactivate() {
		if (!active) {
			return false;
		}
		active = false;
		return true;
	}

	/**
	 * 켠다. 끌 때 차단한 계정은 풀지 않는다.
	 *
	 * @return 실제로 바뀌었는지. 이미 켜져 있으면 {@code false}
	 */
	public boolean activate() {
		if (active) {
			return false;
		}
		active = true;
		return true;
	}

	public Long getId() {
		return id;
	}

	public String getName() {
		return name;
	}

	public PartnerType getType() {
		return type;
	}

	public boolean isActive() {
		return active;
	}

}
