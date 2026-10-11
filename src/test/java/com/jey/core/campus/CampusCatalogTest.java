package com.jey.core.campus;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.UUID;

import com.jey.TestcontainersConfiguration;
import com.jey.core.campus.api.CampusCatalog;
import com.jey.core.campus.api.ClassFee;
import com.jey.core.campus.api.ClassInfo;
import com.jey.core.campus.api.DiscountTypeInfo;
import com.jey.core.campus.domain.AcademyClass;
import com.jey.core.campus.domain.DiscountType;
import com.jey.core.shared.Money;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
class CampusCatalogTest {

	@Autowired
	CampusCatalog catalog;

	@Autowired
	ClassService classService;

	@Autowired
	DiscountTypeService discountTypeService;

	@Test
	void 지점이_있는지_확인한다() {
		assertThat(catalog.campusExists(1)).isTrue();
		assertThat(catalog.campusExists(2)).isTrue();
		assertThat(catalog.campusExists(999_999)).isFalse();
	}

	@Test
	void 반과_수강료를_따로_돌려준다() {
		String name = "반-" + UUID.randomUUID().toString().substring(0, 8);
		AcademyClass created = classService.create(2, name, Money.of(280_000), Money.of(30_000));

		assertThat(catalog.findClass(created.getId())).contains(new ClassInfo(created.getId(), 2, name, true));
		assertThat(catalog.findClassFee(created.getId()))
				.contains(new ClassFee(created.getId(), 2, Money.of(280_000), Money.of(30_000)));
	}

	// 이미 만든 청구가 꺼진 반을 참조한다. 새로 고를 수 있는지는 부르는 쪽이 active로 판단한다.
	@Test
	void 꺼진_반도_돌려준다() {
		String name = "반-" + UUID.randomUUID().toString().substring(0, 8);
		AcademyClass created = classService.create(1, name, Money.of(280_000), Money.ZERO);
		classService.update(created.getId(), name, Money.of(280_000), Money.ZERO, false);

		assertThat(catalog.findClass(created.getId())).map(ClassInfo::active).contains(false);
		assertThat(catalog.findClassFee(created.getId())).isPresent();
	}

	@Test
	void 없는_반은_비어_있다() {
		assertThat(catalog.findClass(999_999_999L)).isEmpty();
		assertThat(catalog.findClassFee(999_999_999L)).isEmpty();
	}

	// 할인율은 0.0500이 아니라 0.05로 온다. 레코드의 equals가 표현까지 비교하므로 여기서 어긋나면 드러난다.
	@Test
	void 코드로_할인_유형을_찾는다() {
		DiscountTypeInfo sibling = catalog.findDiscountType("SIBLING_5").orElseThrow();

		assertThat(sibling.name()).isEqualTo("형제 할인 5%");
		assertThat(sibling.rate()).isEqualTo(new BigDecimal("0.05"));
		assertThat(sibling.active()).isTrue();
		assertThat(catalog.findDiscountType("DONGTAN_PASS")).isPresent();
	}

	@Test
	void 꺼진_할인_유형도_돌려준다() {
		String code = "T_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
		DiscountType created = discountTypeService.create(code, "시험 할인", new BigDecimal("0.03"));
		discountTypeService.update(created.getId(), "시험 할인", new BigDecimal("0.03"), false);

		assertThat(catalog.findDiscountType(code)).map(DiscountTypeInfo::active).contains(false);
	}

	@Test
	void 없는_코드는_비어_있다() {
		assertThat(catalog.findDiscountType("NO_SUCH_CODE")).isEmpty();
		assertThat(catalog.findDiscountType(null)).isEmpty();
	}

}
