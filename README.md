# JEY Platform — Backend

JEY 어학원(동탄관·능동관) 학원 관리 플랫폼의 백엔드입니다.
학부모 편의와 데스크 업무 자동화를 목표로 합니다.

## 주요 기능 (1차)
- **동탄고패스**: 연계 학원 수강 시 발급되는 할인 패스 (QR 인증, 1회 사용)
- **수납 관리**: 수강료 계산, 결제 기록, 납부 현황
- 학생·학부모·직원 계정과 지점 관리

## 구조
모듈러 모놀리스입니다. `com.jey` 바로 아래 패키지가 Spring Modulith 모듈입니다.

```
com.jey
├── core      기초: 인증, 지점, 학생·학부모, 알림, 파일, 감사 로그, 공용 값 객체
├── gopass    동탄고패스: 패스 발급, 확인, 사용 처리
└── billing   수납: 수강료 계산, 청구, 결제
```

- 모듈끼리는 상대 모듈의 `api` 패키지만 사용합니다.
- 의존 방향은 `gopass → billing → core` 한 방향입니다. `billing`은 `gopass`를 모르고, `core`는 다른 모듈에 의존하지 않습니다.
- 이 규칙은 테스트(`ModularityTest`)가 검증하며, 위반하면 빌드가 실패합니다.

## 기술 스택
Java 21 · Spring Boot · Spring Modulith · JPA · MySQL · Redis · AWS
