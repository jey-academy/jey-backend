package com.jey.core.web;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

// 필터가 본문을 이미 읽었다. 뒤에서는 스트림으로 읽든 글자로 읽든 같은 본문을 받아야 한다.
class CachedBodyRequestTest {

	private static final String BODY = "{\"name\":\"김철수\"}";

	@Test
	void 스트림으로_여러_번_읽어도_같은_본문이다() throws IOException {
		CachedBodyRequest request = wrap(BODY.getBytes(StandardCharsets.UTF_8), null);

		assertThat(request.getInputStream().readAllBytes()).isEqualTo(BODY.getBytes(StandardCharsets.UTF_8));
		assertThat(request.getInputStream().readAllBytes()).isEqualTo(BODY.getBytes(StandardCharsets.UTF_8));
		assertThat(request.getInputStream().isFinished()).isFalse();
		assertThat(request.getContentLength()).isEqualTo(BODY.getBytes(StandardCharsets.UTF_8).length);
		assertThat(request.getContentLengthLong()).isEqualTo(BODY.getBytes(StandardCharsets.UTF_8).length);
	}

	// 원래 요청의 본문은 이미 비었다. 원래 요청의 getReader로 넘기면 빈 본문을 읽는다.
	@Test
	void 글자로_읽을_때도_읽어_둔_본문을_준다() throws IOException {
		CachedBodyRequest request = wrap(BODY.getBytes(StandardCharsets.UTF_8), null);

		assertThat(request.getReader().readLine()).isEqualTo(BODY);
	}

	@Test
	void 글자로_읽을_때는_요청이_알려_준_문자_집합을_쓴다() throws IOException {
		Charset eucKr = Charset.forName("EUC-KR");
		CachedBodyRequest request = wrap(BODY.getBytes(eucKr), "EUC-KR");

		assertThat(request.getReader().readLine()).isEqualTo(BODY);
	}

	// 본문을 다 읽은(비어 있는) 원래 요청을 감싼다.
	private static CachedBodyRequest wrap(byte[] body, String encoding) {
		MockHttpServletRequest consumed = new MockHttpServletRequest("POST", "/api/v1/things");
		consumed.setCharacterEncoding(encoding);
		return new CachedBodyRequest(consumed, body);
	}

}
