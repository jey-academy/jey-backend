package com.jey.core.auth;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;

import com.jey.core.auth.api.AuthenticatedUser;
import com.jey.core.auth.api.UserRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.redis.serializer.SerializationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 배포로 세션에 넣는 타입이 바뀌면 예전 세션을 읽지 못한다. 그 경우를 "세션이 없는 것"으로 바꾸는 장치다.
@ExtendWith(OutputCaptureExtension.class)
class TolerantSessionSerializerTest {

	private final TolerantSessionSerializer serializer = new TolerantSessionSerializer(getClass().getClassLoader());

	@AfterEach
	void reset() {
		StrictValue.rejectEverything = false;
	}

	@Test
	void 읽을_수_있는_값은_그대로_되살린다() {
		var user = new AuthenticatedUser(42L, "desk", "김직원", UserRole.STAFF, 1L);

		assertThat(serializer.deserialize(serializer.serialize(user))).isEqualTo(user);
		assertThat(serializer.deserialize(serializer.serialize(1_700_000_000_000L))).isEqualTo(1_700_000_000_000L);
	}

	@Test
	void 자바_객체가_아닌_바이트는_없는_값으로_취급한다(CapturedOutput output) {
		assertThat(serializer.deserialize("not-a-java-object".getBytes(StandardCharsets.UTF_8))).isNull();

		assertThat(output).contains("세션 값을 읽지 못해 없는 것으로 취급한다");
	}

	// 배포 뒤에 클래스가 없어졌거나 이름이 바뀐 경우다.
	@Test
	void 클래스를_찾을_수_없는_값은_없는_값으로_취급한다(CapturedOutput output) {
		byte[] bytes = serializer.serialize(new AuthenticatedUser(42L, "desk", "김직원", UserRole.STAFF, 1L));
		var withoutClass = new TolerantSessionSerializer(new HidingClassLoader(AuthenticatedUser.class.getName()));

		assertThat(withoutClass.deserialize(bytes)).isNull();

		assertThat(output).contains("ClassNotFoundException");
		// 세션에 들어 있던 내용(아이디, 이름)은 로그에 남기지 않는다.
		assertThat(output).doesNotContain("김직원");
	}

	// 레코드는 되살릴 때도 생성자의 검사가 실행된다. 검사에 걸리는 값이면 그 세션은 버려진다.
	@Test
	void 생성자가_거부하는_값은_없는_값으로_취급한다() {
		byte[] bytes = serializer.serialize(new StrictValue("ok"));
		StrictValue.rejectEverything = true;

		assertThat(serializer.deserialize(bytes)).isNull();
	}

	// 메모리 부족이나 클래스 누락 같은 JVM 오류는 세션의 문제가 아니라 서버의 문제다. 삼키지 않는다.
	@Test
	void JVM_오류는_삼키지_않는다() {
		byte[] bytes = serializer.serialize(new AuthenticatedUser(42L, "desk", "김직원", UserRole.STAFF, 1L));
		var broken = new TolerantSessionSerializer(new FailingClassLoader(AuthenticatedUser.class.getName()));

		assertThatThrownBy(() -> broken.deserialize(bytes))
				.isInstanceOf(SerializationException.class)
				.hasRootCauseInstanceOf(NoClassDefFoundError.class);
	}

	record StrictValue(String value) implements Serializable {

		static boolean rejectEverything;

		StrictValue {
			if (rejectEverything) {
				throw new IllegalArgumentException("거부");
			}
		}

	}

	// 지정한 클래스만 "없는 클래스"로 만든다.
	private static class HidingClassLoader extends ClassLoader {

		private final String hidden;

		HidingClassLoader(String hidden) {
			super(TolerantSessionSerializerTest.class.getClassLoader());
			this.hidden = hidden;
		}

		@Override
		protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
			if (name.equals(hidden)) {
				throw new ClassNotFoundException(name);
			}
			return super.loadClass(name, resolve);
		}

	}

	// 지정한 클래스를 읽을 때 JVM 오류를 낸다.
	private static final class FailingClassLoader extends ClassLoader {

		private final String failing;

		FailingClassLoader(String failing) {
			super(TolerantSessionSerializerTest.class.getClassLoader());
			this.failing = failing;
		}

		@Override
		protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
			if (name.equals(failing)) {
				throw new NoClassDefFoundError(name);
			}
			return super.loadClass(name, resolve);
		}

	}

}
