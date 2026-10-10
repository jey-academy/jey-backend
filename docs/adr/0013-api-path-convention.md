# ADR-0013: API 경로 규칙

- 상태: 채택
- 날짜: 2026-10-10

## 배경
초기 설계의 경로 규칙은 `/api/v1/{모듈}/...`이었다. 그런데 ADR-0012에서 패키지를 평탄화하면서 `core`가 Spring Modulith 모듈 1개가 됐고, 그 하위 패키지(`auth`, `campus`, `member`, `file`, `audit`, `notification`)의 경로가 정해지지 않았다.
프론트는 이미 `GET /api/v1/auth/me`를 쓰기로 설계돼 있는데, 이 경로는 "모듈 이름" 규칙으로는 설명되지 않는다. 첫 컨트롤러와 공통 에러 처리(Problem Details `type`, `SecurityConfig` 공개 경로)를 만들기 전에 규칙을 정해야 한다.

## 선택지
1. 모듈 이름 그대로 — `/api/v1/core/auth/me`, `/api/v1/core/campuses`
   - 장점: 규칙이 한 줄("첫 세그먼트 = 모듈 이름"), 모듈별 경로 매칭이 쉬움
   - 단점: 내부 포장 단위인 `core`가 URL에 노출됨. `core`를 쪼개거나 이름을 바꾸면 API가 깨짐. 프론트 설계 수정 필요
2. `core` 하위 패키지 이름 — `/api/v1/auth/me`, `/api/v1/member/students`, `/api/v1/campus/campuses`
   - 장점: 패키지와 경로가 1:1
   - 단점: 패키지 이름(단수)과 리소스 이름(복수)이 겹쳐 `/campus/campuses` 같은 경로가 생김. 패키지 구조가 여전히 URL에 노출됨
3. `core`의 것은 접두사 없이 최상위 리소스 — `/api/v1/auth/me`, `/api/v1/campuses`, `/api/v1/students`
   - 장점: URL이 내부 구조와 무관, 프론트 설계와 일치, 경로가 짧음
   - 단점: 첫 세그먼트가 모듈 이름일 수도 리소스 이름일 수도 있어 규칙이 두 갈래

## 결정
3번을 채택한다.

- 모든 API는 `/api/v1/` 아래에 둔다.
- **첫 세그먼트는 기능 모듈 이름(`dongtanpass`, `billing`)이거나, `core`가 제공하는 리소스 이름(복수형) 또는 `auth`다.**
  - 기능 모듈: `/api/v1/dongtanpass/passes/...`, `/api/v1/billing/invoices/...`
  - `core`: `/api/v1/auth/...`, `/api/v1/campuses`, `/api/v1/students`, `/api/v1/files`, `/api/v1/audit-logs`, `/api/v1/notifications`
  - `core`의 구체적인 리소스 이름은 해당 기능을 만들 때 정한다. 위 목록은 형태를 보여주는 예시다.
- 기능 모듈 이름과 겹치는 `core` 리소스 이름은 만들지 않는다. 새 기능 모듈을 추가할 때도 기존 최상위 리소스 이름과 겹치지 않게 한다.
- 표기: 리소스는 복수형 명사, kebab-case(`audit-logs`). 행위는 하위 경로 동사(`/redeem`, `/confirm`, `/retry`).
- 인증 없이 열리는 경로는 `SecurityConfig`에 명시한 것으로 한정한다: `/api/v1/auth/login`, `/api/v1/auth/refresh`, `/api/v1/dongtanpass/holder/**`, `/api/v1/billing/webhooks/**`. (각 엔드포인트를 만들 때 추가한다.)
- 서비스 간 내부 전용 API는 `/internal/v1/...`로 분리하고 Nginx에서 외부 접근을 막는다(2차, notifier).

## 근거
- `core`가 모듈 1개인 것은 Modulith 기본 탐지에 맞추다 생긴 포장 결정(ADR-0012)이지 클라이언트가 알아야 할 개념이 아니다. URL은 내부 구조보다 오래 유지돼야 한다.
- ADR-0012의 재검토 조건에 "core가 커져 하위 패키지를 별도 모듈로 뗄 때"가 있다. 3번은 그때도 경로가 바뀌지 않는다.
- 프론트가 쓰기로 한 `/api/v1/auth/me`, 2차에 계획한 `/api/v1/notifications/bulk`가 이미 이 형태다. 기존 설계와 프론트 코드 수정이 없다.
- `dongtanpass`, `billing`의 접두사는 유지할 가치가 있다. `passes`, `invoices`, `payments`가 어느 업무 영역인지 드러나고, 이후 쿠폰 같은 모듈이 추가돼도 리소스 이름이 겹치지 않는다.

## 포기한 것
- "첫 세그먼트 = 모듈 이름"이라는 한 줄 규칙. 경로만 보고 어느 Spring 모듈의 컨트롤러인지 바로 알 수 없는 경우가 생긴다(`/api/v1/students` → `core.member`).
- 모듈 단위 경로 매칭(`/api/v1/core/**`)을 쓸 수 없다. 권한 규칙은 리소스 경로별로 적어야 한다.
- 최상위 이름 충돌을 규칙으로만 막는다(자동 검증 없음).

## 재검토 조건
- 최상위 리소스 이름이 기능 모듈 이름이나 다른 리소스와 겹치는 일이 실제로 생길 때
- API를 외부(연계 학원 시스템 등)에 공개해 경로 체계를 다시 잡아야 할 때
- `/api/v2`를 도입할 때
