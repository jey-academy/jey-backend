package com.jey.dongtanpass.application;

import java.time.Clock;
import java.time.LocalDate;

import com.jey.TestcontainersConfiguration;
import com.jey.WithJeyUser;
import com.jey.core.auth.api.UserRole;
import com.jey.core.member.StudentService;
import com.jey.core.shared.Money;
import com.jey.dongtanpass.domain.PaymentMethod;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static com.jey.core.member.MemberFixtures.mother;
import static com.jey.core.member.MemberFixtures.randomPhone;
import static com.jey.core.member.MemberFixtures.registration;
import static com.jey.core.member.MemberFixtures.uniqueName;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

// JEY 발급도 연계 학원 발급처럼 패스 번호가 겹치면 통째로 다시 한다(PassIssueRetryTest와 같은 규칙).
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
class CampusPassIssueRetryTest {

	@Autowired
	CampusPassService campusPassService;

	@Autowired
	StudentService students;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	Clock clock;

	@MockitoSpyBean
	PassNumbers passNumbers;

	@Test
	@WithJeyUser(role = UserRole.ADMIN)
	void 번호가_겹치면_새_번호로_다시_발급하고_겹친_시도의_흔적은_남지_않는다() {
		long student = newStudent();
		String taken = campusPassService.issue(newPass(student)).passNo();
		doReturn(taken).doCallRealMethod().when(passNumbers).next();
		clearInvocations(passNumbers);

		CampusIssuedPass issued = campusPassService.issue(newPass(student));

		assertThat(issued.passNo()).isNotEqualTo(taken);
		verify(passNumbers, times(2)).next();
		assertThat(countOf("dongtanpass_pass WHERE student_id = ?", student)).isEqualTo(2);
		assertThat(countOf("dongtanpass_consent c JOIN dongtanpass_pass p ON p.id = c.pass_id WHERE p.student_id = ?",
				student))
			.isEqualTo(2);
	}

	@Test
	@WithJeyUser(role = UserRole.ADMIN)
	void 계속_겹치면_정해진_횟수만_시도하고_실패한다() {
		long student = newStudent();
		String taken = campusPassService.issue(newPass(student)).passNo();
		doReturn(taken).when(passNumbers).next();
		clearInvocations(passNumbers);

		assertThatThrownBy(() -> campusPassService.issue(newPass(student)))
			.isInstanceOf(DataIntegrityViolationException.class);

		verify(passNumbers, times(PassIssuing.MAX_PASS_NO_ATTEMPTS)).next();
		assertThat(countOf("dongtanpass_pass WHERE student_id = ?", student)).isEqualTo(1);
		doCallRealMethod().when(passNumbers).next();
	}

	private long newStudent() {
		return students.register(registration(uniqueName(), mother(randomPhone()))).id();
	}

	private NewCampusPass newPass(long student) {
		return new NewCampusPass(student, LocalDate.now(clock), Money.of(280000), PaymentMethod.CARD, true,
				ConsentNotice.CAMPUS_ISSUED.version());
	}

	private long countOf(String from, long student) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM " + from, Long.class, student);
	}

}
