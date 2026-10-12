package com.jey;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import com.jey.core.auth.api.PublicEndpoint;
import com.jey.core.shared.Idempotent;
import com.jey.core.shared.NotIdempotent;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.context.request.async.WebAsyncTask;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import static org.assertj.core.api.Assertions.assertThat;

// 부작용이 있는 POST는 Idempotency-Key로 한 번만 실행한다(ADR-0027).
// 붙이는 것을 잊으면 다시 보낸 요청이 조용히 두 번 실행된다. 그래서 POST마다 "받는다"와 "받지 않는다(이유)" 중 하나를 적게 한다.
//
// 무엇이 POST인지는 서버가 실제로 등록한 매핑에 묻는다. 필터도 같은 곳에 묻는다.
// 애너테이션을 직접 읽으면 클래스에 적은 메서드 조건, 메서드를 정하지 않은 매핑, 합성 애너테이션을 놓친다.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class IdempotencyRulesTest {

	private static final JavaClasses CLASSES = new ClassFileImporter().importPackages("com.jey");

	// 메서드를 가리지 않고 받는 경로라 POST로도 불리지만 일을 하지 않는다. 컨테이너가 오류 응답을 만들려고 부른다.
	private static final Set<String> NOT_AN_ACTION = Set.of("ProblemDetailsErrorController#error");

	// 요청을 받은 스레드가 필터로 돌아온 뒤에 응답을 쓰는 타입. 필터가 응답을 보관하지 못한다.
	private static final List<Class<?>> ASYNC_RETURN_TYPES = List.of(Callable.class, Future.class,
			DeferredResult.class, WebAsyncTask.class, ResponseBodyEmitter.class, StreamingResponseBody.class);

	@Autowired
	@Qualifier("requestMappingHandlerMapping")
	RequestMappingHandlerMapping handlerMapping;

	@Test
	void 모든_POST에는_키를_받는지_받지_않는지가_적혀_있다() {
		List<String> unmarked = new ArrayList<>();
		List<String> both = new ArrayList<>();
		for (HandlerMethod handler : postHandlers()) {
			boolean idempotent = handler.hasMethodAnnotation(Idempotent.class);
			boolean notIdempotent = handler.hasMethodAnnotation(NotIdempotent.class);
			if (!idempotent && !notIdempotent) {
				unmarked.add(name(handler));
			}
			if (idempotent && notIdempotent) {
				both.add(name(handler));
			}
		}

		assertThat(unmarked).as("@Idempotent나 @NotIdempotent(이유)가 없는 POST").isEmpty();
		assertThat(both).as("둘 다 붙은 POST").isEmpty();
	}

	// 테스트용 컨트롤러만으로도 "하나 이상"은 채워진다. 실제 경로가 잡히는지 이름으로 본다.
	@Test
	void POST를_찾는_방법이_실제_경로를_본다() {
		assertThat(postHandlers()).extracting(IdempotencyRulesTest::name)
				.contains("AuthController#login", "AuthController#logout", "StudentController#register",
						"ClassController#create")
				.doesNotContainAnyElementsOf(NOT_AN_ACTION);
		// 빼 둔 것이 실제로 있는 메서드인지 본다. 이름이 바뀌면 여기서 드러난다.
		assertThat(allHandlers()).extracting(IdempotencyRulesTest::name).containsAll(NOT_AN_ACTION);
	}

	@Test
	void 받지_않는_이유가_비어_있지_않다() {
		assertThat(postHandlers()).filteredOn(handler -> handler.hasMethodAnnotation(NotIdempotent.class))
				.isNotEmpty()
				.allSatisfy(handler -> assertThat(handler.getMethodAnnotation(NotIdempotent.class).value())
						.as(name(handler))
						.isNotBlank());
	}

	// 필터는 POST만 본다. 다른 메서드에 붙이면 아무 일도 하지 않는데 붙인 사람은 막힌다고 믿는다.
	@Test
	void 표시는_POST에만_붙인다() {
		Set<Method> posts = postHandlers().stream().map(HandlerMethod::getMethod).collect(Collectors.toSet());
		List<String> misplaced = CLASSES.stream()
				.flatMap(type -> type.getMethods().stream())
				.filter(method -> method.isAnnotatedWith(Idempotent.class)
						|| method.isAnnotatedWith(NotIdempotent.class))
				.filter(method -> !posts.contains(method.reflect()))
				.map(method -> method.getFullName())
				.toList();

		assertThat(misplaced).isEmpty();
	}

	// 키는 계정별로 본다. 로그인 없이 열린 경로에 붙이면 필터가 계정을 몰라 요청마다 실패한다.
	@Test
	void 키를_받는_경로는_로그인이_필요한_경로다() {
		assertThat(idempotentHandlers()).isNotEmpty().allSatisfy(handler -> {
			assertThat(handler.hasMethodAnnotation(PublicEndpoint.class)).as(name(handler)).isFalse();
			assertThat(AnnotatedElementUtils.hasAnnotation(userClass(handler), PublicEndpoint.class))
					.as(name(handler))
					.isFalse();
		});
	}

	// 필터는 응답이 다 쓰인 뒤에 보관한다. 비동기로 응답하는 메서드는 필터로 돌아온 뒤에 응답을 쓴다.
	@Test
	void 키를_받는_메서드는_비동기로_응답하지_않는다() {
		assertThat(idempotentHandlers()).allSatisfy(handler -> {
			ResolvableType returned = ResolvableType.forMethodReturnType(handler.getMethod());
			if (ResponseEntity.class.equals(returned.toClass())) {
				returned = returned.getGeneric(0);
			}
			Class<?> type = returned.toClass();
			assertThat(ASYNC_RETURN_TYPES).as(name(handler) + "의 반환 타입 " + type.getName())
					.noneMatch(async -> async.isAssignableFrom(type));
		});
	}

	// 필터가 본문을 먼저 읽는다. 폼과 파일 올리기의 값은 컨테이너가 본문에서 꺼내므로, 그런 메서드는 빈 값을 받는다.
	@Test
	void 키를_받는_메서드는_폼이나_파일을_받지_않는다() {
		assertThat(idempotentHandlers()).allSatisfy(handler -> assertThat(handler.getMethodParameters())
				.as(name(handler))
				.noneMatch(parameter -> parameter.hasParameterAnnotation(RequestPart.class)
						|| parameter.hasParameterAnnotation(ModelAttribute.class)
						|| MultipartFile.class.isAssignableFrom(parameter.getParameterType())
						|| (parameter.getParameterType().isArray()
								&& MultipartFile.class.isAssignableFrom(parameter.getParameterType().getComponentType()))));
	}

	// 지금 키를 받는 실제 경로. 표시를 실수로 떼면 여기서 드러난다.
	@Test
	void 자료를_만드는_POST는_키를_받는다() {
		assertThat(idempotentHandlers()).extracting(IdempotencyRulesTest::name)
				.contains("StudentController#register", "StudentController#addGuardian", "ClassController#create",
						"DiscountTypeController#create");
	}

	private List<HandlerMethod> idempotentHandlers() {
		return postHandlers().stream().filter(handler -> handler.hasMethodAnnotation(Idempotent.class)).toList();
	}

	// POST 요청을 받을 수 있는 우리 컨트롤러 메서드. 메서드를 정하지 않은 매핑은 POST도 받는다.
	private List<HandlerMethod> postHandlers() {
		return handlerMapping.getHandlerMethods()
				.entrySet()
				.stream()
				.filter(entry -> {
					Set<RequestMethod> methods = entry.getKey().getMethodsCondition().getMethods();
					return methods.isEmpty() || methods.contains(RequestMethod.POST);
				})
				.map(entry -> entry.getValue())
				.filter(IdempotencyRulesTest::isOurs)
				.filter(handler -> !NOT_AN_ACTION.contains(name(handler)))
				.toList();
	}

	private List<HandlerMethod> allHandlers() {
		return handlerMapping.getHandlerMethods().values().stream().filter(IdempotencyRulesTest::isOurs).toList();
	}

	// 문서 화면처럼 라이브러리가 등록한 경로는 보지 않는다.
	private static boolean isOurs(HandlerMethod handler) {
		return userClass(handler).getPackageName().startsWith("com.jey");
	}

	private static Class<?> userClass(HandlerMethod handler) {
		return ClassUtils.getUserClass(handler.getBeanType());
	}

	private static String name(HandlerMethod handler) {
		return userClass(handler).getSimpleName() + "#" + handler.getMethod().getName();
	}

	// 규칙이 무엇을 잡는지 한 번은 확인해 둔다. 목록이 비어 있어도 위의 검사들은 통과한다.
	@Test
	void 비동기_반환_타입을_알아본다() {
		assertThat(Arrays.asList(Callable.class, DeferredResult.class, java.util.concurrent.CompletableFuture.class))
				.allMatch(type -> ASYNC_RETURN_TYPES.stream().anyMatch(async -> async.isAssignableFrom(type)));
		assertThat(ASYNC_RETURN_TYPES).noneMatch(async -> async.isAssignableFrom(java.util.Map.class));
	}

}
