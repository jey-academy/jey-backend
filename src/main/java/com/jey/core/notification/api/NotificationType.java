package com.jey.core.notification.api;

/**
 * 알림의 종류(패스 발급 안내, 수납 완료 안내 등). 모듈마다 enum으로 구현한다({@code AuditAction}, {@code ErrorCode}와 같은 방식).
 *
 * <p>문구는 여기에 없다. 실제로 보내는 쪽이 이 이름으로 문구(템플릿)를 고른다.
 */
public interface NotificationType {

	/**
	 * {@code {모듈}.{사건}} 형식의 소문자 kebab-case(예: {@code dongtanpass.pass-issued}). 도메인 이벤트의 이름과 같은 형식이다.
	 * 보내는 쪽의 문구와 이어지고 같은 알림인지 가리는 데 쓰이므로 한 번 쓴 값은 바꾸지 않는다.
	 */
	String code();

}
