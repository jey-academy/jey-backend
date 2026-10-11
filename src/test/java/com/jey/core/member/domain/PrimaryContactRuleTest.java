package com.jey.core.member.domain;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PrimaryContactRuleTest {

	private static final long STUDENT = 1;

	@Test
	void 어머니가_있으면_어머니다() {
		StudentGuardian father = link(10, GuardianRelation.FATHER);
		StudentGuardian mother = link(11, GuardianRelation.MOTHER);

		assertThat(PrimaryContactRule.chooseGuardian(List.of(father, mother))).containsSame(mother);
	}

	// 재혼 가정 등으로 어머니가 둘로 등록될 수 있다. 그때도 한 명만 고른다.
	@Test
	void 어머니가_둘이면_먼저_연결된_쪽이다() {
		StudentGuardian first = link(10, GuardianRelation.MOTHER);
		StudentGuardian second = link(11, GuardianRelation.MOTHER);

		assertThat(PrimaryContactRule.chooseGuardian(List.of(first, second))).containsSame(first);
	}

	@Test
	void 어머니가_없으면_먼저_연결된_학부모다() {
		StudentGuardian grandparent = link(10, GuardianRelation.GRANDPARENT);
		StudentGuardian father = link(11, GuardianRelation.FATHER);

		assertThat(PrimaryContactRule.chooseGuardian(List.of(grandparent, father))).containsSame(grandparent);
	}

	// 이때는 부르는 쪽이 학생 본인 번호를 쓴다.
	@Test
	void 학부모가_없으면_비어_있다() {
		assertThat(PrimaryContactRule.chooseGuardian(List.of())).isEmpty();
	}

	private static StudentGuardian link(long guardianId, GuardianRelation relation) {
		return StudentGuardian.link(STUDENT, guardianId, relation);
	}

}
