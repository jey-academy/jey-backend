package com.jey.dongtanpass.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.stream.Stream;

import com.jey.core.shared.Money;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PassTest {

	private static final String HASH = "a".repeat(64);

	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

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

	// 발급일은 발급 시각을 그 시간대의 날짜로 바꾼 것이다. 따로 받지 않아 둘이 어긋날 수 없다.
	@ParameterizedTest(name = "{0} 발급 → 발급일 {1}, {2}까지")
	@CsvSource({ "2026-10-16T14:59:59.999999Z,2026-10-16,2026-11-16", "2026-10-16T15:00:00Z,2026-10-17,2026-11-17" })
	void 발급일은_발급_시각의_서울_날짜다(Instant issuedAt, LocalDate issuedOn, LocalDate expiresOn) {
		Pass pass = Pass.issue(1L, "DT-ABC234", HASH, "김하윤", "idx", issuedAt, SEOUL, ISSUED_ON, Money.of(150000),
				PaymentMethod.CARD);

		assertThat(pass.getIssuedAt()).isEqualTo(issuedAt);
		assertThat(pass.issuedOn(SEOUL)).isEqualTo(issuedOn);
		assertThat(pass.getExpiresOn()).isEqualTo(expiresOn);
	}

	// 시간대를 고정하지 않고 넘겨받은 것으로 바꾼다. 같은 시각도 UTC로는 전날이다.
	@Test
	void 발급일은_넘겨받은_시간대의_날짜다() {
		Pass pass = Pass.issue(1L, "DT-ABC234", HASH, "김하윤", "idx", Instant.parse("2026-10-16T15:00:00Z"), SEOUL,
				ISSUED_ON, Money.of(150000), PaymentMethod.CARD);

		assertThat(pass.issuedOn(SEOUL)).isEqualTo(LocalDate.of(2026, 10, 17));
		assertThat(pass.issuedOn(ZoneOffset.UTC)).isEqualTo(LocalDate.of(2026, 10, 16));
	}

	// 오늘을 포함해 쓸 수 있는 날 수다. 만료일 당일은 1, 그다음 날부터 0.
	@ParameterizedTest(name = "{0} → {1}일")
	@CsvSource({ "2026-10-14,32", "2026-11-13,2", "2026-11-14,1", "2026-11-15,0", "2027-01-01,0" })
	void 남은_일수는_오늘을_포함해_센다(LocalDate today, int daysLeft) {
		assertThat(issue("김하윤", ISSUED_ON).daysLeftOn(today)).isEqualTo(daysLeft);
	}

	@Test
	void 달_끝에_발급하면_다음_달_마지막_날까지_센다() {
		Pass pass = issue("김하윤", LocalDate.of(2026, 10, 31));

		assertThat(pass.daysLeftOn(LocalDate.of(2026, 11, 30))).isEqualTo(1);
		assertThat(pass.daysLeftOn(LocalDate.of(2026, 12, 1))).isZero();
	}

	// 사용됨은 바꾸는 메서드가 아직 없어 학부모 열람 API 테스트에서 본다.
	@Test
	void 취소한_패스는_기한이_남아도_0일이다() {
		Pass pass = issue("김하윤", ISSUED_ON);

		pass.cancel();

		assertThat(pass.daysLeftOn(ISSUED_ON)).isZero();
	}

	// 만료일은 결제일이 아니라 발급일로 정한다.
	@Test
	void 결제일이_발급일보다_앞서도_만료일은_발급일에서_센다() {
		Pass pass = Pass.issue(1L, "DT-ABC234", HASH, "김하윤", "idx", ISSUED_AT, SEOUL, ISSUED_ON.minusDays(3),
				Money.of(150000), PaymentMethod.CARD);

		assertThat(pass.getExpiresOn()).isEqualTo(LocalDate.of(2026, 11, 14));
		assertThat(pass.getPaidOn()).isEqualTo(LocalDate.of(2026, 10, 11));
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
	void 사용하면_사용됨이고_그_사용_기록을_돌려준다() {
		Pass pass = saved(issue("김하윤", ISSUED_ON));

		Redemption redemption = pass.redeem(5, 1, ISSUED_AT);

		assertThat(redemption.getPassId()).isEqualTo(77);
		assertThat(redemption.getStudentId()).isEqualTo(5);
		assertThat(redemption.getCampusId()).isEqualTo(1);
		assertThat(redemption.getRedeemedAt()).isEqualTo(ISSUED_AT);
		assertThat(pass.getStatus()).isEqualTo(PassStatus.REDEEMED);
		assertThat(pass.stateOn(ISSUED_ON)).isEqualTo(PassState.REDEEMED);
		assertThat(pass.daysLeftOn(ISSUED_ON)).isZero();
	}

	// 사용된 패스는 다시 쓰거나 취소하거나 링크를 바꿀 수 없다. 취소된 패스는 쓸 수 없다.
	@Test
	void 발급_상태가_아니면_사용할_수_없고_사용한_패스는_고칠_수_없다() {
		Pass redeemed = saved(issue("김하윤", ISSUED_ON));
		redeemed.redeem(5, 1, ISSUED_AT);
		Pass canceled = saved(issue("김하윤", ISSUED_ON));
		canceled.cancel();

		assertThatThrownBy(() -> redeemed.redeem(6, 1, ISSUED_AT)).isInstanceOfSatisfying(PassStatusException.class,
				ex -> assertThat(ex.status()).isEqualTo(PassStatus.REDEEMED));
		assertThatThrownBy(redeemed::cancel).isInstanceOfSatisfying(PassStatusException.class,
				ex -> assertThat(ex.status()).isEqualTo(PassStatus.REDEEMED));
		assertThatThrownBy(() -> redeemed.renewLink("b".repeat(64)))
			.isInstanceOfSatisfying(PassStatusException.class,
					ex -> assertThat(ex.status()).isEqualTo(PassStatus.REDEEMED));
		assertThatThrownBy(() -> canceled.redeem(5, 1, ISSUED_AT)).isInstanceOfSatisfying(PassStatusException.class,
				ex -> assertThat(ex.status()).isEqualTo(PassStatus.CANCELED));
		assertThat(canceled.getStatus()).isEqualTo(PassStatus.CANCELED);
	}

	// 기간은 사용 처리가 먼저 본다(오늘을 아는 쪽). 패스는 상태만 본다.
	@Test
	void 사용은_상태만_보고_기간은_보지_않는다() {
		Pass pass = saved(issue("김하윤", LocalDate.of(2026, 1, 1)));

		pass.redeem(5, 1, ISSUED_AT);

		assertThat(pass.getStatus()).isEqualTo(PassStatus.REDEEMED);
	}

	// 기록이 가리킬 패스 ID가 없다. 상태도 바뀌지 않는다.
	@Test
	void 저장하지_않은_패스는_사용할_수_없다() {
		Pass pass = issue("김하윤", ISSUED_ON);

		assertThatThrownBy(() -> pass.redeem(5, 1, ISSUED_AT)).isInstanceOf(IllegalStateException.class);
		assertThat(pass.getStatus()).isEqualTo(PassStatus.ISSUED);
	}

	// 사용 시각이 없으면 기록을 만들지 않고 상태도 바꾸지 않는다.
	@Test
	void 사용_시각이_없으면_상태를_바꾸지_않는다() {
		Pass pass = saved(issue("김하윤", ISSUED_ON));

		assertThatIllegalArgumentException().isThrownBy(() -> pass.redeem(5, 1, null));
		assertThat(pass.getStatus()).isEqualTo(PassStatus.ISSUED);
	}

	private static Pass saved(Pass pass) {
		ReflectionTestUtils.setField(pass, "id", 77L);
		return pass;
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
				ISSUED_AT, SEOUL, ISSUED_ON, Money.of(150000), PaymentMethod.CARD));
		assertThatIllegalArgumentException().isThrownBy(() -> Pass.issue(1L, "DT-ABC234", "x", "김하윤", "idx",
				ISSUED_AT, SEOUL, ISSUED_ON, Money.of(150000), PaymentMethod.CARD));
	}

	@Test
	void 번호_색인과_결제_기록은_비울_수_없다() {
		assertThatIllegalArgumentException().isThrownBy(() -> Pass.issue(1L, "DT-ABC234", HASH, "김하윤", " ",
				ISSUED_AT, SEOUL, ISSUED_ON, Money.of(150000), PaymentMethod.CARD));
		assertThatIllegalArgumentException().isThrownBy(() -> Pass.issue(1L, "DT-ABC234", HASH, "김하윤", "idx",
				ISSUED_AT, SEOUL, null, Money.of(150000), PaymentMethod.CARD));
		assertThatIllegalArgumentException().isThrownBy(() -> Pass.issue(1L, "DT-ABC234", HASH, "김하윤", "idx",
				ISSUED_AT, SEOUL, ISSUED_ON, null, PaymentMethod.CARD));
		assertThatIllegalArgumentException().isThrownBy(() -> Pass.issue(1L, "DT-ABC234", HASH, "김하윤", "idx",
				ISSUED_AT, SEOUL, ISSUED_ON, Money.of(150000), null));
		assertThatIllegalArgumentException().isThrownBy(() -> Pass.issue(1L, "DT-ABC234", HASH, "김하윤", "idx",
				ISSUED_AT, null, ISSUED_ON, Money.of(150000), PaymentMethod.CARD));
	}

	// 서비스가 먼저 업무 오류(400)로 거르지만, 다른 길로 만들어도 저장할 수 없는 금액을 받지 않는다.
	@ParameterizedTest
	@ValueSource(strings = { "0", "0.5", "150000.1" })
	void 결제_금액이_0원이거나_1원_단위가_아니면_만들지_않는다(String amount) {
		assertThatIllegalArgumentException().isThrownBy(() -> Pass.issue(1L, "DT-ABC234", HASH, "김하윤", "idx",
				ISSUED_AT, SEOUL, ISSUED_ON, Money.of(new BigDecimal(amount)), PaymentMethod.CARD));
	}

	// 상태 예외는 "그 상태라서 할 수 없다"는 뜻이다. 발급 상태는 무엇이든 할 수 있으므로 그 상태로는 만들 수 없다.
	@Test
	void 발급_상태로는_상태_예외를_만들_수_없다() {
		assertThatIllegalArgumentException().isThrownBy(() -> new PassStatusException(PassStatus.ISSUED));
		assertThat(new PassStatusException(PassStatus.CANCELED).status()).isEqualTo(PassStatus.CANCELED);
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

	// 발급일(서울) 오전 10시에 발급한 패스
	@Test
	void 연계_학원이_발급한_패스의_방향은_PARTNER_ISSUED이고_JEY_칸은_비어_있다() {
		Pass pass = issue("김하윤", ISSUED_ON);

		assertThat(pass.getDirection()).isEqualTo(PassDirection.PARTNER_ISSUED);
		assertThat(pass.getPartnerId()).isEqualTo(1L);
		assertThat(pass.getIssuerCampusId()).isNull();
		assertThat(pass.getStudentId()).isNull();
		assertThat(pass.getDiscountRate()).isNull();
	}

	// JEY 지점이 다니는 학생에게 발급한다. 발급 때의 할인율을 남긴다(나중에 설정을 바꿔도 이 패스는 그대로).
	@Test
	void JEY_발급_패스는_방향과_발급_지점과_학생과_할인율을_가진다() {
		Pass pass = issueAtCampus(new BigDecimal("0.05"));

		assertThat(pass.getDirection()).isEqualTo(PassDirection.CAMPUS_ISSUED);
		assertThat(pass.getPartnerId()).isNull();
		assertThat(pass.getIssuerCampusId()).isEqualTo(7L);
		assertThat(pass.getStudentId()).isEqualTo(42L);
		assertThat(pass.getDiscountRate()).isEqualByComparingTo("0.05");
		assertThat(pass.getStatus()).isEqualTo(PassStatus.ISSUED);
		assertThat(pass.getExpiresOn()).isEqualTo(LocalDate.of(2026, 11, 14));
		assertThat(pass.getPaidAmount()).isEqualTo(Money.of(280000));
	}

	// core의 할인 유형과 같은 규칙: 0보다 크고 1 이하, 소수 넷째 자리까지(컬럼보다 긴 소수는 DB가 조용히 반올림한다).
	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = { "0", "-0.01", "1.0001", "0.12345" })
	void JEY_발급_할인율은_0보다_크고_1_이하_소수_넷째_자리까지(String rate) {
		assertThatIllegalArgumentException().isThrownBy(() -> issueAtCampus(new BigDecimal(rate)));
	}

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = { "1", "0.0001", "0.05000" })
	void JEY_발급_할인율의_경계는_받는다(String rate) {
		assertThat(issueAtCampus(new BigDecimal(rate)).getDiscountRate()).isEqualByComparingTo(rate);
	}

	@Test
	void JEY_발급_할인율은_비울_수_없다() {
		assertThatIllegalArgumentException().isThrownBy(() -> issueAtCampus(null));
	}

	// 연계 학원 발급과 같은 검증을 거친다(대표로 금액 하나).
	@Test
	void JEY_발급도_0원_결제는_받지_않는다() {
		assertThatIllegalArgumentException().isThrownBy(() -> Pass.issueAtCampus(7L, 42L, "DT-ABC234", HASH,
				"김하윤", "idx", ISSUED_AT, SEOUL, ISSUED_ON, Money.of(0), PaymentMethod.CARD, new BigDecimal("0.05")));
	}

	// 사용 기록(JEY 지점에서 쓴 기록)은 연계 학원 발급분에만 생긴다. 데스크가 막지만, 새 경로가 생겨도 여기서 막힌다.
	@Test
	void JEY_발급_패스로는_JEY_지점의_사용_기록을_만들지_않는다() {
		Pass pass = issueAtCampus(new BigDecimal("0.05"));
		ReflectionTestUtils.setField(pass, "id", 9L);

		assertThatThrownBy(() -> pass.redeem(42L, 1L, ISSUED_AT)).isInstanceOf(IllegalStateException.class);
		assertThat(pass.getStatus()).isEqualTo(PassStatus.ISSUED);
	}

	private static Pass issue(String name, LocalDate issuedOn) {
		return Pass.issue(1L, "DT-ABC234", HASH, name, "idx", issuedOn.atTime(10, 0).atZone(SEOUL).toInstant(), SEOUL,
				issuedOn, Money.of(150000), PaymentMethod.CARD);
	}

	private static Pass issueAtCampus(BigDecimal rate) {
		return Pass.issueAtCampus(7L, 42L, "DT-ABC234", HASH, "김하윤", "idx", ISSUED_AT, SEOUL, ISSUED_ON,
				Money.of(280000), PaymentMethod.CARD, rate);
	}

}
