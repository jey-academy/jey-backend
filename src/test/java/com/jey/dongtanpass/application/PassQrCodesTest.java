package com.jey.dongtanpass.application;

import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static com.jey.dongtanpass.application.QrSigningPropertiesTest.KEY_1;
import static com.jey.dongtanpass.application.QrSigningPropertiesTest.KEY_2;
import static org.assertj.core.api.Assertions.assertThat;

// 학부모 화면의 QR에 넣는 서명 토큰(ADR-0010). 스프링 없이 본다.
@ExtendWith(OutputCaptureExtension.class)
class PassQrCodesTest {

	private static final Instant NOW = Instant.parse("2026-10-16T01:00:00Z");

	private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

	// 운영 코드의 서명 입력 앞부분과 같은 값(테스트에서 토큰을 직접 서명할 때 쓴다).
	private static final String PURPOSE = "dongtanpass.pass|";

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

	// 서명은 맞지만 그 버전의 키가 없다. 다른 키로 대신 확인하지 않는다.
	@Test
	void 모르는_버전을_적고_아는_키로_서명한_토큰도_무효다() {
		String body = ENCODER.encodeToString(body(7L, NOW.plusSeconds(60), 32));

		// 같은 방법으로 버전 1을 적으면 유효하다(직접 서명한 토큰이 맞는 모양인지 확인)
		assertThat(codes.verify(signedToken(KEY_1, "1." + body), NOW)).isPresent();
		assertThat(codes.verify(signedToken(KEY_1, "9." + body), NOW)).isEmpty();
	}

	// 키 회전의 마지막 단계.
	@Test
	void 옛_키를_지우면_그_버전의_토큰은_무효다() {
		PassQrCodes removed = new PassQrCodes(new QrSigningProperties(2, Map.of(2, KEY_2)));

		assertThat(removed.verify(codes.issue(7L, NOW).token(), NOW)).isEmpty();
	}

	// 키를 가진 쪽만 만들 수 있는 입력이지만, 확인은 예외를 던지지 않는다.
	@Test
	void 서명은_맞고_본문_길이가_틀리면_예외_없이_무효다() {
		assertThat(codes.verify(signedToken(KEY_1, "1." + ENCODER.encodeToString(body(7L, NOW.plusSeconds(60), 16))),
				NOW))
			.isEmpty();
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

	// 같은 키를 다른 QR(쿠폰 등)에 쓰더라도 그 토큰이 패스 토큰으로 통과하지 않게, 서명 입력에 용도를 넣는다.
	@Test
	void 용도_없이_같은_키로_서명한_토큰은_무효다() {
		String signed = "1." + ENCODER.encodeToString(body(7L, NOW.plusSeconds(60), 32));

		assertThat(codes.verify(signed + "." + ENCODER.encodeToString(hmac(KEY_1, signed)), NOW)).isEmpty();
	}

	// 확인을 거친 값이라는 뜻이 있으므로 다른 패키지에서 만들 수 없다.
	@Test
	void 확인한_토큰의_내용은_밖에서_만들_수_없다() {
		assertThat(VerifiedQr.class.getDeclaredConstructors())
			.allSatisfy(constructor -> assertThat(Modifier.isPublic(constructor.getModifiers())
					|| Modifier.isProtected(constructor.getModifiers())).isFalse());
	}

	@Test
	void 문자열로_바꿔도_키가_나오지_않는다() {
		assertThat(codes.toString()).doesNotContain(KEY_1);
	}

	// 데스크에는 이유를 가리지 않고 하나로 답하므로, 운영에서 "QR이 안 된다"는 문의를 가리려면 로그에 이유가 있어야 한다.
	@Test
	void 무효인_이유를_토큰_원문_없이_로그에_남긴다(CapturedOutput output) {
		String token = codes.issue(7L, NOW).token();
		String[] parts = token.split("\\.");
		String other = new PassQrCodes(new QrSigningProperties(1, Map.of(1, KEY_2))).issue(7L, NOW).token();

		assertThat(reasonOf(output, null)).isEqualTo("malformed");
		assertThat(reasonOf(output, "1.a")).isEqualTo("malformed");
		assertThat(reasonOf(output, "a.b.c")).isEqualTo("malformed");
		assertThat(reasonOf(output, "1.!!!.@@@")).isEqualTo("malformed");
		assertThat(reasonOf(output, token + "=")).isEqualTo("malformed");
		assertThat(reasonOf(output,
				signedToken(KEY_1, "1." + ENCODER.encodeToString(body(7L, NOW.plusSeconds(60), 16)))))
			.isEqualTo("malformed");
		assertThat(reasonOf(output, "9" + token.substring(1))).isEqualTo("unknown_key_version");
		assertThat(reasonOf(output, parts[0] + "." + parts[1] + "." + flip(parts[2]))).isEqualTo("bad_signature");
		assertThat(reasonOf(output, other)).isEqualTo("bad_signature");
		assertThat(reasonOf(output, token, NOW.plusSeconds(60))).isEqualTo("expired");
		assertThat(output.getAll()).doesNotContain(parts[1]).doesNotContain(parts[2]).doesNotContain(other);
	}

	// 모르는 키 버전은 키 회전이 어긋났다는 신호라 알아채야 한다(키를 너무 일찍 지웠거나, 새 키를 아직 넣지 않은 서버).
	@Test
	void 모르는_키_버전만_경고로_남기고_버전_번호를_적는다(CapturedOutput output) {
		String token = codes.issue(7L, NOW).token();

		codes.verify("9" + token.substring(1), NOW);
		codes.verify(token, NOW.plusSeconds(60));

		assertThat(lineOf(output, "reason=unknown_key_version")).contains("WARN").contains("keyVersion=9");
		assertThat(lineOf(output, "reason=expired")).doesNotContain("WARN");
	}

	@Test
	void 유효한_토큰은_로그를_남기지_않는다(CapturedOutput output) {
		assertThat(codes.verify(codes.issue(7L, NOW).token(), NOW)).isPresent();

		assertThat(output.getAll()).doesNotContain("QR 토큰 확인 실패");
	}

	// 패스 ID, 만료 시각(초), 나머지는 0인 토큰 ID로 채운 본문. 길이를 정할 수 있다.
	private static byte[] body(long passId, Instant expiresAt, int length) {
		ByteBuffer buffer = ByteBuffer.allocate(Math.max(length, 16)).putLong(passId).putLong(expiresAt.getEpochSecond());
		return Arrays.copyOf(buffer.array(), length);
	}

	// 운영 코드와 같은 방식(용도 + 서명할 부분)으로 직접 서명한 토큰.
	private static String signedToken(String base64Key, String signed) {
		return signed + "." + ENCODER.encodeToString(hmac(base64Key, PURPOSE + signed));
	}

	private static byte[] hmac(String base64Key, String signed) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(Base64.getDecoder().decode(base64Key), "HmacSHA256"));
			return mac.doFinal(signed.getBytes(StandardCharsets.US_ASCII));
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private String reasonOf(CapturedOutput output, String token) {
		return reasonOf(output, token, NOW);
	}

	// 확인한 뒤 새로 생긴 로그의 이유. 무효여야 하고 이유는 한 줄이어야 한다.
	private String reasonOf(CapturedOutput output, String token, Instant now) {
		int before = output.getAll().length();
		assertThat(codes.verify(token, now)).isEmpty();
		String added = output.getAll().substring(before);
		Matcher matcher = Pattern.compile("reason=([a-z_]+)").matcher(added);
		assertThat(matcher.find()).as("이유가 로그에 있다: %s", added).isTrue();
		String reason = matcher.group(1);
		assertThat(matcher.find()).as("이유는 한 줄이다: %s", added).isFalse();
		return reason;
	}

	private static String lineOf(CapturedOutput output, String text) {
		return output.getAll().lines().filter(line -> line.contains(text)).findFirst().orElseThrow();
	}

	// 첫 글자를 바꾼다. 마지막 글자는 낮은 비트가 쓰이지 않아(32바이트의 Base64는 43자) 바꿔도 같은 바이트일 수 있다.
	private static String flip(String part) {
		char first = part.charAt(0);
		return (first == 'A' ? 'B' : 'A') + part.substring(1);
	}

}
