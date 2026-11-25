# PT-106 AI 대화 저장 · 요약 · 이어가기 · 삭제

GAP-12 / FR-TUT-05 / NFR-SEC-11의 구현 원본. `docs/AI_WORKFLOW_CONTRACT.md` 6.5~6.7(저장 경계, 요약 호출, `ConversationMemory` 포트)을 따른다. 계약 문서 자체는 PT-113 병합 뒤 갱신한다(아래 "후속").

## 저장 경계

| 종류 | 위치 | 만드는 시점 | Agent 요청에 들어가는 방식 |
| --- | --- | --- | --- |
| 원문 메시지 | `ai_conversation_messages` | 턴이 **끝까지 완료**됐을 때 질문·답변 한 쌍을 한 트랜잭션으로 | 활성 요약 이후 최근 39개(각 8000자 자름) = `context.messages` |
| 요약 | `ai_conversation_summaries` | 턴 응답이 끝난 뒤 Spring이 FastAPI `POST /v1/agent/summarize`로 따로 생성 | 턴 시작 때 활성이던 요약 1개 = `context.summary` (`s_<id>`) |
| 기억 | `ai_memories` | 사용자가 "기억 저장"으로 직접 쓴 글(1~1000자). 화면이 요약 → 마지막 답변 순으로 미리 채운다 | 같은 (사용자, 범위) 최신 20개 = `context.memories` (`m_<id>`) |
| Agent 단기 컨텍스트 | 저장 안 함 | 턴마다 위 세 가지를 다시 조회해 조립 (캐시 없음) | `AgentContextAssembler`가 무효 요약·범위/소유자 불일치를 거른다 |

- 범위(scope): 수업 대화는 `{"kind":"session","sessionId"}`, 개인 대화는 `{"kind":"personal"}`. 기억은 같은 범위끼리만 쓰인다(수업 10의 기억은 수업 11·개인 대화에 안 들어감).
- 실패·중지·연결 끊김·도중 삭제된 턴은 아무것도 저장하지 않는다(부분 답변 저장 없음). 답변은 최대 32000자까지 저장한다.
- 요약 규칙(`app.ai.conversations.summary.*`): 활성 요약 뒤 메시지 중 최신 6개를 뺀 수가 20 이상이면 가장 오래된 것부터 최대 40개를 이전 요약과 함께 요약한다(첫 요약은 26번째 메시지 뒤). 요약은 원본 범위(`from_message_id`~`to_message_id`)를 저장하고, 새 요약이 생기면 이전 것은 `superseded_at`으로 남는다(활성 요약은 부분 UNIQUE로 대화당 1개).
- 요약은 대화당 동시에 1개, AiSlots 자리를 짧게(기본 5초)만 기다리고 못 얻으면 건너뛴다. CHAT 한도를 쓰지 않고, Agent 경로가 꺼져 있으면 만들지 않는다. 실패하면 이전 요약을 유지하고 로그에는 대화 id와 오류 종류만 남긴다. 저장 직전에 활성 요약이 바뀌었거나 대화가 지워졌으면 결과를 버린다.
- FastAPI `/v1/agent/summarize`: 내부 키 전용, 알 수 없는 필드 422, 메시지 1~40개(각 8000자), `previousSummary` ≤4000. 빈 출력 502 `empty_summary`, 모델 실패 503 `llm_unavailable`. 입력·출력 본문은 로그에 남기지 않는다. 메시지 글은 자료로만 다루고 그 안의 지시를 따르지 않는다.

## HTTP (Spring, 로그인 필요, id는 문자열)

- `GET /api/conversations?sessionId=&cursor=&limit=20` → `{items:[{id,topic,title,sessionId,messageCount,lastMessageAt}], nextCursor, hasMore}`. sessionId가 없으면 개인 대화만, 있으면 그 수업의 내 대화만. 최근 순 keyset, limit 1~50, 잘못된 cursor 400.
- `POST /api/conversations {topic?, sessionId?}` → 201 + Location. 개인 대화는 topic(1~30자) 필수, 수업 대화는 진행 중인 수업의 학생만(참가자 아님 404, 종료 410, 선생님 403)이며 수업 주제를 쓴다. 빈 대화는 제목 없이("새 대화") 목록에 보인다.
- `GET /api/conversations/{id}` → `{id, topic, title, sessionId, messageCount, createdAt, lastMessageAt, summary:{text,updatedAt}|null, memories:[{id,text,createdAt}]}`.
- `GET /api/conversations/{id}/messages?before=&limit=50` → 최신 페이지부터, 페이지 안은 오래된 순 `{items:[{id,role,content,createdAt}], nextBefore, hasMore}`. limit 1~100.
- `POST /api/conversations/{id}/messages {content 1..8000}` → SSE `sources` → `delta {t}` … → `agent` → `saved {userMessageId, assistantMessageId}` → `done`, 실패 시 `error {message}`. 스트림 전 거절(400/403/404/410/429)은 JSON `{status, message}`(한도 429는 공통 ProblemDetail). 같은 사용자의 대화 턴이 진행 중이면 429. 스트림 중 대화가 지워지면 `error "대화가 삭제되어 저장하지 않았어요"`.
- `POST /api/conversations/{id}/memories {text 1..1000}` → 201 `{id,text,createdAt}`. 범위당 20개 초과 409. `DELETE /api/conversations/{id}/memories/{memoryId}` → 204.
- `DELETE /api/conversations/{id}` → 204.
- 남의 대화·없는 대화는 조회·이어가기·삭제·기억 저장/삭제 모두 404이며 DB·한도·모델 호출이 일어나지 않는다. 끝난 수업의 대화도 주인은 보고 지울 수 있다(이어가기만 410).

## 삭제

- 대화 삭제: 대화 행을 `FOR UPDATE`로 잠근 뒤 `DELETE FROM ai_conversations` 한 번. 메시지·요약·그 대화에서 저장한 기억은 `ON DELETE CASCADE`로 함께 지워진다. 다음 턴부터는 다시 조회하므로 지운 대화의 요약·기억이 Agent 요청에 들어가지 않는다.
- 기억 삭제: 즉시 물리 삭제, 다음 턴부터 제외.
- 탈퇴·수업 삭제: `user_id`·`session_id` FK가 `ON DELETE CASCADE`라 `AccountCleanup`에 별도 DELETE를 추가하지 않았다.
- 남은 위험: 삭제 커밋 직전에 이미 컨텍스트를 조립한 턴은 지운 요약을 한 번 쓸 수 있다(그 턴의 저장은 막힘). 단일 메시지 삭제(요약 `invalidated_at`)는 이번 범위가 아니다(컬럼만 준비).

## 보관 기간 (미정)

PT-13에 보관 기간 결정이 없다. `app.ai.conversations.retention-days`(환경변수 `AI_CONVERSATION_RETENTION_DAYS`) 기본 0 = 자동 삭제 끔: 사용자가 지우거나 탈퇴할 때까지 보관한다. N을 주면 마지막 메시지가 N일 지난 대화를 1시간마다 `retention-batch-size`(500)개씩 지운다. **PT-13 결정 전까지 운영에서 켜지 않는다.**

## 대화 묶기(ThreadLocal)와 후속

PT-113(Draft)이 `TutorService` · `TutorController` · `ChatRequest` 주변을 고치고 있어 PT-106은 그 파일을 바꾸지 않는다. 대신 `ConversationTurnService`가 `tutor.openChat` / `tutor.chat`을 직접 부르고, `tutor.chat` 동안만 `ConversationBinding`(일반 ThreadLocal, 상속 안 됨)에 (사용자, 범위, 대화, 요약 id, 서버 이력)을 묶는다. `StoredConversationMemory`는 묶음이 있고 사용자·범위가 같을 때만 저장 데이터를 쓰고, 묶음이 없으면(`/api/tutor/chat`) 화면 이력만 쓴다(기존 동작과 같음).

- 이 방식은 `TutorService`가 컨텍스트를 호출 스레드에서 동기로 조립한다는 전제에 기대며, `ConversationTurnAgentIntegrationTest`(실제 TutorService 연결)가 유일한 보호 장치다.
- PT-113 병합 뒤 후속: `ChatRequest`에 conversationId를 실어 ThreadLocal 제거, `ConversationTurnController`와 `TutorController`의 SSE 처리 통합, `src/api/sse.js`와 `tutor.js` 파서 통합, ChatPage · SessionPage · 위젯의 자동 저장과 진입 링크, 계약 6.5의 기억 생성 기준 갱신.
- 알려진 차이: 실패한 턴도 답변 조각이 나온 뒤면 CHAT 한도는 차감된다(PT-97 규칙) — 저장은 안 됨. 빈 대화 행이 쌓일 수 있다. 다중 서버 잠금은 없다(턴 중복 방지·요약 동시 1개는 서버 메모리 기준).

## 검증 경계

JUnit(`learning.conversation.*`, `AiClientSummarizeTest`)은 DB 없이 메모리 저장소와 SQL 문자열 검사로 확인한다. pytest `tests/test_agent_summarize.py`는 모델을 가짜로 바꿔 확인한다. node:test `tests/conversations.test.mjs`는 API 모듈과 화면 상태 함수다. 실제 PostgreSQL에서의 V21(CASCADE · 부분 UNIQUE) 적용, Playwright·Electron 실행, 실제 모델 요약 품질은 확인하지 않았다.
