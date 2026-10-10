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
			Throwable cause = NestedExceptionUtils.getMostSpecificCause(ex);
			// 메모리 부족이나 클래스 누락 같은 JVM 오류는 세션의 문제가 아니라 서버의 문제다.
			// 이것까지 "로그인 안 한 것"으로 바꾸면 배포가 깨졌는데 전원이 로그아웃된 것처럼만 보인다.
			if (cause instanceof Error) {
				throw ex;
			}
			// 세션 내용은 남기지 않고 원인만 남긴다. 이런 세션은 SessionMaxLifetimeFilter가 곧 끊으므로 로그가 반복되지 않는다.
			log.warn("세션 값을 읽지 못해 없는 것으로 취급한다: {}", String.valueOf(cause));
			return null;
		}
	}

}
