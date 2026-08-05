# imlate 로컬 검증 가이드

> **이 문서의 목적** — AWS 프로덕션에 올리기 **전에**, 개발자 PC 한 대에서
> "이 시스템이 정말로 돌아가는가"를 끝까지 확인하는 방법을 정리한다.
> 배포 절차 자체는 [docs/DEPLOYMENT.md](DEPLOYMENT.md), 배포 후 운영은 [docs/OPERATIONS.md](OPERATIONS.md) 를 본다.

대상 환경: **Windows 11 · PowerShell 5.1**(Git Bash 병용) · Node 22 · Docker Desktop · Gradle 툴체인 JDK 21

---

## 0. 셸 표기 규칙 — 이 문서를 읽는 법

이 프로젝트는 **셸을 헷갈리면 반드시 사고가 난다**(환경변수 문법, 따옴표, `./` vs `.\`).
그래서 이 문서의 모든 코드블록에는 **어느 셸에서 실행하는지**를 첫 줄 주석으로 못박아 두었다.

| 태그 | 셸 | 여는 법 | 이 문서에서 쓰는 곳 |
|---|---|---|---|
| ```powershell``` | **Windows PowerShell** | 시작 → `Windows PowerShell` | 기본. 앱 기동, docker, npm, node |
| ```bash``` | **Git Bash** | 시작 → `Git Bash` | 같은 일을 Bash 로 하고 싶을 때의 대안 |

기억할 차이 세 가지:

| | PowerShell | Git Bash |
|---|---|---|
| 환경변수 설정 | `$env:NAME = "값"` | `export NAME="값"` |
| Gradle 래퍼 | `.\gradlew.bat` | `./gradlew` |
| 명령 연결 | `A; if ($?) { B }` (`&&` 없음) | `A && B` |

> **주의(PowerShell)** — `$env:X = "..."` 는 **그 창에서만** 유효하다. 창을 새로 열면 사라진다.
> 앱을 재기동할 때는 **환경변수를 설정한 바로 그 창에서** 다시 띄워야 한다.

---

## 1. 검증 피라미드 — 무엇이 무엇을 보장하는가

아래로 갈수록 빠르고 싸며, 위로 갈수록 느리지만 "진짜"에 가깝다.
**어느 한 층도 다른 층을 대체하지 못한다.** 각 층이 못 보는 것을 표의 마지막 칸에 적었다.

| 층 | 명령 | 무엇을 검증하는가 | 인프라 | 소요 | **못 보는 것** |
|---|---|---|---|---|---|
| ① 백엔드 단위/웹 테스트 | `.\gradlew.bat test` | 등록 창 정책, 정규화·검증 규칙, 멱등성, WAL↔DB 대사 로직, 발송 오케스트레이션, 문구 렌더, rate limit 토큰버킷, 조회 토큰 HMAC, 컨트롤러 상태코드 | 불필요 (H2 / Mock) | 1~3분 | 실제 MySQL 방언·인코딩, 실제 Redis 동작, Flyway 마이그레이션, 스케줄러 실제 발화 |
| ② 프론트 반응형 E2E (90개) | `npm run test:e2e` | 320~2560px 레이아웃 넘침, 다크모드, 폼 검증 문구, 마감/중복/429 안내 문구, 조회 화면 표·검색·인쇄 스타일 | 불필요 (**API 전부 목킹**) | 1~2분 | 백엔드가 실제로 그 응답을 주는지. 목이 거짓말하면 통과한다 |
| ③ **통합 테스트** | `node scripts/integration-test.mjs` | 기동 중인 앱 + **실제 MySQL/Redis** 로 등록→WAL→DB→대사→발송→조회→통계 전 구간. 시각 설정 정합성(마감<발송<재시도<통금), 5회 재제출 멱등, 문구 필수 안내(수신 전용·문의처), rate limit 2단(같은 IP·다른 사람 통과 / 같은 사람 도배 429 / XFF 위조 무력화), 토큰 위조 403, 관리 API 401, 중복 발송 skip | **필요** (앱+DB+Redis 기동) | 1분 내외 | 동시성/성능, 스케줄러 발화 시각, 실제 문자·메일 도달 |
| ④ **부하 테스트** | `node scripts/load-test.mjs` | 동시 등록 처리량·지연, 커넥션 풀·Redis 포화 시 거동, **공용 와이파이(전원 같은 IP) 200명 동시 등록** ([§2.5](#25-공용-와이파이-환경-검증-반드시-본다)) | **필요** | 1~3분 | 정확성. 부하 테스트가 통과해도 데이터가 맞다는 보장은 없다 |
| ⑤ 장애 훈련 | `node scripts/integration-test.mjs --drills` + [§4](#4-장애-훈련-시나리오) 수동 절차 | Redis/MySQL 정지 중 거동, 발송 실패 재시도, 중복 발송 락 | **필요** | 5~15분 | 실제 AWS 장애 양상(ElastiCache 페일오버, RDS Multi-AZ 전환) |
| ⑥ 수동 눈 검사 | 브라우저 | 실제 사람이 보는 화면·문구·인쇄물 | 필요 | 10분 | 자동화되지 않으므로 매번 사람이 해야 함 |
| ⑦ 실발송 리허설 | [§7](#7-실발송-리허설-마지막-관문) | Aligo 문자·SES 메일이 **진짜로 도착**하는지, 한글이 안 깨지는지, 조회 링크가 열리는지 | 필요 + 실제 계정 | 10분 | — (여기까지 통과하면 배포 가능) |

> ④ **옵션은 문서를 믿지 말고 스크립트에게 직접 물어라.**
>
> ```powershell
> # PowerShell — 프로젝트 루트
> node scripts/load-test.mjs --help
> ```
>
> **부하 테스트를 돌릴 때 rate limit 을 끄지 마라.** 예전 문서는 "안 끄면 429 만 받는다"고 안내했지만,
> 그 안내 자체가 결함을 가리고 있었다([§2.5](#25-공용-와이파이-환경-검증-반드시-본다)).
> 지금은 **켜 둔 채로 통과해야 정상**이고, 끄면 부하 테스트가 아무것도 증명하지 못한다
> (스크립트가 `rate limit 이 켜져 있다` 항목으로 이를 검사한다).

### 이미 있는 것을 다시 만들지 말 것

- `scripts/integration-test.mjs` 가 **등록 정확성·대사·발송·조회·통계·보안**을 전부 덮는다. 같은 걸 또 만들지 않는다.
- `frontend/tests/**` 가 **반응형·문구**를 덮는다. 브라우저 눈 검사는 "자동화가 못 보는 것"만 본다([§5](#5-수동-눈-검사-체크리스트)).

---

## 2. 0부터 시작하는 실행 절차

창을 **4개** 연다. 각 창의 역할이 다르고, 특히 **창 B(백엔드)는 환경변수를 바꿀 때마다 여기서만 재기동**한다.

```
창 A  인프라   docker compose (한 번 띄우면 끝)
창 B  백엔드   gradlew bootRun     ← 시간 시나리오는 전부 이 창에서 재기동
창 C  프론트   npm run dev         (한 번 띄우면 끝)
창 D  검증     통합/부하 테스트, curl, docker exec
```

### 2.0 사전 확인 (창 D에서 한 번)

```powershell
# PowerShell — 창 D
docker --version          # Docker Desktop 이 실행 중이어야 한다
node -v                   # v20.19 이상 (현재 v22)
java -version             # 참고용. 빌드는 Gradle 툴체인(JDK 21)이 담당한다
```

> `JAVA_HOME` 이 JDK 17 을 가리켜도 **정상**이다. `backend/build.gradle` 이 툴체인을 21 로 고정해 두었고,
> 로컬에 JDK 21 이 없으면 `settings.gradle` 의 foojay resolver 가 자동으로 내려받는다(첫 실행만 몇 분 소요).

### 2.1 창 A — 인프라 기동

이 PC 에는 이미 MySQL(3306)·Redis(6379)가 돌고 있으므로, 컨테이너는 **13306 / 16379** 를 쓴다.
그 값은 프로젝트 루트 `.env` 에 들어 있고 `docker-compose.yml` 이 읽는다.

```powershell
# PowerShell — 창 A, 프로젝트 루트
cd C:\Users\kkh98\Desktop\skala-imlate
type .env                     # IMLATE_MYSQL_PORT=13306 / IMLATE_REDIS_PORT=16379 확인
docker compose up -d
docker compose ps             # 두 컨테이너가 (healthy) 가 될 때까지 대기 (최대 30초)
```

```bash
# Git Bash — 창 A (대안)
cd /c/Users/kkh98/Desktop/skala-imlate
cat .env
docker compose up -d && docker compose ps
```

컨테이너 이름은 **`imlate-mysql` / `imlate-redis`** 로 고정되어 있다(통합 테스트가 이 이름으로 `docker exec` 한다).

```powershell
# PowerShell — 창 A, 접속 확인
docker exec imlate-redis redis-cli ping                      # PONG
docker exec imlate-mysql mysql -uimlate -pimlate -e "SELECT 1"
```

### 2.2 창 B — 백엔드 기동

`.env` 는 docker compose 만 읽는다. **Spring 에게는 따로 알려줘야 한다.**

```powershell
# PowerShell — 창 B
cd C:\Users\kkh98\Desktop\skala-imlate\backend

$env:IMLATE_DB_URL = "jdbc:mysql://localhost:13306/imlate?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul&characterEncoding=UTF-8"
$env:IMLATE_REDIS_PORT = "16379"

.\gradlew.bat bootRun --args='--spring.profiles.active=local'
```

```bash
# Git Bash — 창 B (대안)
cd /c/Users/kkh98/Desktop/skala-imlate/backend
export IMLATE_DB_URL="jdbc:mysql://localhost:13306/imlate?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul&characterEncoding=UTF-8"
export IMLATE_REDIS_PORT=16379
./gradlew bootRun --args='--spring.profiles.active=local'
```

기동 로그에서 **반드시** 다음 세 줄을 확인한다.

```
Service clock initialized with zone=Asia/Seoul
rate limit 인터셉터 등록: enabled=true, failOpen=true, global=1200/60s(IP), register=300/60s(IP), register-person=5/60s(개인), lookup=20/60s(IP), 신뢰 프록시 0개(0개면 X-Forwarded-For 무시)
Tomcat started on port 8080
```

> **rate limit 로그 줄에서 반드시 확인할 것** — `register` 가 **교육생 규모(200)보다 커야** 하고,
> `register-person` 이 **그보다 훨씬 작아야** 한다. 이 두 조건이 이 시스템의 생사를 가른다([§2.5](#25-공용-와이파이-환경-검증-반드시-본다)).
> 실제로 바인딩된 값은 아래로도 확인할 수 있다.
>
> ```powershell
> # PowerShell — 창 D
> curl.exe -s "http://localhost:8080/actuator/env/imlate.rate-limit.register.capacity"
> curl.exe -s "http://localhost:8080/actuator/env/imlate.rate-limit.register-person.capacity"
> curl.exe -s "http://localhost:8080/actuator/env/imlate.rate-limit.global.capacity"
> ```

`local` 프로파일에서는 문자/메일이 **실제로 나가지 않는다**(`noop` 발송기, 로그만 남음).
관리자 키는 `local-dev-admin-key`, 조회 토큰 시크릿은 `local-dev-lookup-secret-change-me` 가 기본값이다.

### 2.3 창 C — 프론트 기동

```powershell
# PowerShell — 창 C
cd C:\Users\kkh98\Desktop\skala-imlate\frontend
npm install                    # 최초 1회
npm run dev                    # http://localhost:5173 (strictPort)
```

`/api` 요청은 Vite dev 서버가 `http://localhost:8080` 으로 프록시한다(`vite.config.ts`).

### 2.4 창 D — 검증

```powershell
# PowerShell — 창 D, 프로젝트 루트

# 0) 살아 있는지
curl.exe -s http://localhost:8080/actuator/health
curl.exe -s http://localhost:8080/actuator/health/alb
curl.exe -s http://localhost:8080/api/v1/registrations/window

# 1) 백엔드 단위/웹 테스트  (인프라 없어도 됨)
cd backend; .\gradlew.bat test; cd ..

# 2) 프론트 E2E 90개  (백엔드 없어도 됨. dev 서버는 Playwright 가 알아서 재사용/기동)
cd frontend
npx playwright install          # 최초 1회 — 브라우저 다운로드
npm run test:e2e
cd ..

# 3) 통합 테스트 (앱 + 실제 MySQL/Redis 필요)
node scripts/integration-test.mjs

# 4) 장애 훈련 포함 (Redis 와 MySQL 을 실제로 내렸다 올린다. DB 재기동 대기 때문에 1~2분 더 걸린다)
node scripts/integration-test.mjs --drills

# 5) 결과를 눈으로 보고 싶으면 데이터를 남긴다
node scripts/integration-test.mjs --keep
```

> **통합 테스트는 대상 DB 의 데이터를 지운다.** `--base-url` 이 localhost/127.0.0.1 이 아니면 스크립트가 실행을 거부한다.
> 컨테이너 이름이 다르면 `--mysql` / `--redis` 로 지정한다.

```bash
# Git Bash — 창 D (대안)
cd /c/Users/kkh98/Desktop/skala-imlate
curl -s http://localhost:8080/actuator/health
(cd backend && ./gradlew test)
(cd frontend && npm run test:e2e)
node scripts/integration-test.mjs --drills
```

---

## 2.5 공용 와이파이 환경 검증 ★반드시 본다★

### 무엇을 검증하는가

교육생 약 200명은 **기숙사 공용 와이파이**로 등록한다. NAT 뒤라 **전원이 공인 IP 하나를 공유**한다.
따라서 이 시스템이 실제로 돌아가는지를 가르는 질문은 하나다.

> **같은 IP 에서 200명이 각자 한 번씩 등록할 때, 전원이 통과하는가?**

이 질문에 답하는 것이 `scripts/load-test.mjs` 의 **§1B 공용 와이파이 시나리오**이며,
**기본 실행에 항상 포함**된다. 통합 테스트도 §3 에서 같은 성질을 기능 단위로 확인한다.

```powershell
# PowerShell — 창 D, 프로젝트 루트
# rate limit 을 켠 채로(= 기본값 그대로) 돌린다. 끄면 아무것도 증명하지 못한다.
node scripts/load-test.mjs

# 단일 IP 시나리오만 빠르게 보고 싶으면 (IP 분산 기준선을 건너뛴다)
node scripts/load-test.mjs --same-ip

# 기능 단위 확인 (rate limit 2단 계약)
node scripts/integration-test.mjs
```

```bash
# Git Bash — 창 D (대안)
cd /c/Users/kkh98/Desktop/skala-imlate
node scripts/load-test.mjs
node scripts/integration-test.mjs
```

### 통과해야 하는 항목 — 이 줄들이 안 보이면 배포하지 않는다

| 스크립트 | 항목 | 의미 |
|---|---|---|
| `load-test.mjs` §0B | `register(IP) 한도(300) ≥ 동시 등록 인원(200)` | **설정만 보고도** 알 수 있는 결함. 부하를 걸기 전에 걸러진다 |
| `load-test.mjs` §0B | `global(IP) 한도(1200) ≥ 인원 × 2` | 학생 1명이 최소 2회(마감 조회 + 등록) 호출한다 |
| `load-test.mjs` §0B | `register(개인) 한도(5)가 도배를 막을 만큼 작다` | 개인 버킷이 IP 만큼 크면 도배를 방치하는 것이다 |
| `load-test.mjs` §1B | `★ 같은 IP 의 200명 전원 201 신규 등록` | **핵심.** 여기서 429 가 나오면 마감 직전에 정상 교육생이 차단된다 |
| `load-test.mjs` §1B | `★ 429 rate limit 차단 0건` / `★ DB 등록 건수 == 200` | 유실 0건 |
| `load-test.mjs` §3A | `★ 같은 IP · 같은 사람 반복 → 429 차단` | 도배는 여전히 막힌다 |
| `load-test.mjs` §3B | `★ 도배 차단 중에도 같은 IP 의 다른 사람은 정상 등록(201)` | **옆자리 학생이 연대책임을 지지 않는다** |
| `load-test.mjs` §3C | `★ 단일 회선의 대량 폭주가 429 로 차단됨` | IP 버킷이 DDoS 방어 역할은 유지한다 |
| `integration-test.mjs` §3-1 | `★ 같은 IP · 다른 사람 → 201 통과` | 예전 한도(IP당 8회/분)에서 429 가 나던 바로 그 자리 |
| `integration-test.mjs` §3-3 | `★ 위조 IP 로 만들어진 rate limit 버킷이 없음` | `trusted-proxies` 가 비어 있으면 XFF 를 신뢰하지 않는다 |

**§1B 가 실패하면 한도가 아니라 설계를 의심하라.** 버킷을 IP 하나로만 만들면
NAT 뒤 200명은 어떤 숫자를 넣어도 결국 막힌다([docs/SPEC.md §8](SPEC.md)).

### 실행 규모 — 요청 수가 늘었다

시나리오가 늘어난 만큼 요청 수도 늘었다. 스크립트가 끝에 **시나리오별 요청 수와 소요**를 표로 찍는다.

```
        ── 시나리오별 요청 수 / 소요 ─────────────────────────────
        1.  IP 분산 기준선 (비현실적)             0건 ·    0.0초 ·          -  (건너뜀 — XFF 를 신뢰하지 않는 설정)
        1B. 공용 와이파이 (같은 IP·다른 사람)   400건 ·    6.2초 ·    65 req/s
        2.  동시 등록 경합 (멱등성)              20건 ·    0.4초 ·    52 req/s
        3A. 같은 사람 도배 (개인 버킷)            8건 ·    0.2초 ·    40 req/s
        3B. 옆자리 사용자 (같은 IP·다른 사람)     1건 ·    0.0초 ·         -
        3C. 단일 회선 폭주 (IP 버킷)           2201건 ·   14.8초 ·   149 req/s
        ──────────────────────────────────── ──────    ──────
        합계 (부하 요청)                       2635건 ·   21.7초
        전체 소요 (점검·정리 포함)                        38.4초
```

수치는 PC 성능에 따라 다르다. 보아야 할 것은 **429 가 어디에서 나고 어디에서 안 나는가** 다.

### `--same-ip` 는 이제 무슨 뜻인가

`--same-ip` 는 **§1(IP 분산 기준선)을 건너뛰는 스위치**다. "공격 모드"가 아니다.
공용 와이파이 시나리오는 플래그와 무관하게 항상 돈다.

또 하나 — **로컬에서는 `X-Forwarded-For` 가 무시된다.**
`imlate.rate-limit.trusted-proxies` 가 비어 있기 때문이다([SPEC §8.7](SPEC.md)).
그래서 스크립트가 헤더로 IP 를 나누려 해도 나뉘지 않고, 전원이 하나의 클라이언트로 취급된다.
스크립트는 이를 **Redis 버킷 키로 직접 확인**한 뒤 §1 을 자동으로 건너뛴다(헤더를 믿고 넘어가지 않는다).
즉 **로컬 기본 상태가 곧 공용 와이파이 조건**이다.

### 이 결함이 왜 기존 테스트를 전부 통과했는가 — 교훈

> **부하 테스트의 기본 시나리오가 실제 사용 환경과 정반대였다.**

`load-test.mjs` 는 요청마다 다른 `X-Forwarded-For`(10.77.x.x)를 붙여 200명을 **서로 다른 IP** 로
시뮬레이션했다. 그래서 "200명 동시 등록 전원 성공"이라는 초록색 결과가 나왔다.
그러나 실제 운영은 **전원이 같은 IP** 다. `--same-ip` 옵션이 있긴 했지만
"공격 시나리오"로 분류되어 기본 실행에서 빠져 있었고, 아무도 그것이 **정상 사용 환경**임을 눈치채지 못했다.

각 검증 층이 왜 이걸 못 봤는지 정리해 두면 다음에 같은 실수를 줄일 수 있다.

| 층 | 왜 못 봤는가 |
|---|---|
| ① 백엔드 단위 테스트 | 토큰 버킷이 "설정한 대로" 동작하는지만 봤다. **설정값 자체가 틀렸다**는 건 단위 테스트의 관심사가 아니었다 |
| ② 프론트 E2E | API 를 전부 목킹한다. 목이 429 를 안 주면 429 는 존재하지 않는다 |
| ③ 통합 테스트 | 한도 `8` 을 **하드코딩**해 두고 "9번째가 429 면 통과"라고 단언했다. 결함을 검증하고 있었던 셈이다 |
| ④ 부하 테스트 | 기본 시나리오가 현실과 반대. **가장 크게 책임이 있는 층** |
| ⑤ 장애 훈련 | Redis/MySQL 정지만 다뤘다. "정상 상태의 정상 사용자"는 훈련 대상이 아니었다 |
| ⑥ 수동 눈 검사 | 개발 PC 한 대에서 혼자 눌러 봤다. **혼자서는 200명 뒤의 9번째가 될 수 없다** |

다음에 같은 종류의 결함을 막기 위한 원칙 세 가지.

1. **테스트가 만드는 조건이 실제 사용 조건과 같은지 먼저 따진다.**
   "몇 명이 동시에" 만 맞추고 "어디에서" 를 틀리면 통과해도 의미가 없다.
2. **한도·임계값을 테스트에 하드코딩하지 않는다.** 두 스크립트는 이제
   `/actuator/env` 로 **기동 중인 앱의 실제 설정값**을 읽어 단언을 맞춘다.
   그래서 백엔드가 숫자를 바꿔도 테스트가 같이 거짓말하지 않는다.
3. **부등식으로 표현할 수 있는 요구는 설정 검사로 먼저 잡는다.**
   `register(IP) 한도 ≥ 교육생 규모` 는 요청을 한 건도 보내지 않고 확인할 수 있다(§0B / §3-0).

---

## 3. 시간에 얽힌 시나리오를 로컬에서 검증하는 법 ★가장 중요★

이 시스템의 심장은 **21:45 마감 → 21:50 발송 → 22:05/22:20 재시도** 다.
그런데 그 시각을 기다려서 테스트할 수는 없다. **설정만 바꿔서 몇 분 뒤에 재현**한다.

```
00:00 등록 시작 → 21:45 마감 → 21:50 발송 → (22:05 / 22:20 재시도)
                → 22:30 문 잠김 → 23:30 일괄 개방
```

> 마감 21:45 · 발송 21:50 은 운영자 요청으로 앞당겨진 값이다(원래 22:00 / 22:10).
> **통금 22:30 과 일괄 개방 23:30 은 바뀌지 않았다.** 아래 시나리오에서 두 값을 건드리지 마라.

### 3.1 만질 수 있는 스위치 (실제로 존재하는 것만)

| 환경변수 | 프로퍼티 이름 | 기본값 | 의미 |
|---|---|---|---|
| `IMLATE_TIMEZONE` | `imlate.timezone` | `Asia/Seoul` | 서비스 기준 시계 + **모든 cron 의 zone** |
| `IMLATE_REGISTRATION_CLOSE_TIME` | `imlate.registration.close-time` | `21:45` | 이 시각 **정각부터** 등록 거부(`409 REGISTRATION_CLOSED`) |
| `IMLATE_REGISTRATION_RETURN_TIME` | `imlate.registration.return-time` | `23:30` | 안내 문구용 복귀 시각 |
| `IMLATE_REGISTRATION_CURFEW_TIME` | `imlate.registration.curfew-time` | `22:30` | 안내 문구용 문 잠김 시각 |
| `IMLATE_NOTIFICATION_ENABLED` | `imlate.notification.enabled` | `true` | `false` 면 스케줄러가 아무것도 하지 않음(`skipReason=DISABLED`) |
| `IMLATE_NOTIFICATION_DISPATCH_CRON` | `imlate.notification.dispatch-cron` | `0 50 21 * * *` | **정기 발송** cron (6필드, 초 포함) = 21:50 |
| `IMLATE_NOTIFICATION_RETRY_CRON` | `imlate.notification.retry-cron` | `0 5,20 22 * * *` | **실패 채널 재시도** cron (6필드) = 22:05 / 22:20 |
| `IMLATE_NOTIFICATION_MAX_ATTEMPTS` | `imlate.notification.max-attempts` | `3` | 채널당 최대 시도 횟수(백오프 1s→2s→4s, 상한 8s) |
| `IMLATE_NOTIFICATION_LOCK_TTL_SECONDS` | `imlate.notification.lock-ttl-seconds` | `300` | 중복 발송 방지 분산 락 TTL |
| `IMLATE_STATS_SNAPSHOT_CRON` | `imlate.stats.snapshot-cron` | `0 5 0 * * *` | 전일 통계 확정 + 보존기간 정리 |
| (환경변수 없음) | `imlate.stats.today-snapshot-cron` | `0 55 23 * * *` | 당일 통계 선반영. **`--args` 로만 바꿀 수 있다** |

> **등록 시작 시각(`imlate.registration.open-time`)은 `00:00` 으로 고정되어 있고 환경변수가 없다.**
> "아직 열리지 않음(`REGISTRATION_NOT_OPEN`)"을 재현하려면 `--imlate.registration.open-time=23:59` 처럼
> **프로퍼티로만** 넘겨야 한다.

**cron 은 6필드(초 분 시 일 월 요일)** 다. 5필드로 쓰면 기동 시점에 실패한다.

| 쓰고 싶은 것 | cron |
|---|---|
| 매 분 0초마다 | `0 * * * * *` |
| 매 분 30초마다 | `30 * * * * *` |
| 2분마다 | `0 */2 * * * *` |
| 오늘 15:07:00 에 한 번 | `0 7 15 * * *` |

### 3.2 환경변수 vs `--args` — 어느 쪽을 쓸 것인가

두 방법 모두 유효하지만 성격이 다르다.

```powershell
# PowerShell — 창 B  ① 환경변수 방식 (프로덕션과 동일한 키를 쓰므로 "진짜" 검증에 가깝다)
$env:IMLATE_REGISTRATION_CLOSE_TIME = "21:30"
.\gradlew.bat bootRun --args='--spring.profiles.active=local'
```

```powershell
# PowerShell — 창 B  ② --args 방식 (우선순위 최상위. 확실하고, Gradle 데몬 캐시 영향을 안 받는다)
.\gradlew.bat bootRun --args='--spring.profiles.active=local --imlate.registration.close-time=21:30'
```

> **환경변수가 반영되지 않는 것 같으면** Gradle 데몬이 이전 환경을 물고 있는 것이다. 데몬을 죽이고 다시 띄운다.
>
> ```powershell
> # PowerShell — 창 B
> .\gradlew.bat --stop
> ```
>
> 그래도 미심쩍으면 ② `--args` 방식을 쓰거나, 아예 jar 로 띄워 데몬을 배제한다([§3.6](#36-데몬을-완전히-배제하고-싶을-때--jar-로-띄우기)).

> **`application-local.yml` 이 하드코딩한 값은 환경변수로 못 바꾼다.**
> `imlate.sms.provider`, `imlate.email.provider`, `imlate.notification.supervisors`,
> `imlate.rate-limit.enabled`, `imlate.lookup.token-ttl-hours` 는 local 프로파일에 리터럴로 박혀 있다.
> 이것들을 바꾸려면 **반드시 `--args` 프로퍼티**로 넘겨야 한다(명령행 인자가 프로파일 yml보다 우선한다).

### 3.3 시나리오 A — "21:45 마감"을 3분 뒤에 재현하기

```powershell
# PowerShell — 창 B  (백엔드를 Ctrl+C 로 멈춘 뒤)
$close = (Get-Date).AddMinutes(3).ToString('HH:mm')
$close                                                  # 예: 15:24  ← 눈으로 확인
$env:IMLATE_REGISTRATION_CLOSE_TIME = $close
.\gradlew.bat bootRun --args='--spring.profiles.active=local'
```

```bash
# Git Bash — 창 B (대안)
export IMLATE_REGISTRATION_CLOSE_TIME="$(date -d '+3 minutes' +'%H:%M')"
echo "$IMLATE_REGISTRATION_CLOSE_TIME"
./gradlew bootRun --args='--spring.profiles.active=local'
```

**확인 순서**

```powershell
# PowerShell — 창 D
# (1) 마감 전: open=true, secondsUntilClose 가 180 근처에서 줄어든다
curl.exe -s "http://localhost:8080/api/v1/registrations/window"

# (2) 마감 전 등록 → 201
curl.exe -s -o NUL -w "%{http_code}`n" -X POST "http://localhost:8080/api/v1/registrations" `
  -H "Content-Type: application/json" `
  --data-binary "{\"className\":\"1A\",\"studentName\":\"Test User\",\"roomNumber\":\"301\"}"

# (3) 마감 시각을 넘긴 뒤 같은 요청 → 409
```

**기대 결과**

| 시점 | `/registrations/window` | `POST /registrations` | 브라우저(5173) |
|---|---|---|---|
| 마감 전 | `open:true`, `secondsUntilClose>0` | `201` (재요청 시 `200` + `duplicate:true`) | 카운트다운 배지가 줄어듦 |
| 마감 정각 이후 | `open:false`, `secondsUntilClose:0` | `409` + `code:"REGISTRATION_CLOSED"` | "오늘 등록은 마감되었습니다" 경고 + 입력칸 비활성 + 버튼 문구 "등록 마감" |

> 프론트는 **서버 시간(`serverTime`)** 기준으로 카운트다운한다. PC 시계를 바꿔도 마감은 서버가 결정한다.
> 반대로 말하면, **마감 검증을 위해 Windows 시계를 만지지 마라.** 필요 없고, 다른 걸 다 망가뜨린다.

> **한글이 필요하면 curl 을 쓰지 마라.** Windows 콘솔 인코딩 때문에 `400 VALIDATION_FAILED` 가 난다.
> 위 예시가 `1A` / `Test User` 인 이유가 그것이다. 한글 검증은 브라우저나 `node scripts/integration-test.mjs` 로 한다([§8.4](#84-curl-로-한글을-보내면-400-validation_failed)).

### 3.4 시나리오 B — "21:50 발송"을 2분 뒤에 재현하기

가장 확실한 방법은 **매 분 발송**으로 걸어 두는 것이다. 첫 분에 발송되고, 다음 분부터는 중복 방지가 동작하는지까지 한 번에 볼 수 있다.

```powershell
# PowerShell — 창 B
$env:IMLATE_REGISTRATION_CLOSE_TIME = "23:59"      # 등록은 계속 열어 둔다
$env:IMLATE_NOTIFICATION_DISPATCH_CRON = "0 * * * * *"     # 매 분 0초
$env:IMLATE_NOTIFICATION_RETRY_CRON    = "30 * * * * *"    # 매 분 30초
.\gradlew.bat bootRun --args='--spring.profiles.active=local'
```

특정 시각 한 번만 쏘고 싶다면:

```powershell
# PowerShell — 창 B
$t = (Get-Date).AddMinutes(2)
$env:IMLATE_NOTIFICATION_DISPATCH_CRON = "0 $($t.Minute) $($t.Hour) * * *"
$env:IMLATE_NOTIFICATION_DISPATCH_CRON                     # 예: 0 26 15 * * *  ← 눈으로 확인
.\gradlew.bat bootRun --args='--spring.profiles.active=local'
```

```bash
# Git Bash — 창 B (대안)
export IMLATE_NOTIFICATION_DISPATCH_CRON="0 $(date -d '+2 minutes' +'%M %H') * * *"
echo "$IMLATE_NOTIFICATION_DISPATCH_CRON"
./gradlew bootRun --args='--spring.profiles.active=local'
```

**발송 대상이 있어야 발송이 일어난다.** 0명이면 `skipReason=NO_REGISTRATION` 으로 건너뛴다. 먼저 몇 명 등록해 둔다.

```powershell
# PowerShell — 창 D : 브라우저(5173)에서 2~3명 등록하거나, 통합 테스트를 --keep 으로 돌려 데이터를 남긴다
node scripts/integration-test.mjs --keep
```

**창 B 로그에서 확인할 것**

```
정기 사감 발송을 시작합니다. date=2026-08-05
Reconciliation CONSISTENT date=2026-08-05 db=13 wal=13 recovered=0
[NOOP-SMS] 실제 발송하지 않고 로그만 남깁니다.
  수신번호: 010****0001
  본문:
  (사감님이 받는 문자 원문이 그대로 찍힌다)
[NOOP-EMAIL] 실제 발송하지 않고 로그만 남깁니다.
SMS 발송 성공: to=010****0001, attempt=1/3, msgId=noop-sms-...
정기 발송 결과: date=..., 인원=13명, SMS 2/2 성공, EMAIL 2/2 성공
```

그 다음 분(같은 cron 이 다시 발화)에는:

```
정기 발송 건너뜀: date=..., 사유=ALREADY_SENT
```

**API 로도 같은 것을 확인한다**

```powershell
# PowerShell — 창 D
$K = @{ "X-Admin-Key" = "local-dev-admin-key" }
$D = (Get-Date).ToString('yyyy-MM-dd')

# 발송 이력
Invoke-RestMethod -Uri "http://localhost:8080/api/v1/admin/notifications?date=$D" -Headers $K | ConvertTo-Json -Depth 5

# 실제 발송 없이 문구만 렌더해 보기 (조회 링크가 여기 들어 있다)
Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/v1/admin/notifications/preview?date=$D" -Headers $K
```

### 3.5 스케줄러가 진짜 KST 로 도는지 확인하는 법

세 가지를 겹쳐서 본다. 하나만으로는 부족하다.

**(1) 기동 로그 — 서비스 시계**

```
Service clock initialized with zone=Asia/Seoul
```

`ClockConfig` 가 `imlate.timezone` 으로 `Clock` 을 만든 결과다. 앱의 모든 `LocalDate.now(clock)` 이 이걸 쓴다.

**(2) API 응답의 오프셋**

```powershell
# PowerShell — 창 D
curl.exe -s "http://localhost:8080/api/v1/registrations/window"
```

`serverTime` / `opensAt` / `closesAt` 이 모두 **`+09:00`** 으로 끝나야 한다.
(통합 테스트도 `서버 시간이 KST(+09:00)` 항목으로 이걸 검사한다.)

**(3) 실제로 바인딩된 cron 값 — Actuator**

`local` 프로파일은 `env` / `configprops` 엔드포인트를 열어 둔다.

```powershell
# PowerShell — 창 D
curl.exe -s "http://localhost:8080/actuator/env/imlate.notification.dispatch-cron"
curl.exe -s "http://localhost:8080/actuator/env/imlate.notification.retry-cron"
curl.exe -s "http://localhost:8080/actuator/env/imlate.registration.close-time"
curl.exe -s "http://localhost:8080/actuator/env/imlate.timezone"
```

여기서 **내가 준 값이 그대로 보이지 않으면 환경변수가 안 먹은 것**이다([§3.2](#32-환경변수-vs---args--어느-쪽을-쓸-것인가)의 데몬 주의).

**(4) zone 이 정말 cron 에 적용되는지 — 되돌리기 전제의 반증 실험(선택)**

스케줄러는 `@Scheduled(cron=..., zone="${imlate.timezone}")` 로 걸려 있다. PC 가 이미 KST 라 평소에는 zone 이 도는지 안 도는지 구분이 안 된다. 확실히 보고 싶다면 잠깐 다른 zone 으로 띄운다.

```powershell
# PowerShell — 창 B  ※ 확인 후 반드시 원복할 것
$env:IMLATE_TIMEZONE = "UTC"
.\gradlew.bat bootRun --args='--spring.profiles.active=local'
# 로그: Service clock initialized with zone=UTC
# /registrations/window 의 serverTime 오프셋이 +09:00 이 아니게 바뀐다
# → 시간 계산이 설정값을 실제로 따른다는 증거
```

```powershell
# PowerShell — 창 B  원복
Remove-Item Env:IMLATE_TIMEZONE
.\gradlew.bat bootRun --args='--spring.profiles.active=local'
```

> **원복을 잊지 마라.** UTC 로 켜 둔 채 통합 테스트를 돌리면 `서버 시간이 KST(+09:00)` 항목이 실패한다.

### 3.6 데몬을 완전히 배제하고 싶을 때 — jar 로 띄우기

시간 시나리오를 여러 번 반복하면 `bootRun` 재기동이 느리다. jar 로 띄우면 기동이 빠르고 환경변수도 정직하게 먹는다.

```powershell
# PowerShell — 창 B
cd C:\Users\kkh98\Desktop\skala-imlate\backend
.\gradlew.bat bootJar                                  # build\libs\imlate-1.0.0.jar

$env:IMLATE_DB_URL = "jdbc:mysql://localhost:13306/imlate?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul&characterEncoding=UTF-8"
$env:IMLATE_REDIS_PORT = "16379"
$env:IMLATE_NOTIFICATION_DISPATCH_CRON = "0 * * * * *"

java -jar build\libs\imlate-1.0.0.jar --spring.profiles.active=local
```

> `java -jar` 는 **PATH 의 java** 를 쓴다(이 PC 는 JDK 23). `JAVA_HOME`(JDK 17)과 다르다는 점만 인지하면 된다.
> jar 는 JDK 21 툴체인으로 빌드되므로 17 로 실행하면 `UnsupportedClassVersionError` 가 난다.

---

## 4. 장애 훈련 시나리오

각 항목은 **"무엇을 기대해야 하는가"** 를 먼저 읽고 나서 실행한다. 기대와 다르면 그게 버그다.

### 4.1 Redis 정지 중 등록 — **등록은 성공해야 한다** (가용성 우선 설계)

> 자동화되어 있다: `node scripts/integration-test.mjs --drills` 의 14번 섹션이 Redis 를 실제로 내렸다 올린다.
> 아래는 손으로 재현하며 로그를 눈으로 볼 때의 절차다.

```powershell
# PowerShell — 창 D
docker compose stop redis
# 또는: docker stop imlate-redis
```

브라우저(5173)에서 새 인원을 등록한다.

**기대 결과**

| 대상 | 기대 |
|---|---|
| `POST /registrations` | **201 성공** — Redis 가 죽어도 등록은 막지 않는다 |
| MySQL `return_registration` | 행이 정상 저장됨 (`wal_id` 는 채워지지만 WAL 실물은 없음) |
| 창 B 로그 | `WAL append failed — 등록은 계속 진행합니다(WAL 미기록).` (WARN) |
| `/actuator/health` | `status:"DOWN"`, `components.redis.status:"DOWN"` |
| `/actuator/health/alb` | **`status:"UP"` 유지** — alb 그룹은 `db, ping` 만 포함한다. ElastiCache 장애로 인스턴스를 서비스에서 빼면 오히려 전체가 죽으므로 일부러 제외했다 |
| rate limit | Redis 대신 인메모리 폴백(`local-fallback-permits-per-minute`, 기본 **1200** = global 과 같은 수준)으로 동작. `fail-open=true`. **개인 버킷도 이때는 사실상 무력화된다** — 등록을 막는 것보다 낫다는 판단이다 |
| 발송 락 | `발송 락을 사용할 수 없어 락 없이 진행합니다(DB 이력으로 중복 방지).` — 중복 발송은 `notification_dispatch` 의 SUCCESS 이력으로 막힌다 |
| 대사 | `Reconciliation skipped — Redis(WAL) unavailable` → 보고 status `WAL_UNAVAILABLE` |

복구:

```powershell
# PowerShell — 창 D
docker compose start redis
docker exec imlate-redis redis-cli ping        # PONG
```

**복구 후 기대** — Redis 장애 중 등록한 인원은 WAL 에 없으므로, 대사 결과에서 **`dbOnly`** 로 잡히고 상태는 `MISMATCH` 가 된다. **이건 정상이다.** DB 가 진실이고 WAL 은 보조 기록이므로 명단·발송에는 영향이 없다.

```powershell
# PowerShell — 창 D : 대사 결과 확인
$K = @{ "X-Admin-Key" = "local-dev-admin-key" }
$D = (Get-Date).ToString('yyyy-MM-dd')
Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/v1/admin/notifications/dispatch?date=$D&force=true" -Headers $K
docker exec imlate-redis redis-cli hlen "imlate:wal:$D"
```

### 4.2 MySQL 정지 중 등록 — **등록은 실패한다. 하지만 WAL 에 PENDING 으로 남아 대사에서 복구된다**

이건 Redis 와 정반대다. **DB 는 이 시스템의 필수 의존성**이라 등록 요청 자체는 실패한다.
다만 기숙사 도메인에서 "명단 누락 = 교육생이 밖에서 밤을 샌다" 이므로,
**등록 의도만은 잃지 않도록** WAL 기록을 중복 선행 조회(DB READ)보다 **앞**에 둔다(SPEC §5.2 R7 쓰기 순서).

> 자동화되어 있다: `node scripts/integration-test.mjs --drills` 의 **15번 섹션**이 MySQL 을 실제로 내렸다 올리며,
> 500 응답 → WAL `PENDING` 잔존 → 대사 복구 → 사감 명단 포함 → 통계 +1 까지 한 번에 검증한다.
> 아래는 손으로 재현하며 로그를 눈으로 볼 때의 절차다.

```powershell
# PowerShell — 창 D
docker compose stop mysql
```

브라우저나 curl 로 등록을 시도한다.

**기대 결과 — 그리고 왜 그런가**

| 대상 | 기대 | 근거 |
|---|---|---|
| `POST /registrations` | **500** + `code:"INTERNAL_ERROR"`, 메시지 "일시적인 오류가 발생했습니다…" | `RegistrationService.register()` 의 중복 선행 조회(`findExisting`)에서 터진 `DataAccessException` 은 업무 예외가 아니므로 `GlobalExceptionHandler` 의 마지막 핸들러가 500 으로 변환한다. **사용자 응답 문구는 예전과 같다** |
| Redis WAL | **해당 건이 `status:"PENDING"` 으로 남는다** | WAL append(3단계)가 중복 선행 조회(4단계)보다 **앞**에 있다. DB 접근 실패는 `FAILED` 로 덮어쓰지 않고 `PENDING` 그대로 둔다 — 그래야 대사가 "최초 INSERT 가 실패한 건"으로 보고 통계까지 재집계한다 |
| 21:50 대사 | **DB 로 복구된다** | `ReconciliationService` 는 WAL 상태가 아니라 **DB 존재 여부**로 복구를 판단한다. 복구된 인원은 사감 명단·문자·메일에 정상 포함된다 |
| 등록 통계 | 복구 시점에 **+1** | WAL 상태가 `COMMITTED` 가 아니므로 `countAsNewRegistration=true` — 최초 INSERT 가 실패해 아직 집계되지 않았던 건이다 |
| `/actuator/health/alb` | **`DOWN` (HTTP 503)** | alb 그룹에 `db` 가 포함되어 있다. 운영에서는 ALB 가 이 인스턴스를 타깃에서 빼는 것이 의도된 동작 |
| `GET /registrations/window` | **200 정상** | DB 를 쓰지 않는다(시계 계산만) |
| `GET /registrations/summary` | 500 | 인원 수를 세려면 DB 가 필요 |
| 프론트 화면 | 창 B 로그에 `Unhandled exception path=/api/v1/registrations type=org.springframework.dao...`, 화면에는 "서버에 일시적인 문제가 발생했습니다." | `client.ts` 의 `INTERNAL_ERROR` 폴백 문구 |

**DB 가 죽어 있는 동안 WAL 을 직접 들여다본다** — 이게 이 훈련의 핵심 증거다.

```powershell
# PowerShell — 창 D  (MySQL 이 멈춰 있는 상태에서)
$D = (Get-Date).ToString('yyyy-MM-dd')
docker exec imlate-redis redis-cli hvals "imlate:wal:$D"
# → 방금 실패한 등록의 반/이름/호수가 보이고, 그 항목의 "status" 가 "PENDING" 이어야 한다
```

복구:

```powershell
# PowerShell — 창 D
docker compose start mysql
docker compose ps                                # (healthy) 까지 대기
curl.exe -s http://localhost:8080/actuator/health/alb        # UP 으로 돌아올 때까지(커넥션 풀 재생성에 몇 초)

# 21:50 대사를 손으로 돌려 복구를 확인한다
$K = @{ "X-Admin-Key" = "local-dev-admin-key" }
Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/v1/admin/notifications/dispatch?date=$D&force=true" -Headers $K
docker exec imlate-mysql mysql -uimlate -pimlate -D imlate -e "SELECT class_name,student_name,room_number,wal_id FROM return_registration WHERE registration_date=CURDATE();"
```

창 B 로그에 `Recovered missing registration from WAL id=... walId=...` (WARN) 이 찍히고,
DB 에 그 인원의 행이 **장애 중 남긴 것과 같은 `wal_id`** 로 생겨 있어야 한다.

> **WAL 원시 항목 수가 DB 행 수보다 많은 것은 정상이다.**
> WAL 을 중복 선행 조회보다 먼저 쓰므로, 같은 사람이 재제출할 때마다 `walId` 가 하나씩 더 쌓인다.
> `HLEN imlate:wal:{date}` 가 DB 행 수보다 커도 사고가 아니다. 대사는 `personKey`(`날짜|반|이름|호수`)로
> 중복을 제거해 세므로, 사감에게 가는 "DB N / WAL N" 표기는 영향을 받지 않는다.

> **이 훈련의 결론을 문서로 남겨 둘 것** — WAL 은 이제 **세 가지**를 지켜 준다.
> ① **DB 가 완전히 죽어 등록이 500 으로 실패한 경우**(→ `PENDING` 으로 남았다가 대사에서 복구, 통계 +1),
> ② **WAL 은 썼는데 DB INSERT 가 실패한 경우**(→ 같은 `PENDING` 복구 경로),
> ③ **DB 에 들어갔다가 행이 유실된 경우**(→ `COMMITTED` 복구, 통계는 재집계하지 않음).
> 통합 테스트 7·8번 섹션이 ②③을, `--drills` 의 15번 섹션이 ①을 검증한다.
>
> 다만 **"등록이 성공한다"는 뜻은 아니다.** 사용자는 500 을 받고 재시도해야 한다.
> 재시도해도 `personKey` 기준 멱등이라 중복 행은 생기지 않는다.
> 그리고 **Redis 까지 같이 죽으면 이 안전망도 없다**(WAL append 가 WARN 만 남기고 지나간다).

### 4.3 알리고 / SES 실패 시 재시도·이력 기록

실제 계정 없이도 **"발송기가 실패했을 때의 경로"** 를 완전히 재현할 수 있다.
Aligo 발송기를 켜되 API 키를 비워 두면, 네트워크를 타기도 전에 `SendResult.fail(...)` 이 나온다.

```powershell
# PowerShell — 창 B  (Ctrl+C 후 재기동)
# imlate.sms.provider 는 application-local.yml 에 리터럴 noop 이므로 --args 로만 덮을 수 있다
.\gradlew.bat bootRun --args='--spring.profiles.active=local --imlate.sms.provider=aligo'
```

기동 로그에 `Aligo RestClient 를 생성합니다.` 가 보이면 발송기가 바뀐 것이다. 이제 강제 발송한다.

```powershell
# PowerShell — 창 D
$K = @{ "X-Admin-Key" = "local-dev-admin-key" }
$D = (Get-Date).ToString('yyyy-MM-dd')
Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/v1/admin/notifications/dispatch?date=$D&force=true" -Headers $K
```

**기대 결과**

| 대상 | 기대 |
|---|---|
| 응답 | `smsSuccess:0`, `smsFailed:2`(사감 2명), `emailSuccess:2`, `emailFailed:0` — **한 채널이 죽어도 다른 채널은 나간다** |
| 창 B 로그 | `SMS 발송 실패: to=010****0001, attempt=1/3, reason=Aligo 설정(api-key / user-id / sender)이 비어 있어 발송할 수 없습니다.` 가 **attempt 1/3 → 2/3 → 3/3** 으로 세 번, 사이에 1초·2초 백오프 |
| 마지막 로그 | `SMS 발송 최종 실패: to=..., date=..., reason=...` |
| `notification_dispatch` | 채널·수신처당 **FAILED 행 1건**(중간 시도마다 쌓이지 않는다. `attempt` 컬럼에 최종 시도 횟수 3이 기록됨) |
| 스케줄러 스레드 | 죽지 않는다. 모든 예외를 `safeSend` 가 흡수한다 |

```powershell
# PowerShell — 창 D : 이력 확인
docker exec imlate-mysql mysql -uimlate -pimlate -D imlate -e "SELECT channel,recipient_name,status,attempt,LEFT(error_message,60) AS err FROM notification_dispatch WHERE dispatch_date=CURDATE();"
```

**이어서 재시도 경로를 검증한다.** 백엔드를 다시 `noop` 으로(= 발송기가 복구된 상황) 띄운 뒤:

```powershell
# PowerShell — 창 B
.\gradlew.bat bootRun --args='--spring.profiles.active=local'
```

```powershell
# PowerShell — 창 D
Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/v1/admin/notifications/retry?date=$D" -Headers $K
```

| 대상 | 기대 |
|---|---|
| 응답 | `smsSuccess:2` — **실패한 SMS 채널만** 다시 보낸다 |
| 이미 성공한 EMAIL | 다시 보내지 않는다(`pendingFailures` 가 성공 이력을 제외한다) |
| 실패 이력이 없는 상태에서 재시도 | `skipped:true`, `skipReason:"NO_FAILURE"` |
| `notification_dispatch` | 기존 FAILED 행은 남고, SUCCESS 행이 추가된다(이력은 감사 목적이라 지우지 않는다) |

> 실제 Aligo/SES 자격증명으로 하는 검증은 [§7 실발송 리허설](#7-실발송-리허설-마지막-관문) 로 미룬다. 여기서는 **경로**만 본다.

### 4.4 발송 중복 방지 확인 — 두 겹의 방어를 각각 본다

이 시스템은 중복 발송을 **두 겹**으로 막는다. 하나씩 따로 확인해야 의미가 있다.

**(a) DB 이력 기반 — `ALREADY_SENT`**

```powershell
# PowerShell — 창 D
$K = @{ "X-Admin-Key" = "local-dev-admin-key" }
$D = (Get-Date).ToString('yyyy-MM-dd')

Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/v1/admin/notifications/dispatch?date=$D&force=true" -Headers $K   # 실제 발송
Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/v1/admin/notifications/dispatch?date=$D" -Headers $K              # force 없이 재요청
```

기대: 두 번째 응답이 `skipped:true`, `skipReason:"ALREADY_SENT"`. 로그에 `이미 성공한 발송 이력이 있어 재발송하지 않습니다.`

**(b) Redis 분산 락 기반 — `LOCK_NOT_ACQUIRED`**

멀티 인스턴스를 흉내 내기 위해 **락 키를 남이 잡고 있는 상태**를 직접 만든다.

```powershell
# PowerShell — 창 D
$D = (Get-Date).ToString('yyyy-MM-dd')

# 다른 인스턴스가 락을 잡은 상태를 위조
docker exec imlate-redis redis-cli set "imlate:lock:dispatch:$D" other-instance EX 120 NX

# 이 상태에서 발송을 시도한다
Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/v1/admin/notifications/dispatch?date=$D&force=true" -Headers $K

# 락이 그대로 살아 있는지 — 남의 락을 지우면 안 된다
docker exec imlate-redis redis-cli get "imlate:lock:dispatch:$D"
docker exec imlate-redis redis-cli ttl "imlate:lock:dispatch:$D"

# 정리
docker exec imlate-redis redis-cli del "imlate:lock:dispatch:$D"
```

| 대상 | 기대 |
|---|---|
| 응답 | `skipped:true`, `skipReason:"LOCK_NOT_ACQUIRED"` |
| 창 B 로그 | `다른 인스턴스가 이미 발송 중이라 건너뜁니다. date=...` |
| 락 키 | **`other-instance` 그대로, TTL 도 유지** — 해제 스크립트가 "내가 넣은 값일 때만" 지우기 때문 |
| `notification_dispatch` | 새 행이 생기지 않는다 |

**(c) 0명일 때는 아예 안 보낸다**

```powershell
# PowerShell — 창 D
Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/v1/admin/notifications/dispatch?date=2020-01-01&force=true" -Headers $K
```

기대: `skipped:true`, `skipReason:"NO_REGISTRATION"`, 이력 행도 남지 않음.

### 4.5 훈련 뒤 정리

```powershell
# PowerShell — 창 D
docker compose start mysql redis
docker exec imlate-redis redis-cli flushall
docker exec imlate-mysql mysql -uimlate -pimlate -D imlate -e "DELETE FROM return_registration; DELETE FROM notification_dispatch; DELETE FROM daily_stat;"
node scripts/integration-test.mjs           # 깨끗한 상태에서 전 구간 재확인
```

완전히 초기화하고 싶으면 볼륨까지 지운다(Flyway 가 스키마를 다시 만든다).

```powershell
# PowerShell — 창 A
docker compose down -v
docker compose up -d
```

---

## 5. 수동 눈 검사 체크리스트

Playwright 90개가 레이아웃·문구를 이미 본다. 여기서는 **자동화가 못 보는 것**과 **사람이 실제로 겪는 흐름**만 본다.
브라우저: <http://localhost:5173>

### 5.1 등록 화면 (`/`)

- [ ] **반응형** — DevTools(F12) → 디바이스 툴바로 **320 / 375 / 768 / 1280 / 1920px** 을 차례로 본다.
      가로 스크롤바가 생기면 실패. 320px 에서 버튼 글자가 잘리지 않는지.
- [ ] **실기기** — 같은 Wi‑Fi 의 휴대폰에서 `http://<PC의 IP>:5173` 으로 접속(Vite `strictPort: 5173`).
      Windows 방화벽 인바운드 허용이 필요할 수 있다. 실제 손가락으로 눌러야 보이는 문제가 있다.
- [ ] **이전 입력값 기억(R6)** — 한 번 등록 → 탭을 닫았다 다시 연다.
      반/이름/호수가 채워져 있고, 화면 낭독기용 상태 문구가 "이전에 입력한 정보를 불러왔습니다."
- [ ] **최근 3건 제안** — 서로 다른 3명 이상을 등록한 뒤, 각 입력칸을 클릭하면 `datalist` 제안이 뜬다.
- [ ] **저장 정보 삭제** — "저장된 정보 지우기" 버튼 → 입력칸이 비고, 새로고침해도 다시 안 채워진다.
      DevTools → Application → Local Storage 에서 `imlate.lastInput` / `imlate.recentInputs` 가 사라졌는지 확인.
- [ ] **카운트다운** — 배지의 남은 시간이 1초씩 줄어든다. **PC 시계를 5분 틀어 놓아도** 서버 시간 기준으로 맞게 나온다.
- [ ] **마감 안내** — [§3.3](#33-시나리오-a--2200-마감을-3분-뒤에-재현하기) 으로 마감을 넘긴다.
      경고 박스 "오늘 등록은 마감되었습니다 (HH:mm)" + 입력칸 3개 비활성 + 버튼 문구 "등록 마감" + "등록 시간 다시 확인" 버튼 동작.
- [ ] **중복 안내** — 방금 등록한 것과 **똑같은** 반/이름/호수로 다시 제출 → 오류가 아니라 "이미 등록되어 있습니다."
      결과 카드가 뜨고, 등록 인원 수가 늘지 않는다.
- [ ] **공백 정규화** — `" 1반 "` 처럼 앞뒤 공백을 넣어도 같은 사람으로 인식(중복 처리)된다.
- [ ] **429 안내** — **같은 인원(반·이름·호수를 그대로)** 으로 6회 이상 다시 제출한다.
      개인 버킷(`register-person`, 기본 5회/분)에 걸려 빨간 안내가 뜬다 —
      **N 초가 실제로 표시되는지**, 그리고 문구가 "같은 정보로 너무 자주…" 인지가 포인트.
      한도값은 `curl.exe -s http://localhost:8080/actuator/env/imlate.rate-limit.register-person.capacity` 로 확인한다.
- [ ] **429 가 나오면 안 되는 경우** ★ — **서로 다른 인원**을 20명 이상 연속으로 빠르게 등록한다.
      한 대의 PC = 한 IP 이므로 이것이 곧 공용 와이파이 조건이다. **끝까지 전원 성공해야 한다.**
      여기서 429 가 나오면 마감 직전 기숙사에서 정상 교육생이 막힌다는 뜻이다([§2.5](#25-공용-와이파이-환경-검증-반드시-본다)).
- [ ] **입력 검증** — 이름에 `<script>` , 21자 이상, 이모지를 넣어 본다. 제출 전에 필드 아래 빨간 문구가 뜨고 첫 오류 칸으로 포커스가 간다.
- [ ] **네트워크 오류** — 창 B(백엔드)를 Ctrl+C 로 끈 채 등록 → "네트워크에 연결할 수 없습니다…" (하얀 화면이나 콘솔 에러가 아니라 **사람 문구**여야 한다)
- [ ] **키보드만으로** — Tab 만으로 반→이름→호수→버튼까지 이동하고 Enter 로 제출된다.
- [ ] **다크 모드** — Windows 설정 → 색 → 다크 모드로 전환 후 새로고침. 대비가 무너지지 않는지.

### 5.2 사감 조회 화면 (`/lookup`)

토큰이 필요하다. 관리 API preview 로 링크를 얻는다.

```powershell
# PowerShell — 창 D
$K = @{ "X-Admin-Key" = "local-dev-admin-key" }
(Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/v1/admin/notifications/preview" -Headers $K).lookupUrl
```

출력된 `http://localhost:5173/lookup?date=...&token=...` 을 브라우저에 붙여넣는다.

- [ ] **명단** — 반→이름 순으로 번호가 1부터 붙어 있고, 반/이름/호수/등록시각이 다 보인다.
- [ ] **모바일 카드 전환** — 375px 로 줄이면 표가 카드 형태로 바뀌고 가로 스크롤이 없다.
- [ ] **검색/필터** — 이름 일부를 입력하면 즉시 걸러진다.
- [ ] **반별 요약** — 반별 인원 합이 총원과 같다.
- [ ] **대사 배지** — `CONSISTENT` / `RECOVERED` / `MISMATCH` / `WAL_UNAVAILABLE` 이 사람이 읽을 수 있게 표시된다.
      [§4.1](#41-redis-정지-중-등록--등록은-성공해야-한다-가용성-우선-설계) 훈련 후에 다시 보면 상태가 바뀌어 있어야 한다.
- [ ] **인쇄** ★ — "인쇄" 버튼 → **인쇄 미리보기에서**:
      - 검색 행·버튼 등 `no-print` 요소가 **빠져** 있다
      - 명단이 페이지 경계에서 잘리지 않는다
      - 배경이 어둡게 인쇄되지 않는다(잉크 낭비)
      - **한글이 깨지지 않는다**
      - PDF 로 저장해서 실제 파일도 열어 본다(사감님이 그렇게 쓸 수 있다)
- [ ] **토큰 보안** — URL 의 `token=` 뒤 한 글자를 바꾸면 "조회 권한이 없습니다…" (403). 빈 화면이 아니라 안내 문구여야 한다.
- [ ] **다른 날짜** — `date=` 를 어제로 바꾸면 403(토큰은 날짜별로 서명된다).

### 5.3 문자·메일 문구 (창 B 로그 또는 preview API)

- [ ] 문자 본문에 **총원 N명**, **23:30**, **22:30** 이 들어 있다.
- [ ] 문자에 조회 URL 이 들어 있고, 그 URL 이 실제로 열린다.
- [ ] 이메일 텍스트 본문에 **반·이름·호수 전원**이 들어 있다.
- [ ] 이메일에 대사(검증) 결과가 한 줄 들어 있다.
- [ ] **치환문자(`?`, `□`)가 하나도 없다** — 한글 인코딩 사고 조기 발견.
- [ ] 문자 길이가 90바이트를 넘으면 LMS 로 전환된다(제목 필수). 인원이 많은 날을 가정해 확인.

---

## 6. 프로덕션 전 최종 점검표

배포 당일 아침에 이 목록을 위에서부터 그대로 훑는다.

### 6.1 자동 검증

- [ ] `cd backend; .\gradlew.bat test` — **전부 통과** (실패 0건)
- [ ] `cd frontend; npm run typecheck` — 오류 0
- [ ] `cd frontend; npm run test:e2e` — **90개 전부 통과**
- [ ] `cd frontend; npm run build` — 빌드 성공(`dist/` 생성)
- [ ] `node scripts/integration-test.mjs` — **실패 0건**
- [ ] `node scripts/integration-test.mjs --drills` — **실패 0건**
- [ ] `node scripts/load-test.mjs` — **실패 0건**, **rate limit 을 켠 채로**. 목표 지연/에러율 충족
- [ ] `cd infra\terraform; terraform fmt -check; terraform validate`

**공용 와이파이(rate limit 2단) — 별도로 눈으로 확인한다** ([§2.5](#25-공용-와이파이-환경-검증-반드시-본다))

- [ ] 기동 로그의 `rate limit 인터셉터 등록:` 줄에서 `register` 한도가 **교육생 규모(200)보다 크다**
- [ ] 같은 줄에서 `register-person` 한도가 **`register` 한도보다 훨씬 작다**
- [ ] `load-test.mjs §1B` — `★ 같은 IP 의 200명 전원 201 신규 등록` / `★ 429 rate limit 차단 0건`
- [ ] `load-test.mjs §3A/§3B` — 같은 사람 도배는 429, **같은 IP 의 다른 사람은 201**
- [ ] `load-test.mjs §3C` — 단일 회선 대량 폭주는 여전히 429 (DDoS 방어 유지)
- [ ] `integration-test.mjs §3-3` — **위조 `X-Forwarded-For` 로 만들어진 버킷이 없다**
- [ ] 운영 배포용 `trusted-proxies` 값이 ALB 사설 대역으로 채워져 있다(로컬은 빈 목록이 정상)

### 6.2 시간 시나리오 (§3)

- [ ] `integration-test.mjs §1` — **시각 정합성 단언 전부 통과**
      (`opensAt < closesAt < 통금(22:30) < 복귀(23:30)`, 발송 cron 이 마감 뒤·통금 앞, 재시도가 발송 뒤)
- [ ] `window` 응답의 `closesAt` 이 **21:45** 이고 `curfewTime` 22:30 · `returnTime` 23:30 은 그대로다
- [ ] 마감 시각을 앞당겨 **409 REGISTRATION_CLOSED** 를 눈으로 확인했다
- [ ] 발송 cron 을 앞당겨 **스케줄러가 실제로 발화**하는 것을 로그로 확인했다
- [ ] 같은 cron 이 두 번 발화했을 때 **ALREADY_SENT** 로 건너뛰는 것을 확인했다
- [ ] 재시도 cron 발화를 확인했다
- [ ] `serverTime` 이 `+09:00` 이고 `Service clock initialized with zone=Asia/Seoul` 로그를 확인했다
- [ ] **실험용으로 바꿨던 환경변수를 전부 원복했다** (`IMLATE_TIMEZONE`, `*_CRON`, `*_CLOSE_TIME`)

**문구 / 멱등성 (운영자가 명시적으로 확인을 요청한 항목)**

- [ ] `integration-test.mjs §10` — 문자·메일 모두에 **수신 전용(답장 불가) 안내**가 들어 있다
- [ ] `integration-test.mjs §10` — 문자·메일 모두에 **문의처(SKALA 운영진 / khdev07@naver.com)** 가 들어 있다
- [ ] `integration-test.mjs §5` — **같은 사람 5회 재제출 → DB 행 1건, 2번째부터 200 + `duplicate=true`, `id` 불변**

### 6.3 장애 훈련 (§4)

- [ ] Redis 정지 중 등록 201, `/actuator/health/alb` UP 유지
- [ ] MySQL 정지 중 등록 500 + `/actuator/health/alb` DOWN(503) — **의도된 동작임을 이해했다**
- [ ] MySQL 정지 중 등록분이 **WAL 에 `PENDING` 으로 남고**, DB 복구 후 대사에서 DB 로 복구되며 등록 통계가 +1 되는 것을 확인했다 ([§4.2](#42-mysql-정지-중-등록--등록은-실패한다-하지만-wal-에-pending-으로-남아-대사에서-복구된다))
- [ ] 발송 실패 시 3회 재시도 후 FAILED 이력, `retry` 로 실패 채널만 재발송
- [ ] 락이 잡혀 있을 때 `LOCK_NOT_ACQUIRED`, 남의 락을 지우지 않음

### 6.4 눈 검사 (§5)

- [ ] 등록 화면 체크리스트 전부
- [ ] 조회 화면 체크리스트 전부 (**인쇄 PDF 실물 확인 포함**)
- [ ] 문자·메일 문구에 치환문자 없음

### 6.5 설정 / 시크릿 — 배포 전 마지막으로

- [ ] `git status` 가 깨끗하고, `config/application-secret.yml` · `*.tfvars` · `.env` 가 커밋되지 않았다
- [ ] `IMLATE_LOOKUP_TOKEN_SECRET` 이 **운영용 랜덤 값**이다 (`local-dev-lookup-secret-change-me` 가 아니다)
- [ ] `IMLATE_ADMIN_API_KEY` 가 **운영용 랜덤 값**이다 (`local-dev-admin-key` 가 아니다)
      — 비워 두면 관리 API 가 전부 401 이 되어 수동 발송·재시도를 못 한다
- [ ] `IMLATE_SUPERVISOR1_*` / `IMLATE_SUPERVISOR2_*` 에 **실제 사감 연락처**가 들어 있다
- [ ] `IMLATE_LOOKUP_BASE_URL` 이 운영 도메인이다 (문자 속 조회 링크가 `localhost:5173` 이면 사고)
- [ ] `IMLATE_WEB_ALLOWED_ORIGIN_1` 이 운영 도메인이다
- [ ] `IMLATE_ALIGO_TEST_MODE` — 리허설은 `true`, 실제 운영 전환 시 `false`
- [ ] `IMLATE_SES_FROM` 이 **검증된** SES 아이덴티티이고, 샌드박스면 수신 사감 메일도 검증되어 있다
- [ ] 운영은 `spring.profiles.active=prod` 이며, `application-prod.yml` 의 필수 환경변수가 **전부** 채워졌다(하나라도 없으면 기동 실패)
- [ ] `ddl-auto=validate` · Flyway `clean-disabled=true` 확인 (prod 프로파일 기본값)
- [ ] `IMLATE_REDIS_SSL_ENABLED` 가 ElastiCache 설정과 일치한다(prod 기본 `true`)
- [ ] `IMLATE_NOTIFICATION_ENABLED=true` (테스트하느라 `false` 로 둔 채 배포하는 사고 방지)

### 6.6 배포 직후 (참고)

- [ ] [docs/DEPLOYMENT.md](DEPLOYMENT.md) §5 "배포 후 확인" 절차 수행
- [ ] [docs/OPERATIONS.md](OPERATIONS.md) §1 일일 운영 체크리스트로 첫날 21:45 마감 → 21:50 발송을 지켜본다

---

## 7. 실발송 리허설 (마지막 관문)

`noop` 으로는 **"진짜 문자가 도착하는가"** 를 절대 알 수 없다. 배포 전에 한 번은 진짜로 보내 봐야 한다.

로컬에서 실발송을 하려면 `application-local.yml` 이 리터럴로 박아 둔 값들(발송기, 사감 목록)을 덮어야 하므로,
**프로퍼티를 `--args` 로 전부 넘긴다.**

> **주의 — 리스트 프로퍼티는 소스끼리 병합되지 않는다.** `supervisors` 를 명령행에서 덮을 때는
> **한 사람의 name/phone/email 을 모두** 적어야 한다. 하나만 적으면 나머지가 비어 버린다.

```powershell
# PowerShell — 창 B  ※ 실제 문자·메일이 나갑니다. 수신 번호는 본인 번호로.
$env:IMLATE_DB_URL = "jdbc:mysql://localhost:13306/imlate?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul&characterEncoding=UTF-8"
$env:IMLATE_REDIS_PORT = "16379"
$env:IMLATE_ALIGO_API_KEY = "<발급받은 키>"
$env:IMLATE_ALIGO_USER_ID = "<알리고 아이디>"
$env:IMLATE_ALIGO_SENDER  = "<사전 등록한 발신번호>"
$env:IMLATE_SES_REGION    = "ap-northeast-2"
$env:IMLATE_SES_FROM      = "<SES 에서 검증된 발신 주소>"

.\gradlew.bat bootRun --args='--spring.profiles.active=local --imlate.sms.provider=aligo --imlate.email.provider=ses --imlate.sms.aligo.test-mode=true --imlate.notification.supervisors[0].name=리허설 --imlate.notification.supervisors[0].phone=010XXXXXXXX --imlate.notification.supervisors[0].email=me@example.com'
```

- `--imlate.sms.aligo.test-mode=true` — Aligo **테스트 모드**. 과금·실제 단말 도달 없이 API 응답만 확인한다.
  진짜 단말 수신까지 보려면 `false` 로 한 번 더(**본인 번호로만**).
- SES 는 `DefaultCredentialsProvider` 체인을 쓴다. `aws configure` 프로필이나 `AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY` 가 필요하다.
  SES 샌드박스면 **수신 주소도 검증**되어 있어야 한다.

```powershell
# PowerShell — 창 D : 등록 몇 건 만들고 강제 발송
node scripts/integration-test.mjs --keep
$K = @{ "X-Admin-Key" = "local-dev-admin-key" }
$D = (Get-Date).ToString('yyyy-MM-dd')
Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/v1/admin/notifications/dispatch?date=$D&force=true" -Headers $K
```

**확인할 것**

- [ ] 문자가 **도착**했다 (90바이트 초과 시 LMS 로 전환되어 제목이 붙는다)
- [ ] 문자 안의 조회 링크를 **휴대폰에서 눌러** 열린다
- [ ] 메일이 도착했고 **스팸함이 아니다**
- [ ] 메일 HTML 과 텍스트 본문 모두 한글이 정상이다
- [ ] `notification_dispatch` 에 `provider_message_id` 가 기록되었다
- [ ] 리허설이 끝나면 **환경변수를 지우고 창을 닫는다** (다음 실행에서 실수로 또 나가지 않도록)

```powershell
# PowerShell — 창 B : 리허설 정리
Remove-Item Env:IMLATE_ALIGO_API_KEY, Env:IMLATE_ALIGO_USER_ID, Env:IMLATE_ALIGO_SENDER, Env:IMLATE_SES_FROM -ErrorAction SilentlyContinue
```

---

## 8. 자주 만나는 오류와 해결

### 8.1 `docker compose up -d` 가 포트 충돌로 실패한다

```
Error response from daemon: ... Bind for 0.0.0.0:3306 failed: port is already allocated
```

이 PC 에는 **이미 MySQL(3306)·Redis(6379)가 설치되어 실행 중**이다. 기존 서비스를 끄지 말고 컨테이너 포트를 옮긴다.
프로젝트 루트 `.env` 에 이미 그렇게 되어 있다.

```powershell
# PowerShell — 창 A : 확인
type C:\Users\kkh98\Desktop\skala-imlate\.env      # IMLATE_MYSQL_PORT=13306 / IMLATE_REDIS_PORT=16379
docker compose config | Select-String "13306|16379"

# 누가 3306 을 쓰는지 보고 싶으면
netstat -ano | Select-String ":3306" | Select-Object -First 3
```

그리고 **앱에도 같은 포트를 알려야 한다**. 이걸 빠뜨리면 다음 증상이 난다.

| 증상 | 원인 |
|---|---|
| 기동은 되는데 `Table 'imlate.return_registration' doesn't exist` 또는 Flyway 오류 | `IMLATE_DB_URL` 을 안 줘서 **PC 에 원래 있던 3306 MySQL** 에 붙었다 |
| WAL/통계/rate limit 이 이상하게 동작 | `IMLATE_REDIS_PORT` 를 안 줘서 원래 있던 6379 Redis 에 붙었다 |

```powershell
# PowerShell — 창 B : 어디에 붙었는지 확인
curl.exe -s "http://localhost:8080/actuator/env/spring.datasource.url"
curl.exe -s "http://localhost:8080/actuator/env/spring.data.redis.port"
```

### 8.2 Docker Desktop 이 안 떠 있다

```
error during connect: ... The system cannot find the file specified.
docker: error during connect: Post "http://%2F%2F.%2Fpipe%2FdockerDesktopLinuxEngine/...
```

Docker Desktop 을 실행하고 트레이 아이콘이 초록색이 될 때까지 기다린다. 그 다음:

```powershell
# PowerShell — 창 A
docker info | Select-String "Server Version"
docker compose up -d
```

통합 테스트도 `docker exec` 를 쓰므로 **Docker 가 없으면 사전 점검에서 종료 코드 2 로 멈춘다**
(`MySQL 컨테이너(imlate-mysql)에 접근할 수 없습니다.`).

### 8.3 `JAVA_HOME` 이 JDK 17 이라 빌드가 안 될까 봐 걱정된다 — 안 그렇다

이 PC 는 `JAVA_HOME=C:\Program Files\Java\jdk-17`, PATH 의 `java` 는 23 이다. **그래도 정상 동작한다.**

- `backend/build.gradle` 이 `toolchain { languageVersion = 21 }` 로 **컴파일·실행 JVM 을 21 로 고정**한다.
- 로컬에 JDK 21 이 없으면 `settings.gradle` 의 foojay resolver 가 **자동으로 내려받는다**.
  → 첫 `gradlew` 실행이 유난히 오래 걸리는 이유가 이것이다(네트워크 필요).

문제가 나는 경우는 두 가지뿐이다.

| 증상 | 원인 / 해결 |
|---|---|
| `No matching toolchains found for requested specification: {languageVersion=21}` + 다운로드 실패 | 오프라인/프록시. JDK 21 을 직접 설치하거나 `.\gradlew.bat -Porg.gradle.java.installations.paths="C:\Path\To\jdk-21" build` |
| `java -jar build\libs\imlate-1.0.0.jar` 에서 `UnsupportedClassVersionError ... class file version 65.0` | JDK 17 로 실행했다. PATH 의 `java` 가 21 이상인지 확인(`java -version`) |

```powershell
# PowerShell : Gradle 이 실제로 어떤 JVM 을 쓰는지
cd C:\Users\kkh98\Desktop\skala-imlate\backend
.\gradlew.bat -q javaToolchains
```

### 8.4 curl 로 한글을 보내면 `400 VALIDATION_FAILED`

```json
{"code":"VALIDATION_FAILED","message":"요청 본문을 읽을 수 없습니다. 형식을 확인해 주세요."}
```

**앱 문제가 아니다.** Windows 콘솔이 본문을 UTF-8 이 아닌 코드페이지로 인코딩해서 JSON 이 깨진 것이다.
(참고로 PowerShell 에서 `curl` 은 `Invoke-WebRequest` 별칭이므로, 진짜 curl 을 쓰려면 **`curl.exe`** 라고 적어야 한다.)

**해결책 4가지 — 위쪽이 권장**

```powershell
# ① 그냥 통합 테스트를 쓴다 (한글 12명 데이터가 이미 들어 있다) — 가장 권장
node scripts/integration-test.mjs
```

```powershell
# ② PowerShell 네이티브로 보낸다 (UTF-8 로 직접 바이트를 만든다)
$body = @{ className='1반'; studentName='홍길동'; roomNumber='302' } | ConvertTo-Json
$bytes = [System.Text.Encoding]::UTF8.GetBytes($body)
Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/v1/registrations" `
  -ContentType "application/json; charset=utf-8" -Body $bytes
```

```powershell
# ③ UTF-8 파일로 저장해서 --data-binary 로 보낸다
#    ※ Out-File -Encoding utf8 은 PowerShell 5.1 에서 BOM 을 붙인다. BOM 없이 쓰려면 아래처럼.
[System.IO.File]::WriteAllText("$env:TEMP\body.json",
  '{"className":"1반","studentName":"홍길동","roomNumber":"302"}',
  (New-Object System.Text.UTF8Encoding $false))
curl.exe -s -X POST "http://localhost:8080/api/v1/registrations" `
  -H "Content-Type: application/json; charset=utf-8" `
  --data-binary "@$env:TEMP\body.json"
```

```powershell
# ④ 브라우저(5173)에서 직접 입력한다 — 어차피 사용자는 이 경로를 쓴다
```

응답의 한글이 깨져 보이는 것도 같은 원인이다. 콘솔 출력만 깨진 것이지 데이터는 멀쩡할 수 있으니, **DB 를 직접 확인**한다.

```powershell
# PowerShell — 창 D
docker exec imlate-mysql mysql -uimlate -pimlate -D imlate --default-character-set=utf8mb4 -e "SELECT class_name,student_name,room_number FROM return_registration WHERE registration_date=CURDATE();"
```

### 8.5 등록 창 마감 이후에 통합 테스트를 돌렸다

```
등록 창이 닫혀 있어 이후 시험을 진행할 수 없습니다(마감 21:45 이후).
IMLATE_REGISTRATION_CLOSE_TIME=23:59 로 앱을 재기동한 뒤 다시 실행하세요.
(종료 코드 2)
```

괄호 안의 시각은 스크립트가 `window` 응답의 `closesAt` 을 그대로 읽어 찍은 것이다(하드코딩 아님).
통합 테스트는 실제로 등록을 해야 하므로 **등록 창이 열려 있어야 한다**. 마감(기본 21:45) 이후에 작업 중이라면:

```powershell
# PowerShell — 창 B : 백엔드 Ctrl+C 후
$env:IMLATE_REGISTRATION_CLOSE_TIME = "23:59"
.\gradlew.bat bootRun --args='--spring.profiles.active=local'
```

```bash
# Git Bash — 창 B (대안)
export IMLATE_REGISTRATION_CLOSE_TIME=23:59
./gradlew bootRun --args='--spring.profiles.active=local'
```

> **끝나고 반드시 원복한다.** `Remove-Item Env:IMLATE_REGISTRATION_CLOSE_TIME` 후 재기동.
> 23:59 마감으로 배포하는 사고를 막기 위해 [§6.2](#62-시간-시나리오-3) 체크리스트에도 넣어 두었다.

> **마감을 23:59 로 늘려 두면** 통합 테스트 §1-3(마감 → 발송 → 재시도 → 통금 순서 검사)이
> **실패가 아니라 "건너뜀"** 으로 표시된다. 결과 요약에 이렇게 찍힌다.
>
> ```
> 건너뜀: 1-3. 시각 정합성(마감 → 발송 → 재시도 → 통금) — 등록 창이 23:59 까지 늘어나 있어 건너뜀. 기본 설정으로 재기동해 한 번은 확인할 것.
> ```
>
> 시험용 설정을 결함으로 보고하지 않기 위한 장치다. **기본 설정(마감 21:45)으로 한 번은 반드시 돌려서
> 이 항목이 통과하는 것을 확인한다.**

참고로 **자정을 넘겨서** 테스트하면 `targetDate` 가 바뀌므로, 전날 데이터로 발송을 확인하려면 `?date=` 를 명시해야 한다.

### 8.6 Playwright 브라우저가 설치되어 있지 않다

```
Executable doesn't exist at C:\Users\...\ms-playwright\chromium-....\chrome-win\headless_shell.exe
╔════════════════════════════════════════════════════════════╗
║ Looks like Playwright Test or Playwright was just installed ║
║ npx playwright install                                      ║
╚════════════════════════════════════════════════════════════╝
```

```powershell
# PowerShell — 창 D
cd C:\Users\kkh98\Desktop\skala-imlate\frontend
npx playwright install          # 이 설정은 chromium 하나만 쓰므로 이걸로 충분
npm run test:e2e
```

> `--with-deps` 는 리눅스 전용 시스템 패키지 설치 옵션이다. **Windows 에서는 그냥 `npx playwright install`** 이면 된다.

**그 밖의 Playwright 관련**

| 증상 | 해결 |
|---|---|
| `Port 5173 is already in use` | 창 C 의 dev 서버가 이미 떠 있으면 `reuseExistingServer: true` 라 재사용한다. **다른 프로그램**이 5173 을 쓰고 있다면 그걸 끈다(`strictPort: true` 라 대체 포트로 안 옮긴다) |
| 결과를 보고 싶다 | `npx playwright show-report` (`playwright-report/` 에 HTML 리포트가 쌓인다) |
| 브라우저를 눈으로 보고 싶다 | `npx playwright test --headed` / 한 파일만: `npx playwright test tests/register.spec.ts` |
| E2E 는 통과하는데 실제로는 깨진다 | **당연하다.** E2E 는 API 를 전부 목킹한다. 실제 응답 검증은 `scripts/integration-test.mjs` 의 몫이다 |

### 8.7 부하 테스트나 등록 화면에서 429 가 나온다

**먼저 어떤 429 인지 가른다.** 버킷이 2단이라 원인이 두 가지다([SPEC §8](SPEC.md)).

| 상황 | 원인 | 정상인가 |
|---|---|---|
| **서로 다른 사람**을 여러 명 등록하는데 429 | IP 버킷(`global` / `register`) 한도가 인원보다 작다 | **아니다. 결함이다.** 아래 참고 |
| **같은 사람**을 반복 제출해서 429 | 개인 버킷(`register-person`) | **정상.** 도배를 막는 것이 목적이다 |
| 한 IP 에서 수천 건을 쏴서 429 | IP 버킷(DDoS 방어) | **정상** |

응답의 `X-RateLimit-Limit` 값이 어느 규칙에 걸렸는지 알려 준다
(`register-person` 이면 5, `register` 면 300, `global` 이면 1200, `lookup` 이면 20).
개인 버킷에 걸리면 안내 문구도 다르다 — "같은 정보로 너무 자주 등록을 시도했습니다…".

```powershell
# PowerShell — 창 D : 지금 적용 중인 한도 확인
curl.exe -s "http://localhost:8080/actuator/env/imlate.rate-limit.global.capacity"
curl.exe -s "http://localhost:8080/actuator/env/imlate.rate-limit.register.capacity"
curl.exe -s "http://localhost:8080/actuator/env/imlate.rate-limit.register-person.capacity"
```

**첫 번째 행(서로 다른 사람이 막힌다)이면 한도를 임시로 올리는 것으로 넘어가지 마라.**
그것이 바로 [§2.5](#25-공용-와이파이-환경-검증-반드시-본다) 의 결함이다.
`imlate.rate-limit.register.capacity` 가 **교육생 규모(200)보다 커야** 한다. 설정을 고쳐서 해결한다.

> ### rate limit 을 끄고 부하 테스트를 돌리지 마라
>
> 예전 이 문서는 "부하 테스트는 rate limit 을 끄고 돌려라"라고 안내했다.
> **그 안내가 결함을 가리고 있었다.** 끈 채로 돌리면 부하 테스트는 리미터에 대해 아무것도 증명하지 못하고,
> "공용 와이파이 뒤 200명이 막힌다"는 사실이 초록색 결과 뒤에 숨는다.
> 지금은 **켠 채로 통과해야 정상**이며, `load-test.mjs` 가 `rate limit 이 켜져 있다` 항목으로 이를 검사한다.

버킷을 직접 들여다보거나 비우는 방법(스크립트가 쓰는 방식):

```powershell
# PowerShell — 창 D
docker exec imlate-redis redis-cli --scan --pattern "imlate:rl:*"      # IP 버킷 + 개인 버킷이 함께 보인다
docker exec imlate-redis redis-cli --scan --pattern "imlate:rl:*" | ForEach-Object { docker exec imlate-redis redis-cli del $_ }
```

> 개인 버킷 키는 `imlate:rl:register-person:{해시 16자}` 형태다. **키에 이름이 보이지 않는 것이 정상**이다
> — 교육생 실명·호수가 Redis 키로 남지 않도록 `SHA-256(반|이름|호수)` 앞 16자만 쓴다([SPEC §8.3](SPEC.md)).

> 정말로 한도를 임시로 올려야 한다면(예: 500명 규모 리허설) `--args` 로 넘긴다.
> `application-local.yml` 이 리터럴로 박아 둔 값은 환경변수로 못 바꾼다.
>
> ```powershell
> .\gradlew.bat bootRun --args='--spring.profiles.active=local --imlate.rate-limit.register.capacity=3000 --imlate.rate-limit.register.refill-tokens=3000 --imlate.rate-limit.global.capacity=6000 --imlate.rate-limit.global.refill-tokens=6000'
> ```
>
> **끝나면 반드시 되돌린다.** 임시 한도로 배포하면 R14 요구사항이 무너진다.

### 8.8 그 밖에 자주 보는 것

| 증상 | 원인 / 해결 |
|---|---|
| `Web server failed to start. Port 8080 was already in use.` | 이전 `bootRun` 이 살아 있다. 창 B 에서 Ctrl+C. 안 죽으면 `netstat -ano \| Select-String ":8080"` 으로 PID 확인 후 `Stop-Process -Id <PID>` |
| 관리 API 가 계속 `401 UNAUTHORIZED` | 헤더 이름은 정확히 `X-Admin-Key`. local 기본값은 `local-dev-admin-key`. 로그에 `imlate.admin.api-key 가 비어 있습니다` 가 있으면 키 자체가 설정되지 않은 것(프로파일 없이 띄웠을 때 발생) |
| `/lookup` 이 계속 `403 FORBIDDEN` | 토큰은 **날짜별로 서명**된다. `date` 와 `token` 이 짝이 맞아야 한다. preview API 로 새 링크를 받는다. 앱을 다른 `IMLATE_LOOKUP_TOKEN_SECRET` 으로 재기동했다면 이전 토큰은 전부 무효 |
| 발송했는데 `skipped:true, skipReason:"NO_REGISTRATION"` | 그날 등록 인원이 0명. 요구사항상 **0명이면 보내지 않는다**. 먼저 등록을 만든다 |
| 발송했는데 `skipReason:"DISABLED"` | `IMLATE_NOTIFICATION_ENABLED=false` 로 띄웠다. `force=true` 로는 강제 발송이 가능하다 |
| 발송했는데 `skipReason:"NO_SUPERVISOR"` | 사감 목록이 비었다. `--args` 로 supervisors 를 덮을 때 리스트가 통째로 교체된 경우가 흔하다([§7](#7-실발송-리허설-마지막-관문) 주의 참고) |
| 부하 테스트가 `rate limit 이 켜져 있다` 에서 실패 | `--imlate.rate-limit.enabled=false` 로 앱을 띄워 놓았다. **켜고 다시 돌린다**([§8.7](#87-부하-테스트나-등록-화면에서-429-가-나온다)) |
| 부하 테스트 §0B 의 `register(IP) 한도 ≥ 인원` 에서 실패 | 설정값 자체가 공용 와이파이를 감당하지 못한다. 한도를 고친다([§2.5](#25-공용-와이파이-환경-검증-반드시-본다)) |
| 부하 테스트가 `§1. IP 분산 기준선` 을 건너뛴다 | **정상이다.** `trusted-proxies` 가 비어 XFF 가 무시되므로 헤더로 IP 를 나눌 수 없다([SPEC §8.7](SPEC.md)) |
| 통합 테스트가 `대사 상태 정상` 에서 실패 | Redis 장애 훈련 후 잔여 데이터일 가능성이 높다. [§4.5](#45-훈련-뒤-정리) 로 정리하고 다시 돌린다 |
| Gradle 이 이상하게 굴 때 | `.\gradlew.bat --stop` → 다시 실행. 그래도 안 되면 `.\gradlew.bat clean build` |

---

## 9. 관련 문서

| 문서 | 언제 보는가 |
|---|---|
| [docs/DEPLOYMENT.md](DEPLOYMENT.md) | 이 문서의 점검표를 다 통과한 뒤 **AWS 에 올릴 때**. Terraform → SSM → EC2 배포, SES/Aligo 준비, 배포 후 확인 |
| [docs/OPERATIONS.md](OPERATIONS.md) | 배포 **이후 매일**. 일일 체크리스트, 발송 실패 대응, 대사 MISMATCH 대응, 설정 변경, 로그 위치, 장애 런북 |
| [docs/SPEC.md](SPEC.md) | "원래 어떻게 동작해야 하는가"가 헷갈릴 때 — 구현 계약서 |
| [docs/API.md](API.md) | 엔드포인트 요청/응답 스키마 전체 |
| [docs/ARCHITECTURE.md](ARCHITECTURE.md) | 왜 이렇게 설계했는지, 시퀀스와 장애 시나리오 |
| [README.md](../README.md) | 프로젝트 개요와 빠른 시작 |
