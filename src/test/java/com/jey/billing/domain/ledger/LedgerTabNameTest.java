package com.jey.billing.domain.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.YearMonth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

// 장부 탭 이름의 틀. 실제 장부는 한 파일에 지점별·월별 탭이고 이름이 "11월 동탄관(26)" 모양이다.
class LedgerTabNameTest {

	@Test
	void 실제_장부의_탭_이름을_만든다() {
		LedgerTabName name = LedgerTabName.of("{month}월 {campus}({yy})");

		assertThat(name.format(YearMonth.of(2026, 11), "동탄관")).isEqualTo("11월 동탄관(26)");
		assertThat(name.format(YearMonth.of(2027, 1), "능동관")).isEqualTo("1월 능동관(27)");
	}

	@Test
	void 두_자리_월과_네_자리_연도도_쓸_수_있다() {
		assertThat(LedgerTabName.of("{year}-{month2}").format(YearMonth.of(2026, 3), "동탄관")).isEqualTo("2026-03");
	}

	@Test
	void 지점이_틀에_있는지_알려_준다() {
		assertThat(LedgerTabName.of("{month}월 {campus}({yy})").includesCampus()).isTrue();
		assertThat(LedgerTabName.of("{year}-{month2}").includesCampus()).isFalse();
	}

	// 월이나 연도가 빠지면 다른 달·다른 해의 탭과 이름이 겹친다. 모르는 자리표시는 오타다.
	@ParameterizedTest
	@ValueSource(strings = { "{campus}({yy})", "{month}월 {campus}", "{month}월 {campus}({yyyy})", "{month}월 {지점}({yy})",
			"", "   " })
	void 월과_연도가_없거나_모르는_자리표시가_있으면_받지_않는다(String pattern) {
		assertThatIllegalArgumentException().isThrownBy(() -> LedgerTabName.of(pattern));
	}

	@Test
	void 틀이_없으면_받지_않는다() {
		assertThatIllegalArgumentException().isThrownBy(() -> LedgerTabName.of(null));
	}

}
