package com.jey.core.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.data.redis.serializer.JdkSerializationRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.SerializationException;

/**
 * 세션 값을 Java 직렬화로 저장하되, 읽지 못하는 값은 없는 것으로 취급한다.
 *
 * <p>세션에 넣는 타입이 배포로 바뀌면(클래스 이름, 필드 타입, 역할 이름) 예전 세션을 읽다가 예외가 난다.
 * 그대로 두면 그 쿠키를 가진 사용자는 모든 요청이 500이 되고, 로그인 요청도 같은 세션을 먼저 읽기 때문에 다시 로그인할 수도 없다.
 * 값을 없는 것으로 돌려주면 로그인하지 않은 요청으로 처리돼 401을 받고, 다시 로그인하면 된다.
 */
final class TolerantSessionSerializer implements RedisSerializer<Object> {

	private static final Logger log = LoggerFactory.getLogger(TolerantSessionSerializer.class);

	private final JdkSerializationRedisSerializer delegate;

	TolerantSessionSerializer(ClassLoader classLoader) {
		this.delegate = new JdkSerializationRedisSerializer(classLoader);
	}

	@Override
	public byte[] serialize(Object value) {
		return delegate.serialize(value);
	}

	@Override
	public Object deserialize(byte[] bytes) {
		try {
			return delegate.deserialize(bytes);
		}
		catch (SerializationException ex) {
			// 세션 내용은 남기지 않고 원인만 남긴다.
			log.warn("세션 값을 읽지 못해 없는 것으로 취급한다: {}", String.valueOf(NestedExceptionUtils.getMostSpecificCause(ex)));
			return null;
		}
	}

}
