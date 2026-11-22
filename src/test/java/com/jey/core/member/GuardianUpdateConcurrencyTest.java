package com.jey.core.member;

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
import com.jey.core.member.GuardianService.GuardianProfile;
import com.jey.core.member.StudentView.GuardianView;
import com.jey.core.shared.BusinessException;
import com.jey.core.shared.PhoneNumber;

// 같은 버전(같은 화면)으로 보낸 학부모 수정이 정말 동시에 와도 하나만 반영된다. 학부모 행 잠금이 저장을 줄 세우고,
// 잠근 뒤의 버전 비교가 뒤에 선 저장을 거부한다. 어느 쪽이 이기는지는 매번 다르므로 여러 번 되풀이한다.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
class GuardianUpdateConcurrencyTest {

	private static final int ROUNDS = 10;

	private static final int CROWD = 8;

	@Autowired
	StudentService studentService;

	@Autowired
	GuardianService guardianService;

	@Test
	void 같은_버전으로_동시에_고치면_하나만_반영되고_나머지는_거부된다() throws Exception {
		for (int round = 0; round < ROUNDS; round++) {
			StudentView student = studentService.register(registration(uniqueName(), mother(randomPhone())));
			GuardianView screen = student.guardians().getFirst();
			PhoneNumber newPhone = randomPhone();

			List<Object> results = runTogether(
					() -> guardianService.update(screen.id(), screen.version(), "박어머니", null),
					() -> guardianService.update(screen.id(), screen.version(), screen.name(), newPhone));

			assertOnlyOneApplied(results);
			GuardianView after = studentService.get(student.id()).guardians().getFirst();
			assertThat(after.version()).isEqualTo(screen.version() + 1);
			boolean nameWon = results.get(0) instanceof GuardianProfile;
			// 이긴 쪽의 변경만 있고, 진 쪽의 값은 섞이지 않는다.
			assertThat(after.name()).isEqualTo(nameWon ? "박어머니" : screen.name());
			assertThat(after.phone()).isEqualTo(nameWon ? screen.phone() : newPhone);
		}
	}

	@Test
	void 여러_명이_같은_버전으로_동시에_고쳐도_하나만_성공한다() throws Exception {
		StudentView student = studentService.register(registration(uniqueName(), mother(randomPhone())));
		GuardianView screen = student.guardians().getFirst();
		List<Callable<Object>> saves = new ArrayList<>();
		for (int i = 0; i < CROWD; i++) {
			String name = "학부모-" + i;
			saves.add(() -> guardianService.update(screen.id(), screen.version(), name, null));
		}

		@SuppressWarnings("unchecked")
		List<Object> results = runTogether(saves.toArray(new Callable[0]));

		assertOnlyOneApplied(results);
		assertThat(studentService.get(student.id()).guardians().getFirst().version()).isEqualTo(screen.version() + 1);
	}

	private static void assertOnlyOneApplied(List<Object> results) {
		assertThat(results).filteredOn(GuardianProfile.class::isInstance).hasSize(1);
		assertThat(results).filteredOn(result -> !(result instanceof GuardianProfile))
			.hasSize(results.size() - 1)
			.allSatisfy(result -> assertThat(result).isInstanceOfSatisfying(BusinessException.class,
					ex -> assertThat(ex.getErrorCode()).isEqualTo(MemberErrorCode.GUARDIAN_CHANGED)));
	}

}
