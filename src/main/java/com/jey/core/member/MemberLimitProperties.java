package com.jey.core.member;

import java.time.Duration;

import com.jey.core.auth.api.RateLimit;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 학생·학부모 기능의 계정별 횟수 한도({@code jey.member.*}, ADR-0026). 값이 없거나 잘못되면 서버가 뜨지 않는다.
 *
 * @param phoneRevealLimit 전체 전화번호 보기
 * @param phoneChangeLimit 전화번호가 실린 변경 요청(학생 등록, 학부모 추가, 번호를 실은 학부모 수정). 성공해도 센다.
 * 번호를 바꿔 가며 넣는 대입에서 틀린 번호는 거부가 아니라 성공으로 끝나기 때문이다
 * @param phoneProbeLimit "그 번호가 이미 있다"가 드러나는 거부 응답(번호 중복, 이미 연결됨, 같은 이름의 학생).
 * 넘으면 번호가 실린 변경 요청이 막힌다
 */
@ConfigurationProperties("jey.member")
record MemberLimitProperties(Limit phoneRevealLimit, Limit phoneChangeLimit, Limit phoneProbeLimit) {

	private static final String PREFIX = "jey.member.";

	// 단위를 빼먹은 "15"는 15밀리초로 읽힌다. 그런 값으로 뜨면 제한이 사실상 꺼진다.
	private static final Duration MIN_WINDOW = Duration.ofMinutes(1);

	MemberLimitProperties {
		check("phone-reveal-limit", phoneRevealLimit);
		check("phone-change-limit", phoneChangeLimit);
		check("phone-probe-limit", phoneProbeLimit);
	}

	RateLimit phoneReveal() {
		return RateLimit.of(MemberRateLimit.PHONE_REVEAL, phoneRevealLimit.max(), phoneRevealLimit.window());
	}

	RateLimit phoneChange() {
		return RateLimit.of(MemberRateLimit.PHONE_CHANGE, phoneChangeLimit.max(), phoneChangeLimit.window());
	}

	RateLimit phoneProbe() {
		return RateLimit.of(MemberRateLimit.PHONE_PROBE, phoneProbeLimit.max(), phoneProbeLimit.window());
	}

	private static void check(String name, Limit limit) {
		if (limit == null) {
			throw new IllegalArgumentException(PREFIX + name + "가 없다");
		}
		if (limit.window() == null || limit.window().compareTo(MIN_WINDOW) < 0) {
			throw new IllegalArgumentException(
					PREFIX + name + ".window는 1분 이상이어야 한다(단위를 붙였는지 확인): " + limit.window());
		}
		if (limit.max() < 1) {
			throw new IllegalArgumentException(PREFIX + name + ".max는 1 이상이어야 한다: " + limit.max());
		}
	}

	/**
	 * @param window 세는 구간. 처음 센 때부터 이 시간이 지나면 횟수가 지워진다
	 * @param max 구간 안에서 할 수 있는 횟수
	 */
	record Limit(Duration window, int max) {
	}

}
