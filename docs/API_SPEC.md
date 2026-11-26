# TUTIPAL API 명세서

> 기준 develop b50079d + 회원 탈퇴 전체 세션 종료(d5e3536), 2026-11-26 · WBS "API·업무 이벤트·검사 기준 계약 설계"
> 대상: Electron 앱(`tutipal_front`) ↔ Spring(`tutipal_back`) HTTP · SSE · STOMP, Spring → FastAPI(`tutipal_ai`) 내부 호출.
> 근거: `tutipal_back/src/main/java/**` 의 controller · record DTO · `SecurityConfig` · `WebSocketConfig` · `SessionEvents` · `LiveKitProxy` · `GlobalExceptionHandler`. Spring에는 springdoc/OpenAPI가 없으므로 **이 문서와 DTO 코드가 계약**이다.
> 기능별 상세(정책·동시성·보관)는 각 문서가 원본이고, 여기서는 표와 링크만 둔다.

## 목차

1. [공통 규칙](#1-공통-규칙)
2. [HTTP 엔드포인트](#2-http-엔드포인트)
3. [SSE 스트림](#3-sse-스트림)
4. [WebSocket · STOMP · LiveKit 중계](#4-websocket--stomp--livekit-중계)
5. [Spring → FastAPI 내부 API](#5-spring--fastapi-내부-api)
6. [검사 기준](#6-검사-기준)
7. [변경 관리](#7-변경-관리)

---

## 1. 공통 규칙

### 1.1 주소와 형식

| 항목 | 값 |
| --- | --- |
| Base URL | Spring 서버 루트(로컬 `http://127.0.0.1:8080`). 업무 API는 `/api/**`, 로그인 보조는 `/auth/app/**`, 실시간은 `/ws`, `/livekit/**` |
| 본문 | JSON(UTF-8). 예외: 자료 업로드 `multipart/form-data`, SSE `text/event-stream`, 자료 다운로드 바이너리, `/auth/app/done` HTML |
| 시각 | `Instant` → ISO-8601 UTC 문자열 |
| ID 표기 | 대부분 JSON 숫자(Long). **AI 대화(`/api/conversations/**`)와 도움 채팅(`/api/help/{id}/messages`)은 id·순번을 문자열**로 준다 |
| 사용자 식별 | 항상 로그인 principal(`OAuth2User.getName()` = users.id). 요청 본문·쿼리의 userId로 바꿀 수 없다 |
| 업로드 상한 | `spring.servlet.multipart.max-file-size` 10MB, 요청 11MB |

### 1.2 인증 · CSRF · Origin

| 규칙 | 내용 (근거: `SecurityConfig`, `OriginCheckFilter`, `application.yaml`) |
| --- | --- |
| 로그인 세션 | 서블릿 세션 쿠키 **`TUTIPAL_SESSION`** (HttpOnly, SameSite=Lax, Secure=`SESSION_COOKIE_SECURE` 기본 true). 유휴 `SESSION_IDLE_TIMEOUT` 2h, 절대 `app.session.absolute-timeout` 12h(HTTP 필터·STOMP·LiveKit 공통) |
| 로그인 경로 | 앱: `GET /auth/app/start` → 소셜 로그인(`/oauth2/authorization/{google\|naver\|kakao}`) → `/auth/app/done?code=` → `tutipal://` 딥링크 → `POST /api/auth/app/exchange {code, verifier}` 로 세션 쿠키 발급(PKCE 방식, 코드 60초 1회용). **한 계정당 기기 1대**: 로그인(교환·dev 로그인)이 성공하면 같은 계정의 다른 세션은 무효화되고 그 기기의 STOMP·LiveKit 연결도 닫힌다 |
| 공개 경로 | **GET만**: `/health`, `/actuator/health/**`, `/api/auth/csrf`, `/api/auth/providers`, `/api/auth/config`, `/auth/app/**`, `/oauth2/**`, `/login/oauth2/**`. HEAD: `/health`, `/actuator/health/**`. POST: `/api/auth/app/exchange` 하나. `/dev/login`은 dev-login 켜졌을 때만 |
| 로그인 필요 | `/api/**`, `/ws`, `/ws/**`, `/livekit/**` → 비로그인 **401**(리다이렉트 없음, 본문 없음) |
| 관리자 | `/api/admin/**` → `ROLE_ADMIN`만(일반 사용자 403). **현재 이 경로의 컨트롤러는 없다** (규칙만 존재) |
| 그 밖 | 목록 밖 경로는 `denyAll` → 로그인 상태에서도 403 |
| CSRF | 상태 변경(POST/PUT/PATCH/DELETE) 전부. 쿠키 **`XSRF-TOKEN`**(HttpOnly 아님, SameSite=Lax) 값을 헤더 **`X-XSRF-TOKEN`** 으로 보낸다. 토큰은 `GET /api/auth/csrf` 로도 받는다(`{token, headerName}`). 마스킹 없는 원본 토큰(`CsrfTokenRequestAttributeHandler`). 예외는 `POST /api/auth/app/exchange` 하나. 실패 403 |
| Origin | 상태 변경 요청에 `Origin` 헤더가 있으면 `app.allowed-origins`(기본 `http://127.0.0.1:[*],http://localhost:[*]`)와 맞아야 함, 아니면 403(CSRF가 맞아도). `Origin` 없음은 통과 후 CSRF 검사. `Origin: null` 은 403. 같은 패턴을 `/ws`, `/livekit/**` 핸드셰이크에도 적용 |
| 로그아웃 | `POST /logout` (Spring Security 기본, CSRF 필요) → 204, 세션 무효화 |

### 1.3 오류 본문

`GlobalExceptionHandler` 기준. **429(AI 한도) 하나만 ProblemDetail이고 나머지는 `{status, message}` 맵**이다.

| 원인 | 상태 | 본문 |
| --- | --- | --- |
| `ApiException` (서비스의 업무 거절) | 예외에 담긴 상태 | `{"status": 404, "message": "방을 찾을 수 없어요"}` |
| `@Valid` 실패 (`MethodArgumentNotValidException`) | 400 | `{"status": 400, "message": "<필드>: <메시지>"}` (첫 필드 오류 하나) |
| `AiUnavailableException` (FastAPI/모델 실패) | 503 | `{status, message}` |
| `DataIntegrityViolationException` (동시 중복 요청) | 409 | `{status, message: "이미 처리된 요청이에요. 새로고침해 주세요"}` |
| `AiQuotaExceededException` (PT-97 한도) | 429 | `application/problem+json`: `type, title("AI 사용 한도 초과"), status, detail, message(=detail), feature, retryAt(UTC ISO), retryAfterSeconds` + 헤더 `Retry-After` |
| 초대 코드 실패 제한 (`RetryLater`) | 429 | `{status, message}` + `Retry-After` (`StudySessionController` 전용 핸들러) |
| Spring Security 거절(401/403) | 401/403 | 본문 없음 |

### 1.4 공통 상태 코드의 뜻

| 코드 | 뜻 |
| --- | --- |
| 400 | 입력 형식·범위 위반(Bean Validation 또는 서비스 검사) |
| 401 | 로그인 없음·세션 만료, 앱 로그인 코드 만료, LiveKit 접속 키 무효 |
| 403 | CSRF/Origin 실패, 목록 밖 경로, **같은 수업 참가자지만 역할이 맞지 않음**(학생이 교사 기능 등), 동의 없음 |
| 404 | 없음 **또는 참가자가 아님**(존재 여부를 숨기려고 비참가자는 403 대신 404). 남의 대화·기록도 404 |
| 409 | 상태 충돌: 이미 응답·처리됨, 동시 도움 한도(4명), 같은 clientMessageId 다른 내용, 동의 버전 불일치, 기억 20개 초과 |
| 410 | 끝난 수업(`requireActive`), 마감된 퀴즈, 끝난 도움, 스트림 중 삭제된 대화 |
| 413 / 415 | 자료 10MB 초과 / txt·md·pdf 외 형식 |
| 429 | AI 한도 초과(ProblemDetail), 같은 사용자의 AI 턴 진행 중, 초대 코드 5회/5분 실패, 집중도 50초 내 재제출, LiveKit·앱 로그인 대기열 포화 |
| 502 | 모델이 빈 답·잘못된 형식을 줌(퀴즈 생성, 대화 빈 답) |
| 503 | AI 서버 불가·AI 자리(`app.ai.max-concurrent` 2, 대기 30s) 없음, LiveKit 준비 안 됨, 저장소 오류, 초대 코드 생성 실패 |

### 1.5 AI 호출 한도 (`app.ai.quota`, PT-97 임시값)

사용자 × 기능별 슬라이딩 윈도(서버 메모리, 서버 1대 기준). 초과 시 1.3의 429 ProblemDetail.

| `feature` | 설정 키 | 한도 | 적용 엔드포인트 |
| --- | --- | --- | --- |
| `SCREEN_AUTO` | `screen-auto` | 120 / 1h | `POST /api/screen` (question 없음) |
| `SCREEN_MANUAL` | `screen-manual` | 30 / 1h | `POST /api/screen` (question 있음) |
| `CHAT` | `chat` | 60 / 1h | `POST /api/tutor/chat`, `POST /api/conversations/{id}/messages`(저장된 답 재생은 제외) |
| `QUIZ_GENERATE` | `quiz-generate` | 20 / 1h | `POST /api/sessions/{id}/quizzes/generate` |
| `INSIGHTS` | `insights` | 10 / 1h | `POST /api/sessions/{id}/insights/confusions` (캐시 적중·데이터 부족은 세지 않음) |

그 밖의 속도 제한: 초대 코드 실패 5회/5분(`InviteCodeJoinService`), 집중도 제출 간격 `app.focus.submit-min-interval` 50s, Agent 턴 사용자당 1개 동시(`app.ai.agent.enabled=true`일 때).

### 1.6 권한 표기

| 표기 | 뜻 (서비스 검사) |
| --- | --- |
| 공개 | 로그인 불필요 |
| 로그인 | 로그인 사용자 누구나, 자기 데이터만 |
| 참가자 | 수업 참가자(아니면 404). `+활성` = 끝난 수업이면 410 (`requireActiveRole`) |
| 교사 | 그 수업 TEACHER (`requireTeacherOf`: 비참가 404, 학생 403) |
| 학생 | 그 수업 STUDENT (교사면 403) |

---

## 2. HTTP 엔드포인트

총 **85개 컨트롤러 매핑** + Spring Security 필터 경로 4개(`POST /logout`, `GET /actuator/health/**`, `/oauth2/authorization/{p}`, `/login/oauth2/code/{p}`).
표의 "응답" 은 성공 상태와 DTO, "주요 오류"는 1.4 공통 코드 외에 그 엔드포인트에서 의미 있는 것만 적는다.

### 2.1 인증 · 상태 확인

| 메서드 | 경로 | 권한 | 요청 | 응답 | 주요 오류 |
| --- | --- | --- | --- | --- | --- |
| GET | `/auth/app/start` | 공개 | query `provider`(google·naver·kakao, dev-login 시 `dev`), `challenge`(base64url 43자) | 302 → `/oauth2/authorization/{provider}` (dev: `/auth/app/done?code=`) | 400 잘못된 challenge·provider |
| GET | `/auth/app/done` | 공개 | query `code`(선택) | 200 HTML (딥링크 `tutipal://` 열기) | — |
| POST | `/api/auth/app/exchange` | 공개, **CSRF 예외**, Origin 검사 | `ExchangeRequest{code ≤64, verifier ≤128}` | 200 `{name}` + `TUTIPAL_SESSION` 쿠키 | 401 코드 만료·불일치, 429 대기 코드 1만 개 |
| GET | `/api/auth/csrf` | 공개 | — | 200 `{token, headerName}` + `XSRF-TOKEN` 쿠키 | — |
| GET | `/api/auth/providers` | 공개 | — | 200 `string[]` (등록된 OAuth 제공자 id) | — |
| GET | `/api/auth/config` | 공개 | — | 200 `{devLogin: boolean, providers: string[]}` | — |
| POST | `/logout` | 로그인(CSRF) | — | 204 | 403 CSRF |
| GET | `/health` | 공개 (HEAD도) | — | 200 `{status:"ok", virtualThread}` | — |
| GET | `/actuator/health/**` | 공개 (HEAD도) | — | Spring Actuator(세부 없음, probes) | — |

### 2.2 개발 전용 (profile-gated)

| 메서드 | 경로 | 조건 | 요청 | 응답 |
| --- | --- | --- | --- | --- |
| POST | `/dev/login` | `@Profile("local & !prod")` **그리고** `app.dev-login=true`(`DEV_LOGIN`). 그 밖에서 true면 `DevLoginGuard`가 기동 실패. 꺼져 있으면 매핑 없음 → 401 | query `n`(1~9, 기본 1, 범위 밖은 보정). CSRF 필요 | 200 `{name}` + 세션 쿠키 (`dev-<n>` 계정) |

`GET /auth/app/start?provider=dev` 도 같은 조건에서만 동작한다.

### 2.3 사용자 (`/api/users`)

| 메서드 | 경로 | 권한 | 요청 | 응답 | 주요 오류 |
| --- | --- | --- | --- | --- | --- |
| GET | `/api/users/me` | 로그인 | — | 200 `UserResponse{id, name, provider, role(ADMIN\|USER)}` | 404 |
| PATCH | `/api/users/me/name` | 로그인 | `UpdateNameRequest{name}` (`@Valid` 없음, 서비스가 검사) | 200 `UserResponse` | 400 제어문자·1~50자 위반 |
| DELETE | `/api/users/me` | 로그인 | — | 204. 계정 익명화(provider=`withdrawn`, 이름 `탈퇴한 사용자`, 이메일·소셜 ID 제거), 기록은 모두 보존. 내가 연 진행 중 수업은 종료(`ended`), 내 열린 도움 요청은 DONE(`helpEnded`). 커밋 뒤 그 사용자의 모든 로그인 세션 종료 (PM 2026-11-26, PT-13) | 404 (없거나 이미 탈퇴한 사용자) |

### 2.4 수업 (`/api/sessions`)

`SessionResponse{id, title, topic, inviteCode, myRole(TEACHER\|STUDENT), difficulty, hideAnswers, preferMaterials, participantCount, ended, pinned}`

| 메서드 | 경로 | 권한 | 요청 | 응답 | 주요 오류 |
| --- | --- | --- | --- | --- | --- |
| POST | `/api/sessions` | 로그인(만든 사람 = 교사) | `CreateSessionRequest{title ≤50, topic ≤30}` | 201 `SessionResponse` | 503 초대 코드 생성 실패 |
| POST | `/api/sessions/join` | 로그인 | `JoinSessionRequest{code ≤20}` | 200 `SessionResponse` | 404 코드 없음, 410 닫힌 방, 429 실패 5회/5분(+`Retry-After`), 409 동시 중복 |
| GET | `/api/sessions` | 로그인 | — | 200 `SessionResponse[]` (끝나지 않은 내 수업, 즐겨찾기 먼저) | — |
| GET | `/api/sessions/{id}` | 참가자 | — | 200 `SessionResponse` | 404 |
| GET | `/api/sessions/{id}/participants` | 교사 | — | 200 `ParticipantResponse[]{userId, name, role, joinedAt, online}` | 403·404 |
| PUT | `/api/sessions/{id}/settings` | 교사+활성 | `UpdateSettingsRequest{topic ≤30, difficulty 1~5, hideAnswers, preferMaterials}` | 200 `SessionResponse` | 410 |
| PUT | `/api/sessions/{id}/pin` | 참가자 | `PinRequest{pinned: boolean}` | 204 | 404 |
| POST | `/api/sessions/{id}/end` | 교사 | — | 200 `SessionResponse` | 403·404 |
| DELETE | `/api/sessions/{id}/participants/me` | 학생 참가자 | — | 204 (열린 도움 요청도 닫힘) | 403 교사는 나갈 수 없음 |
| DELETE | `/api/sessions/{id}/participants/{userId}` | 교사 | — | 204 | 400 교사 대상, 404 참가자 없음 |

### 2.5 퀴즈 · 채점 (`QuizController`)

- `QuizType`: `CHOICE | TEXT`, `GradedBy`: `AUTO | AI | TEACHER`
- `QuizSummary{id, no, type, question, choices, answerIndex, modelAnswer, closesAt, open, answered, correct, pending, studentCount}` (교사용)
- `StudentQuiz{id, sessionId, type, question, choices, closesAt, open, myAnswer}` (정답 없음), `MyAnswer{choiceIndex, text, correct, gradedBy, feedback}`
- `AnswerRow{answerId, userId, name, choiceIndex, text, correct, gradedBy, feedback}`

| 메서드 | 경로 | 권한 | 요청 | 응답 | 주요 오류 |
| --- | --- | --- | --- | --- | --- |
| POST | `/api/sessions/{sessionId}/quizzes` | 교사+활성 | `CreateQuizRequest{type, question ≤1000, choices ≤5개(각 ≤200), answerIndex, modelAnswer ≤2000, timeLimitSec 10~600}` | 201 `QuizSummary` | 400 보기 2개 미만·정답 미선택·TEXT 모범답안 없음 |
| POST | `/api/sessions/{sessionId}/quizzes/generate` | 교사 | `GenerateQuizRequest{type, hint ≤200}` | 200 `QuizDraft{type, question, choices, answerIndex, modelAnswer}` (저장 안 함) | 429 `QUIZ_GENERATE`, 502, 503 |
| GET | `/api/sessions/{sessionId}/quizzes` | 교사 | — | 200 `QuizSummary[]` | — |
| GET | `/api/sessions/{sessionId}/quizzes/open` | 참가자+활성 | — | 200 `StudentQuiz[]` | 410 |
| GET | `/api/quizzes/{quizId}` | 참가자+활성 | — | 200 `StudentQuiz` | 404 |
| POST | `/api/quizzes/{quizId}/answers` | 학생+활성 | `AnswerRequest{choiceIndex, text ≤2000}` | 200 `MyAnswer` (TEXT는 AI 채점) | 403 교사, 409 이미 응답, 410 마감 |
| POST | `/api/quizzes/{quizId}/close` | 교사 | — | 200 `QuizSummary` | — |
| GET | `/api/quizzes/{quizId}/answers` | 교사 | — | 200 `AnswerRow[]` | — |
| PUT | `/api/quiz-answers/{answerId}` | 교사 | `GradeRequest{correct: Boolean(필수)}` | 200 `AnswerRow` (`gradedBy=TEACHER`) | 404 응답 없음 |

### 2.6 개념 태그 · 인사이트 (PT-87, 원본 [PT-87_INSIGHTS_CONTRACT.md §3](PT-87_INSIGHTS_CONTRACT.md))

| 메서드 | 경로 | 권한 | 요청 | 응답 | 주요 오류 |
| --- | --- | --- | --- | --- | --- |
| GET | `/api/sessions/{sessionId}/concepts` | 교사(끝난 수업 허용) | — | 200 `ConceptView[]{id, name}` | — |
| GET | `/api/sessions/{sessionId}/quiz-concepts` | 교사 | — | 200 `{ "<quizId>": QuizConceptView[]{conceptId, name, source} }` | — |
| PUT | `/api/quizzes/{quizId}/concepts` | 교사 | `ConceptNamesRequest{names ≤3(각 ≤200), source: TEACHER\|AI}` (전체 교체) | 200 `QuizConceptView[]` | 400 이름 규칙·수업 개념 50개 초과, 404(다른 수업 문항도 같은 문구) |
| GET | `/api/sessions/{sessionId}/insights/mastery` | 교사 | — | 200 `MasteryResponse{concepts[{id,name,quizCount,classAvg,belowHalf}], students[{userId,name,cells[{conceptId,pKnown,observations,lowConfidence}]}]}` | — (AI 미사용) |
| POST | `/api/sessions/{sessionId}/insights/confusions` | 교사 | — | 200 `ConfusionsResponse{status(OK\|LOW_QUALITY\|INSUFFICIENT_DATA), reason(FEW_QUESTIONS\|FEW_STUDENTS\|NO_SHARED_TOPIC\|null), questionCount, studentCount, algorithm, k, silhouette, cached, topics[{rank,label,conceptId,questionCount,studentCount}]}` | 429 `INSIGHTS`, 503 |

### 2.7 자료 · RAG 색인 (`MaterialController`)

`MaterialRow{id, sessionId, filename, sizeBytes, status(PROCESSING|DONE|FAILED), chunkCount, error, createdAt, indexedAt, indexRetryable}`

| 메서드 | 경로 | 권한 | 요청 | 응답 | 주요 오류 |
| --- | --- | --- | --- | --- | --- |
| POST | `/api/sessions/{sessionId}/materials` | 교사+활성 | multipart `file`(txt·md·pdf), query `alert`(기본 false) | 201 `MaterialRow` (비동기 색인 시작) | 413 10MB, 415 형식, 400 읽기 실패 |
| GET | `/api/sessions/{sessionId}/materials/reusable` | 교사+활성 | — | 200 `ReusableMaterial[]{id, filename, sessionTitle, status}` (내가 만든 다른 수업 자료) | — |
| POST | `/api/sessions/{sessionId}/materials/reuse/{sourceId}` | 교사+활성(원본 수업도 교사) | query `alert` | 201 `MaterialRow` | 400 같은 수업 자료 |
| GET | `/api/sessions/{sessionId}/materials` | 참가자+활성 | — | 200 `MaterialRow[]` | 410 |
| GET | `/api/materials/{id}/file` | 참가자+활성 | — | 200 바이너리, 항상 `Content-Disposition: attachment`, `application/pdf` 또는 `text/plain`, `nosniff` | 404, 503 저장소 |
| DELETE | `/api/materials/{id}` | 교사 | — | 204 | 404 |
| POST | `/api/materials/{id}/index` | 교사+활성 | — | 202 `MaterialRow` (재색인 요청, [RAG_REINDEX.md](RAG_REINDEX.md)) | 409 재시도 불가 파일 |

### 2.8 공지 · 질문 게시판

`NoticeResponse{id, title, body, authorName, createdAt}` / `PostSummary{id, title, authorName, commentCount, createdAt, mine}` / `PostDetail{id, title, body, authorName, createdAt, mine, comments[CommentResponse{id, body, authorName, createdAt, mine}]}`

| 메서드 | 경로 | 권한 | 요청 | 응답 | 주요 오류 |
| --- | --- | --- | --- | --- | --- |
| POST | `/api/sessions/{sessionId}/notices` | 교사+활성 | `CreateNotice{title ≤100, body ≤3000, alert}` | 201 `NoticeResponse` | 410 |
| GET | `/api/sessions/{sessionId}/notices` | 참가자+활성 | — | 200 `NoticeResponse[]` | 410 |
| DELETE | `/api/notices/{id}` | 교사 | — | 204 | 404 |
| POST | `/api/sessions/{sessionId}/posts` | 참가자+활성 | `CreatePost{title ≤100, body ≤3000}` | 201 `PostSummary` | 410 |
| GET | `/api/sessions/{sessionId}/posts` | 참가자+활성 | — | 200 `PostSummary[]` | 410 |
| GET | `/api/posts/{id}` | 참가자+활성 | — | 200 `PostDetail` | 404 |
| POST | `/api/posts/{id}/comments` | 참가자+활성 | `CreateComment{body ≤1000}` | 200 `PostDetail` | 404 |
| DELETE | `/api/posts/{id}` | 작성자 또는 교사(+활성) | — | 204 | 403 |
| DELETE | `/api/comments/{id}` | 작성자 또는 교사(+활성) | — | 204 | 403, 404 |

### 2.9 화면공유 · 도움 요청 · 판서 · 도움 채팅

`JoinInfo{url, token, room, identity}`: `url` = `/livekit/<1회 키>`, `token` = `"session-bound"`(실제 LiveKit JWT는 앱에 주지 않고 중계가 붙인다), `room` = `session-{id}-class` 또는 `session-{id}-help-{helpId}`, `identity` = `u{userId}`.
`HelpResponse{id, userId, name, message, shareScreen, status(PENDING|ACCEPTED|DECLINED|DONE), createdAt}`

| 메서드 | 경로 | 권한 | 요청 | 응답 | 주요 오류 |
| --- | --- | --- | --- | --- | --- |
| POST | `/api/sessions/{sessionId}/screenshare/token` | 참가자+활성 (교사=발행+보기, 학생=보기) | — | 200 `JoinInfo` | 503 LiveKit 준비 안 됨, 429 키 1만 개 |
| POST | `/api/sessions/{sessionId}/help` | 학생+활성 | `CreateHelpRequest{message ≤300, shareScreen}` | 201 `HelpResponse` | 403 교사, 409 열린 요청 있음 |
| GET | `/api/sessions/{sessionId}/help` | 참가자+활성 (교사=전체 열린 요청, 학생=내 것) | — | 200 `HelpResponse[]` | — |
| POST | `/api/help/{helpId}/accept` | 교사 | — | 200 `HelpResponse` | 409 이미 처리·동시 4명 한도 |
| POST | `/api/help/{helpId}/later` | 교사 | — | 204 (DECLINED) | 409 진행 중은 end로 |
| POST | `/api/help/{helpId}/end` | 교사 또는 요청한 학생 | — | 204 (DONE) | 404, 409 |
| POST | `/api/help/{helpId}/token` | 요청한 학생 또는 교사(+활성), ACCEPTED일 때만 | — | 200 `JoinInfo` (방 B) | 409 진행 중 아님, 410 |
| POST | `/api/help/{id}/draw` | 교사+활성, ACCEPTED | `Drawing{message}` (아래 형식) | 204, 학생 `/user/queue/draw`로 전달 | 400 형식, 409 |
| POST | `/api/help/{helpId}/messages` | ACCEPTED 도움의 학생·교사 당사자 | `Send{clientMessageId: UUID, text}` (1~1000 code point, 공백만 불가) | 200 `Message{id, helpId, sequence, senderId, clientMessageId, text, createdAt}` (id·순번 문자열) | 404 당사자 아님, 409 같은 id 다른 내용, 410 끝난 도움 |
| GET | `/api/help/{helpId}/messages` | 위와 같음 | query `afterSequence`(기본 0), `limit`(1~100, 기본 100) | 200 `Page{items, nextCursor, hasMore, maxLength(1000)}` | 400 범위 |

판서 `message` 형식(`DrawingController.valid`, 키 6개 이하): `{k:"clear"}` · `{k:"del", ids: string[≤500, 각 ≤40]}` · `{k:"add", id ≤40, tool:"pen"|"hl", c:"#ef4444"|"#2563eb"|"#facc15", p: [[x,y], …≤500] (0~1 정규화)}`. 원본: [TUTIPAL_DESIGN.md §10](TUTIPAL_DESIGN.md), 채팅 상세 [HELP_CHAT.md](HELP_CHAT.md).

### 2.10 LiveKit 중계 HTTP

| 메서드 | 경로 | 권한 | 요청 | 응답 |
| --- | --- | --- | --- | --- |
| GET | `/livekit/{key}/rtc/validate` | 로그인 + 키를 발급받은 같은 세션 + 방/도움 권한 재확인 | LiveKit SDK 쿼리(허용 목록만 전달) | LiveKit `/rtc/validate` 응답 그대로(`Cache-Control: no-store`). 키 무효 401 |

WebSocket 중계는 [4.4](#44-livekit-중계-livekit).

### 2.11 AI 도우미 · 화면 분석

| 메서드 | 경로 | 권한 | 요청 | 응답 | 주요 오류 |
| --- | --- | --- | --- | --- | --- |
| POST | `/api/tutor/chat` | 로그인. `sessionId` 있으면 참가자+활성. `variantQuizId`는 학생만 | `ChatRequest{topic ≤30, messages[1..40]{role: user\|assistant, content ≤8000}, sessionId?, variantQuizId?, screenSummary? ≤4000}` | **SSE** ([3.1](#31-post-apitutorchat)) | 스트림 전 HTTP: 400 `variantQuizId`만 있음, 403 교사의 변형, 404, 410, 429 턴 진행 중 / `CHAT` 한도 |
| POST | `/api/screen` | 로그인 | `ScreenRequest{image ≤2,500,000자(data URL), question? ≤500}` | 200 `{text}` | 400 이미지, 429 `SCREEN_AUTO`/`SCREEN_MANUAL`, 503 |

### 2.12 저장 AI 대화 (PT-106, 원본 [AI_CONVERSATIONS.md](AI_CONVERSATIONS.md))

id는 모두 문자열. `Detail{id, topic, title, sessionId, messageCount, createdAt, lastMessageAt, summary{text, updatedAt}|null, memories[MemoryView{id, text, createdAt}]}`

| 메서드 | 경로 | 권한 | 요청 | 응답 | 주요 오류 |
| --- | --- | --- | --- | --- | --- |
| GET | `/api/conversations` | 로그인(내 것) | query `sessionId?`, `cursor?`, `limit?`(1~50) | 200 `Page{items[ListItem{id, topic, title, sessionId, messageCount, lastMessageAt}], nextCursor, hasMore}` | 400 cursor |
| POST | `/api/conversations` | 로그인. 수업 대화는 학생+활성 | `CreateRequest{topic? ≤30, sessionId?}` | 201 `Detail` + `Location` | 400 개인 대화 topic 없음, 403 교사, 404, 410 |
| GET | `/api/conversations/{id}` | 주인 | — | 200 `Detail` | 404 |
| GET | `/api/conversations/{id}/messages` | 주인 | query `before?`, `limit?`(1~100) | 200 `MessagePage{items[{id, role, content, createdAt}], nextBefore, hasMore}` | 400, 404 |
| POST | `/api/conversations/{id}/messages` | 주인. 수업 대화는 학생+활성 | `MessageRequest{content 1..8000, clientMessageId? UUID}` | **SSE** ([3.2](#32-post-apiconversationsidmessages)) | 스트림 전 HTTP: 400/403/404/409(같은 id 다른 질문)/410/429 |
| DELETE | `/api/conversations/{id}` | 주인 | — | 204 (soft delete) | 404 |
| POST | `/api/conversations/{id}/memories` | 주인 | `MemoryRequest{text 1..1000}` | 201 `MemoryView` | 409 범위당 20개 |
| DELETE | `/api/conversations/{id}/memories/{memoryId}` | 주인 | — | 204 | 404 |

### 2.13 복습 · 학습 결과

| 메서드 | 경로 | 권한 | 요청 | 응답 | 주요 오류 |
| --- | --- | --- | --- | --- | --- |
| POST | `/api/review/observe` | 로그인 | `ObserveRequest{summary? ≤2000, seconds: int}` | 200 `Observed{recorded, suggestion{id, summary}\|null}` | 400 |
| GET | `/api/review/items` | 로그인 | — | 200 `Item[]{id, summary, seenCount, seenSeconds, firstSeen, lastSeen, suggestedAt}` | — |
| GET | `/api/review/history` | 로그인 | query `page`(≥0, 기본 0), `size`(1~100, 기본 20) | 200 `ReviewHistoryPage{items: Item[], totalCount, page, size, totalPages}` | 400 |
| GET | `/api/review/items/{id}` | 로그인(내 것) | — | 200 `Item` | 404 |
| DELETE | `/api/review/items` | 로그인 | — | 204 (내 기록 전부) | — |
| DELETE | `/api/review/items/{id}` | 로그인(내 것) | — | 204 | — |
| GET | `/api/learning-results` | 로그인(내 답안, 진행 중 수업의 STUDENT 참여분) | query `page`(≥0), `size`(1~100), `sessionId?`(≥1), `verdict`(ALL\|PENDING\|CORRECT\|INCORRECT, 기본 ALL) | 200 `LearningResultsPage{items[{answerId, quizId, sessionId, sessionTitle, topic, type, question, choices, choiceIndex, text, submittedAt, verdict}], sessions[{id, title}], totalCount, page, size, totalPages}` (정답·모범답안·피드백 없음) | 400 |

원본: [PT-54_REVIEW_HISTORY.md](PT-54_REVIEW_HISTORY.md), [PT-73_REVIEW_EXPLAIN_CHECKS.md](PT-73_REVIEW_EXPLAIN_CHECKS.md), [PT-101_LEARNING_RESULTS.md](PT-101_LEARNING_RESULTS.md).

### 2.14 집중도 (PT-90, 원본 [AI_WORKFLOW_CONTRACT.md §8](AI_WORKFLOW_CONTRACT.md#8-집중도-예측-v1focuspredict-pt-90-2026-11-26))

`FocusState`: `FOCUS | DISTRACTED | BREAK | UNKNOWN | PENDING | NOT_PARTICIPATING`

| 메서드 | 경로 | 권한 | 요청 | 응답 | 주요 오류 |
| --- | --- | --- | --- | --- | --- |
| POST | `/api/sessions/{id}/focus` | 학생+활성 + 현재 버전 동의 | `FocusSubmitRequest{rows[1..15]{exeName? (^[a-z0-9._-]{1,64}\.exe$), idleSec 0~60, switchCount 0~120}}` | 200 `FocusStatusResponse{state, updatedAt}` (모델 실패도 200, UNKNOWN) | 403 교사·동의 없음, 404, 410, 429 50초 |
| GET | `/api/sessions/{id}/focus` | 교사+활성 | — | 200 `StudentFocus[]{userId, state, updatedAt}` | 403 |
| GET | `/api/sessions/{id}/focus/me` | 학생+활성 | — | 200 `FocusStatusResponse` | 403 |
| GET | `/api/focus/consent` | 로그인 | — | 200 `ConsentResponse{consented, version, consentedAt, currentVersion, retentionDays, maxSampleAgeDays, staleAfterSeconds}` | — |
| PUT | `/api/focus/consent` | 로그인 | `ConsentRequest{version ≤20}` | 200 `ConsentResponse` | 409 버전 불일치 |
| DELETE | `/api/focus/consent` | 로그인 | — | 204 (기록 즉시 삭제) | — |

---

## 3. SSE 스트림

두 엔드포인트 모두 `produces = text/event-stream`, 타임아웃 10분, 15초마다 주석 줄 `: ping`(화면은 무시, Cloudflare 100초 무응답 대비). 권한·한도 거절은 **스트림을 열기 전에 HTTP 상태**로 나간다(1.3 형식). 화면이 연결을 끊으면 다음 조각에서 생성을 멈추고 한도 예약·턴 자리를 반납한다. 모르는 이벤트는 무시한다.

### 3.1 `POST /api/tutor/chat`

| 순서 | event | data | 비고 |
| --- | --- | --- | --- |
| 1 | `sources` | `{"items": string[]}` (출처 문자열 `파일명 p.N`) | 자료를 찾았을 때만 |
| 2 | `delta` × N | `{"t": string}` | 답변 조각 + Spring 안내 문구(변형 문항, 도움 추천, 재시도 안내) |
| 3 | `agent` | `AgentEvent{route, suggestHelp, variant{type, question, choices, answerIndex, modelAnswer, explanation}\|null, answerHidden, retryable, helpDraft}` | Agent 경로(`app.ai.agent.enabled=true`, 학생·개인)에서만, `done` 직전 1회. 교사는 없음 |
| 4 | `done` | `{}` | 정상 끝 |
| — | `error` | `{"message": string}` | 실패 시 `done` 대신. 이후 스트림 종료 |

상세: [AI_WORKFLOW_CONTRACT.md §7.7](AI_WORKFLOW_CONTRACT.md#77-spring-sse-매핑).

### 3.2 `POST /api/conversations/{id}/messages`

| 순서 | event | data | 비고 |
| --- | --- | --- | --- |
| 1 | `sources` | `{"items": string[]}` | 있을 때만 |
| 2 | `delta` × N | `{"t": string}` | `clientMessageId` 재생이면 저장된 답 전체 1개 |
| 3 | `agent` | 3.1과 같음 | Agent 경로만 |
| 4 | `saved` | `Saved{userMessageId, assistantMessageId, answer?, replaced?}` | `answer`·`replaced`는 동시 저장 충돌로 저장된 답이 다를 때만 |
| 5 | `done` | `{}` | |
| — | `error` | `{"message"}` | 이 경우 아무것도 저장하지 않음(스트림 중 대화 삭제 포함) |

---

## 4. WebSocket · STOMP · LiveKit 중계

### 4.1 STOMP 연결 (`WebSocketConfig`)

| 항목 | 값 |
| --- | --- |
| 엔드포인트 | `/ws` (네이티브 WebSocket, SockJS 없음). 로그인 세션 쿠키로 핸드셰이크 인증(비로그인 401), Origin = `app.allowed-origins` |
| 브로커 | simple broker `/topic`, `/queue` (서버 1대 기준). 앱 prefix `/app`, 사용자 prefix `/user` |
| 클라이언트 SEND | **전부 거절** (`StompAuthInterceptor`). `@MessageMapping` 없음. 보낼 일은 REST로 |
| 구독 허용 | `/user/queue/events`, `/user/queue/draw`, `/topic/sessions/{id}/events`(참가자), `/topic/sessions/{id}/teacher`(그 수업 교사). 그 밖은 거절 |
| 세션 만료 | `SessionWebSocketGuard`가 로그아웃·만료 시 열린 연결을 최대 5초 안에 닫음. 개인 큐는 로그인 세션이 살아 있는 연결에만 전달(`SessionEventAccessInterceptor`) |
| 전달 시점 | `AfterCommit.run` — DB 커밋 뒤에만 발행 |

### 4.2 업무 이벤트 (`SessionEvents.Event{type, id}`)

payload는 항상 `{"type": string, "id": number}` 하나. **원문은 싣지 않고 화면이 REST로 다시 읽는다**(권한 검사를 REST 한 곳에 두려고).

| destination | 받는 사람 | `type` | `id` 의미 | 발행 위치 |
| --- | --- | --- | --- | --- |
| `/topic/sessions/{sid}/events` | 수업 참가자 전체 | `quiz` | quizId | 퀴즈 출제 |
| 〃 | 〃 | `quizClosed` | quizId | 퀴즈 마감 |
| 〃 | 〃 | `settings` | sessionId | 수업 설정 변경 |
| 〃 | 〃 | `ended` | sessionId | 수업 종료, 교사 탈퇴로 수업 종료 |
| 〃 | 〃 | `notice` / `notices` | noticeId | 알림 공지(`alert=true`) / 일반 공지 등록·삭제 |
| 〃 | 〃 | `posts` | postId | 글·댓글 등록·삭제 |
| 〃 | 〃 | `materialAlert` / `materials` | materialId | 알림 업로드(`alert=true`) / 업로드·재사용·삭제·재색인·색인 완료 |
| `/topic/sessions/{sid}/teacher` | 그 수업 교사 | `joined` / `left` | userId | 학생 참여 / 나감·내보냄 |
| 〃 | 〃 | `presence` | userId | 접속 상태 변화(`Presence`) |
| 〃 | 〃 | `quizStats` | quizId | 응답 제출·AI 채점·교사 재채점 |
| 〃 | 〃 | `help` | helpId | 도움 요청 생성·수락·나중에·종료 |
| 〃 | 〃 | `focus` | studentId | 집중도 상태 갱신 |
| `/user/queue/events` | 한 사용자 | `quizResult` | quizId | 내 답 채점됨 |
| 〃 | 요청한 학생 | `helpAccepted` / `helpDeclined` / `helpEnded` | helpId | 도움 상태 변경 |
| 〃 | 도움 당사자 2명 | `helpChat` | helpId | 도움 채팅 새 메시지 |
| 〃 | 내보내진 학생 | `removed` | sessionId | 강퇴 |

### 4.3 판서 큐

| destination | 받는 사람 | payload |
| --- | --- | --- |
| `/user/queue/draw` | ACCEPTED 도움의 학생 | `{"helpId": number, "message": <2.9의 판서 message>}` (`DrawingController` → `convertAndSendToUser`) |

### 4.4 LiveKit 중계 (`/livekit/**`)

| 경로 | 종류 | 동작 |
| --- | --- | --- |
| `/livekit/{key}/rtc`, `/livekit/{key}` | WebSocket | `LiveKitProxy`가 LiveKit 내부 주소(`tutipal.livekit.internal-url`)로 양방향 중계. 핸드셰이크에서 키 → 발급 세션·로그인 만료·방/도움 권한 확인(실패 401). 업스트림 URL에 허용된 SDK 쿼리만 옮기고 `access_token`(TTL 60s, `canPublishData=false`)을 서버가 붙인다 |
| `/livekit/{key}/rtc/validate` | HTTP GET | 2.10 |

키는 `POST …/screenshare/token`·`POST /api/help/{id}/token` 응답 `JoinInfo.url`로만 받는다. 5초마다 열린 연결의 권한을 재확인하고, 수업 종료·나가기·도움 종료·세션 만료면 연결을 닫고 RoomService로 참가자를 제거한다. 상세: [TUTIPAL_DESIGN.md §8](TUTIPAL_DESIGN.md), [HELP_ACCESS_CHECKS.md](HELP_ACCESS_CHECKS.md), [LIVEKIT_MEDIA_CHECKS.md](LIVEKIT_MEDIA_CHECKS.md).

---

## 5. Spring → FastAPI 내부 API

주소 `app.ai.base-url`(`FAST_API_BASE_URL`), 모든 요청에 헤더 `X-Internal-Key: ${AI_INTERNAL_KEY}`. 동시 호출 `app.ai.max-concurrent`(2). 형식은 원본 문서가 계약이며 여기서 중복하지 않는다.

| FastAPI 경로 | Spring 호출자 | 쓰는 기능 | 계약 원본 |
| --- | --- | --- | --- |
| `POST /v1/embed` | `AiClient.embedWithModel` ← `MaterialIndexWorker`, `RagSearchService`, `ReviewService`, `TopicAnalysisService` | 자료 색인, RAG 검색, 복습 유사도, 질문 군집 | [AI_WORKFLOW_CONTRACT.md §1](AI_WORKFLOW_CONTRACT.md#1-경계-a안) |
| `POST /v1/chat` | `AiClient.chatStream`/`complete` ← `TutorService`(교사·Agent 꺼짐), `QuizAi`(생성·채점), `ScreenService` | 챗봇, 퀴즈 생성·서술형 채점, 화면 분석 | §4 · §6.3(기존 형식 유지, 전용 절 없음) |
| `POST /v1/agent/chat` (NDJSON) | `AiClient.agentStream` ← `TutorService`, `ConversationTurnService` | 학생·개인 학습 Agent | [§6](AI_WORKFLOW_CONTRACT.md#6-agent-컨텍스트-계약-pt-85--pt-106--pt-16) · [§7](AI_WORKFLOW_CONTRACT.md#7-agent-실행-요청응답-post-v1agentchat) |
| `POST /v1/agent/summarize` | `AiClient.summarize` ← `ConversationSummarizer` | 저장 대화 요약 | §6.5, [AI_CONVERSATIONS.md](AI_CONVERSATIONS.md) |
| `POST /v1/rerank` | `RerankClient` (기본 꺼짐 `tutipal.rag.rerank.enabled`) | 검색 후보 재순위 | [§3.1](AI_WORKFLOW_CONTRACT.md#31-검색-후보-재순위화-v1rerank-pt-86-2026-11-25) |
| `POST /v1/insights/cluster` | `ClusterClient` ← `TopicAnalysisService` | 질문 군집화 | [PT-87_INSIGHTS_CONTRACT.md §4](PT-87_INSIGHTS_CONTRACT.md) |
| `POST /v1/focus/predict` | `FocusClient` ← `FocusService` (timeout 2s) | 집중도 상태 | [§8](AI_WORKFLOW_CONTRACT.md#8-집중도-예측-v1focuspredict-pt-90-2026-11-26) |

FastAPI 오류는 Spring에서 `AiUnavailableException` → 503 또는 기능별 대체값(집중도 UNKNOWN, 재순위 생략)으로 바뀐다.

---

## 6. 검사 기준

각 그룹의 계약을 지키는지 확인하는 자동 검사. Spring 테스트는 `tutipal_back`에서 `./gradlew test`, 프론트는 `tutipal_front`에서 `node --test tests/*.test.mjs` / Playwright(`tests/e2e/*.spec.js`). **검사가 비어 있는 칸은 계약 변경 시 테스트를 함께 추가해야 하는 공백**이다.

| 그룹 | Spring 테스트 (`src/test/java/com/my/tutipal_back/…`) | 프론트·통합 검사 / 검사 문서 |
| --- | --- | --- |
| 1.2 인증·CSRF·Origin·401/403·admin·logout | `common/config/SecurityConfigWebTest`, `SecurityPolicyFollowupWebTest`, `SessionAbsoluteTimeoutFilterTest`, `SessionTimeout*Test`, `CookieProfileConfigTest`, `ProdProfileConfigTest`, `ProdForwardedHeadersTest`, `OptionalOAuthConfigTest`, `common/auth/service/ActiveSessionsTest` | `tests/authSession.test.mjs`, `e2e/auth-recovery.spec.js`, `e2e/desktop-security.spec.js`, [DESKTOP_SECURITY_CHECKS.md](DESKTOP_SECURITY_CHECKS.md) |
| 1.3 오류 본문(404/403/410 경로) | `SecurityConfigWebTest`(`otherClassResource…`, `studentOnTeacherResource…`, `endedSession…`) | — |
| 1.5 AI 한도 429 | `ai/quota/AiQuota*Test`, `QuizGenerateQuotaWebTest`, `ScreenQuotaWebTest`, `TutorChatQuotaWebTest`, `*ServiceQuotaTest` | `tests/apiClientQuota.test.mjs`, `quotaRetry.test.mjs` |
| 2.2 개발 전용 | `DevLoginGuardTest`, `DevLoginRequestedValueTest`, `SecurityConfigWebTest`(dev-login on/off) | — |
| 2.3 사용자 | `users/controller/UserNameWebTest`, `UserServiceNameTest`, `WithdrawalServiceTest`, `AccountCleanup*Test` | `e2e/profile-name.spec.js` |
| 2.4 수업 | `sessions/service/StudySessionServiceAuthTest`, `InviteCodeJoinServiceTest` | `e2e/auth-rooms.spec.js`, `session-lifecycle.spec.js`, `join-rate-limit.spec.js` |
| 2.5 퀴즈 | `QuizGenerateQuotaWebTest`, `QuizServiceQuotaTest`, `QuizVariant*Test` (출제·응답·재채점 컨트롤러 시험 없음) | `e2e/quiz-flow.spec.js` |
| 2.6 개념·인사이트 | `ConceptControllerWebTest`, `InsightsControllerWebTest`, `InsightsResponsePrivacyTest`, `ConceptTagService*Test`, `TopicAnalysisService*Test`, `MasteryServiceTest`, `ConceptInsightsPostgresTest` | `tests/insights*.test.mjs`, [PT-87_INSIGHTS_CONTRACT.md](PT-87_INSIGHTS_CONTRACT.md) |
| 2.7 자료·RAG | `materials/service/Chunker*Test`, `MaterialIndexWorkerChunkerTest`, `MaterialObjectStoreTest`, `rag/**` (컨트롤러 시험 없음) | `e2e/material-board.spec.js`, [RAG_REINDEX.md](RAG_REINDEX.md), [PT-86_RAG_EVAL.md](PT-86_RAG_EVAL.md) |
| 2.8 공지·게시판 | (없음) | `e2e/material-board.spec.js` |
| 2.9 화면공유·도움·판서·채팅 | `livekit/service/LiveKitAccessTest`, `LiveKitTokensTest`, `HelpChatServiceTest` (판서 `DrawingController` 단위 시험 없음) | `tests/helpAccess.integration.mjs`(`npm run test:help-access`), `help*.test.mjs`, `e2e/help-chat.spec.js`, `help-escalation.spec.js`, [HELP_ACCESS_CHECKS.md](HELP_ACCESS_CHECKS.md), [SCREEN_SHARE_TESTING.md](SCREEN_SHARE_TESTING.md) |
| 2.10 · 4.4 LiveKit 중계 | `LiveKitAccessTest`, `LiveKitTokensTest` | `npm run test:livekit-media`, [LIVEKIT_MEDIA_CHECKS.md](LIVEKIT_MEDIA_CHECKS.md) |
| 2.11 · 3.1 챗봇 SSE·화면 | `TutorChatAccessWebTest`, `TutorAgentSseWebTest`, `TutorAgentTurnLimitWebTest`, `TutorService*Test`, `ScreenQuotaWebTest`, `ScreenServiceQuotaTest` | `tests/tutorStream.test.mjs`, `screenAnalysis.test.mjs`, `e2e/review-widget.spec.js`, [PT-53_REVIEW_CHECKS.md](PT-53_REVIEW_CHECKS.md) |
| 2.12 · 3.2 저장 대화 SSE | `ConversationControllerWebTest`, `ConversationTurnWebTest`, `ConversationTurnIdempotencyWebTest`, `ConversationTurnEmptyAnswerTest`, `ConversationTurnAgentIntegrationTest`, `conversation/service/**`, `JdbcConversationStore*SqlTest` | `tests/conversations*.test.mjs`, `e2e/ai-conversations.spec.js` |
| 2.13 복습·학습 결과 | `ReviewHistoryWebTest`, `ReviewPrivacyWebTest`, `ReviewService*Test`, `ReviewStorePostgresTest`, `LearningResultsWebTest` | `e2e/review-history.spec.js`, `review-explain.spec.js`, `learning-results.spec.js`, [PT-73_REVIEW_EXPLAIN_CHECKS.md](PT-73_REVIEW_EXPLAIN_CHECKS.md) |
| 2.14 집중도 | `FocusControllerWebTest`, `FocusConsentWebTest`, `FocusService*Test`, `FocusSubmit*Test`, `FocusIsolationTest`, `FocusStorePostgresTest`(`./gradlew focusDbTest`) | `tests/focus*.test.mjs` |
| 4.1~4.3 STOMP | `common/realtime/StompAuthInterceptorTest`, `SessionEventAccessInterceptorTest`, `SessionWebSocketGuardTest` | (구독 경로를 직접 확인하는 프론트 검사 없음. e2e 흐름이 간접 확인) |
| 5 FastAPI 내부 | `ai/client/AiClient*Test`, `ClusterClientTest`, `RerankClient*Test`, `FocusClientTest`, `AiSlotsTest` | `tutipal_ai` pytest, [PT-84_HANDOFF.md](PT-84_HANDOFF.md) |

통과 기준: 해당 그룹의 위 테스트가 모두 통과하고, 새 엔드포인트·이벤트는 **정상 흐름 1건 + 권한 없는 흐름(401/403/404) 1건 이상**을 시험에 추가한다([TUTIPAL_DESIGN.md §15](TUTIPAL_DESIGN.md) "계약과 변경 관리").

---

## 7. 변경 관리

- **controller 매핑, 요청·응답 record DTO, 오류 상태, STOMP `type`/destination, SSE 이벤트, `app.ai.quota` 값, FastAPI 호출 경로** 중 하나라도 바꾸는 PR은 **같은 PR에서 이 문서를 고친다**. 기능별 원본 문서(AI_CONVERSATIONS, HELP_CHAT, PT-87 등)가 있으면 그것도 함께 고친다.
- 고칠 때 맨 위 "기준" 줄을 병합 대상 커밋과 날짜로 갱신한다.
- 새 엔드포인트는 6절 표에 검사 클래스를 적는다. 검사가 없으면 "없음"으로 남겨 공백을 드러낸다.
- 오류 본문은 현재 `{status, message}`(429만 ProblemDetail)다. ProblemDetail로 통일하려면 화면의 오류 읽기(`message`)와 함께 바꾸는 별도 PR로 한다.
- 리뷰어(Codex 포함)는 controller·DTO·`SessionEvents` 호출이 바뀐 diff에서 이 문서 변경이 없으면 `주요`로 지적한다.
