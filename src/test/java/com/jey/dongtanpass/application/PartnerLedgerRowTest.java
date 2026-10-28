package com.jey.dongtanpass.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.jey.core.shared.Money;

// 연계 학원 장부의 한 줄(A~J)과 머리글, 앱 표시로 쓴 줄 찾기. Docker가 필요 없다.
class PartnerLedgerRowTest {

	private static final LocalDate USED_ON = LocalDate.of(2026, 11, 3);

	@Test
	void 머리글은_열_개다() {
		assertThat(PartnerLedgerRow.HEADER).containsExactly("구분", "사용일", "학생", "패스 번호", "원래 수강료", "할인율", "할인액",
				"받을 금액", "JEY 지점", "앱 표시");
	}

	@Test
	void 할인해_준_기록은_금액을_숫자로_할인율을_퍼센트_글자로_쓴다() {
		PartnerLedgerRow row = PartnerLedgerRow.partnerDiscount(7, USED_ON, "김하윤", "JP-0007", Money.of(280_000),
				new BigDecimal("0.05"), Money.of(14_000), Money.of(266_000), "동탄관");

		assertThat(row.cells()).containsExactly("제이G패스 할인", "2026-11-03", "김하윤", "JP-0007", 280_000L, "5%", 14_000L,
				266_000L, "동탄관", "앱:패스-7");
	}

	@Test
	void 할인율의_소수는_필요한_자리까지만_쓴다() {
		PartnerLedgerRow row = PartnerLedgerRow.partnerDiscount(7, USED_ON, "김하윤", "JP-0007", Money.of(280_000),
				new BigDecimal("0.0750"), Money.of(21_000), Money.of(259_000), "동탄관");

		assertThat(row.cells().get(5)).isEqualTo("7.5%");
	}

	@Test
	void JEY에서_쓰인_기록은_금액_칸이_비어_있다() {
		PartnerLedgerRow row = PartnerLedgerRow.usedAtJey(8, USED_ON, "이서준", "DP-0008", "능동관");

		assertThat(row.cells()).containsExactly("JEY에서 사용", "2026-11-03", "이서준", "DP-0008", "", "", "", "", "능동관",
				"앱:패스-8");
	}

	@Test
	void 머리글이_같은지는_1행만_보고_앞뒤_공백은_무시한다() {
		List<String> header = PartnerLedgerRow.HEADER.stream().map(name -> " " + name + " ").toList();

		assertThat(PartnerLedgerRow.headerMatches(List.of(header, List.of("x")))).isTrue();
		assertThat(PartnerLedgerRow.headerMatches(List.of())).isFalse();
		assertThat(PartnerLedgerRow.headerMatches(List.of(PartnerLedgerRow.HEADER.subList(0, 9)))).isFalse();
		assertThat(PartnerLedgerRow.headerMatches(List.of(List.of("학년", "No", "학교")))).isFalse();
	}

	@Test
	void 앱_표시로_그_패스의_줄_번호를_찾는다() {
		List<List<String>> values = List.of(PartnerLedgerRow.HEADER,
				List.of("제이G패스 할인", "2026-11-01", "가", "JP-1", "1", "5%", "1", "1", "동탄관", "앱:패스-70"),
				List.of(),
				List.of("JEY에서 사용", "2026-11-03", "나", "DP-7", "", "", "", "", "동탄관", " 앱:패스-7 "));

		assertThat(PartnerLedgerRow.rowOf(values, 7)).contains(4);
		assertThat(PartnerLedgerRow.rowOf(values, 70)).contains(2);
		assertThat(PartnerLedgerRow.rowOf(values, 8)).isEmpty();
	}

}
