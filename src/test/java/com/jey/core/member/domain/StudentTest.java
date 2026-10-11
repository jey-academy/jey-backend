package com.jey.core.member.domain;

import java.time.LocalDate;

import com.jey.core.shared.ProtectedPhone;
import com.jey.core.shared.TestPhones;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class StudentTest {

	private static final LocalDate REGISTERED = LocalDate.of(2026, 10, 11);

	@Test
	void 새_학생은_재원_상태이고_이름과_학교의_앞뒤_공백을_뗀다() {
		Student student = Student.create(1L, null, "  김학생 ", " 동탄고 ", Grade.HIGH_1, REGISTERED, null);

		assertThat(student.getName()).isEqualTo("김학생");
		assertThat(student.getSchool()).isEqualTo("동탄고");
		assertThat(student.getStatus()).isEqualTo(StudentStatus.ENROLLED);
		assertThat(student.getClassId()).isNull();
		assertThat(student.hasPhone()).isFalse();
	}

	@ParameterizedTest
	@NullSource
	@ValueSource(strings = { "", " ", "　" })
	void 학교는_비어_있으면_null로_둔다(String school) {
		assertThat(Student.create(1L, null, "김학생", school, Grade.HIGH_1, REGISTERED, null).getSchool()).isNull();
	}

	@ParameterizedTest
	@NullSource
	@ValueSource(strings = { "", " ", "　" })
	void 이름은_비울_수_없다(String name) {
		assertThatIllegalArgumentException()
				.isThrownBy(() -> Student.create(1L, null, name, null, Grade.HIGH_1, REGISTERED, null));
	}

	@Test
	void 이름과_학교는_50자까지다() {
		assertThat(Student.create(1L, null, "가".repeat(50), "나".repeat(50), Grade.ETC, REGISTERED, null).getName())
				.hasSize(50);
		assertThatIllegalArgumentException()
				.isThrownBy(() -> Student.create(1L, null, "가".repeat(51), null, Grade.ETC, REGISTERED, null));
		assertThatIllegalArgumentException()
				.isThrownBy(() -> Student.create(1L, null, "김학생", "나".repeat(51), Grade.ETC, REGISTERED, null));
	}

	@Test
	void 지점_학년_등록일은_비울_수_없다() {
		assertThatIllegalArgumentException()
				.isThrownBy(() -> Student.create(null, null, "김학생", null, Grade.HIGH_1, REGISTERED, null));
		assertThatIllegalArgumentException()
				.isThrownBy(() -> Student.create(1L, null, "김학생", null, null, REGISTERED, null));
		assertThatIllegalArgumentException()
				.isThrownBy(() -> Student.create(1L, null, "김학생", null, Grade.HIGH_1, null, null));
	}

	// 값이 틀리면 일부만 바뀐 채로 남지 않아야 한다.
	@Test
	void 수정할_값이_틀리면_아무것도_바뀌지_않는다() {
		Student student = Student.create(1L, 5L, "김학생", "동탄고", Grade.HIGH_1, REGISTERED, null);

		assertThatIllegalArgumentException().isThrownBy(() -> student.update(2L, 6L, "이학생", "능동고", Grade.HIGH_2,
				null, REGISTERED.plusDays(1)));

		assertThat(student.getCampusId()).isEqualTo(1L);
		assertThat(student.getClassId()).isEqualTo(5L);
		assertThat(student.getName()).isEqualTo("김학생");
		assertThat(student.getGrade()).isEqualTo(Grade.HIGH_1);
	}

	@Test
	void 정보를_고치고_퇴원으로_바꾼다() {
		Student student = Student.create(1L, 5L, "김학생", "동탄고", Grade.HIGH_1, REGISTERED, null);

		student.update(2L, null, " 김학생2 ", "", Grade.HIGH_2, StudentStatus.WITHDRAWN, REGISTERED.plusDays(1));

		assertThat(student.getCampusId()).isEqualTo(2L);
		assertThat(student.getClassId()).isNull();
		assertThat(student.getName()).isEqualTo("김학생2");
		assertThat(student.getSchool()).isNull();
		assertThat(student.getGrade()).isEqualTo(Grade.HIGH_2);
		assertThat(student.getStatus()).isEqualTo(StudentStatus.WITHDRAWN);
		assertThat(student.getRegisteredAt()).isEqualTo(REGISTERED.plusDays(1));
	}

	@Test
	void 본인_번호를_넣고_바꾸고_지운다() {
		Student student = Student.create(1L, null, "김학생", null, Grade.HIGH_1, REGISTERED, null);
		ProtectedPhone first = TestPhones.protect("010-1111-2222");
		ProtectedPhone second = TestPhones.protect("010-3333-4444");

		student.changePhone(first);
		assertThat(student.getPhone()).isSameAs(first);
		student.changePhone(second);
		assertThat(student.getPhone()).isSameAs(second);
		student.removePhone();
		assertThat(student.hasPhone()).isFalse();
		assertThatIllegalArgumentException().isThrownBy(() -> student.changePhone(null));
	}

	@Test
	void 학부모는_이름과_전화번호가_있어야_한다() {
		ProtectedPhone phone = TestPhones.protect("010-1111-2222");

		assertThat(Guardian.create(" 김학부모 ", phone).getName()).isEqualTo("김학부모");
		assertThatIllegalArgumentException().isThrownBy(() -> Guardian.create(" ", phone));
		assertThatIllegalArgumentException().isThrownBy(() -> Guardian.create("가".repeat(51), phone));
		assertThatIllegalArgumentException().isThrownBy(() -> Guardian.create("김학부모", null));
		assertThatIllegalArgumentException().isThrownBy(() -> Guardian.create("김학부모", phone).changePhone(null));
		assertThatIllegalArgumentException().isThrownBy(() -> Guardian.create("김학부모", phone).rename(""));
	}

	@Test
	void 연결은_학생_학부모_관계가_모두_있어야_한다() {
		assertThatIllegalArgumentException().isThrownBy(() -> StudentGuardian.link(null, 1L, GuardianRelation.MOTHER));
		assertThatIllegalArgumentException().isThrownBy(() -> StudentGuardian.link(1L, null, GuardianRelation.MOTHER));
		assertThatIllegalArgumentException().isThrownBy(() -> StudentGuardian.link(1L, 1L, null));
		assertThatIllegalArgumentException()
				.isThrownBy(() -> StudentGuardian.link(1L, 1L, GuardianRelation.MOTHER).changeRelation(null));
	}

}
