package com.jey;

import java.util.ArrayList;
import java.util.List;

import com.jey.core.auth.api.PublicEndpoint;
import com.jey.core.shared.Idempotent;
import com.jey.core.shared.NotIdempotent;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import static org.assertj.core.api.Assertions.assertThat;

// 부작용이 있는 POST는 Idempotency-Key로 한 번만 실행한다(ADR-0027).
// 붙이는 것을 잊으면 다시 보낸 요청이 조용히 두 번 실행된다. 그래서 POST마다 "받는다"와 "받지 않는다(이유)" 중 하나를 적게 한다.
class IdempotencyRulesTest {

	private static final JavaClasses CLASSES = new ClassFileImporter().importPackages("com.jey");

	@Test
	void 모든_POST에는_키를_받는지_받지_않는지가_적혀_있다() {
		List<String> unmarked = new ArrayList<>();
		List<String> both = new ArrayList<>();
		for (JavaMethod method : postMethods()) {
			boolean idempotent = method.isAnnotatedWith(Idempotent.class);
			boolean notIdempotent = method.isAnnotatedWith(NotIdempotent.class);
			if (!idempotent && !notIdempotent) {
				unmarked.add(method.getFullName());
			}
			if (idempotent && notIdempotent) {
				both.add(method.getFullName());
			}
		}

		assertThat(postMethods()).as("POST 메서드를 하나도 찾지 못했다. 찾는 방법이 틀렸다").isNotEmpty();
		assertThat(unmarked).as("@Idempotent나 @NotIdempotent(이유)가 없는 POST").isEmpty();
		assertThat(both).as("둘 다 붙은 POST").isEmpty();
	}

	@Test
	void 받지_않는_이유가_비어_있지_않다() {
		assertThat(postMethods()).filteredOn(method -> method.isAnnotatedWith(NotIdempotent.class))
				.isNotEmpty()
				.allSatisfy(method -> assertThat(method.getAnnotationOfType(NotIdempotent.class).value())
						.as(method.getFullName())
						.isNotBlank());
	}

	// 필터는 POST만 본다. 다른 메서드에 붙이면 아무 일도 하지 않는데 붙인 사람은 막힌다고 믿는다.
	@Test
	void 표시는_POST에만_붙인다() {
		List<JavaMethod> posts = postMethods();
		List<String> misplaced = CLASSES.stream()
				.flatMap(type -> type.getMethods().stream())
				.filter(method -> method.isAnnotatedWith(Idempotent.class)
						|| method.isAnnotatedWith(NotIdempotent.class))
				.filter(method -> !posts.contains(method))
				.map(JavaMethod::getFullName)
				.toList();

		assertThat(misplaced).isEmpty();
	}

	// 키는 계정별로 본다. 로그인 없이 열린 경로에 붙이면 필터가 계정을 몰라 요청마다 실패한다.
	@Test
	void 키를_받는_경로는_로그인이_필요한_경로다() {
		assertThat(postMethods()).filteredOn(method -> method.isAnnotatedWith(Idempotent.class))
				.isNotEmpty()
				.allSatisfy(method -> {
					assertThat(method.isAnnotatedWith(PublicEndpoint.class)).as(method.getFullName()).isFalse();
					assertThat(method.getOwner().isAnnotatedWith(PublicEndpoint.class)).as(method.getFullName())
							.isFalse();
				});
	}

	// 지금 키를 받는 실제 경로. 표시를 실수로 떼면 여기서 드러난다.
	@Test
	void 자료를_만드는_POST는_키를_받는다() {
		assertThat(postMethods()).filteredOn(method -> method.isAnnotatedWith(Idempotent.class))
				.extracting(method -> method.getOwner().getSimpleName() + "#" + method.getName())
				.contains("StudentController#register", "StudentController#addGuardian", "ClassController#create",
						"DiscountTypeController#create");
	}

	private static List<JavaMethod> postMethods() {
		return CLASSES.stream()
				.flatMap(type -> type.getMethods().stream())
				.filter(IdempotencyRulesTest::isPost)
				.toList();
	}

	private static boolean isPost(JavaMethod method) {
		if (method.isAnnotatedWith(PostMapping.class)) {
			return true;
		}
		return method.tryGetAnnotationOfType(RequestMapping.class)
				.map(mapping -> List.of(mapping.method()).contains(org.springframework.web.bind.annotation.RequestMethod.POST))
				.orElse(false);
	}

}
