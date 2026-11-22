package com.jey.core.member;

import static com.jey.core.member.MemberFixtures.DONGTAN;
import static com.jey.core.member.MemberFixtures.mother;
import static com.jey.core.member.MemberFixtures.randomPhone;
import static com.jey.core.member.MemberFixtures.registration;
import static com.jey.core.member.MemberFixtures.uniqueName;
import static com.jey.core.member.StudentContactConcurrencyTest.runTogether;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.jey.TestcontainersConfiguration;
import com.jey.core.campus.ClassService;
import com.jey.core.campus.domain.AcademyClass;
import com.jey.core.shared.BusinessException;
import com.jey.core.shared.Money;

// 같은 버전(같은 화면)로 보낸 저장이 정말 동시에 와도 하나만 반영된다. 학생 행 잠금이 저장을 줄 세우고,
// 잠근 뒤의 버전 비교가 뒤에 선 저장을 거부한다. 어느 쪽이 이기는지는 매번 다르므로 여러 번 되풀이한다.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
class StudentUpdateConcurrencyTest {

	private static final int ROUNDS = 10;

	private static final int CROWD = 8;

	@Autowired
	StudentService studentService;

	@Autowired
	ClassService classService;

	@Test
	void 같은_버전으로_동시에_고치면_하나만_반영되고_나머지는_거부된다() throws Exception {
		for (int round = 0; round < ROUNDS; round++) {
			AcademyClass newClass = classService.create(DONGTAN, "반-" + uniqueName(), Money.of(280_000), Money.ZERO);
			StudentView screen = studentService.register(registration(uniqueName(), mother(randomPhone())));
			StudentChange classOnly = new StudentChange(screen.version(), DONGTAN, newClass.getId(), screen.name(),
					screen.school(), screen.grade(), screen.status(), screen.registeredAt(), null);
			StudentChange schoolOnly = new StudentChange(screen.version(), DONGTAN, screen.classId(), screen.name(),
					"능동고", screen.grade(), screen.status(), screen.registeredAt(), null);

			List<Object> results = runTogether(() -> studentService.update(screen.id(), classOnly),
					() -> studentService.update(screen.id(), schoolOnly));

			assertOnlyOneApplied(results);
			StudentView after = studentService.get(screen.id());
			assertThat(after.version()).isEqualTo(screen.version() + 1);
			boolean classWon = results.get(0) instanceof StudentView;
			// 이긴 쪽의 변경만 있고, 진 쪽의 변경은 섞이지 않는다(둘 다 반영되거나 하나가 다른 하나를 되돌리지 않는다).
			assertThat(after.classId()).isEqualTo(classWon ? newClass.getId() : screen.classId());
			assertThat(after.school()).isEqualTo(classWon ? screen.school() : "능동고");
		}
	}

	@Test
	void 여러_명이_같은_버전으로_동시에_고쳐도_하나만_성공한다() throws Exception {
		StudentView screen = studentService.register(registration(uniqueName(), mother(randomPhone())));
		List<Callable<Object>> saves = new ArrayList<>();
		for (int i = 0; i < CROWD; i++) {
			StudentChange change = new StudentChange(screen.version(), DONGTAN, null, screen.name(), "학교-" + i,
					screen.grade(), screen.status(), screen.registeredAt(), null);
			saves.add(() -> studentService.update(screen.id(), change));
		}

		@SuppressWarnings("unchecked")
		List<Object> results = runTogether(saves.toArray(new Callable[0]));

		assertOnlyOneApplied(results);
		assertThat(studentService.get(screen.id()).version()).isEqualTo(screen.version() + 1);
	}

	private static void assertOnlyOneApplied(List<Object> results) {
		assertThat(results).filteredOn(StudentView.class::isInstance).hasSize(1);
		assertThat(results).filteredOn(result -> !(result instanceof StudentView))
			.hasSize(results.size() - 1)
			.allSatisfy(result -> assertThat(result).isInstanceOfSatisfying(BusinessException.class,
					ex -> assertThat(ex.getErrorCode()).isEqualTo(MemberErrorCode.STUDENT_CHANGED)));
	}

}
