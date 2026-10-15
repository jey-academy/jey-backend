package com.jey.dongtanpass.application;

import java.time.LocalDate;

import com.jey.TestcontainersConfiguration;
import com.jey.WithJeyUser;
import com.jey.core.auth.api.UserRole;
import com.jey.core.shared.Money;
import com.jey.dongtanpass.domain.PaymentMethod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static com.jey.core.member.MemberFixtures.randomPhone;
import static com.jey.dongtanpass.DongtanpassFixtures.upsertPartner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

// 패스 번호는 무작위라 이미 있는 번호와 겹칠 수 있다. 겹치면 발급을 통째로 다시 한다.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
class PassIssueRetryTest {

	private static final long PARTNER = 900_401L;

	@Autowired
	PassService passService;

	@Autowired
	JdbcTemplate jdbc;

	@MockitoSpyBean
	PassNumbers passNumbers;

	@BeforeEach
	void setUp() {
		upsertPartner(jdbc, PARTNER, true);
	}

	@Test
	@WithJeyUser(role = UserRole.PARTNER, partnerId = PARTNER)
	void 번호가_겹치면_새_번호로_다시_발급하고_겹친_시도의_흔적은_남지_않는다() {
		String taken = passService.issue(PARTNER, newPass()).passNo();
		int passesBefore = count("dongtanpass_pass");
		int consentsBefore = count("dongtanpass_consent");
		int eventsBefore = issuedEvents();
		doReturn(taken).doCallRealMethod().when(passNumbers).next();
		clearInvocations(passNumbers);

		IssuedPass issued = passService.issue(PARTNER, newPass());

		assertThat(issued.passNo()).isNotEqualTo(taken);
		verify(passNumbers, times(2)).next();
		assertThat(count("dongtanpass_pass")).isEqualTo(passesBefore + 1);
		assertThat(count("dongtanpass_consent")).isEqualTo(consentsBefore + 1);
		assertThat(issuedEvents()).isEqualTo(eventsBefore + 1);
	}

	// 계속 겹치면 정해진 횟수 뒤에 포기한다(서버 오류). 32글자 여섯 자리라 실제로는 일어나지 않는다.
	@Test
	@WithJeyUser(role = UserRole.PARTNER, partnerId = PARTNER)
	void 계속_겹치면_정해진_횟수만_시도하고_실패한다() {
		String taken = passService.issue(PARTNER, newPass()).passNo();
		int passesBefore = count("dongtanpass_pass");
		doReturn(taken).when(passNumbers).next();
		clearInvocations(passNumbers);

		assertThatThrownBy(() -> passService.issue(PARTNER, newPass()))
			.isInstanceOf(DataIntegrityViolationException.class);

		verify(passNumbers, times(PassService.MAX_PASS_NO_ATTEMPTS)).next();
		assertThat(PassService.MAX_PASS_NO_ATTEMPTS).isEqualTo(3);
		assertThat(count("dongtanpass_pass")).isEqualTo(passesBefore);
	}

	private static NewPass newPass() {
		return new NewPass("김하윤", randomPhone(), LocalDate.of(2026, 10, 1), Money.of(150000), PaymentMethod.CARD, true,
				ConsentNotice.CURRENT.version());
	}

	private int count(String table) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
	}

	// 발행 기록은 리스너마다 한 줄이라 이벤트 ID로 센다.
	private int issuedEvents() {
		return jdbc.queryForObject("""
				SELECT COUNT(DISTINCT JSON_UNQUOTE(JSON_EXTRACT(serialized_event, '$.metadata.eventId')))
				FROM event_publication WHERE serialized_event LIKE '%dongtanpass.pass-issued%'
				""", Integer.class);
	}

}
