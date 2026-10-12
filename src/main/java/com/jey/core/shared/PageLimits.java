package com.jey.core.shared;

/**
 * 목록 조회가 받는 페이지 번호의 한계. 목록을 내주는 컨트롤러가 요청 검증에 쓴다.
 */
public final class PageLimits {

	/**
	 * 받는 가장 큰 페이지 번호(0부터 센다). 넘으면 조회하지 않고 거부한다.
	 *
	 * <p>아주 큰 값은 건너뛸 줄 수(페이지 × 크기)가 정수 범위를 넘어 조회가 서버 오류로 끝난다.
	 * 화면이 이만큼 깊은 페이지를 볼 일은 없다. 그렇게 뒤쪽을 봐야 하면 조건으로 좁혀서 찾는다.
	 */
	public static final int MAX_PAGE = 10_000;

	private PageLimits() {
	}

}
