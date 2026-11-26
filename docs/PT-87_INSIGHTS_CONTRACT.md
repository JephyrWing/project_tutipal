# PT-87 개념 분석 계약 (개념 태그 · 질문 군집 TOP3 · BKT 숙련도)

상태: 구현 초안 (2026-11-25). 요구사항 승인 · 실환경 검증과는 별개다. 미보정 값과 미결 정책은 아래에 표시한다.

## 1. 범위

- 선생님이 이해도 질문(퀴즈)에 개념 태그를 붙인다 (문항당 최대 3개, 수업당 개념 최대 50개).
- 수업 학생의 챗봇 질문 원문을 임시 보관하고, 선생님이 "분석하기"를 누르면 비슷한 질문끼리 묶어 학생들이 헷갈리는 주제 TOP3를 보여 준다.
- 태그된 문항의 채점 결과로 학생 × 개념 숙련도(BKT)를 계산해 표로 보여 준다.
- 모든 API는 그 수업 선생님만 쓴다. 같은 수업 학생은 403, 다른 수업 선생님 · 없는 수업/문항은 404 (`StudySessionService.requireTeacherOf`). 끝난 수업도 조회 · 태그 편집 가능.
- 범위 밖: AI 태그 추천(AC-5, P2 — 이번 구현에 없음), LLM 주제 라벨러, 학생 본인의 질문 삭제(PT-106).

## 2. 데이터 (`V22__concept_insights.sql`)

| 테이블 | 내용 | 삭제 |
| --- | --- | --- |
| `session_concepts` | 수업별 개념. `name` 1~30자(앞뒤 공백 제외), `normalized_name` = 소문자 + 공백 하나로 합침, `UNIQUE(session_id, normalized_name)` | 수업 삭제 CASCADE. 어느 문항에도 안 붙은 개념은 태그 교체 때 정리 |
| `quiz_concepts` | 문항 ↔ 개념, `source` TEACHER \| AI(선생님이 받아들인 추천) | 문항 · 개념 삭제 CASCADE |
| `chat_questions` | 학생 질문 원문(1~1000자), `session_id`, `user_id`, `created_at`. 벡터 컬럼 없음 | 보관기간 · 나가기 · 탈퇴 · 수업 삭제 (5장) |

V20(PT-86 `chunk_provenance`) · V21(PT-106 `ai_conversations`)은 develop에 병합됐고 V22는 그 뒤에 적용된다. 백필 없음, 기존 테이블 무수정.

## 3. Spring API

| 메서드 · 경로 | 요청 | 응답 |
| --- | --- | --- |
| `GET /api/sessions/{sid}/concepts` | — | `[{id, name}]` |
| `GET /api/sessions/{sid}/quiz-concepts` | — | `{ "<quizId>": [{conceptId, name, source}] }` (태그 없는 문항은 없음) |
| `PUT /api/quizzes/{quizId}/concepts` | `{names: [≤3], source?: "TEACHER"\|"AI"}` | 저장된 태그 `[{conceptId, name, source}]` |
| `GET /api/sessions/{sid}/insights/mastery` | — | `{concepts:[{id,name,quizCount,classAvg,belowHalf}], students:[{userId,name,cells:[{conceptId,pKnown,observations,lowConfidence}]}]}` |
| `POST /api/sessions/{sid}/insights/confusions` | — | `{status, reason, questionCount, studentCount, algorithm, k, silhouette, cached, topics:[{rank,label,conceptId,questionCount,studentCount}]}` |

- PUT은 전체 교체이고 멱등이다. 이름은 유니코드 공백(NBSP · U+3000 포함, 화면의 JS `\s`와 같은 기준)을 공백 하나로 합치고 앞뒤를 지운다. 대소문자 · 공백만 다른 이름은 하나로 합치며 처음 표기(수업에 이미 있으면 그 표기)를 쓴다. 위반은 모두 400 `{status, message}`: 4개 이상, 빈 이름, 31자 이상(소문자로 바꾼 정규화 이름이 30자를 넘는 경우 포함, 예: `İ`), 제어문자(탭 · 줄바꿈 포함, 정리 전 원본 기준) · 짝 없는 서로게이트, 원문 200자 초과, 알 수 없는 source, 교체 후 수업 개념 50개 초과. 50개는 "교체 뒤" 기준으로 센다: 이 문항만 쓰던 개념은 교체 때 지워지므로 세지 않는다.
- AC-3 문구의 "ProblemDetail"은 이번 구현에서 기존 `GlobalExceptionHandler`의 `{status, message}` 본문으로 대신한다 (공유 핸들러 무수정). 429만 ProblemDetail.
- 없는 문항과 다른 수업 문항의 404는 같은 문구("방을 찾을 수 없어요")라 문항 id 존재 여부를 알 수 없다.
- `confusions` 상태: `OK` | `LOW_QUALITY` | `INSUFFICIENT_DATA`. reason: `FEW_QUESTIONS` | `FEW_STUDENTS` | `NO_SHARED_TOPIC` | null. 미매칭 주제 라벨은 `태그되지 않은 주제 N`(conceptId null).
- `confusions` 처리 순서: 권한 → 데이터 부족 판정(AI 호출 0, 한도 0) → 결과 캐시(적중 시 AI 0, 한도 0) → `INSIGHTS` 한도 예약 → 임베딩 → FastAPI 군집 → Spring 후처리. AI 실패는 503(한도 환불). mastery는 AI를 쓰지 않으므로 AI가 꺼져도 정상.
- 한도: `AiFeature.INSIGHTS`, `app.ai.quota.insights` (임시 10회/1시간, PT-97 정책과 같은 임시값 성격). 기존 quota 키는 env 예제에 없으므로 env 예제는 바꾸지 않았다.

## 4. FastAPI 계약 `POST /v1/insights/cluster`

- 인증: 기존 `internal_only`(X-Internal-Key). 모델 제공자를 쓰지 않으므로 provider 검사는 붙이지 않는다.
- 요청 `{vectors: [[float ×1024] ×10..256], concepts: [[float ×1024] ×0..50]}`, 유한값만, `extra=forbid`. 텍스트 · user_id 없음. Spring은 소수 5자리로 반올림해 보낸다 (최대 요청 256+50개 기준 약 3.2MB, 본문 제한 4.5MB 미만).
- 응답 `{status: "OK"|"LOW_QUALITY", algorithm: "kmeans-silhouette-v1", k, silhouette|null, clusters:[{members:[int], concept:{index, similarity}|null}]}`. members는 요청 vectors의 순번, concept.index는 요청 concepts의 순번. representatives 없음.
- 422 본문은 기존 `safe_error`로 입력값을 되돌려 주지 않는다.

## 5. 원문 보관 · 삭제 · 외부 전송 경계

- 저장 조건: 수업(sessionId) STUDENT 턴, 변형 문항 턴 제외, 마지막 메시지가 user일 때 그 내용. 앞뒤 공백 제거 후 1000 코드포인트로 자르고, 공백뿐이면 저장하지 않는다. INSERT 문 안에서 "그 수업의 STUDENT 참가자 + 진행 중인 수업"을 다시 확인한다 (나간 학생 · 끝난 수업은 0건). 끝난 수업 확인(`ended_at IS NULL`)은 수업 행을 잠그지 않는 최선 노력 검사다. 수업 종료에는 질문 삭제 규칙이 없고 끝난 수업의 질문도 수업 뒤 분석용으로 같은 보관기간을 적용하므로, 종료와 동시에 들어온 질문 하나가 저장돼도 종료 직전 질문과 같은 취급이며 노출 · 보관 정책을 넘지 않는다. 반면 나가기 · 내보내기는 그 학생 질문을 지우는 규칙이 있어 참가자 행 잠금으로 경합을 막는다(5장). 선생님 미리보기 · 개인 채팅은 저장하지 않는다.
- 기록 실패는 채팅을 멈추지 않는다. 로그에는 예외 클래스명만 남긴다 (원문 · DB 메시지 없음).
- 보관기간: `app.insights.questions.retention` = **임시 30일**. NFR 보관기간은 PT-13에서 미결이므로 결정되면 이 값만 바꾼다. `QuestionRetentionJob`이 `cleanup-interval`(1시간)마다 1000건씩 지운다. 두 키는 application.yaml에만 있다(코드 기본값 없음).
- 나가기 · 내보내기: `ParticipantRemoved` 이벤트에서 그 학생의 그 수업 행만 같은 트랜잭션으로 삭제.
  - 질문 기록과 겹칠 때: 리스너는 JPA가 참가자 행 DELETE를 커밋 때 flush하기 **전에** 돈다. 그래서 질문 INSERT는 참가자 행을 `FOR SHARE`로 잠그고, 리스너의 삭제(`deleteBySessionAndUser`)는 먼저 같은 행을 `FOR UPDATE`로 잠근다.
  - 제거가 먼저 잠갔으면 INSERT는 커밋을 기다렸다가 행이 지워진 것을 보고 0건, INSERT가 먼저면 제거가 INSERT 커밋을 기다린 뒤 새 문장(READ COMMITTED 새 스냅숏)으로 그 행을 지운다.
  - 수업 행은 잠그지 않는다. 대기 뒤에도 INSERT의 스냅숏은 그대로라 소용이 없고, 제거가 수업 → 참가자 순으로 잠그므로 교착이 생길 수 있다.
  - 탈퇴는 users · study_sessions 삭제의 CASCADE가 마지막에 남은 행까지 지운다.
- 탈퇴: `AccountCleanup`이 본인 질문 + 본인이 연 수업의 질문 전부를 명시적으로 지운다. 수업 삭제는 CASCADE.
- 원문을 읽는 코드는 `ChatQuestionStore` 하나뿐이고, 꺼낸 원문은 `RawQuestion`(toString 가림, JSON 직렬화 시 예외, `revealForEmbedding()`만 공개)에 담긴다. 응답 DTO의 문자열 필드는 label/name/status/algorithm/reason/source로 제한하고 반사 검사 테스트로 지킨다.
- 외부 전송: 원문은 분석 요청 때만 기존 임베딩 경로(`AiClient.embedWithModel` → FastAPI `/v1/embed`)로 나간다. **`MODEL_PROVIDER=auto`에서 Ollama를 쓸 수 없으면 OpenAI 임베딩으로 전환되어 질문 원문이 외부로 전송될 수 있다** (기존 자료 RAG 임베딩과 같은 경계). 벡터는 저장하지 않는다. FastAPI 군집 요청에는 벡터와 순번만 있다.
- **분석용 재전송**: 저장한 원문은 "분석하기"를 누를 때마다 임베딩 경로로 다시 전송된다 (저장 시점에 한 번 보내고 끝나는 것이 아님). PT-13 개인정보 고지에 "수업 질문은 개념 분석 때 임베딩 서비스로 전송될 수 있음(설정이 auto면 외부)"을 포함해야 한다.
- 분석은 지금 그 수업의 STUDENT 참가자인 사람의 질문만 읽는다 (나가기와 채팅 기록이 엇갈려 남은 행도 분석에서 빠짐). 또 `created_at >= 지금 - retention`인 행만 읽으므로, 정리 작업(1시간 주기) 사이에 만료된 행도 분석에 들어가지 않는다.
- 결과 캐시(`TopicResultCache`)는 라벨 · 숫자만 담고 메모리에만 있다 (최대 100개, 10분). 키의 지문에는 개념 집합과 분석한 질문 id 목록이 함께 들어간다.

## 6. 알고리즘 선택: KMeans + 실루엣 (HDBSCAN 아님)

- 입력은 수업 최근 질문 최대 256개(5자 미만 제외)로 작고, 결과는 "TOP3 주제"만 쓴다. KMeans는 같은 입력에 같은 결과(`random_state=0`, `n_init=10`, `algorithm="lloyd"`, BLAS 스레드 1개)를 내기 쉬워 테스트 · 설명이 단순하다.
- k는 2..min(8, n//5, n-1) 중 실루엣 최대(동점이면 작은 k). 군집 후보가 없거나 벡터가 전부 같으면 k=1, silhouette null, LOW_QUALITY. 최고 실루엣 < 0.1이면 LOW_QUALITY.
- HDBSCAN은 잡음 점을 버려 주는 장점이 있지만, 질문 수십 개 규모에서는 `min_cluster_size` 등에 결과가 크게 흔들리고 "전부 잡음"이 자주 나와 선생님 화면에 설명하기 어렵다. 추가 의존성도 피했다. 대신 KMeans는 잡담도 어느 군집에 넣으므로 "학생 3명 이상" 규칙과 개념 매칭으로 걸러 낸다 (한계 8장).
- 벡터는 L2 정규화 후 유클리드 거리 = 코사인 거리와 같은 순서.
- 개념 매칭: 정규화한 군집 중심과 정규화한 개념 벡터의 코사인이 0.55 이상인 것 중 최대 (FastAPI 상수 `CONCEPT_MATCH_THRESHOLD`, **미보정**). Spring은 같은 개념에 매칭된 군집을 합치고 고유 학생 수를 다시 센다.

## 7. 소수 인원 규칙과 BKT

- 수업 전체에서 분석 대상 질문 10개 미만 또는 질문한 학생 3명 미만이면 `INSUFFICIENT_DATA` (AI · 한도 사용 없음).
- 주제별 고유 학생 3명 미만인 군집은 버린다 (한두 명의 질문이 특정되지 않게). TOP3 정렬: 고유 학생 수 내림차순 → 질문 수 내림차순 → 최신 질문 순.
- FastAPI가 k=1(묶을 수 없음)을 돌려주면 "전원 질문" 하나를 주제로 보여 주지 않고 topics를 비운다 (reason `NO_SHARED_TOPIC`).
- 응답에는 학생 식별자가 없다. 숙련도 표는 선생님이 이미 보는 참가자 이름만 쓴다.
- BKT(Corbett & Anderson, 1995 관례값, **TUTIPAL 데이터로 적합하지 않음**): P(L0)=0.3, T=0.1, S=0.1, G = 객관식 min(0.3, 1/보기 수) · 주관식 0.1.
  - 정답: post = P(1-S) / (P(1-S) + (1-P)G), 오답: post = PS / (PS + (1-P)(1-G)), 다음 P = post + (1-post)T.
  - 조회할 때마다 답안 created_at · id 순으로 처음부터 다시 계산하고 저장하지 않는다 (재채점이 바로 반영). 채점 전(correct NULL) 답은 무시, 태그 없는 문항은 영향 없음, 여러 태그는 개념마다 독립 갱신. 관측 0 → null, 3개 미만 → lowConfidence. 현재 STUDENT 참가자만 표에 나온다.
  - classAvg = 값이 있는 학생 pKnown의 평균(반올림), belowHalf = classAvg < 50.

## 8. 한계 · 남은 위험

- 미보정 값: 0.55(개념 매칭), 0.1(실루엣), BKT 네 값, 최소 인원 3 · 질문 10. 실제 수업 데이터로 보정 전까지 "참고용"으로 표시한다.
  - 1회 확인(2026-11-25, 로컬 Ollama bge-m3, 합성 한국어 질문 30개 = 재귀 · 스택 · 반복문 각 10개, 개념 이름 3개): k=4, silhouette 0.085 → **LOW_QUALITY**. 군집 순도 24/30(80%), 4개 군집 모두 맞는 개념에 매칭(코사인 0.60~0.76, 반복문 군집 2개는 Spring에서 합쳐짐). 다만 재귀 군집은 반복문과의 코사인도 0.653으로 차이가 작았다. 짧은 한국어 질문에서는 주제가 분명해도 실루엣이 0.1 아래로 나올 수 있으므로, 0.1 기준은 실제 수업 데이터로 다시 정해야 한다 (표본 1회, 결론 아님).
- KMeans는 모든 질문을 어딘가에 넣으므로 인사 · 잡담 군집이 생길 수 있다.
- 분석 때 임베딩이 GPU(Ollama)를 쓴다. 256개면 4번 호출로 수 초~수십 초. 배치마다 `AiSlots` 자리를 잡고 돌려주므로 사이사이 챗봇이 끼어들 수 있다.
- 같은 수업에서 "분석하기"가 동시에 두 번 오면 둘 다 캐시 미스로 한도를 2회 쓸 수 있다 (선생님 한 명 · 버튼은 처리 중 비활성이라 드묾. 키별 진행 중 요청 공유는 숙제).
- 소수 인원 규칙의 한계 (수용 리스크): 학생을 내보낸 뒤 다시 분석해 결과 차이로 그 학생 주제를 추정하는 차분 공격은 막지 않는다. `INSUFFICIENT_DATA`는 질문한 학생 수(1~2 포함)를 보여 준다.
- AI 장애 중에는 CHAT 한도가 환불되므로 학생이 한도 소비 없이 질문 행을 계속 쌓을 수 있다 (분석 창 오염. 학생 3명 하한 때문에 한 명이 가짜 주제를 만들 수는 없음). 학생 · 수업별 기록 상한은 숙제.
- 질문 기록 INSERT에 별도 쿼리 타임아웃이 없다 (DB가 멈추면 채팅 시작이 늦어질 수 있음, 숙제).
- FastAPI 요청 JSON(최대 약 3MB) 파싱 · 검증은 이벤트 루프 위에서 돈다. 군집 계산만 스레드풀. 실측 후 판단. 군집 계산은 프로세스 안에서 한 번에 하나(lock)씩 돈다.
- FastAPI 숫자 검증은 pydantic lax 모드라 `"0.5"` · `true`도 숫자로 받는다. 호출자는 Spring 하나이고 Spring은 숫자만 보낸다. 확인 결과(pydantic 2.14.3) `Field(strict=True)` float도 JSON 정수 `0`은 받고 `"0.5"` · `true`만 거절하므로 strict 전환은 안전하다. 전환 여부는 후속 결정(현재는 lax). 유한하지만 극단적인 값(1e200 등)은 정규화에서 영벡터가 될 수 있다 (Spring은 임베딩 값만 보내므로 도달하지 않음).
- 개념 id는 고정이 아니다: 어느 문항에도 안 붙은 개념은 지워지고 같은 이름을 다시 붙이면 새 id가 된다. 화면 · 캐시는 이름 기준으로 보므로 문제 없지만, 외부에서 개념 id를 오래 보관하면 안 된다.
- 캐시 키의 임베딩 모델은 "직전 분석에서 본 모델"이다 (AiClient에 현재 모델 조회가 없음). 제공자 전환 직후 최대 10분간 이전 모델 결과가 나올 수 있다.
- 캐시 · 한도는 서버 메모리라 단일 인스턴스 전제(AiQuota · AiSlots와 같음).
- 채팅 재시도 시 같은 질문이 두 번 기록될 수 있다.
- **원문 이중 저장 (PT-106 병합 후)**: PT-106 대화 턴(`ConversationTurnService`)도 `TutorService.chat`을 감싸므로, 수업 학생이 저장 대화로 물은 질문은 `ai_conversation_messages`와 `chat_questions`에 모두 남는다. 기존 챗봇 경로(저장 대화 아님)의 질문은 `chat_questions`에만 남는다.
- **PT-106 저장소로 바꾸지 않은 이유 (2026-11-26 판단)**: 분석은 `SessionQuestionSource`로만 읽으므로(현재 구현은 어댑터 빈 `ChatQuestionSource`, 계약은 그 인터페이스 javadoc) 빈 하나만 바꾸면 되지만, 지금 바꾸면 계약을 못 지킨다.
  - 저장 대화를 거치지 않은 질문이 빠진다.
  - 보관이 다르다: PT-106은 PT-13 결정(2026-11-26)에 따라 수업 대화를 선생님이 정한 기간(수업 종료 후 0~90일), 개인 대화를 마지막 활동 후 30일 뒤 지우고("저장"한 대화는 남김), 지운 대화는 soft delete 뒤 30일에 물리 삭제한다(`docs/AI_CONVERSATIONS.md` "보관 기간"). 분석은 30일 창이다.
  - PT-106은 나가기 · 내보내기 때 대화를 지우지 않는다.
  - 학생 본인의 대화 이력을 선생님 분석에 쓰는 것은 목적이 달라 PT-13 고지 · 동의 결정이 먼저다.
  - 이관 조건: 학생 챗봇이 모두 저장 대화를 거치고 PT-13이 정해지면 `ConversationQuestionSource`(`deleted_at IS NULL`, role `user`, 그 수업, 현재 STUDENT 참가자, `created_at >= now - 30일`, 메시지 id 순)로 바꾸고 `chat_questions` · `ChatQuestionRecorder`를 걷어 낸다.

## 9. 병합 조건 · 조율 항목

- **Flyway 번호**: `validate-on-migrate: true`이고 `outOfOrder`는 꺼져 있다. V20(#57) · V21(#61)이 develop에 먼저 병합됐으므로 V20 → V21 → V22 순서가 지켜진다. 이미 적용된 DB가 생긴 뒤에는 번호를 바꾸지 않는다.
- **TutorService 생성자**: 9번째 파라미터 `StudentQuestionSink`를 추가했고, 기존 8인자 생성자를 NONE으로 위임하는 오버로드로 남겼다 (Spring은 `@Autowired`가 붙은 9인자 생성자를 쓴다). develop의 PT-106 `ConversationTurnAgentIntegrationTest`(8인자)는 병합 후 그대로 컴파일 · 통과한다.
- **공유 파일 충돌 가능**: `build.gradle`(insightsDbTest), `requirements.txt` · `requirements.lock.txt`, `app/main.py`(라우터), `AccountCleanup`(DELETES · javadoc), `application.yaml`(quota.insights, insights.questions). 대부분 줄 추가라 병합 시 양쪽을 다 살리면 된다.
- **보관기간 키**: `app.insights.questions.retention`은 PT-106의 보관기간 키와 이름 · 형식이 다르다. PT-13에서 보관기간이 정해질 때 하나의 형식으로 맞춘다.
- **insightsDbTest 실행 기록**: 2026-11-25 21:44 KST, 로컬 일회용 컨테이너 `pgvector/pgvector:0.8.7-pg17-bookworm`(PostgreSQL 17.12, `infra/postgres/Dockerfile`과 같은 PG · pgvector 버전)에서 `./gradlew insightsDbTest` **19개 통과**(실패 · 건너뜀 0). AC-1(V19 시드 → V22 적용 후 기존 행 보존 · 백필 없음)과 저장소 SQL을 실제 DB로 확인했다. CI에는 아직 이 태스크가 없으므로 V22 · 저장소 SQL을 바꾸면 다시 돌린다.

## 10. 화면

- 선생님 수업 화면의 "개념 분석" 탭: TOP3(분석하기를 눌렀을 때만 POST) + 숙련도 표. 숙련도는 학생 답안 알림이 몰려도 마지막 알림 뒤 3초에 한 번만 다시 읽는다.
- 닫힌 수업: 선생님은 "방은 닫혔어요" 안내와 함께 개념 분석 패널을 계속 본다. 학생 화면은 그대로.
- 이해도 질문 탭: 목록에 태그 칩, "답안 보기"에서 태그 편집, 출제 대화상자에서 태그 입력. 태그 · 개념 목록은 처음과 저장 뒤에만 읽는다.

## 11. 검사

- Spring 단위 · 웹: `./gradlew test` (insights · tutor · quota · AccountCleanup · ClusterClient).
- 실제 DB: `./gradlew insightsDbTest` — 일회용 PostgreSQL(pgvector)에서 Flyway clean 후 V19까지 올리고 시드 → V22 적용 → 저장소 검사. env: `INSIGHTS_TEST_DB_URL=jdbc:postgresql://127.0.0.1:PORT/tutipal_insights_e2e`, `INSIGHTS_TEST_DB_ALLOW_RESET=disposable`, `INSIGHTS_TEST_DB_PASSWORD`(사용자 `tutipal_insights_e2e`). env가 없으면 skip이 아니라 실패한다. 기본 `test`에서는 제외.
- FastAPI: `.venv/Scripts/python.exe -m pytest tests/test_insights_cluster.py`.
- 화면: `node --test tests/insights.test.mjs`.
