package com.jey.core.audit;

import java.util.Random;
import java.util.UUID;

import com.jey.core.shared.PhoneNumber;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static java.time.Duration.ofSeconds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

// 감사 로그에 전화번호가 실리지 않게 하는 마지막 그물이다.
// 놓치면 번호가 기록에 남고(지울 방법이 없다), 번호가 아닌 것을 막으면 그 값을 싣는 정상 기록이 실패한다. 양쪽을 다 고정한다.
class PhoneLikeTextTest {

	@ParameterizedTest
	@ValueSource(strings = {
			// 흔한 표기
			"010-1234-5678", "01012345678", "010 1234 5678", "010.1234.5678", "011-234-5678", "011-1234-5678",
			"016-123-4567", "017-123-4567", "018-123-4567", "019-1234-5678", "0112345678",
			// 국가 번호
			"+82 10-1234-5678", "+821012345678", "+82 010-1234-5678", "+82 (10) 1234-5678", "+82-10-1234-5678", "abc+821012345678",
			"+82 (0)10 1234 5678", "+82-(0)10-1234-5678", "+82(0)1012345678", "+ 82 10 1234 5678", "+-821012345678",
			"+.82 10-1234-5678",
			// 다른 기호
			"010/1234/5678", "010_1234_5678", "(010)1234-5678", "(010) 1234-5678", "010)1234-5678", "010·1234·5678",
			"010 - 1234 - 5678", "010\t1234\t5678", "010\n1234\n5678", "０１０－１２３４－５６７８",
			// 반각으로 바꾸면 영숫자가 되는 기호(℡ → TEL, ① → 1)에 붙은 번호
			"℡010-1234-5678", "№010-1234-5678", "①010-1234-5678", "010-1234-5678②", "Ａ010-1234-5678",
			// 글 속의 번호
			"연락처 010.1234.5678 로 변경", "연락처01012345678입니다", "Kim 010-1234-5678", "Mr.Kim 010-1234-5678 call",
			"p.010-1234-5678", "tel:01012345678", "kim_01012345678", "010-1234-5678 Kim", "\"010-1234-5678\"",
			"번호는 010-1234-5678.", "a01012345678b 010-1234-5678", "2026-11-01 010-1234-5678", " 010-1234-5678 ",
			// 흩어 쓴 번호. 전화번호 값 객체가 번호로 받아들이는 입력이다.
			"01 0 1234 5678", "0-1-0-1-2-3-4-5-6-7-8", "0.1.0.1234.5678", "+82 1 0 1234 5678",
			// 가운데를 가린 번호. 뒷자리가 남는다.
			"010-****-5678", "011-***-5678", "019-***-1234", "010****5678", "mom 010-****-5678", "+82 10-****-5678" })
	void 전화번호로_본다(String text) {
		assertThat(PhoneLikeText.found(text)).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = {
			// 식별자. 결제 키나 멱등 키가 이런 모양이다.
			"SIBLING_5", "ORD-2026-1011-0001", "a01012345678b", "pay01012345678", "01012345678a",
			"3fa85f64-0101-2345-6780-10123456789a", "0192f8a4-0101-7234-8567-801012345678",
			"00000000-8210-4234-8567-000000000000", "id-10-1234-5678",
			// 날짜와 시각
			"2026-11", "2026-10-11", "2026-10-11T01:02:03.012345Z", "2026-10-11T01:02:03.012345678Z",
			"2026-11-01/2026-11-30", "2026-10-11 2026-10-12", "2026.10.11 - 2026.10.12", "2026-10-11-2026-10-18",
			"2026-01-10/2026-01-31", "10-11-2026 09", "2026-01-01 01:01",
			// IP 주소
			"192.168.10.11", "10.19.123.45", "10.19.123.45/678", "10.16.0.12 34567", "10.11.12.13.14.15", "110.12.34.56",
			// 숫자만으로 된 값. 0으로 시작하지 않으면 국내 번호 표기가 아니다.
			"1012345678", "1760000000", "161234567", "20261101123456", "0101234567890", "8210-1234-5678",
			"821012345678", "010-12-5678", "012-1234-5678", "02-1234-5678", "031-123-4567" })
	void 전화번호로_보지_않는다(String text) {
		assertThat(PhoneLikeText.found(text)).isFalse();
	}

	// 알면서 놓치는 것. 영숫자에 바로 붙은 번호는 식별자 안의 숫자와 구분할 수 없고, 흔치 않은 기호는 다 따라갈 수 없다.
	// 여기 적어 두면 검사식을 바꿀 때 무엇이 달라지는지 드러난다.
	@ParameterizedTest
	@ValueSource(strings = { "tel01012345678", "010–1234–5678", "010‐1234‐5678", "010~1234~5678",
			"010,1234,5678", "010:1234:5678", "010-XXXX-5678", "010-12**-5678", "82-10-1234-5678",
			"0082-10-1234-5678", "3 01 0 1234 5678", "2026-10-11 01 0 1234 5678" })
	void 알면서_놓치는_표기(String text) {
		assertThat(PhoneLikeText.found(text)).isFalse();
	}

	// 알면서 막는 것. 0으로 시작하는 10~11자리 숫자 구간은 번호가 아니어도 번호로 본다.
	// 이런 모양의 값(일련번호, 계좌번호, 소수)은 감사 로그에 문자열로 싣지 못한다. 숫자로 싣거나 모양을 바꿔 싣는다.
	@ParameterizedTest
	@ValueSource(strings = { "016-2026-0001", "017-02-123456", "INV-0101234567", "tid_01012345678",
			"/orders/01012345678", "0.1666666667", "0.10.1234.5678", "010-123-4567" })
	void 번호가_아니어도_번호로_보는_모양(String text) {
		assertThat(PhoneLikeText.found(text)).isTrue();
	}

	// 전화번호 값 객체가 번호로 받아들이는 것은 이 검사도 번호로 봐야 한다. 두 규칙이 어긋나면 그 틈으로 번호가 실린다.
	// 값 객체는 공백·점·하이픈을 어디에 넣어도 받는다. 번호의 글자 사이사이에 기호를 아무렇게나 끼워 본다.
	@Test
	void 전화번호_값_객체가_받는_표기는_모두_전화번호로_본다() {
		Random random = new Random(20261011);
		String[] prefixes = { "010", "011", "016", "017", "018", "019" };
		String[] separators = { " ", "-", ".", "\t" };
		for (int i = 0; i < 50_000; i++) {
			String prefix = prefixes[random.nextInt(prefixes.length)];
			int middleLength = prefix.equals("010") ? 4 : 3 + random.nextInt(2);
			String number = prefix + digits(random, middleLength + 4);
			String written = switch (random.nextInt(3)) {
				case 0 -> number;
				case 1 -> "+82" + number.substring(1);
				default -> "+82" + number;
			};
			StringBuilder text = new StringBuilder();
			for (char character : written.toCharArray()) {
				// 세 번에 한 번꼴로 글자 앞에 기호를 하나에서 둘 끼운다.
				if (random.nextInt(3) == 0) {
					text.append(separators[random.nextInt(separators.length)]);
					if (random.nextInt(4) == 0) {
						text.append(separators[random.nextInt(separators.length)]);
					}
				}
				text.append(character);
			}
			String input = text.toString();

			assertThat(PhoneNumber.of(input).value()).as(input).isEqualTo(number);
			assertThat(PhoneLikeText.found(input)).as(input).isTrue();
			assertThat(PhoneLikeText.found("메모 " + input + " 끝")).as(input).isTrue();
		}
	}

	// 묶음 모양이 맞지 않으면 걸리지 않으므로 UUID는 구조상 걸릴 수 없다. 검사식을 바꾸다 이 성질이 깨지면 여기서 드러난다.
	@Test
	void 무작위_UUID는_전화번호로_보지_않는다() {
		Random random = new Random(20261011);
		for (int i = 0; i < 20_000; i++) {
			String uuid = new UUID(random.nextLong(), random.nextLong()).toString();
			assertThat(PhoneLikeText.found(uuid)).as(uuid).isFalse();
		}
	}

	// 이벤트의 내용에는 긴 문자열이 올 수 있다. 훑는 시간이 길이에 비해 터무니없이 늘어나면 리스너가 멈춘다.
	@Test
	void 긴_글도_금방_훑는다() {
		String digitsAndGaps = "1 2-3.4 ".repeat(25_000);
		String digitsThenLetter = "0".repeat(100_000) + "a";
		String almostPhones = "010-1234-567x ".repeat(15_000);

		assertTimeoutPreemptively(ofSeconds(5), () -> {
			assertThat(PhoneLikeText.found(digitsAndGaps)).isFalse();
			assertThat(PhoneLikeText.found(digitsThenLetter)).isFalse();
			assertThat(PhoneLikeText.found(almostPhones)).isFalse();
		});
	}

	private static String digits(Random random, int length) {
		StringBuilder digits = new StringBuilder();
		for (int i = 0; i < length; i++) {
			digits.append(random.nextInt(10));
		}
		return digits.toString();
	}

}
