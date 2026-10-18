package com.jey.core.member;

import java.util.List;

import com.jey.TestcontainersConfiguration;
import com.jey.core.member.StudentView.SiblingView;
import com.jey.core.member.domain.Grade;
import com.jey.core.member.domain.GuardianRelation;
import com.jey.core.member.domain.StudentStatus;
import com.jey.core.shared.PhoneNumber;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.domain.Page;
import org.springframework.test.context.ActiveProfiles;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static com.jey.core.member.MemberFixtures.DONGTAN;
import static com.jey.core.member.MemberFixtures.NEUNGDONG;
import static com.jey.core.member.MemberFixtures.distinctPhonesEndingWith;
import static com.jey.core.member.MemberFixtures.father;
import static com.jey.core.member.MemberFixtures.mother;
import static com.jey.core.member.MemberFixtures.randomLast4;
import static com.jey.core.member.MemberFixtures.randomPhone;
import static com.jey.core.member.MemberFixtures.randomPhoneEndingWith;
import static com.jey.core.member.MemberFixtures.registration;
import static com.jey.core.member.MemberFixtures.removeAllContacts;
import static com.jey.core.member.MemberFixtures.registrationWithPhone;
import static com.jey.core.member.MemberFixtures.uniqueName;
import static com.jey.core.member.StudentRegistrationTest.assertFails;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.awaitility.Awaitility.await;

// 목록은 다른 테스트가 넣은 학생과 섞인다. 테스트마다 이름의 앞부분을 새로 만들어 그 이름으로 걸러서 본다.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class StudentQueryTest {

	@Autowired
	StudentService studentService;

	@Autowired
	EntityManagerFactory entityManagerFactory;

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void 상세에_학부모와_대표_연락처가_나온다() {
		PhoneNumber fatherPhone = randomPhone();
		PhoneNumber motherPhone = randomPhone();
		long id = studentService.register(registration(uniqueName(), father(fatherPhone), mother(motherPhone))).id();

		StudentView found = studentService.get(id);

		assertThat(found.guardians()).hasSize(2).allSatisfy(each -> assertThat(each.linkedToExisting()).isFalse());
		// 아버지를 먼저 연결했어도 어머니가 대표다.
		assertThat(found.primaryContact().relation()).isEqualTo(GuardianRelation.MOTHER);
		assertThat(found.primaryContact().name()).isEqualTo("김어머니");
		assertThat(found.primaryContact().phone()).isEqualTo(motherPhone);
	}

	@Test
	void 어머니가_없으면_먼저_연결된_학부모가_대표이고_학부모가_없으면_학생_번호다() {
		PhoneNumber grandparentPhone = randomPhone();
		PhoneNumber studentPhone = randomPhone();
		long withGuardians = studentService
				.register(registrationWithPhone(uniqueName(), studentPhone,
						new GuardianInput("김할머니", GuardianRelation.GRANDPARENT, grandparentPhone),
						father(randomPhone())))
				.id();
		long alone = studentService.register(registrationWithPhone(uniqueName(), studentPhone)).id();

		assertThat(studentService.get(withGuardians).primaryContact().phone()).isEqualTo(grandparentPhone);
		assertThat(studentService.get(alone).primaryContact().phone()).isEqualTo(studentPhone);
		assertThat(studentService.get(alone).primaryContact().relation()).isNull();
	}

	// 등록과 삭제가 막고 있어 정상 경로로는 생기지 않는다. 그래도 자료가 그렇게 되면, 그 한 줄 때문에
	// 같은 페이지의 다른 학생까지 못 보게 되어서는 안 된다. 연락처 칸만 비워서 내보낸다.
	@Test
	void 연락처가_없는_학생이_있어도_목록의_다른_학생은_대표_연락처와_함께_나온다() {
		String prefix = uniqueName();
		PhoneNumber motherPhone = randomPhone();
		long withContact = studentService.register(registration(prefix + "-가", mother(motherPhone))).id();
		long withoutContact = studentService.register(registration(prefix + "-나", mother(randomPhone()))).id();
		removeAllContacts(jdbc, withoutContact);

		List<StudentSummaryView> found = studentService.search(null, prefix, null, 0, 20).getContent();

		assertThat(found).extracting(StudentSummaryView::id).containsExactly(withContact, withoutContact);
		assertThat(found.get(0).primaryContact().phone()).isEqualTo(motherPhone);
		assertThat(found.get(1).primaryContact()).isNull();
	}

	@Test
	void 연락처가_없는_학생의_상세는_대표_연락처_없이_나온다() {
		long id = studentService.register(registration(uniqueName(), mother(randomPhone()))).id();
		removeAllContacts(jdbc, id);

		StudentView found = studentService.get(id);

		assertThat(found.primaryContact()).isNull();
		assertThat(found.guardians()).isEmpty();
		assertThat(found.phone()).isNull();
	}

	// 예외 대신 빈 칸으로 내보내므로, 이 로그가 그런 자료가 있다는 것을 알리는 유일한 통로다.
	// 어느 학생인지와 어느 지점인지를 ID로 남긴다. 이름은 남기지 않는다.
	@Test
	void 연락처가_없는_학생을_만나면_누구인지_경고로_남긴다(CapturedOutput output) {
		String prefix = uniqueName();
		long first = studentService.register(registration(prefix + "-가", mother(randomPhone()))).id();
		long second = studentService.register(registration(prefix + "-나", mother(randomPhone()))).id();
		removeAllContacts(jdbc, first);
		removeAllContacts(jdbc, second);

		List<StudentSummaryView> found = studentService.search(null, prefix, null, 0, 20).getContent();

		assertThat(found).hasSize(2).allSatisfy(student -> assertThat(student.primaryContact()).isNull());
		assertThat(output.getAll()).contains("studentId=" + first + " campusId=" + DONGTAN)
			.contains("studentId=" + second + " campusId=" + DONGTAN)
			.doesNotContain(prefix);
	}

	@Test
	void 없는_학생은_찾을_수_없다() {
		assertFails(() -> studentService.get(999_999_999L), MemberErrorCode.STUDENT_NOT_FOUND);
	}

	// 형제는 따로 저장하지 않는다. 같은 학부모에 연결된 학생이 형제다.
	@Test
	void 같은_학부모를_가진_학생이_형제다() {
		PhoneNumber motherPhone = randomPhone();
		PhoneNumber fatherPhone = randomPhone();
		StudentView first = studentService.register(registration(uniqueName(), mother(motherPhone), father(fatherPhone)));
		// 부모 둘이 모두 같아도 형제는 한 번만 나온다.
		StudentView second = studentService.register(registration(uniqueName(), mother(motherPhone), father(fatherPhone)));
		// 한 명만 같아도 형제다.
		StudentView third = studentService.register(registration(uniqueName(), father(fatherPhone)));
		StudentView stranger = studentService.register(registration(uniqueName(), mother(randomPhone())));

		assertThat(studentService.get(first.id()).siblings()).extracting(SiblingView::id)
				.containsExactly(second.id(), third.id());
		assertThat(studentService.get(third.id()).siblings()).extracting(SiblingView::name)
				.containsExactly(first.name(), second.name());
		assertThat(studentService.get(stranger.id()).siblings()).isEmpty();
	}

	@Test
	void 목록을_이름의_앞부분과_지점으로_거른다() {
		String prefix = uniqueName();
		studentService.register(registration(prefix + "-나", mother(randomPhone())));
		studentService.register(registration(prefix + "-가", mother(randomPhone())));
		studentService.register(new StudentRegistration(NEUNGDONG, null, prefix + "-다", null, Grade.MIDDLE_2, null,
				null, List.of(mother(randomPhone()))));
		studentService.register(registration("앞-" + prefix, mother(randomPhone())));

		Page<StudentSummaryView> all = studentService.search(null, " " + prefix + " ", null, 0, 20);
		Page<StudentSummaryView> dongtan = studentService.search(DONGTAN, prefix, null, 0, 20);

		// 이름 순이다. 앞부분이 아닌 곳에 든 이름은 나오지 않는다.
		assertThat(all.getContent()).extracting(StudentSummaryView::name)
				.containsExactly(prefix + "-가", prefix + "-나", prefix + "-다");
		assertThat(all.getTotalElements()).isEqualTo(3);
		assertThat(dongtan.getContent()).extracting(StudentSummaryView::name).containsExactly(prefix + "-가", prefix + "-나");
		assertThat(all.getContent().getLast().grade()).isEqualTo(Grade.MIDDLE_2);
		assertThat(all.getContent().getFirst().primaryContact().relation()).isEqualTo(GuardianRelation.MOTHER);
	}

	@Test
	void 목록을_페이지로_나눈다() {
		String prefix = uniqueName();
		for (int i = 1; i <= 5; i++) {
			studentService.register(registration(prefix + "-" + i, mother(randomPhone())));
		}

		Page<StudentSummaryView> first = studentService.search(null, prefix, null, 0, 2);
		Page<StudentSummaryView> last = studentService.search(null, prefix, null, 2, 2);

		assertThat(first.getContent()).extracting(StudentSummaryView::name).containsExactly(prefix + "-1", prefix + "-2");
		assertThat(first.getTotalElements()).isEqualTo(5);
		assertThat(first.getTotalPages()).isEqualTo(3);
		assertThat(last.getContent()).extracting(StudentSummaryView::name).containsExactly(prefix + "-5");
	}

	@Test
	void 퇴원한_학생도_목록에_나온다() {
		String name = uniqueName();
		StudentView created = studentService.register(registration(name, mother(randomPhone())));
		studentService.update(created.id(), new StudentChange(DONGTAN, null, name, null, Grade.HIGH_1,
				StudentStatus.WITHDRAWN, created.registeredAt(), null));

		assertThat(studentService.search(null, name, null, 0, 20).getContent()).singleElement()
				.satisfies(each -> assertThat(each.status()).isEqualTo(StudentStatus.WITHDRAWN));
	}

	// 이름에 든 %와 _는 글자 그대로 찾는다. 그대로 넘기면 "%"가 모든 학생을 찾는다.
	@Test
	void 이름의_특수문자는_글자_그대로_찾는다() {
		String prefix = uniqueName();
		studentService.register(registration(prefix + "_a", mother(randomPhone())));
		studentService.register(registration(prefix + "xa", mother(randomPhone())));
		studentService.register(registration(prefix + "!%", mother(randomPhone())));

		assertThat(studentService.search(null, prefix + "_", null, 0, 20).getContent())
				.extracting(StudentSummaryView::name)
				.containsExactly(prefix + "_a");
		assertThat(studentService.search(null, prefix + "%", null, 0, 20).getContent()).isEmpty();
		assertThat(studentService.search(null, prefix + "!", null, 0, 20).getContent())
				.extracting(StudentSummaryView::name)
				.containsExactly(prefix + "!%");
	}

	@Test
	void 뒷자리로_찾으면_학부모_번호와_학생_번호를_모두_본다() {
		String prefix = uniqueName();
		String last4 = randomLast4();
		studentService.register(registration(prefix + "-학부모", mother(randomPhoneEndingWith(last4))));
		studentService.register(registrationWithPhone(prefix + "-본인", randomPhoneEndingWith(last4), mother(randomPhone())));
		studentService.register(registration(prefix + "-무관", mother(randomPhoneEndingWith(other(last4)))));

		Page<StudentSummaryView> found = studentService.search(null, prefix, last4, 0, 20);

		assertThat(found.getContent()).extracting(StudentSummaryView::name)
				.containsExactly(prefix + "-본인", prefix + "-학부모");
		assertThat(found.getTotalElements()).isEqualTo(2);
	}

	// 조인으로 찾으면 맞는 번호의 수만큼 같은 학생이 되풀이되고 전체 건수도 늘어난다.
	@Test
	void 맞는_번호가_여럿이어도_학생은_한_번만_나온다() {
		String name = uniqueName();
		String last4 = randomLast4();
		List<PhoneNumber> phones = distinctPhonesEndingWith(last4, 3);
		studentService.register(registrationWithPhone(name, phones.get(0), mother(phones.get(1)), father(phones.get(2))));

		Page<StudentSummaryView> found = studentService.search(null, name, last4, 0, 20);

		assertThat(found.getContent()).hasSize(1);
		assertThat(found.getTotalElements()).isEqualTo(1);
	}

	@Test
	void 뒷자리는_숫자_4자리여야_한다() {
		assertThatIllegalArgumentException().isThrownBy(() -> studentService.search(null, null, "123", 0, 20));
		assertThatIllegalArgumentException().isThrownBy(() -> studentService.search(null, null, "12a4", 0, 20));
	}

	// 학생마다 학부모를 따로 조회하면 목록 한 번에 쿼리가 수십 번 나간다.
	@Test
	void 목록의_쿼리_수는_학생_수에_비례하지_않는다() {
		String few = uniqueName();
		String many = uniqueName();
		studentService.register(registration(few + "-1", mother(randomPhone())));
		for (int i = 1; i <= 8; i++) {
			studentService.register(registration(many + "-" + i, mother(randomPhone()), father(randomPhone())));
		}

		// 방금 등록한 학생들의 이벤트를 리스너가 다 적을 때까지 기다린다. 그 쿼리가 측정에 섞이지 않게 한다.
		await().atMost(java.time.Duration.ofSeconds(20))
				.untilAsserted(() -> assertThat(jdbc.queryForObject(
						"SELECT COUNT(*) FROM event_publication WHERE completion_date IS NULL "
								+ "AND serialized_event LIKE '%member.student-registered%'",
						Integer.class))
						.isZero());

		long forOne = statementsDuring(() -> assertThat(studentService.search(null, few, null, 0, 20)).hasSize(1));
		long forEight = statementsDuring(() -> assertThat(studentService.search(null, many, null, 0, 20)).hasSize(8));

		assertThat(forEight).isEqualTo(forOne);
		// 통계가 집계되지 않으면 둘 다 0이라 위의 비교가 그냥 통과한다. 학생, 연결, 학부모는 읽어야 한다.
		assertThat(forOne).isBetween(3L, 4L);
	}

	// 통계는 애플리케이션 전체의 값이다. 방금 등록한 학생의 이벤트를 리스너가 다른 스레드에서 적는 중이면 그 쿼리가 섞인다.
	// 여러 번 재서 가장 작은 값을 쓴다. 섞인 쿼리는 값을 키우기만 한다.
	private long statementsDuring(Runnable work) {
		long fewest = Long.MAX_VALUE;
		for (int attempt = 0; attempt < 5; attempt++) {
			fewest = Math.min(fewest, statementsDuringOnce(work));
		}
		return fewest;
	}

	private long statementsDuringOnce(Runnable work) {
		Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
		boolean wasEnabled = statistics.isStatisticsEnabled();
		statistics.setStatisticsEnabled(true);
		try {
			long before = statistics.getPrepareStatementCount();
			work.run();
			return statistics.getPrepareStatementCount() - before;
		}
		finally {
			statistics.setStatisticsEnabled(wasEnabled);
		}
	}

	private static String other(String last4) {
		return String.format("%04d", (Integer.parseInt(last4) + 1) % 10_000);
	}

}
