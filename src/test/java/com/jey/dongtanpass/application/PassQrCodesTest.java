package com.jey.dongtanpass.application;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static com.jey.dongtanpass.application.QrSigningPropertiesTest.KEY_1;
import static com.jey.dongtanpass.application.QrSigningPropertiesTest.KEY_2;
import static org.assertj.core.api.Assertions.assertThat;

// 학부모 화면의 QR에 넣는 서명 토큰(ADR-0010). 스프링 없이 본다.
class PassQrCodesTest {

	private static final Instant NOW = Instant.parse("2026-10-16T01:00:00Z");

	private final PassQrCodes codes = new PassQrCodes(new QrSigningProperties(1, Map.of(1, KEY_1)));

	@Test
	void 만든_토큰은_검증되고_패스_ID와_만료_시각과_토큰_ID를_돌려준다() {
		QrCode code = codes.issue(7L, NOW);

		assertThat(code.expiresAt()).isEqualTo(NOW.plus(PassQrCodes.TTL));
		assertThat(PassQrCodes.TTL).isEqualTo(Duration.ofSeconds(60));
		assertThat(codes.verify(code.token(), NOW)).hasValueSatisfying(verified -> {
			assertThat(verified.passId()).isEqualTo(7L);
			assertThat(verified.expiresAt()).isEqualTo(NOW.plusSeconds(60));
			assertThat(verified.tokenId()).matches("[0-9a-f]{32}");
		});
	}

	@Test
	void 토큰은_키_버전과_본문과_서명_세_부분이다() {
		assertThat(codes.issue(7L, NOW).token()).matches("1\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");
	}

	@Test
	void 만들_때마다_토큰_ID가_다르다() {
		String first = codes.verify(codes.issue(7L, NOW).token(), NOW).orElseThrow().tokenId();
		String second = codes.verify(codes.issue(7L, NOW).token(), NOW).orElseThrow().tokenId();

		assertThat(first).isNotEqualTo(second);
	}

	@ParameterizedTest
	@ValueSource(longs = { 0, 1, 59 })
	void 수명_안이면_유효하다(long seconds) {
		assertThat(codes.verify(codes.issue(7L, NOW).token(), NOW.plusSeconds(seconds))).isPresent();
	}

	@ParameterizedTest
	@ValueSource(longs = { 60, 61, 3600 })
	void 만료_시각_정각부터_무효다(long seconds) {
		assertThat(codes.verify(codes.issue(7L, NOW).token(), NOW.plusSeconds(seconds))).isEmpty();
	}

	@Test
	void 만료_시각_직전_나노초까지는_유효하다() {
		assertThat(codes.verify(codes.issue(7L, NOW).token(), NOW.plusSeconds(60).minusNanos(1))).isPresent();
	}

	// 운영 시계는 초 아래 자리가 있다. 화면에 알린 만료 시각과 토큰이 무효가 되는 시각이 같아야 한다.
	@Test
	void 초_아래_자리가_있는_시각에_만들어도_알린_만료_시각과_토큰의_만료_시각이_같다() {
		Instant now = NOW.plusMillis(700);
		QrCode code = codes.issue(7L, now);

		assertThat(code.expiresAt()).isEqualTo(NOW.plus(PassQrCodes.TTL));
		assertThat(codes.verify(code.token(), now)).hasValueSatisfying(
				verified -> assertThat(verified.expiresAt()).isEqualTo(code.expiresAt()));
		assertThat(codes.verify(code.token(), code.expiresAt().minusNanos(1))).isPresent();
		assertThat(codes.verify(code.token(), code.expiresAt())).isEmpty();
	}

	@Test
	void 서명이나_본문을_한_글자라도_바꾸면_무효다() {
		String token = codes.issue(7L, NOW).token();
		String[] parts = token.split("\\.");

		assertThat(codes.verify(parts[0] + "." + parts[1] + "." + flip(parts[2]), NOW)).isEmpty();
		assertThat(codes.verify(parts[0] + "." + flip(parts[1]) + "." + parts[2], NOW)).isEmpty();
	}

	// 같은 바이트로 풀리는 다른 글자(패딩, 마지막 글자의 쓰이지 않는 낮은 비트)도 받으면 한 토큰이 여러 문자열이 된다.
	@Test
	void 같은_서명을_다른_글자로_적으면_무효다() {
		String token = codes.issue(7L, NOW).token();
		String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
		char last = token.charAt(token.length() - 1);
		char sameBytes = alphabet.charAt(alphabet.indexOf(last) ^ 1);
		String head = token.substring(0, token.length() - 1);

		assertThat(codes.verify(token + "=", NOW)).isEmpty();
		assertThat(codes.verify(head + sameBytes, NOW)).isEmpty();
	}

	@Test
	void 다른_키로_서명한_토큰은_무효다() {
		PassQrCodes other = new PassQrCodes(new QrSigningProperties(1, Map.of(1, KEY_2)));

		assertThat(codes.verify(other.issue(7L, NOW).token(), NOW)).isEmpty();
	}

	@Test
	void 모르는_키_버전은_무효다() {
		String token = codes.issue(7L, NOW).token();

		assertThat(codes.verify("9" + token.substring(1), NOW)).isEmpty();
	}

	// 키를 바꾸는 동안 옛 버전으로 만든 토큰도 확인된다.
	@Test
	void 옛_버전의_키가_남아_있으면_그_버전의_토큰도_유효하고_새_토큰은_새_버전이다() {
		PassQrCodes rotated = new PassQrCodes(new QrSigningProperties(2, Map.of(1, KEY_1, 2, KEY_2)));

		assertThat(rotated.verify(codes.issue(7L, NOW).token(), NOW)).isPresent();
		assertThat(rotated.issue(7L, NOW).token()).startsWith("2.");
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = { "x", "1.a", "1.a.b.c", "1.!!!.@@@", "a.b.c", "1..", "-1.AAAA.AAAA", "99999999999.AAAA.AAAA",
			"1.AAAA.AAAA", "١.AAAA.AAAA", "１.AAAA.AAAA" })
	void 모양이_틀린_토큰은_예외_없이_무효다(String token) {
		Optional<VerifiedQr> verified = codes.verify(token, NOW);

		assertThat(verified).isEmpty();
	}

	@Test
	void 문자열로_바꿔도_키가_나오지_않는다() {
		assertThat(codes.toString()).doesNotContain(KEY_1);
	}

	// 첫 글자를 바꾼다. 마지막 글자는 낮은 비트가 쓰이지 않아(32바이트의 Base64는 43자) 바꿔도 같은 바이트일 수 있다.
	private static String flip(String part) {
		char first = part.charAt(0);
		return (first == 'A' ? 'B' : 'A') + part.substring(1);
	}

}
