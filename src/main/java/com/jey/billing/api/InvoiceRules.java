package com.jey.billing.api;

import java.time.YearMonth;

/**
 * 청구 입력의 한도. 수납 모듈의 계산·저장과, 청구를 만드는 요청(수납과 다른 모듈의 것)이 이 값을 함께 가리킨다.
 * 요청이 따로 숫자를 적어 두면 한쪽만 바뀌었을 때 사용자 입력이 400이 아니라 500이 된다.
 */
public final class InvoiceRules {

	/** 청구 하나의 반(라인) 수. 반 나눔이면 둘 이상. */
	public static final int MAX_LINES = 4;

	public static final int MIN_LESSONS = 1;

	public static final int MAX_LESSONS = 31;

	/** 라인 하나의 할인 수(근거가 있는 할인과 직원이 고른 할인을 합친 것). */
	public static final int MAX_DISCOUNTS = 4;

	/** 패스 사용 기록에서 만들 때 직원이 더 고를 수 있는 할인 수(패스 할인이 한 칸을 쓴다). */
	public static final int MAX_EXTRA_DISCOUNTS_WITH_PASS = MAX_DISCOUNTS - 1;

	/** 할인율의 소수 자릿수(할인 유형 테이블과 같다). 5%는 0.05, 7.25%는 0.0725. */
	public static final int MAX_RATE_SCALE = 4;

	/** 할인 유형 코드의 길이. */
	public static final int MAX_DISCOUNT_CODE_LENGTH = 30;

	public static final YearMonth MIN_MONTH = YearMonth.of(2000, 1);

	public static final YearMonth MAX_MONTH = YearMonth.of(2099, 12);

	private InvoiceRules() {
	}

	/** 대상 월이 2000-01~2099-12인지. */
	public static boolean acceptsMonth(YearMonth month) {
		return month != null && !month.isBefore(MIN_MONTH) && !month.isAfter(MAX_MONTH);
	}

}
