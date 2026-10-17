# 부하 시험

## 패스 사용 처리의 중복 사용 방지 방식 비교 (`k6/redeem.js`)

같은 패스를 동시에 사용 처리하는 요청을 몰아, 방식마다 정확성(중복 사용 없음, 5xx 없음)과 응답 시간을 잰다.

### 방식

| 값 | 방식 |
|---|---|
| `optimistic` | 낙관적 락(패스의 버전) + 사용 기록 유니크 키. 패스를 먼저 고치고 기록을 넣는다. 운영 방식 |
| `pessimistic` | 비관적 락(`SELECT … FOR UPDATE`) + 유니크 키 |
| `unique-first` | 사용 기록을 먼저 넣고 패스를 고친다(유니크 키가 먼저 막는다) |
| `redis` | 트랜잭션 밖에서 패스마다 Redis 락(SET NX + 토큰), 못 잡으면 바로 409. 안에서는 `optimistic` |

### 시나리오

| `SCENARIO` | 반복마다 | 기본값 | 정확성 |
|---|---|---|---|
| `contention` | 한 패스에 사용 처리 N개를 동시에 | N=8, 가상 사용자 10(N이 32 이상이면 4), 반복 300 | 성공 정확히 1개 |
| `spread` | 서로 다른 패스에 1개 | 가상 사용자 32, 반복 300 | 성공 정확히 1개 |
| `mixed` | 사용 처리 N/2개 + 같은 패스의 링크 새로 만들기 N/2개 | N=8 | 성공 많아야 1개 |

### 실행

Docker와 k6가 필요하다. 터미널 둘을 쓴다.

```bash
# 터미널 1: 실험용 앱(Testcontainers로 MySQL·Redis를 새로 띄우고, 실험 계정을 만든다). 끝나면 Ctrl+C
./gradlew loadtestRun -Plocking=optimistic

# 터미널 2: "loadtest ready" 로그가 보이면
mkdir -p build/k6
k6 run -e LOCKING=optimistic -e SCENARIO=contention -e N=8 \
  --summary-export=build/k6/optimistic-contention-N8.json loadtest/k6/redeem.js
```

- 방식을 바꿀 때마다 앱을 새로 띄운다(DB가 새것이 된다).
- 사용 처리·링크 새로 만들기의 5xx, 예상하지 못한 4xx(사용 처리는 409 세 가지 — 이미 사용됨, 신규 아님, 다시 시도 — 만 예상한다), 연결 실패(서버가 받지 못한 요청) 중 하나라도 있거나 `redeem_correct`가 100%가 아니면 k6가 실패로 끝난다.
- 연결이 한꺼번에 아주 많이 몰리면(가상 사용자 × N) 서버의 접속 대기열이 넘쳐 일부 연결이 거절될 수 있다. 그래서 N이 크면 가상 사용자를 줄인다.
- 응답 시간은 `redeem_ms`(사용 처리 요청만)를 본다. `http_req_duration`에는 준비 단계의 요청이 섞인다.
- 앱 터미널에는 DB 연결 풀의 활성·대기 수 최대값이 5초 구간마다 찍힌다(`loadtest pool window …`, 구간이 끝나면 0부터 다시 센다). 끌 때는 띄운 뒤 전체의 최대값이 `[final]`로 찍힌다.
