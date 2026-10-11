package com.jey.core.auth.api;

import com.jey.core.shared.Actor;

/**
 * 지금 이 코드를 실행시킨 주체. 서비스 깊은 곳에서 "누가 했는지"를 남길 때 쓴다(감사 로그, 이벤트의 행위자, 엔티티의 작성자).
 * 컨트롤러는 이것 대신 {@code @AuthenticationPrincipal AuthenticatedUser}로 받는다.
 *
 * <p>로그인 없이 도는 코드(배치, 이벤트 리스너)와 로그인하지 않은 요청에서는 {@link Actor#none()}이다.
 * 로그인은 했는데 누구인지 알 수 없으면 {@code none}으로 넘기지 않고 예외를 던진다.
 * 그런 기록은 "시스템이 한 일"과 구분되지 않기 때문이다.
 */
public interface CurrentActor {

	/**
	 * @throws IllegalStateException 로그인한 요청인데 계정을 알 수 없을 때
	 */
	Actor get();

}
