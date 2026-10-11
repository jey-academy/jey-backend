package com.jey.core.member;

import com.jey.core.member.domain.GuardianRelation;
import com.jey.core.shared.PhoneNumber;

/**
 * 학생에게 붙일 학부모. 같은 번호의 학부모가 이미 있으면 새로 만들지 않고 그 학부모를 붙인다.
 * 그때 {@code name}은 쓰이지 않는다(저장된 이름을 바꾸지 않는다).
 */
public record GuardianInput(String name, GuardianRelation relation, PhoneNumber phone) {

	/**
	 * 학부모는 학생보다 먼저 따로 커밋된다. 틀린 값이 그 뒤에 걸리면 학부모만 남으므로 여기서 먼저 거른다.
	 *
	 * @throws IllegalArgumentException 이름이 비었거나 관계·전화번호가 없을 때
	 */
	public GuardianInput {
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("학부모의 name은 비울 수 없다");
		}
		if (relation == null || phone == null) {
			throw new IllegalArgumentException("학부모의 relation과 phone은 비울 수 없다");
		}
	}

}
