package com.jey.billing.domain.ledger;

import static com.jey.billing.domain.ledger.LedgerSamples.dongtanOctober;
import static com.jey.billing.domain.ledger.LedgerSamples.with;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.jey.billing.domain.LedgerSyncReason;
import com.jey.core.shared.Money;
import com.jey.core.shared.PhoneNumber;

/**
 * 샘플 장부(10월 탭)의 행 번호: EJ5 블록 5~13행(첫 빈 줄 10행), 브릿지 14~20행, EJ6 21~35행(첫 빈 줄 28행),
 * EE6 36~46행, MJ1 47~56행. 비고는 Q열(그 사이에 "다음달 선납" 칸이 있다).
 */
class LedgerLayoutTest {

	private static final YearMonth OCTOBER = YearMonth.of(2026, 10);

	private static final String MARKER = "앱:결제-123";

	private static final int NOTE_COLUMN = 16;

	@Test
	void 반_블록에서_빈_첫_줄을_찾는다() {
		LedgerLayout layout = LedgerLayout.parse(dongtanOctober(), OCTOBER);

		assertThat(layout.find("EJ5", MARKER)).isEqualTo(new LedgerTarget.EmptyRow(10));
		assertThat(layout.find("EJ6", MARKER)).isEqualTo(new LedgerTarget.EmptyRow(28));
		assertThat(layout.find("MJ1", MARKER)).isEqualTo(new LedgerTarget.EmptyRow(54));
	}

	@Test
	void 반_칸은_첫_줄의_앞뒤_공백을_다듬어_비교한다() {
		List<List<String>> values = with(dongtanOctober(), 4, 0, "  EJ5 \r\nBH/SH");

		assertThat(LedgerLayout.parse(values, OCTOBER).find("EJ5", MARKER)).isEqualTo(new LedgerTarget.EmptyRow(10));
	}

	@Test
	void 찾는_반_이름의_앞뒤_공백도_다듬는다() {
		assertThat(LedgerLayout.parse(dongtanOctober(), OCTOBER).find(" EJ6 ", MARKER))
				.isEqualTo(new LedgerTarget.EmptyRow(28));
	}

	@Test
	void 반_블록이_없으면_이유를_돌려준다() {
		LedgerLayout layout = LedgerLayout.parse(dongtanOctober(), OCTOBER);

		assertThat(layout.find("ZZ9", MARKER)).isEqualTo(new LedgerTarget.Missing(LedgerSyncReason.BLOCK_NOT_FOUND));
		assertThat(layout.find("BH/SH", MARKER)).isEqualTo(new LedgerTarget.Missing(LedgerSyncReason.BLOCK_NOT_FOUND));
	}

	@Test
	void 블록에_빈_줄이_없으면_이유를_돌려준다() {
		List<List<String>> values = dongtanOctober();
		for (int index = 9; index <= 12; index++) {
			values = with(values, index, 3, "누누누(EJ5)");
		}

		assertThat(LedgerLayout.parse(values, OCTOBER).find("EJ5", MARKER))
				.isEqualTo(new LedgerTarget.Missing(LedgerSyncReason.NO_EMPTY_ROW));
	}

	@Test
	void 마지막_블록의_빈_줄이_시트_값_끝에서_잘려_있으면_빈_줄이_없는_것이다() {
		List<List<String>> values = new ArrayList<>(dongtanOctober().subList(0, 53));

		assertThat(LedgerLayout.parse(values, OCTOBER).find("MJ1", MARKER))
				.isEqualTo(new LedgerTarget.Missing(LedgerSyncReason.NO_EMPTY_ROW));
	}

	@Test
	void 비고_같은_칸이_차_있는_줄은_빈_줄로_보지_않는다() {
		List<List<String>> values = with(dongtanOctober(), 9, NOTE_COLUMN, "자리 맡아 둠");

		assertThat(LedgerLayout.parse(values, OCTOBER).find("EJ5", MARKER)).isEqualTo(new LedgerTarget.EmptyRow(11));
	}

	@Test
	void 빈_칸만_있는_완전히_빈_줄도_블록_안이면_빈_줄이다() {
		List<List<String>> values = dongtanOctober();
		values.set(9, new ArrayList<>());

		assertThat(LedgerLayout.parse(values, OCTOBER).find("EJ5", MARKER)).isEqualTo(new LedgerTarget.EmptyRow(10));
	}

	@Test
	void 그_탭에_같은_결제의_표시가_있으면_그_줄을_돌려준다() {
		List<List<String>> values = with(dongtanOctober(), 27, NOTE_COLUMN, MARKER);

		assertThat(LedgerLayout.parse(values, OCTOBER).find("EJ5", MARKER))
				.isEqualTo(new LedgerTarget.AlreadyWritten(28));
	}

	@Test
	void 취소_표시가_붙은_표시도_같은_결제로_본다() {
		List<List<String>> values = with(dongtanOctober(), 9, NOTE_COLUMN, "앱:결제-123 취소됨(10/31)");

		assertThat(LedgerLayout.parse(values, OCTOBER).find("EJ5", MARKER))
				.isEqualTo(new LedgerTarget.AlreadyWritten(10));
	}

	@Test
	void 앞부분만_같은_다른_결제의_표시는_같은_결제가_아니다() {
		List<List<String>> values = with(dongtanOctober(), 9, NOTE_COLUMN, "앱:결제-1234");

		assertThat(LedgerLayout.parse(values, OCTOBER).find("EJ5", MARKER)).isEqualTo(new LedgerTarget.EmptyRow(11));
	}

	@Test
	void 성명_머리글이_없으면_머리글_없음이다() {
		List<List<String>> values = with(dongtanOctober(), 2, 3, "이름");

		assertThatThrownBy(() -> LedgerLayout.parse(values, OCTOBER))
				.isInstanceOfSatisfying(LedgerLayoutException.class,
						e -> assertThat(e.reason()).isEqualTo(LedgerSyncReason.HEADER_NOT_FOUND));
	}

	@Test
	void 대상_월_칸이_없으면_머리글_없음이다() {
		assertThatThrownBy(() -> LedgerLayout.parse(dongtanOctober(), YearMonth.of(2026, 11)))
				.isInstanceOfSatisfying(LedgerLayoutException.class,
						e -> assertThat(e.reason()).isEqualTo(LedgerSyncReason.HEADER_NOT_FOUND));
	}

	@Test
	void 앱이_쓰는_칸의_머리글이_하나라도_없으면_머리글_없음이다() {
		List<List<String>> values = with(dongtanOctober(), 2, NOTE_COLUMN, "");

		assertThatThrownBy(() -> LedgerLayout.parse(values, OCTOBER))
				.isInstanceOfSatisfying(LedgerLayoutException.class,
						e -> assertThat(e.reason()).isEqualTo(LedgerSyncReason.HEADER_NOT_FOUND));
	}

	@Test
	void 빈_탭은_머리글_없음이다() {
		assertThatThrownBy(() -> LedgerLayout.parse(List.of(), OCTOBER))
				.isInstanceOfSatisfying(LedgerLayoutException.class,
						e -> assertThat(e.reason()).isEqualTo(LedgerSyncReason.HEADER_NOT_FOUND));
	}

	@Test
	void 머리글_안의_줄바꿈과_공백은_무시하고_비교한다() {
		List<List<String>> values = with(dongtanOctober(), 2, 7, "매월 납입금액\n(8회기준)");

		assertThat(LedgerLayout.parse(values, OCTOBER).find("EJ5", MARKER)).isEqualTo(new LedgerTarget.EmptyRow(10));
	}

	@Test
	void 쓸_칸을_머리글_위치대로_A1_표기로_만든다() {
		LedgerLayout layout = LedgerLayout.parse(dongtanOctober(), OCTOBER);
		LedgerRow row = LedgerRow.of(new LedgerEntry(123L, "동탄고1", "김하윤", "EJ5", PhoneNumber.of("01012345678"),
				OCTOBER, 9, List.of("제이G패스"), Money.of(299_250), Money.of(266_000), LocalDate.of(2026, 10, 1),
				Money.of(299_250), Money.of(29_000)));

		assertThat(layout.cells(10, row)).containsExactly(
				org.assertj.core.api.Assertions.entry("C10", "동탄고1"),
				org.assertj.core.api.Assertions.entry("D10", "김하윤(EJ5)"),
				org.assertj.core.api.Assertions.entry("E10", "(010) 1234-5678"),
				org.assertj.core.api.Assertions.entry("F10", 299_250L),
				org.assertj.core.api.Assertions.entry("G10", "10월 9회분 수강료 (제이G패스)"),
				org.assertj.core.api.Assertions.entry("H10", 266_000L),
				org.assertj.core.api.Assertions.entry("L10", "10/1"),
				org.assertj.core.api.Assertions.entry("M10", 299_250L),
				org.assertj.core.api.Assertions.entry("N10", 29_000L),
				org.assertj.core.api.Assertions.entry("O10", "10월 9회분 수강료 (제이G패스) + 교재비 (29,000원)"),
				org.assertj.core.api.Assertions.entry("Q10", "앱:결제-123"));
	}

	@Test
	void 줄이_아직_비어_있는지와_비고를_읽는다() {
		List<List<String>> values = with(dongtanOctober(), 27, NOTE_COLUMN, MARKER);
		LedgerLayout layout = LedgerLayout.parse(values, OCTOBER);

		assertThat(layout.isEmptyRow(10)).isTrue();
		assertThat(layout.isEmptyRow(5)).isFalse();
		assertThat(layout.isEmptyRow(28)).isFalse();
		assertThat(layout.isEmptyRow(999)).isTrue();
		assertThat(layout.noteCell(28)).isEqualTo("Q28");
		assertThat(layout.noteAt(28)).isEqualTo(MARKER);
		assertThat(layout.noteAt(999)).isEmpty();
	}

	@Test
	void 이_결제의_표시가_있는_줄을_찾는다() {
		List<List<String>> values = with(dongtanOctober(), 27, NOTE_COLUMN, MARKER);
		LedgerLayout layout = LedgerLayout.parse(values, OCTOBER);

		assertThat(layout.rowOf(MARKER)).contains(28);
		assertThat(layout.rowOf("앱:결제-999")).isEmpty();
	}

}
