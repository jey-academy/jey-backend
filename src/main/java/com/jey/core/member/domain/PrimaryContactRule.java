package com.jey.core.member.domain;

import java.util.List;
import java.util.Optional;

/**
 * 대표 연락처를 고르는 규칙. 알림과 패스 링크는 이 연락처로 간다.
 *
 * <p>어머니가 있으면 어머니, 없으면 먼저 연결된 학부모다. 학부모가 한 명도 없으면 학생 본인 번호를 쓰는데,
 * 그 판단은 이 규칙이 비어 있는 값을 돌려줬을 때 부르는 쪽이 한다.
 * 대표를 따로 저장하지 않는다. 저장하면 대표가 둘이거나 없는 상태가 생길 수 있다(ADR-0023).
 */
public final class PrimaryContactRule {

	private PrimaryContactRule() {
	}

	/**
	 * @param linksInLinkedOrder 한 학생의 연결. 먼저 연결된 것이 앞에 온다
	 * @return 대표 학부모의 연결. 학부모가 없으면 비어 있다
	 */
	public static Optional<StudentGuardian> chooseGuardian(List<StudentGuardian> linksInLinkedOrder) {
		return linksInLinkedOrder.stream()
				.filter(link -> link.getRelation() == GuardianRelation.MOTHER)
				.findFirst()
				.or(() -> linksInLinkedOrder.stream().findFirst());
	}

}
