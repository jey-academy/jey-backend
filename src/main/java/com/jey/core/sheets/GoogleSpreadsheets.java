package com.jey.core.sheets;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.jey.core.sheets.api.SheetsRejected;
import com.jey.core.sheets.api.SheetsRejection;
import com.jey.core.sheets.api.SheetsUnavailable;
import com.jey.core.sheets.api.Spreadsheets;

/**
 * 구글 시트 값 API(v4)로 읽고 쓴다. 구글 클라이언트 라이브러리 없이 REST를 직접 부른다(ADR-0044, ADR-0047).
 *
 * <ul>
 * <li>읽기: {@code GET values/'탭'!A:AZ} — 보이는 글자 그대로({@code FORMATTED_VALUE}), 줄 단위.
 * <li>칸 쓰기: {@code POST values:batchUpdate} — 칸마다 범위 하나, {@code RAW}(입력한 그대로).
 * <li>줄 붙이기: {@code POST values/'탭'!A:끝열:append} — {@code RAW}, {@code INSERT_ROWS}(표 끝에 줄을 끼워 넣는다).
 * <li>장애: 시간 초과·연결 끊김·408·5xx·429 → {@link SheetsUnavailable}. 그 밖의 거절 → {@link SheetsRejected}.
 * </ul>
 *
 * <p>응답 본문에는 시트의 값(이름·번호)이 있을 수 있어 예외 문구와 로그에 싣지 않는다. 상태 코드만 싣는다.
 */
class GoogleSpreadsheets implements Spreadsheets {

	private static final Logger log = LoggerFactory.getLogger(GoogleSpreadsheets.class);

	/** 읽는 열의 범위. 장부는 Q열 안팎이다. 줄은 끝까지 읽는다. */
	private static final String READ_COLUMNS = "A:AZ";

	/** 붙일 수 있는 칸 수(A~Z). 읽는 범위 안에 있어야 붙인 줄을 다시 읽을 수 있다. */
	private static final int MAX_APPEND_CELLS = 26;

	/** 붙인 범위({@code '제이G패스'!A7:J7})의 첫 줄 번호. 탭 이름에 느낌표가 있을 수 있어 마지막 느낌표 뒤를 본다. */
	private static final Pattern UPDATED_ROW = Pattern.compile("![A-Z]+(\\d+)(?::[A-Z]+\\d+)?$");

	/** 400 가운데 범위(탭 이름)를 읽지 못한 것. 탭이 없을 때 구글이 이렇게 답한다. */
	private static final String UNPARSABLE_RANGE = "Unable to parse range";

	private final String baseUrl;

	private final RestClient client;

	private final GoogleAccessTokens tokens;

	GoogleSpreadsheets(String baseUrl, Duration connectTimeout, Duration readTimeout, GoogleAccessTokens tokens) {
		HttpClient http = HttpClient.newBuilder()
			.connectTimeout(connectTimeout)
			.version(HttpClient.Version.HTTP_1_1)
			.build();
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(http);
		requestFactory.setReadTimeout(readTimeout);
		this.baseUrl = baseUrl;
		this.client = RestClient.builder().requestFactory(requestFactory).build();
		this.tokens = tokens;
	}

	@Override
	public boolean enabled() {
		return tokens.enabled();
	}

	@Override
	public List<List<String>> read(String spreadsheetId, String tab) {
		URI uri = UriComponentsBuilder.fromUriString(baseUrl)
			.path("/v4/spreadsheets/{id}/values/{range}")
			.queryParam("valueRenderOption", "FORMATTED_VALUE")
			.queryParam("majorDimension", "ROWS")
			.buildAndExpand(spreadsheetId, range(tab, READ_COLUMNS))
			.encode()
			.toUri();
		ValueRange body = call(() -> client.get()
			.uri(uri)
			.header(HttpHeaders.AUTHORIZATION, tokens.authorization())
			.retrieve()
			.body(ValueRange.class));
		if (body == null || body.values() == null) {
			return List.of();
		}
		List<List<String>> values = new ArrayList<>();
		for (List<String> row : body.values()) {
			values.add(row == null ? List.of() : row.stream().map(cell -> cell == null ? "" : cell).toList());
		}
		return values;
	}

	@Override
	public void write(String spreadsheetId, String tab, Map<String, Object> cells) {
		URI uri = UriComponentsBuilder.fromUriString(baseUrl)
			.path("/v4/spreadsheets/{id}/values:batchUpdate")
			.buildAndExpand(spreadsheetId)
			.encode()
			.toUri();
		List<CellWrite> data = new ArrayList<>();
		cells.forEach((cell, value) -> data.add(new CellWrite(range(tab, cell), List.of(List.of(value)))));
		call(() -> client.post()
			.uri(uri)
			.header(HttpHeaders.AUTHORIZATION, tokens.authorization())
			.contentType(MediaType.APPLICATION_JSON)
			.body(new BatchUpdate("RAW", data))
			.retrieve()
			.toBodilessEntity());
	}

	@Override
	public int append(String spreadsheetId, String tab, List<Object> row) {
		if (row.isEmpty() || row.size() > MAX_APPEND_CELLS) {
			throw new IllegalArgumentException("붙이는 줄은 1~" + MAX_APPEND_CELLS + "칸이다");
		}
		char lastColumn = (char) ('A' + row.size() - 1);
		URI uri = UriComponentsBuilder.fromUriString(baseUrl)
			.path("/v4/spreadsheets/{id}/values/{range}:append")
			.queryParam("valueInputOption", "RAW")
			.queryParam("insertDataOption", "INSERT_ROWS")
			.buildAndExpand(spreadsheetId, range(tab, "A:" + lastColumn))
			.encode()
			.toUri();
		AppendResponse body = call(() -> client.post()
			.uri(uri)
			.header(HttpHeaders.AUTHORIZATION, tokens.authorization())
			.contentType(MediaType.APPLICATION_JSON)
			.body(new AppendRequest(List.of(row)))
			.retrieve()
			.body(AppendResponse.class));
		String updatedRange = body == null || body.updates() == null ? null : body.updates().updatedRange();
		Matcher matcher = updatedRange == null ? null : UPDATED_ROW.matcher(updatedRange);
		if (matcher == null || !matcher.find()) {
			// 붙였는데 어디인지 모른다. 부르는 쪽은 앱 오류로 다루고, 다음 시도가 시트를 읽어 붙인 줄을 찾는다.
			throw new IllegalStateException("구글 시트가 붙인 범위를 알려 주지 않았다");
		}
		return Integer.parseInt(matcher.group(1));
	}

	// 탭 이름은 항상 작은따옴표로 감싼다('2026-11'). 이름 안의 작은따옴표는 두 번 쓴다.
	private static String range(String tab, String cells) {
		return "'" + tab.replace("'", "''") + "'!" + cells;
	}

	// 시험에서 예외의 모양을 직접 넣어 보려고 패키지 안에 연다.
	static <T> T call(java.util.function.Supplier<T> request) {
		try {
			return request.get();
		} catch (RestClientResponseException e) {
			throw translate(e);
		} catch (ResourceAccessException e) {
			throw new SheetsUnavailable("구글 시트에 닿지 못했다", null, e);
		} catch (RestClientException e) {
			// 응답 머리글은 받았는데 본문을 꺼내다 끊긴 경우(운영체제에 따라 이 모양으로 온다). 원인에 입출력 오류가 있으면 장애다.
			// 그 밖(응답 모양이 기대와 다름)은 다시 해도 낫지 않으므로 그대로 던진다(앱 오류).
			if (hasIoCause(e)) {
				throw new SheetsUnavailable("구글 시트의 응답을 끝까지 읽지 못했다", null, e);
			}
			throw e;
		}
	}

	private static boolean hasIoCause(Throwable error) {
		for (Throwable cause = error.getCause(); cause != null; cause = cause.getCause()) {
			if (cause instanceof IOException) {
				return true;
			}
		}
		return false;
	}

	// 응답 예외(e)는 원인으로 잇지 않는다 — 문구에 응답 본문이 실린다.
	private static RuntimeException translate(RestClientResponseException e) {
		int status = e.getStatusCode().value();
		String message = "구글 시트 응답 " + status;
		if (status == 429) {
			return new SheetsUnavailable(message, retryAfter(e.getResponseHeaders()), null);
		}
		if (status == 408 || status >= 500) {
			return new SheetsUnavailable(message, null, null);
		}
		SheetsRejection rejection = switch (status) {
			case 400 -> e.getResponseBodyAsString().contains(UNPARSABLE_RANGE) ? SheetsRejection.TAB_NOT_FOUND
					: SheetsRejection.REJECTED;
			case 401, 403 -> SheetsRejection.ACCESS_DENIED;
			case 404 -> SheetsRejection.SPREADSHEET_NOT_FOUND;
			default -> SheetsRejection.REJECTED;
		};
		if (rejection == SheetsRejection.REJECTED) {
			log.warn("구글 시트가 요청을 거절했다: {}", status);
		}
		return new SheetsRejected(rejection, message);
	}

	// Retry-After는 초(숫자)만 읽는다. 날짜 모양이면 없는 것으로 보고 정한 간격을 따른다.
	private static Duration retryAfter(HttpHeaders headers) {
		String value = headers == null ? null : headers.getFirst(HttpHeaders.RETRY_AFTER);
		if (value == null || !value.strip().matches("\\d{1,6}")) {
			return null;
		}
		return Duration.ofSeconds(Long.parseLong(value.strip()));
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record ValueRange(List<List<String>> values) {
	}

	record BatchUpdate(String valueInputOption, List<CellWrite> data) {
	}

	record CellWrite(String range, List<List<Object>> values) {
	}

	record AppendRequest(List<List<Object>> values) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record AppendResponse(Updates updates) {

		@JsonIgnoreProperties(ignoreUnknown = true)
		record Updates(String updatedRange) {
		}

	}

}
