package com.jey.core.notification.api;

/**
 * 알림의 종류(패스 발급 안내, 수납 완료 안내 등). 모듈마다 enum으로 구현한다({@code AuditAction}, {@code ErrorCode}와 같은 방식).
 * enum이 아닌 구현(람다, 그때그때 만든 객체)으로는 요청을 만들 수 없다.
 *
 * <p>문구는 여기에 없다. 실제로 보내는 쪽이 이 이름으로 문구(템플릿)를 고른다.
 */
public interface NotificationType {

	/**
	 * {@code {모듈}.{사건}} 형식의 소문자 kebab-case, 80자 이하(예: {@code dongtanpass.pass-issued}). 도메인 이벤트의 이름과 같은 형식이다.
	 * 알림을 일으킨 이벤트의 이름과 같아도 된다(감사 로그의 행위 이름과 달리 이벤트 이름과 한곳에 저장되지 않는다).
	 * 알림 종류끼리는 전체에서 겹치지 않아야 한다.
	 * 보내는 쪽의 문구와 이어지고 같은 알림인지 가리는 데 쓰이므로 한 번 쓴 값은 바꾸지 않는다.
	 */
	String code();

}
