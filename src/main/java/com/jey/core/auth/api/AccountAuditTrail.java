package com.jey.core.auth.api;

import com.jey.core.shared.BusinessException;

/**
 * 계정에 대해 한 일과 거부된 시도를 받아 적는 쪽. 계정을 다루는 코드가 부르고, 감사 로그를 아는 쪽이 구현한다.
 *
 * <p>인증 패키지는 감사 로그를 직접 부르지 않는다. 감사 로그가 인증 패키지의 접근 규칙과 로그인 정보를 쓰고 있어서,
 * 반대 방향으로도 부르면 두 패키지가 서로를 물게 된다. 그래서 필요한 것을 여기에 인터페이스로 적어 두고 방향을 뒤집는다.
 */
public interface AccountAuditTrail {

	/**
	 * 한 일을 적는다. 그 일의 트랜잭션 <b>안에서</b> 불린다. 같이 커밋되고 같이 되돌려진다. 적지 못하면 예외를 던져 그 일도 되돌린다.
	 *
	 * @throws IllegalArgumentException 거부된 시도({@link AccountActivity.Kind#denied()})를 넘겼을 때
	 */
	void done(AccountActivity activity);

	/**
	 * 거부된 시도를 적는다. 그 요청의 트랜잭션이 <b>끝난 뒤에</b> 불린다.
	 * 적지 못하면 원래의 거부 대신 그 실패를 던진다(기록 없이 "거부됐다"는 응답을 내보내지 않는다).
	 *
	 * @param denial 원래의 거부. 적지 못했을 때 그 실패에 붙여 로그에 같이 남긴다
	 * @throws IllegalArgumentException 한 일을 넘겼을 때
	 */
	void denied(AccountActivity activity, BusinessException denial);

}
