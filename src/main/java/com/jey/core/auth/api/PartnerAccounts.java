package com.jey.core.auth.api;

/**
 * 연계 학원 계정을 만들고 한꺼번에 차단하는 창구 (ADR-0034). 제이G패스 모듈이 부른다.
 *
 * <p>연계 학원은 패스 모듈에 있다. 그 학원이 있고 켜져 있는지는 부르는 쪽이 확인한다(이 모듈은 다른 모듈을 모른다).
 * 만든 뒤의 관리(이름, 차단과 풀기, 비밀번호 다시 정하기)는 계정 관리 API가 한다. 소속은 바뀌지 않는다.
 *
 * <p>계정 관리의 다른 메서드처럼 트랜잭션 밖에서 부른다(고치고 커밋한 뒤에 세션을 끊는다).
 */
public interface PartnerAccounts {

	/**
	 * 연계 학원 계정을 만든다. 아이디, 비밀번호 규칙, 아이디 중복의 거부 기록은 직원 계정과 같다.
	 *
	 * @throws com.jey.core.shared.BusinessException 비밀번호가 규칙에 맞지 않거나, 이미 쓰는 아이디일 때
	 * @throws IllegalArgumentException 아이디나 이름의 형식이 틀렸을 때
	 * @throws IllegalStateException 트랜잭션 안에서 불렀을 때
	 */
	PartnerAccount create(NewPartnerAccount account);

	/**
	 * 그 연계 학원의 활성 계정을 모두 차단한다. 계정마다 세션도 끊는다. 이미 차단된 계정은 건너뛴다.
	 * 중간에 실패하면 예외를 그대로 던진다. 이미 차단한 계정은 차단된 채로 남고, 다시 부르면 남은 계정만 차단한다.
	 *
	 * @return 이번에 차단한 계정 수
	 * @throws IllegalStateException 트랜잭션 안에서 불렀을 때
	 */
	int disableAll(long partnerId);

}
