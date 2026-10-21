package com.jey.billing.infrastructure;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
import com.jey.billing.application.LedgerSheets;
import com.jey.billing.application.LedgerSheetsRejected;
import com.jey.billing.application.LedgerSheetsUnavailable;
import com.jey.billing.domain.LedgerSyncReason;

/**
 * 구글 시트 값 API(v4)로 장부를 읽고 쓴다. 구글 클라이언트 라이브러리 없이 REST를 직접 부른다(ADR-0044).
 *
 * <ul>
 * <li>읽기: {@code GET values/'탭'!A:AZ} — 보이는 글자 그대로({@code FORMATTED_VALUE}), 줄 단위.
 * <li>쓰기: {@code POST values:batchUpdate} — 칸마다 범위 하나, {@code RAW}(입력한 그대로).
 * <li>장애: 시간 초과·연결 끊김·408·5xx·429 → {@link LedgerSheetsUnavailable}. 그 밖의 거절 → {@link LedgerSheetsRejected}.
 * </ul>
 *
 * <p>응답 본문에는 장부의 값(이름·번호)이 있을 수 있어 예외 문구와 로그에 싣지 않는다. 상태 코드만 싣는다.
 */
public class GoogleSheetsLedgerSheets implements LedgerSheets {

	private static final Logger log = LoggerFactory.getLogger(GoogleSheetsLedgerSheets.class);

	/** 읽는 열의 범위. 장부는 Q열 안팎이다. 줄은 끝까지 읽는다. */
	private static final String READ_COLUMNS = "A:AZ";

	/** 400 가운데 범위(탭 이름)를 읽지 못한 것. 탭이 없을 때 구글이 이렇게 답한다. */
	private static final String UNPARSABLE_RANGE = "Unable to parse range";

	private final String baseUrl;

	private final RestClient client;

	private final GoogleAccessTokens tokens;

	GoogleSheetsLedgerSheets(String baseUrl, Duration connectTimeout, Duration readTimeout, GoogleAccessTokens tokens) {
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
			throw new LedgerSheetsUnavailable("구글 시트에 닿지 못했다", null, e);
		} catch (RestClientException e) {
			// 응답 머리글은 받았는데 본문을 꺼내다 끊긴 경우(운영체제에 따라 이 모양으로 온다). 원인에 입출력 오류가 있으면 장애다.
			// 그 밖(응답 모양이 기대와 다름)은 다시 해도 낫지 않으므로 그대로 던진다(앱 오류).
			if (hasIoCause(e)) {
				throw new LedgerSheetsUnavailable("구글 시트의 응답을 끝까지 읽지 못했다", null, e);
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
			return new LedgerSheetsUnavailable(message, retryAfter(e.getResponseHeaders()), null);
		}
		if (status == 408 || status >= 500) {
			return new LedgerSheetsUnavailable(message, null, null);
		}
		LedgerSyncReason reason = switch (status) {
			case 400 -> e.getResponseBodyAsString().contains(UNPARSABLE_RANGE) ? LedgerSyncReason.TAB_NOT_FOUND
					: LedgerSyncReason.GOOGLE_REJECTED;
			case 401, 403 -> LedgerSyncReason.ACCESS_DENIED;
			case 404 -> LedgerSyncReason.NOT_CONFIGURED;
			default -> LedgerSyncReason.GOOGLE_REJECTED;
		};
		if (reason == LedgerSyncReason.GOOGLE_REJECTED) {
			log.warn("구글 시트가 요청을 거절했다: {}", status);
		}
		return new LedgerSheetsRejected(reason, message);
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

}
