package com.jey.dongtanpass.application;

import java.util.List;

import com.jey.TestcontainersConfiguration;
import com.jey.core.campus.api.CampusCatalog;
import com.jey.dongtanpass.domain.Benefit;
import com.jey.dongtanpass.domain.BenefitRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// 패스를 받는 지점과 그 지점이 주는 할인. 지점과 할인율은 저장하지 않고 기초 모듈에서 읽는다.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
class PassBenefitsTest {

	@Autowired
	PassBenefits passBenefits;

	@Autowired
	CampusCatalog catalog;

	@MockitoSpyBean
	BenefitRepository benefits;

	@Test
	void 받는_지점과_할인율을_돌려준다() {
		PassBenefit benefit = passBenefits.current();

		assertThat(benefit.campusId()).isEqualTo(1);
		assertThat(benefit.campusName()).isEqualTo(catalog.findCampus(1).orElseThrow().name());
		assertThat(benefit.discountTypeCode()).isEqualTo("DONGTAN_PASS");
		assertThat(benefit.discountRate()).isEqualByComparingTo("0.05");
	}

	// 받는 지점은 한 곳이라고 보고 화면을 만든다. 행이 없거나 늘면 설정을 고쳐야 한다(조용히 하나를 고르지 않는다).
	@Test
	void 행이_없으면_설정_오류다() {
		doReturn(List.of()).when(benefits).findAll();

		assertThatIllegalStateException().isThrownBy(passBenefits::current);
	}

	@Test
	void 행이_둘이면_설정_오류다() {
		List<Benefit> one = benefits.findAll();
		doReturn(List.of(one.getFirst(), one.getFirst())).when(benefits).findAll();

		assertThatIllegalStateException().isThrownBy(passBenefits::current);
	}

	@Test
	void 가리키는_지점이_없으면_설정_오류다() {
		doReturn(List.of(benefit(999_999L, "DONGTAN_PASS"))).when(benefits).findAll();

		assertThatIllegalStateException().isThrownBy(passBenefits::current).withMessageContaining("지점이 없다");
	}

	@Test
	void 가리키는_할인_유형이_없으면_설정_오류다() {
		doReturn(List.of(benefit(1L, "NO_SUCH_DISCOUNT"))).when(benefits).findAll();

		assertThatIllegalStateException().isThrownBy(passBenefits::current).withMessageContaining("할인 유형이 없다");
	}

	private static Benefit benefit(long campusId, String discountTypeCode) {
		Benefit benefit = mock(Benefit.class);
		when(benefit.getProviderCampusId()).thenReturn(campusId);
		when(benefit.getDiscountTypeCode()).thenReturn(discountTypeCode);
		return benefit;
	}

}
