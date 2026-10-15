package com.jey.dongtanpass.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.stream.Stream;

import com.jey.core.shared.Money;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PassTest {

	private static final String HASH = "a".repeat(64);

	private static final LocalDate ISSUED_ON = LocalDate.of(2026, 10, 14);

	// 서울 기준 2026-10-14 10:00
	private static final Instant ISSUED_AT = Instant.parse("2026-10-14T01:00:00Z");

	@ParameterizedTest(name = "{0} 발급 → {1}까지")
	@CsvSource({ "2026-10-14,2026-11-14", "2026-10-31,2026-11-30", "2026-01-31,2026-02-28", "2028-01-31,2028-02-29",
			"2026-12-15,2027-01-15" })
	void 유효기간은_발급일에_한_달을_더한_날까지다(LocalDate issuedOn, LocalDate expiresOn) {
		assertThat(Pass.expiryOf(issuedOn)).isEqualTo(expiresOn);
		assertThat(issue("김하윤", issuedOn).getExpiresOn()).isEqualTo(expiresOn);
	}

	@Test
	void 발급한_패스는_발급_상태다() {
		Pass pass = issue("김하윤", ISSUED_ON);

		assertThat(pass.getStatus()).isEqualTo(PassStatus.ISSUED);
		assertThat(pass.stateOn(ISSUED_ON)).isEqualTo(PassState.ISSUED);
	}

	@Test
	void 만료일_당일까지는_쓸_수_있고_다음_날부터_만료다() {
		Pass pass = issue("김하윤", ISSUED_ON);

		assertThat(pass.stateOn(LocalDate.of(2026, 11, 14))).isEqualTo(PassState.ISSUED);
		assertThat(pass.isExpiredOn(LocalDate.of(2026, 11, 14))).isFalse();
		assertThat(pass.stateOn(LocalDate.of(2026, 11, 15))).isEqualTo(PassState.EXPIRED);
		assertThat(pass.isExpiredOn(LocalDate.of(2026, 11, 15))).isTrue();
	}

	@Test
	void 취소하면_취소_상태이고_기간이_지나도_취소로_보인다() {
		Pass pass = issue("김하윤", ISSUED_ON);

		pass.cancel();

		assertThat(pass.getStatus()).isEqualTo(PassStatus.CANCELED);
		assertThat(pass.stateOn(LocalDate.of(2027, 1, 1))).isEqualTo(PassState.CANCELED);
	}

	@Test
	void 취소한_패스는_다시_취소할_수도_링크를_새로_만들_수도_없다() {
		Pass pass = issue("김하윤", ISSUED_ON);
		pass.cancel();

		assertThatThrownBy(pass::cancel).isInstanceOfSatisfying(PassStatusException.class,
				ex -> assertThat(ex.status()).isEqualTo(PassStatus.CANCELED));
		assertThatThrownBy(() -> pass.renewLink("b".repeat(64))).isInstanceOfSatisfying(PassStatusException.class,
				ex -> assertThat(ex.status()).isEqualTo(PassStatus.CANCELED));
		assertThat(pass.getLinkTokenHash()).isEqualTo(HASH);
	}

	@Test
	void 링크를_새로_만들면_해시가_바뀐다() {
		Pass pass = issue("김하윤", ISSUED_ON);

		pass.renewLink("b".repeat(64));

		assertThat(pass.getLinkTokenHash()).isEqualTo("b".repeat(64));
	}

	// 취소는 환불 정리에, 링크는 학부모가 만료를 확인하는 데 쓸 수 있다.
	@Test
	void 기간이_지난_패스도_링크를_새로_만들고_취소할_수_있다() {
		Pass pass = issue("김하윤", LocalDate.of(2026, 1, 1));

		pass.renewLink("b".repeat(64));
		pass.cancel();

		assertThat(pass.getStatus()).isEqualTo(PassStatus.CANCELED);
	}

	static Stream<String> wrongHashes() {
		return Stream.of(null, "", "c", "C".repeat(64), "g".repeat(64), "a".repeat(63), "a".repeat(65));
	}

	@ParameterizedTest
	@MethodSource("wrongHashes")
	void 링크_해시는_64자_소문자_16진수만_받는다(String hash) {
		Pass pass = issue("김하윤", ISSUED_ON);

		assertThatIllegalArgumentException().isThrownBy(() -> pass.renewLink(hash));
	}

	@Test
	void 학생_이름은_앞뒤_공백만_떼고_저장한다() {
		assertThat(issue("  김 하윤 ", ISSUED_ON).getStudentName()).isEqualTo("김 하윤");
		assertThat(issue("　김하윤　", ISSUED_ON).getStudentName()).isEqualTo("김하윤");
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = { " ", "　" })
	void 학생_이름이_비면_만들지_않는다(String name) {
		assertThatIllegalArgumentException().isThrownBy(() -> issue(name, ISSUED_ON));
	}

	@Test
	void 학생_이름은_50자까지다() {
		assertThat(issue("가".repeat(Pass.MAX_STUDENT_NAME_LENGTH), ISSUED_ON).getStudentName()).hasSize(50);
		assertThatIllegalArgumentException().isThrownBy(() -> issue("가".repeat(51), ISSUED_ON));
	}

	@Test
	void 패스_번호와_링크_해시가_형식에_맞지_않으면_만들지_않는다() {
		assertThatIllegalArgumentException().isThrownBy(() -> Pass.issue(1L, "DT-ABC23O", HASH, "김하윤", "idx",
				ISSUED_AT, ISSUED_ON, ISSUED_ON, Money.of(150000), PaymentMethod.CARD));
		assertThatIllegalArgumentException().isThrownBy(() -> Pass.issue(1L, "DT-ABC234", "x", "김하윤", "idx",
				ISSUED_AT, ISSUED_ON, ISSUED_ON, Money.of(150000), PaymentMethod.CARD));
	}

	@Test
	void 번호_색인과_결제_기록은_비울_수_없다() {
		assertThatIllegalArgumentException().isThrownBy(() -> Pass.issue(1L, "DT-ABC234", HASH, "김하윤", " ",
				ISSUED_AT, ISSUED_ON, ISSUED_ON, Money.of(150000), PaymentMethod.CARD));
		assertThatIllegalArgumentException().isThrownBy(() -> Pass.issue(1L, "DT-ABC234", HASH, "김하윤", "idx",
				ISSUED_AT, ISSUED_ON, null, Money.of(150000), PaymentMethod.CARD));
		assertThatIllegalArgumentException().isThrownBy(() -> Pass.issue(1L, "DT-ABC234", HASH, "김하윤", "idx",
				ISSUED_AT, ISSUED_ON, ISSUED_ON, null, PaymentMethod.CARD));
		assertThatIllegalArgumentException().isThrownBy(() -> Pass.issue(1L, "DT-ABC234", HASH, "김하윤", "idx",
				ISSUED_AT, ISSUED_ON, ISSUED_ON, Money.of(150000), null));
	}

	@Test
	void 화면에_보이는_상태와_결제_수단은_한국어_표시를_가진다() {
		assertThat(PassState.ISSUED.label()).isEqualTo("발급됨");
		assertThat(PassState.EXPIRED.label()).isEqualTo("만료");
		assertThat(PassState.REDEEMED.label()).isEqualTo("사용됨");
		assertThat(PassState.CANCELED.label()).isEqualTo("취소됨");
		assertThat(PaymentMethod.CARD.label()).isEqualTo("카드");
		assertThat(PaymentMethod.CASH.label()).isEqualTo("현금");
		assertThat(PaymentMethod.TRANSFER.label()).isEqualTo("계좌이체");
		assertThat(PaymentMethod.OTHER.label()).isEqualTo("기타");
	}

	private static Pass issue(String name, LocalDate issuedOn) {
		return Pass.issue(1L, "DT-ABC234", HASH, name, "idx", ISSUED_AT, issuedOn, issuedOn, Money.of(150000),
				PaymentMethod.CARD);
	}

}
