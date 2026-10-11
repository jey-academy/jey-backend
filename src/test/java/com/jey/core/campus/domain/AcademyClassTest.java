package com.jey.core.campus.domain;

import java.math.BigDecimal;

import com.jey.core.shared.Money;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class AcademyClassTest {

	private static final Money FEE = Money.of(280_000);

	private static final Money BOOK_FEE = Money.of(30_000);

	@Test
	void 반을_만들면_운영_중이고_이름의_앞뒤_공백을_뗀다() {
		AcademyClass created = AcademyClass.create(1L, "  중등 A반 ", FEE, BOOK_FEE);

		assertThat(created.getCampusId()).isEqualTo(1L);
		assertThat(created.getName()).isEqualTo("중등 A반");
		assertThat(created.getBaseFee8()).isEqualTo(FEE);
		assertThat(created.getBookFee()).isEqualTo(BOOK_FEE);
		assertThat(created.isActive()).isTrue();
	}

	@Test
	void 필수값이_비면_만들_수_없다() {
		assertThatIllegalArgumentException().isThrownBy(() -> AcademyClass.create(null, "A반", FEE, BOOK_FEE));
		assertThatIllegalArgumentException().isThrownBy(() -> AcademyClass.create(1L, " ", FEE, BOOK_FEE));
		assertThatIllegalArgumentException().isThrownBy(() -> AcademyClass.create(1L, null, FEE, BOOK_FEE));
		assertThatIllegalArgumentException().isThrownBy(() -> AcademyClass.create(1L, "A반", null, BOOK_FEE));
		assertThatIllegalArgumentException().isThrownBy(() -> AcademyClass.create(1L, "A반", FEE, null));
	}

	@Test
	void 이름은_50자까지다() {
		assertThat(AcademyClass.create(1L, "가".repeat(50), FEE, BOOK_FEE).getName()).hasSize(50);
		assertThatIllegalArgumentException()
				.isThrownBy(() -> AcademyClass.create(1L, "가".repeat(51), FEE, BOOK_FEE));
	}

	// Money는 계산 중간값을 담으려고 소수를 허용한다. 수강료표에는 끊긴 금액만 들어간다.
	@Test
	void 수강료와_교재비는_1원_단위여야_한다() {
		Money fractional = Money.of(new BigDecimal("280000.5"));

		assertThatIllegalArgumentException().isThrownBy(() -> AcademyClass.create(1L, "A반", fractional, BOOK_FEE));
		assertThatIllegalArgumentException().isThrownBy(() -> AcademyClass.create(1L, "A반", FEE, fractional));
	}

	@Test
	void 수강료는_0원일_수_없고_교재비는_0원일_수_있다() {
		assertThatIllegalArgumentException().isThrownBy(() -> AcademyClass.create(1L, "A반", Money.ZERO, BOOK_FEE));
		assertThat(AcademyClass.create(1L, "A반", FEE, Money.ZERO).getBookFee()).isEqualTo(Money.ZERO);
	}

	@Test
	void 이름과_금액과_운영_여부를_바꾼다() {
		AcademyClass academyClass = AcademyClass.create(1L, "A반", FEE, BOOK_FEE);

		academyClass.update(" B반 ", Money.of(300_000), Money.ZERO, false);

		assertThat(academyClass.getName()).isEqualTo("B반");
		assertThat(academyClass.getBaseFee8()).isEqualTo(Money.of(300_000));
		assertThat(academyClass.getBookFee()).isEqualTo(Money.ZERO);
		assertThat(academyClass.isActive()).isFalse();
	}

	// 값 하나가 틀리면 앞의 값만 바뀐 채로 남지 않는다.
	@Test
	void 바꿀_값이_틀리면_아무것도_바뀌지_않는다() {
		AcademyClass academyClass = AcademyClass.create(1L, "A반", FEE, BOOK_FEE);

		assertThatIllegalArgumentException()
				.isThrownBy(() -> academyClass.update("B반", Money.of(300_000), Money.of(new BigDecimal("0.5")), false));

		assertThat(academyClass.getName()).isEqualTo("A반");
		assertThat(academyClass.getBaseFee8()).isEqualTo(FEE);
		assertThat(academyClass.isActive()).isTrue();
	}

}
