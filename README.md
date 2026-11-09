# barofit-workout

**barofit** 백엔드의 운동 세션 서비스 레포입니다. 원래 `backend_baro` 모노레포의
`services/workout` 모듈을 폴리레포 전환 과정에서 분리했습니다(커밋 이력 보존).

운동 세션 진행/오프라인 결과 동기화, WebSocket 기반 실시간 코칭(OpenAI 피드백, ElevenLabs TTS)을
담당합니다. 세션이 완료되면 **DB 아웃박스 패턴**(`workout.analytics_completion_outbox`, 5초 폴링,
지수 백오프 재시도)으로 `barofit-analytics`에 비동기 전달합니다.

## 기술 스택

- Java 21 / Gradle (단일 모듈, 독립 레포)
- Spring Boot 4.1 (Web MVC, Data JPA, WebSocket)
- PostgreSQL `workout` 스키마, Flyway
- Testcontainers(테스트), OpenAI/ElevenLabs API 클라이언트

## 의존성

[barofit-common](https://github.com/Barofit-team/barofit-common)의 `barofit-runtime`을 참조합니다.
먼저 그 레포에서 `./gradlew publishToMavenLocal`을 실행해둬야 이 레포가 빌드됩니다.

`IDENTITY_BASE_URL`, `ROUTINE_BASE_URL`, `ANALYTICS_BASE_URL` 환경변수로 다른 서비스를 호출하고,
`ANALYTICS_INTERNAL_TOKEN`은 아웃박스 전달 시 analytics 서비스와 공유하는 내부 인증 토큰입니다
(워크아웃·analytics 양쪽에 동일한 값이 설정되어 있어야 합니다).

## 로컬 실행

```bash
cp .env.example .env   # 레포에 .env.example이 없다면 barofit-infra의 것을 참고
./gradlew bootRun       # 기본 포트 3006
```

## 테스트 / 빌드

```bash
./gradlew test    # Docker 필요(Testcontainers)
./gradlew build   # build/libs/app.jar
```

## Docker 이미지

```bash
DOCKERHUB_USER=<hub-user> ./scripts/build-and-push.sh
```

## 배포

로컬 minikube 배포는 [barofit-infra](https://github.com/Barofit-team/barofit-infra)의
`infra/charts/workout` Helm 차트로 합니다. 게이트웨이의 `/ws/coaching` 경로가 이 서비스로
WebSocket 업그레이드됩니다.

## API 요약

- `POST/PATCH /api/v1/workout-sessions/*` — 세션 시작/진행/완료 (JWT 필요)
- `WS /ws/coaching` — 실시간 코칭 WebSocket (JWT 필요)
- Swagger UI: `/swagger-ui.html`, OpenAPI: `/v3/api-docs`

## 관련 레포

- [barofit-common](https://github.com/Barofit-team/barofit-common) — 공유 라이브러리(이 레포가 의존)
- [barofit-identity](https://github.com/Barofit-team/barofit-identity) · [barofit-routine](https://github.com/Barofit-team/barofit-routine) · [barofit-analytics](https://github.com/Barofit-team/barofit-analytics)(아웃박스 전달 대상) · [barofit-report](https://github.com/Barofit-team/barofit-report)
- [barofit-infra](https://github.com/Barofit-team/barofit-infra) — Helm 차트, 로컬 검증 스크립트(`verify-workout.cjs`)
- [backend_baro](https://github.com/Barofit-team/backend_baro) — 분리 이전 모노레포(참고용)
