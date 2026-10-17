plugins {
	java
	id("org.springframework.boot") version "4.1.1"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "com.jey"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

extra["springModulithVersion"] = "2.1.1"
extra["springdocVersion"] = "3.1.1"

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-data-redis")
	implementation("org.springframework.boot:spring-boot-starter-flyway")
	implementation("org.springframework.boot:spring-boot-starter-security")
	implementation("org.springframework.boot:spring-boot-starter-session-data-redis")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.flywaydb:flyway-mysql")
	// 다른 스레드로 넘기는 작업에 로그 문맥(MDC)을 같이 넘긴다 (ADR-0028). Spring Modulith가 이미 들여오는 라이브러리이고 버전은 Spring Boot가 정한다.
	implementation("io.micrometer:context-propagation")
	implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:${property("springdocVersion")}")
	implementation("org.springframework.modulith:spring-modulith-observability-api")
	implementation("org.springframework.modulith:spring-modulith-starter-core")
	implementation("org.springframework.modulith:spring-modulith-starter-jpa")
	compileOnly("org.projectlombok:lombok")
	runtimeOnly("com.mysql:mysql-connector-j")
	runtimeOnly("org.springframework.modulith:spring-modulith-actuator")
	runtimeOnly("org.springframework.modulith:spring-modulith-observability-core")
	runtimeOnly("org.springframework.modulith:spring-modulith-runtime")
	annotationProcessor("org.projectlombok:lombok")
	testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
	testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
	testImplementation("org.springframework.boot:spring-boot-starter-data-redis-test")
	testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
	testImplementation("org.springframework.boot:spring-boot-starter-security-test")
	testImplementation("org.springframework.boot:spring-boot-starter-session-data-redis-test")
	testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.springframework.boot:spring-boot-testcontainers")
	testImplementation("org.springframework.modulith:spring-modulith-starter-test")
	testImplementation("org.testcontainers:testcontainers-junit-jupiter")
	testImplementation("org.testcontainers:testcontainers-mysql")
	testCompileOnly("org.projectlombok:lombok")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
	testAnnotationProcessor("org.projectlombok:lombok")
}

dependencyManagement {
	imports {
		mavenBom("org.springframework.modulith:spring-modulith-bom:${property("springModulithVersion")}")
	}
}

tasks.withType<Test> {
	useJUnitPlatform()
	// 테스트 JVM의 시간대를 한국도 UTC도 아닌 곳으로 고정한다.
	// 시각을 다루는 코드가 JVM 기본 시간대에 기대고 있으면, 개발 PC(한국)와 CI(UTC) 어디서 돌려도 똑같이 드러난다.
	jvmArgs("-Duser.timezone=America/New_York")

	// API 명세 파일(docs/api/openapi.json)을 다시 쓴다: ./gradlew test --tests "*ApiSpecTest" -PupdateApiSpec
	// 옵션이 없으면 ApiSpecTest는 비교만 한다. CI는 옵션을 주지 않는다.
	val updateApiSpec = providers.gradleProperty("updateApiSpec").isPresent
	systemProperty("jey.updateApiSpec", updateApiSpec)
	if (updateApiSpec) {
		// 파일을 쓰는 실행은 "입력이 같으니 건너뛴다"의 대상이 아니다.
		outputs.upToDateWhen { false }
	}
}

// 부하 시험(k6)용 실험 앱을 띄운다: ./gradlew loadtestRun -Plocking=pessimistic
// 사용 처리의 중복 사용 방지 방식을 고른다(optimistic, pessimistic, unique-first, redis. 기본은 optimistic = 운영 방식).
// 테스트 소스의 실행기라 운영 jar에 들어가지 않는다. MySQL·Redis는 Testcontainers로 새로 뜬다(Docker 필요). 포트 8080.
tasks.register<JavaExec>("loadtestRun") {
	group = "application"
	description = "부하 시험용 실험 앱을 띄운다(-Plocking=…)"
	dependsOn("testClasses")
	classpath = sourceSets["test"].runtimeClasspath
	mainClass.set("com.jey.loadtest.LoadtestApplication")
	val locking = providers.gradleProperty("locking").getOrElse("optimistic")
	args("--spring.profiles.active=test,loadtest", "--jey.loadtest.redeem-locking=$locking")
}
