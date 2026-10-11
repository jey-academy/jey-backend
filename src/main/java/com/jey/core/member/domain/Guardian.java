package com.jey.core.member.domain;

import com.jey.core.shared.BaseEntity;
import com.jey.core.shared.ProtectedPhone;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 학부모. 전화번호 하나에 학부모는 한 명이다(ADR-0023). 학생과의 관계는 {@link StudentGuardian}이 갖는다.
 * 전화번호는 암호문과 인덱스로만 갖는다(ADR-0009).
 */
@Entity
@Table(name = "core_guardian")
public class Guardian extends BaseEntity {

	public static final int MAX_NAME_LENGTH = 50;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = MAX_NAME_LENGTH)
	private String name;

	@Embedded
	private ProtectedPhone phone;

	protected Guardian() {
	}

	private Guardian(String name, ProtectedPhone phone) {
		this.name = name;
		this.phone = phone;
	}

	/**
	 * @param name 앞뒤 공백을 떼고 저장한다
	 * @throws IllegalArgumentException 이름이 비었거나 길 때, 전화번호가 없을 때
	 */
	public static Guardian create(String name, ProtectedPhone phone) {
		return new Guardian(Names.valid(name, MAX_NAME_LENGTH), requirePhone(phone));
	}

	/**
	 * @throws IllegalArgumentException 이름이 비었거나 길 때
	 */
	public void rename(String name) {
		this.name = Names.valid(name, MAX_NAME_LENGTH);
	}

	/** 암호문과 인덱스를 통째로 바꿔 끼운다. 옛 번호로는 더 찾아지지 않는다. */
	public void changePhone(ProtectedPhone phone) {
		this.phone = requirePhone(phone);
	}

	public Long getId() {
		return id;
	}

	public String getName() {
		return name;
	}

	public ProtectedPhone getPhone() {
		return phone;
	}

	private static ProtectedPhone requirePhone(ProtectedPhone phone) {
		if (phone == null) {
			throw new IllegalArgumentException("학부모의 전화번호는 비울 수 없다");
		}
		return phone;
	}

}
