package com.jey.dongtanpass.application;

import java.sql.SQLException;
import java.util.UUID;

import com.jey.TestcontainersConfiguration;
import com.jey.core.shared.BusinessException;
import com.jey.dongtanpass.domain.PartnerRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

// API로는 만들기 어려운 갈래를 서비스에서 본다. 흐름 전체는 PartnerApiTest와 PartnerDeactivationTest가 본다.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
class PartnerServiceTest {

	@Autowired
	PartnerService partnerService;

	@Autowired
	TransactionTemplate transaction;

	@Autowired
	JdbcTemplate jdbc;

	@MockitoSpyBean
	PartnerRepository partners;

	// 이름의 유니크 키가 아닌 제약에 걸린 저장을 "같은 이름이 있다"로 답하면 다른 원인의 오류가 중복처럼 보인다. 서버 오류로 드러나야 한다.
	@Test
	void 이름이_아닌_제약에_걸린_저장은_이름_중복이_아니라_그대로_던진다() {
		DataIntegrityViolationException other = violationOf("dongtanpass_partner.uk_dongtanpass_partner_other");
		doThrow(other).when(partners).saveAndFlush(any());

		assertThatThrownBy(() -> partnerService.create(uniqueName())).isSameAs(other);
	}

	@Test
	void 이름의_유니크_키에_걸린_저장은_이름_중복이다() {
		doThrow(violationOf("dongtanpass_partner.uk_dongtanpass_partner_name")).when(partners).saveAndFlush(any());

		assertThatThrownBy(() -> partnerService.create(uniqueName())).isInstanceOfSatisfying(BusinessException.class,
				ex -> assertThat(ex.getErrorCode()).isEqualTo(DongtanpassErrorCode.PARTNER_NAME_DUPLICATED));
	}

	// 바깥 트랜잭션에 합류하면 학원을 끈 것이 커밋되기 전에 계정을 차단하게 된다.
	// 계정 차단(disableAll)도 같은 확인을 하지만, 끄기가 일을 시작하기 전에 스스로 멈추는지 본다.
	@Test
	void 끄기는_트랜잭션_안에서_부를_수_없고_학원도_그대로다() {
		long partnerId = partnerService.create(uniqueName()).id();

		assertThatThrownBy(() -> transaction.executeWithoutResult(status -> partnerService.deactivate(partnerId)))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("PartnerService#deactivate");
		assertThat(jdbc.queryForObject("SELECT active FROM dongtanpass_partner WHERE id = ?", Boolean.class, partnerId))
			.isTrue();
	}

	private static DataIntegrityViolationException violationOf(String constraintName) {
		return new DataIntegrityViolationException("일부러 낸 제약 위반",
				new ConstraintViolationException("일부러 낸 제약 위반", new SQLException("duplicate"), constraintName));
	}

	private static String uniqueName() {
		return "서비스확인-" + UUID.randomUUID().toString().substring(0, 8);
	}

}
