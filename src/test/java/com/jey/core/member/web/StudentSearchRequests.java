package com.jey.core.member.web;

import java.util.StringJoiner;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static com.jey.TestCsrf.csrfToken;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 테스트에서 학생 검색 요청을 만든다. 조건은 주소가 아니라 본문에 싣는다 (ADR-0032).
 */
final class StudentSearchRequests {

	static final String SEARCH = "/api/v1/students/search";

	private StudentSearchRequests() {
	}

	/**
	 * @param conditions 이름과 값을 번갈아 적는다. 예: {@code studentSearch(mockMvc, "name", "김", "page", 1)}.
	 * {@code name}과 {@code phoneLast4}는 문자열로, 그 밖의 값은 적은 그대로(숫자) 본문에 들어간다
	 */
	static MockHttpServletRequestBuilder studentSearch(MockMvc mockMvc, Object... conditions) throws Exception {
		return post(SEARCH).with(csrfToken(mockMvc)).contentType(MediaType.APPLICATION_JSON).content(json(conditions));
	}

	static String json(Object... conditions) {
		if (conditions.length % 2 != 0) {
			throw new IllegalArgumentException("조건은 이름과 값의 쌍으로 적는다");
		}
		StringJoiner body = new StringJoiner(", ", "{", "}");
		for (int i = 0; i < conditions.length; i += 2) {
			String name = (String) conditions[i];
			Object value = conditions[i + 1];
			boolean text = name.equals("name") || name.equals("phoneLast4");
			body.add("\"" + name + "\": " + ((text && value != null) ? quoted(value.toString()) : String.valueOf(value)));
		}
		return body.toString();
	}

	private static String quoted(String value) {
		return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
	}

}
