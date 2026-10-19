# 운영 앱 이미지. jar는 CI가 먼저 만든다(./gradlew build → build/libs/app.jar).
# 서버가 Graviton이라 linux/arm64로 만든다. 최종 단계에는 RUN이 없어 에뮬레이션 없이 어느 PC에서든 만들 수 있다.

# jar를 계층으로 푼다. 의존성 계층은 거의 바뀌지 않아 서버가 바뀐 계층만 받는다.
FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jre-noble AS extract
WORKDIR /work
COPY build/libs/app.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination /extracted

FROM eclipse-temurin:21-jre-noble
WORKDIR /app
# 자주 바뀌지 않는 것부터 쌓는다.
COPY --from=extract /extracted/dependencies/ ./
COPY --from=extract /extracted/spring-boot-loader/ ./
COPY --from=extract /extracted/snapshot-dependencies/ ./
COPY --from=extract /extracted/application/ ./
# root로 돌지 않는다. 파일은 root 소유 그대로 두어 앱이 자기 코드를 고치지 못한다.
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
