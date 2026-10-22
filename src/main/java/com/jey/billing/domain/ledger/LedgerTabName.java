package com.jey.billing.domain.ledger;

import java.time.YearMonth;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 장부 탭 이름의 틀(예: {@code {month}월 {campus}({yy})} → {@code 11월 동탄관(26)}).
 *
 * <p>자리표시: {@code {month}} 앞에 0 없는 월, {@code {month2}} 두 자리 월, {@code {year}} 네 자리 연도, {@code {yy}} 연도 두 자리,
 * {@code {campus}} 앱의 지점 이름. 월과 연도는 꼭 있어야 한다(다른 달·다른 해의 탭과 이름이 겹치지 않게). 그 밖의 자리표시는 오타로 본다.
 */
public final class LedgerTabName {

	private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^{}]*)}");

	private static final Map<String, Part> PARTS = Map.of("month", Part.MONTH, "month2", Part.MONTH, "year", Part.YEAR,
			"yy", Part.YEAR, "campus", Part.CAMPUS);

	private enum Part {
		MONTH, YEAR, CAMPUS
	}

	private final String pattern;

	private final boolean includesCampus;

	private LedgerTabName(String pattern, boolean includesCampus) {
		this.pattern = pattern;
		this.includesCampus = includesCampus;
	}

	/**
	 * @throws IllegalArgumentException 틀이 비었거나, 월·연도가 없거나, 모르는 자리표시가 있을 때
	 */
	public static LedgerTabName of(String pattern) {
		if (pattern == null || pattern.isBlank()) {
			throw new IllegalArgumentException("장부 탭 이름의 틀이 비었다");
		}
		boolean month = false;
		boolean year = false;
		boolean campus = false;
		Matcher matcher = PLACEHOLDER.matcher(pattern);
		while (matcher.find()) {
			Part part = PARTS.get(matcher.group(1));
			if (part == null) {
				throw new IllegalArgumentException("장부 탭 이름의 틀에 모르는 자리표시가 있다: {" + matcher.group(1) + "}");
			}
			switch (part) {
				case MONTH -> month = true;
				case YEAR -> year = true;
				case CAMPUS -> campus = true;
			}
		}
		if (!month || !year) {
			throw new IllegalArgumentException("장부 탭 이름의 틀에는 월({month} 또는 {month2})과 연도({year} 또는 {yy})가 있어야 한다");
		}
		return new LedgerTabName(pattern, campus);
	}

	/** 그 달, 그 지점의 탭 이름. */
	public String format(YearMonth month, String campusName) {
		Map<String, String> values = Map.of("month", String.valueOf(month.getMonthValue()), "month2",
				"%02d".formatted(month.getMonthValue()), "year", String.valueOf(month.getYear()), "yy",
				"%02d".formatted(month.getYear() % 100), "campus", campusName);
		return PLACEHOLDER.matcher(pattern).replaceAll(m -> Matcher.quoteReplacement(values.get(m.group(1))));
	}

	/** 지점 이름이 탭 이름에 들어가는지. 두 지점이 한 파일을 쓰면 들어가야 한다. */
	public boolean includesCampus() {
		return includesCampus;
	}

	@Override
	public String toString() {
		return pattern;
	}

}
