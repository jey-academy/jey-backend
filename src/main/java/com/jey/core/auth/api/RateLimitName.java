package com.jey.core.auth.api;

/**
 * 계정별 횟수 한도의 이름. 무엇의 한도인지를 가린다(ADR-0026).
 *
 * <p>모듈마다 enum으로 선언한다(예: {@code MemberRateLimit}). 이름은 {@code {모듈}.{대상}} 꼴의 소문자이고
 * 선언한 모듈의 이름으로 시작한다. 전체에서 겹치면 안 된다: 이름이 횟수를 세는 키에 들어가므로, 두 한도가 같은 이름을 쓰면
 * 서로의 횟수를 같이 센다. 형식, 모듈 이름, 겹침은 {@code RateLimitRulesTest}가 선언된 것을 모두 모아 검사한다.
 *
 * <p>한 번 쓴 이름은 바꾸지 않는다. 바꾸면 그때까지 센 횟수를 찾지 못해 처음부터 다시 센다(막혀 있던 계정이 풀린다).
 */
public interface RateLimitName {

	/** 예: {@code member.phone-reveal} */
	String code();

}
