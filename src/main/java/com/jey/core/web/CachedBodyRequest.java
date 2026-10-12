package com.jey.core.web;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

/**
 * 본문을 이미 읽어 둔 요청. 요청의 본문은 한 번만 읽을 수 있어서, 앞에서 읽은 것을 뒤의 컨트롤러가 다시 읽을 수 있게 한다.
 */
class CachedBodyRequest extends HttpServletRequestWrapper {

	private final byte[] body;

	CachedBodyRequest(HttpServletRequest request, byte[] body) {
		super(request);
		this.body = body;
	}

	@Override
	public ServletInputStream getInputStream() {
		ByteArrayInputStream source = new ByteArrayInputStream(body);
		return new ServletInputStream() {
			@Override
			public int read() {
				return source.read();
			}

			@Override
			public int read(byte[] buffer, int offset, int length) {
				return source.read(buffer, offset, length);
			}

			@Override
			public boolean isFinished() {
				return source.available() == 0;
			}

			@Override
			public boolean isReady() {
				return true;
			}

			@Override
			public void setReadListener(ReadListener listener) {
				throw new UnsupportedOperationException("이미 읽어 둔 본문이라 비동기로 읽지 않는다");
			}
		};
	}

	@Override
	public BufferedReader getReader() {
		String encoding = getCharacterEncoding();
		Charset charset = (encoding != null) ? Charset.forName(encoding) : StandardCharsets.UTF_8;
		return new BufferedReader(new InputStreamReader(getInputStream(), charset));
	}

	@Override
	public int getContentLength() {
		return body.length;
	}

	@Override
	public long getContentLengthLong() {
		return body.length;
	}

}
