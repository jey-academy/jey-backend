package com.jey.core.auth.api;

import java.util.Objects;
import java.util.function.Consumer;

import com.jey.core.shared.ErrorCode;
import com.jey.core.shared.RateLimitedException;

/**
 * 로그인 없는 경로에서 틀린 시도를 세서 대입을 막는다. 계정이 없으므로 "대상 + 접속 주소"와 "접속 주소" 두 기준으로 센다.
 *
 * <p>"대상 + 접속 주소"는 한 곳에서 한 대상(예: 학부모 번호)을 계속 찌르는 시도를, "접속 주소"는 한 곳에서 대상을 바꿔 가며
 * 찌르는 시도를 막는다. 대상만으로 세지 않는 것은 밖에서 일부러 틀려 그 대상의 주인을 막지 못하게 하기 위해서다.
 * 학원 와이파이처럼 여러 사람이 주소 하나를 함께 쓰므로 "접속 주소"의 한도는 넉넉하게 둔다.
 *
 * <p>확인하기 <b>전에</b> {@link #acquire}로 센다. 맞은 요청이면 받아 둔 {@link Attempt}로 {@link #refund}한다.
 * 고정 구간이다: 처음 센 때부터 구간이 지나면 그 기준의 횟수가 지워진다. 막힌 요청은 세지 않는다.
 *
 * <p>횟수를 세지 못하면(저장소에 닿지 못함) 예외를 던진다. 세지 못한 채로 통과시키지 않는다.
 */
public interface ClientAttemptLimiter {

	/**
	 * 한 번 센다. 두 기준 중 하나라도 한도에 닿아 있으면 어느 쪽도 세지 않고 {@link RateLimitedException}을 던진다.
	 * 둘 다 닿아 있으면 남은 시간이 긴 쪽으로 답한다.
	 *
	 * <p>{@code onFirstBlock}은 막은 기준마다 한 구간에서 처음 막힌 요청에서만 부른다
	 * ({@link AccountRateLimiter#acquire}와 같다. 실패하면 그 실패를 던지고 다음에 막힌 요청에서 다시 부른다).
	 *
	 * @param perSubjectAndClient "대상 + 접속 주소"의 한도
	 * @param perClient "접속 주소"의 한도. 이름이 {@code perSubjectAndClient}와 달라야 한다
	 * @param subject 무엇을 찌르는지. 원문이 아니라 대조값(HMAC 색인 등)을 넘긴다. 장치는 해시를 줄여 키에 넣는다
	 * @param clientAddress 접속 주소. IPv6는 앞 64비트 대역으로 센다
	 * @param limitedCode 막혔을 때 던질 예외의 에러 코드. 상태가 429여야 한다
	 * @return 센 한 번. 되돌릴 때 쓴다
	 * @throws RateLimitedException 한도에 닿아 막혔을 때
	 * @throws IllegalArgumentException 에러 코드의 상태가 429가 아니거나, 두 한도의 이름이 같거나, 대상이 비었을 때. 세지 않는다
	 */
	Attempt acquire(RateLimit perSubjectAndClient, RateLimit perClient, String subject, String clientAddress,
			ErrorCode limitedCode, Consumer<RateLimitedException> onFirstBlock);

	/**
	 * 센 한 번을 되돌린다. 기준마다, 그 한 번을 센 구간이 이미 끝났으면 그 기준은 건드리지 않는다.
	 * 구간이 바뀐 뒤에 되돌리면 새 구간에서 센 것이 지워지기 때문이다.
	 */
	void refund(Attempt attempt);

	/**
	 * {@link #acquire}가 센 한 번.
	 *
	 * @param subjectAndClientKey "대상 + 접속 주소" 기준의 키
	 * @param subjectAndClientWindow 그 기준에서 센 구간의 표식
	 * @param clientKey "접속 주소" 기준의 키
	 * @param clientWindow 그 기준에서 센 구간의 표식
	 */
	record Attempt(String subjectAndClientKey, String subjectAndClientWindow, String clientKey, String clientWindow) {

		public Attempt {
			Objects.requireNonNull(subjectAndClientKey, "subjectAndClientKey");
			Objects.requireNonNull(subjectAndClientWindow, "subjectAndClientWindow");
			Objects.requireNonNull(clientKey, "clientKey");
			Objects.requireNonNull(clientWindow, "clientWindow");
		}

	}

}
