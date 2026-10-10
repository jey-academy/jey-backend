# ADR-0012: 모듈 패키지 구조와 의존 방향

- 상태: 채택
- 날짜: 2026-10-10

## 배경
설계 초안은 `com.jey.core`와 `com.jey.modules.{dongtanpass, billing}` 구조였다. 그런데 Spring Modulith는 기본적으로 메인 클래스 패키지(`com.jey`) 바로 아래 패키지를 모듈로 본다. 초안 그대로면 `core`와 `modules` 두 개만 모듈로 잡히고, `dongtanpass`와 `billing` 사이 경계는 검증되지 않는다.
또 초안은 Gradle 멀티 모듈(`jey-app`, `jey-notifier`, `jey-event-contract`)이었는데, Initializr로 만든 골격은 단일 프로젝트다.

## 선택지
1. `modules` 층 유지 + 탐지 방식 변경(`explicitly-annotated` 또는 커스텀 `ApplicationModuleDetectionStrategy`) — 초안 구조 유지 / 설정이나 커스텀 코드가 필요하고, Modulith 기본 동작에서 벗어남
2. `modules` 층을 없애고 `com.jey.{core, dongtanpass, billing}`로 평탄화 — 기본 탐지 그대로 사용, 설정 없음 / 모듈이 늘면 최상위 패키지가 많아짐

## 결정
- 패키지를 평탄화한다: `com.jey.core`, `com.jey.dongtanpass`, `com.jey.billing`.
- `core`는 모듈 1개로 둔다. 하위 패키지(`auth`, `campus`, `member`, `notification`, `file`, `audit`)는 `api` 패키지만 `@NamedInterface`로 공개하고(`core.member.api` → `core::member`), `shared`만 패키지 전체를 공개한다. `allowedDependencies = {}`로 다른 모듈 의존을 막는다.
- `dongtanpass`, `billing`은 `api` 패키지만 `@NamedInterface("api")`로 공개한다. 나머지 하위 패키지는 모듈 내부다.
- 허용 의존을 각 모듈 `package-info.java`에 명시한다.
  - `dongtanpass`: `core::shared`, `core::member`, `core::campus`, `billing::api`
  - `billing`: `core::shared`, `core::member`, `core::campus`
- **의존 방향은 `dongtanpass → billing` 한 방향**이다. `billing`은 `dongtanpass`를 모른다.
- `billing::api`의 청구 생성은 할인을 출처와 무관한 범용 형태로 받는다: `discountType`, `discountRate`, `discountRef`. `billing`은 `discountRef`(예: 패스 번호)를 해석하지 않고 저장만 한다.
- `core` 하위 패키지끼리의 순환 의존은 ArchUnit 규칙으로 막는다(Modulith는 모듈 사이만 검사).
- 1차는 단일 Gradle 프로젝트로 간다. 2차에 `jey-notifier`를 만들 때 멀티 모듈로 전환한다.

## 의존 방향 (dongtanpass → billing)
반대 방향(`billing → dongtanpass`, 결제할 때 billing이 패스 유효성 확인·사용 처리를 요청)도 검토했다. `dongtanpass → billing`을 고른 이유:

- **안정 의존 원칙**: 수납은 동탄고 패스 없이도 존재하는 기본 기능이고, 동탄고 패스는 그 위에 얹는 프로모션이다. 덜 안정적인 선택 기능이 안정적인 기본 기능에 의존해야 한다.
- **할인 모듈 확장성**: `billing`이 할인 출처를 알면 제휴 쿠폰 같은 할인 모듈이 추가될 때마다 `billing`을 고쳐야 한다. 범용 할인 입력(`discountType`, `discountRate`, `discountRef`)을 받으면 새 할인 모듈은 같은 API를 쓰기만 하면 되고 `billing`은 바뀌지 않는다("새 기능 추가 시 수납 코드 무변경").
- **흐름과 일치**: 사용 처리 API는 dongtanpass에 있다(`POST /api/v1/dongtanpass/passes/{id}/redeem`). 데스크에서 패스 화면으로 시작해 dongtanpass가 청구 생성을 요청한다. 같은 DB라 `dongtanpass_redemption`(pass_id 유니크) 기록과 청구 생성이 한 트랜잭션으로 묶인다.

## 이벤트 순환 2건과 해결
이벤트 구독도 이벤트 타입에 대한 의존이라 Modulith 순환 판정에 들어간다. 초안의 이벤트 구독 표에는 순환이 2건 있었다.

1. **dongtanpass ↔ billing**: `billing`이 `dongtanpass.pass-redeemed`를 구독하고, `dongtanpass`가 `billing.payment-completed`를 구독했다.
   → `billing`의 `pass-redeemed` **구독을 제거**했다. 청구는 `dongtanpass`가 `billing::api`를 호출해 만들고, 납부 일지 반영은 `billing` 자신의 `payment-completed`로 처리한다.
2. **core → 모듈**: `audit`(core)이 `dongtanpass`·`billing`의 개별 이벤트를 구독해 `core`가 모듈에 의존했다.
   → 모든 도메인 이벤트가 `core.shared`의 **공통 봉투 타입**을 구현하고, `audit`은 그 공통 타입만 구독한다.

## 근거
- 기본 탐지를 쓰면 설정·커스텀 코드가 없어 Modulith 문서·예제와 그대로 맞는다.
- 1차 모듈은 3개뿐이라 `modules` 층이 주는 정리 효과가 거의 없다.
- 멀티 모듈은 두 번째 애플리케이션(notifier)이 생길 때 필요하다. 지금 나누면 빌드 설정만 복잡해진다.

## 포기한 것
- 이후 기능(attendance, community, grade 등)이 모두 추가되면 `com.jey` 아래 패키지가 10개 안팎이 된다.
- `core` 하위 패키지마다 `api` 패키지를 따로 두므로 패키지 깊이가 한 단계 늘어난다.
- `core`의 다른 하위 패키지를 쓰게 되면 `allowedDependencies`를 고쳐야 한다(의도한 마찰).
- `dongtanpass`는 결제 완료를 이벤트로만 알 수 있다. `billing`이 `dongtanpass`에 직접 물어볼 수 없다.
- 2차에 멀티 모듈로 전환할 때 디렉터리 이동 작업이 한 번 필요하다.

## 재검토 조건
- 최상위 패키지가 많아져 구조 파악이 어려워질 때
- `core`가 커져 하위 패키지를 별도 모듈로 떼어야 할 때(`api` 패키지가 이미 있어 패키지 이동으로 가능)
- `billing`이 할인 출처별로 다른 처리를 해야 하는 요구가 생길 때
- `jey-notifier` 도입 시(멀티 모듈 전환)
