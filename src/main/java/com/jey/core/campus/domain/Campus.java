package com.jey.core.campus.domain;

import com.jey.core.shared.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 지점. 마이그레이션으로 넣은 행을 읽기만 한다. 만들거나 고치는 기능은 없다.
 */
@Entity
@Table(name = "core_campus")
public class Campus extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, updatable = false, length = 50)
	private String name;

	protected Campus() {
	}

	public Long getId() {
		return id;
	}

	public String getName() {
		return name;
	}

}
