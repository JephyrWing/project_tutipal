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
- 요약 입력은 메시지 최대 40개이면서 글자 합계 `max-input-chars`(12000) 이내다(첫 메시지는 항상 포함, 나머지는 다음 요약으로). FastAPI는 받은 입력을 Agent와 같은 토큰 추정으로 약 2400토큰(이전 요약 600토큰 포함)에 다시 맞춘다.
- 요약은 대화당 동시에 1개, 서버 전체 `max-concurrent`개(운영 설정 1, 코드 기본 2)까지만 돈다. 자리가 없으면 기다리지 않고 건너뛴다. AiSlots 자리도 짧게(기본 5초)만 기다리고 못 얻으면 건너뛴다. CHAT 한도를 쓰지 않고, Agent 경로가 꺼져 있으면 만들지 않는다. 실패하면 이전 요약을 유지하고 로그에는 대화 id와 오류 종류만 남긴다. 저장 직전에 활성 요약이 바뀌었거나 대화가 지워졌으면 결과를 버린다.
- `AI_MAX_CONCURRENT=1`이면 요약 동안 채팅이 최대 30초 기다릴 수 있음 — 그때는 요약 동시 실행을 0/agent 요약을 끄거나 자리를 2 이상으로.
- FastAPI `/v1/agent/summarize`: 내부 키 전용, 알 수 없는 필드 422, 메시지 1~40개(각 8000자), `previousSummary` ≤4000. 빈 출력 502 `empty_summary`, 모델·스트림의 어떤 실패도 503 `llm_unavailable`. 입력·출력 본문은 로그에 남기지 않는다. 시스템 지시문에는 고정 문구만 두고, 이전 요약과 메시지는 user 메시지 안의 JSON 문자열로 보내서 그 안의 태그·화자 표시가 경계를 넘지 못한다.

## HTTP (Spring, 로그인 필요, id는 문자열)

- `GET /api/conversations?sessionId=&cursor=&limit=20` → `{items:[{id,topic,title,sessionId,messageCount,lastMessageAt}], nextCursor, hasMore}`. sessionId가 없으면 개인 대화만, 있으면 그 수업의 내 대화만. 최근 순 keyset, limit 1~50, 잘못된 cursor 400.
- `POST /api/conversations {topic?, sessionId?}` → 201 + Location. 개인 대화는 topic(1~30자) 필수, 수업 대화는 진행 중인 수업의 학생만(참가자 아님 404, 종료 410, 선생님 403)이며 수업 주제를 쓴다. 빈 대화는 제목 없이("새 대화") 목록에 보인다.
- `GET /api/conversations/{id}` → `{id, topic, title, sessionId, messageCount, createdAt, lastMessageAt, summary:{text,updatedAt}|null, memories:[{id,text,createdAt}]}`.
- `GET /api/conversations/{id}/messages?before=&limit=50` → 최신 페이지부터, 페이지 안은 오래된 순 `{items:[{id,role,content,createdAt}], nextBefore, hasMore}`. limit 1~100.
- `POST /api/conversations/{id}/messages {content 1..8000, clientMessageId?}` → SSE `sources` → `delta {t}` … → `agent` → `saved {userMessageId, assistantMessageId}` → `done`, 실패 시 `error {message}`. 스트림 전 거절(400/403/404/409/410/429)은 JSON `{status, message}`(한도 429는 공통 ProblemDetail). 같은 사용자의 대화 턴이 진행 중이면 429. 스트림 중 대화가 지워지면 `error "대화가 삭제되어 저장하지 않았어요"`.
- 중복 저장 방지(`clientMessageId`, UUID, HELP_CHAT과 같은 방식): 화면은 질문마다 새 UUID를 만들고, `saved` 전에 끊기거나 실패·중지된 질문을 **글자 그대로** 다시 보내면 같은 값을 쓴다(글을 고치면 새 값, 409면 버림). 서버는 소유·수업 확인(404/410/403) 뒤, 한도·모델 호출 전에 그 대화의 질문 행에서 이 값을 찾는다. 같은 질문이면 모델을 부르지 않고 SSE로 저장된 답을 다시 보낸다(`delta` 1개(저장된 답 전체) → `saved {저장된 ids}` → `done`, CHAT 한도·요약 없음). 다른 질문이면 409 JSON. 값은 질문 행 `client_message_id`에 저장되고 부분 UNIQUE `(conversation_id, client_message_id)`다. 같은 값으로 두 요청이 모두 모델까지 간 경우(다중 서버)에도 `savePair`가 대화 행 잠금 뒤 다시 찾아 이미 있으면 그 쌍을 돌려주고, 그래도 UNIQUE에 걸리면 되돌린 뒤 저장된 쌍을 `saved`로 보낸다(쌍·`message_count` 한 번만). 이때 방금 스트리밍한 답이 저장된 답과 다르면 `saved {userMessageId, assistantMessageId, answer, replaced:true}`로 저장된 답을 함께 보내고, 화면은 말풍선을 그 글로 바꾼다(새로 고침과 같게). 값이 없는 요청은 예전처럼 매번 새 턴이다.
- `POST /api/conversations/{id}/memories {text 1..1000}` → 201 `{id,text,createdAt}`. 범위당 살아 있는 기억 20개 초과 409. `DELETE /api/conversations/{id}/memories/{memoryId}` → 204(soft delete).
- `DELETE /api/conversations/{id}` → 204(soft delete). 지운 대화는 다시 지우면 404.
- 남의 대화·없는 대화·지운 대화는 목록에서 빠지고 조회·메시지·이어가기(SSE)·같은 `clientMessageId` 재생·삭제·기억 저장/삭제 모두 404이며 DB 쓰기·한도·모델 호출이 일어나지 않는다. 끝난 수업의 대화도 주인은 보고 지울 수 있다(이어가기만 410).

## 삭제

사용자가 지우는 대화·기억은 **soft delete**다(PM 결정, 2026-11-25). 물리 삭제는 유예 기간 뒤 정리 작업이 한다.

- 대화 삭제: 살아 있는 대화 행을 `FOR UPDATE`로 잠근 뒤 `UPDATE ai_conversations SET deleted_at = now` 한 번. 그 순간부터 모든 사람에게 없는 대화와 같다: 목록·상세·메시지·이어가기·재생·기억 저장/삭제가 404이고, 그 대화의 메시지·요약·기억은 어떤 조회에도 나오지 않는다. 저장소의 모든 조회가 `deleted_at IS NULL`(메시지·요약·기억은 부모 대화가 살아 있는지 `EXISTS`)로 거르므로 `StoredConversationMemory`의 이력·요약·기억(`memories(userId, scope)`도 지운 대화의 기억은 뺌), 요약기의 입력·저장(`saveSummary`가 잠글 행이 없어 버림)에 들어가지 않는다. 스트리밍 중에 지워지면 `savePair`가 잠글 행을 못 찾아 기존 `error "대화가 삭제되어 저장하지 않았어요"`로 끝난다.
- 기억 삭제: 살아 있는 대화 행을 `FOR UPDATE`로 잠근 뒤(동시에 들어온 대화 삭제는 먼저 끝났으면 404, 아니면 이 트랜잭션을 기다림) `UPDATE ai_memories SET deleted_at = now`. UPDATE 자체도 부모 대화가 살아 있어야 하며 0행이면 404. 즉시 상세·다음 턴 기억·20개 상한에서 빠진다(계약 6.6의 `deletedAt IS NULL`). 지운 대화의 기억도 상한에 세지 않는다.
- 물리 삭제(유예): `app.ai.conversations.deleted-purge-days`(환경변수 `AI_CONVERSATION_DELETED_PURGE_DAYS`) 기본 **30일 — PT-13 결정 전 임시값**. 1시간마다 `deleted_at < 지금 - N일`인 대화(메시지·요약·기억은 `ON DELETE CASCADE`)와 기억을 `retention-batch-size`(500)개씩 지운다. 지금 잠긴 행은 `FOR UPDATE SKIP LOCKED`로 건너뛰고, 바깥 `DELETE`가 `deleted_at < ?`를 다시 확인한다. **값은 1 이상만 허용**하고 0·음수는 시작할 때 설정 오류로 막는다("0 = 삭제 즉시 물리 삭제"는 삭제 경로가 둘이 되어 지원하지 않음).
- 인덱스(V21): 목록 `(user_id, session_id, last_message_at DESC, id DESC) WHERE deleted_at IS NULL`, 기억 범위 `(user_id, session_id, created_at DESC, id DESC) WHERE deleted_at IS NULL`, 정리 대상 `(deleted_at, id) WHERE deleted_at IS NOT NULL`(대화·기억 각각). 보관 기간용 `last_message_at` 인덱스는 지운 행도 포함한다.
- 탈퇴·수업 삭제: 그대로 물리 삭제다. `user_id`·`session_id` FK가 `ON DELETE CASCADE`라 `AccountCleanup`에 별도 DELETE를 추가하지 않았다.
- 계약 문서: `docs/AI_WORKFLOW_CONTRACT.md` 6.6의 "대화 삭제 → 메시지·요약 삭제" 문구는 PR #60 병합 뒤 "soft delete 후 유예 기간 뒤 물리 삭제"로 고쳐야 한다(이 PR에서는 계약 문서를 고치지 않음).
- 남은 위험: 턴이 열린 뒤 대화가 지워지면 Agent 경로는 저장 이력·요약을 쓰지 않는다(같은 범위의 살아 있는 다른 대화의 기억은 씀). `StoredConversationMemory`는 턴 시작 때 사본을 쓰지 않고 recall 시점에 이력(묶인 요약 범위 뒤 최근 39개, 각 8000자)·요약·기억을 각각 한 번의 live 필터 조회로 다시 읽으므로, 조회 사이에 삭제가 끝나도 지운 대화의 행은 들어가지 않는다. 남는 창은 조회가 끝난 뒤 삭제가 끝난 경우뿐이다(이미 읽은 데이터가 그 턴의 Agent 요청 하나에 들어감, 저장은 막힘). 같은 `clientMessageId` 재생은 SSE로 보내기 직전에 live 경로로 저장된 쌍을 다시 읽고, 그사이 지워졌으면 `delta`·`saved` 없이 `error "대화를 찾을 수 없어요"`(지운 대화를 물었을 때와 같은 404 의미)로 끝난다.
 Agent가 꺼진 기존 경로는 턴을 열 때 만든 `ChatRequest.messages` 사본을 그대로 쓴다(그 턴의 저장은 막힘). 요약기는 자리(요약 자리·AiSlots)를 얻은 뒤 모델 호출 직전에 대화가 살아 있는지 다시 확인하고 지워졌으면 건너뛴다. 그 확인과 모델 호출 사이(또는 호출 도중)에 지워지면 그 대화의 메시지가 요약 모델에 한 번 갈 수 있다(결과는 저장 직전 확인에서 버려짐) — 남은 아주 짧은 창이다.
- 요약이 밀린 경우(자리 없음·모델 실패·Agent를 나중에 켬): 활성 요약 끝과 최근 39개 사이의 메시지는 요약에도 이력에도 들어가지 않는다. 다음 요약이 따라잡을 때까지의 차이이며 리코 테스트(AC-1·AC-19)가 이 창 계산을 고정하고 있다. 단일 메시지 삭제(요약 `invalidated_at`)는 이번 범위가 아니다(컬럼만 준비).

## 보관 기간 (미정)

PT-13에 보관 기간 결정이 없다. 두 값이 따로 있다.

- 비활성 보관 `app.ai.conversations.retention-days`(환경변수 `AI_CONVERSATION_RETENTION_DAYS`) 기본 0 = 끔: 사용자가 지우거나 탈퇴할 때까지 보관한다. N을 주면 마지막 메시지가 N일 지난 대화를 **지운 것(soft delete)까지 포함해** 1시간마다 `retention-batch-size`(500)개씩 물리 삭제한다. 지금 저장 중인(잠긴) 대화는 건너뛰고, 고르는 사이에 새 메시지가 저장돼 기준을 넘긴 대화는 지우지 않는다. **PT-13 결정 전까지 운영에서 켜지 않는다.**
- 삭제 유예 `app.ai.conversations.deleted-purge-days` 기본 30(임시, 1 이상): 위 "삭제" 참고. 비활성 보관이 꺼져 있어도 돈다. PT-13이 정해지면 기본값을 다시 정한다.

## 대화 묶기(ThreadLocal)와 후속

PT-113(Draft)이 `TutorService` · `TutorController` · `ChatRequest` 주변을 고치고 있어 PT-106은 그 파일을 바꾸지 않는다. 대신 `ConversationTurnService`가 `tutor.openChat` / `tutor.chat`을 직접 부르고, `tutor.chat` 동안만 `ConversationBinding`(일반 ThreadLocal, 상속 안 됨)에 (사용자, 범위, 대화, 요약 id, 서버 이력)을 묶는다. `StoredConversationMemory`는 묶음이 있고 사용자·범위가 같을 때만 저장 데이터를 쓰고, 묶음이 없으면(`/api/tutor/chat`) 화면 이력만 쓴다(기존 동작과 같음).

- 이 방식은 `TutorService`가 컨텍스트를 호출 스레드에서 동기로 조립한다는 전제에 기대며, `ConversationTurnAgentIntegrationTest`(실제 TutorService 연결)가 유일한 보호 장치다.
- PT-113 병합 뒤 후속: `ChatRequest`에 conversationId를 실어 ThreadLocal 제거, `ConversationTurnController`와 `TutorController`의 SSE 처리 통합, `src/api/sse.js`와 `tutor.js` 파서 통합, ChatPage · SessionPage · 위젯의 자동 저장과 진입 링크, 계약 6.5의 기억 생성 기준 갱신, 계약 6.6의 대화 삭제 문구를 soft delete로 갱신.
- 알려진 차이: 실패한 턴도 답변 조각이 나온 뒤면 CHAT 한도는 차감된다(PT-97 규칙) — 저장은 안 됨. 저장 직후 연결이 끊긴 턴은 화면에 실패로 보이지만 저장은 됐다: 같은 글을 다시 보내면 위 재생으로 한 번만 보이고, 글을 고쳐 보내거나 화면을 새로 고쳐 입력이 사라지면 새 턴이 된다(그때는 목록·새로 고침에서 앞 쌍이 보인다). 재생도 턴 자리를 잠깐 쓰므로 앞 턴이 아직 정리 중이면 429일 수 있다. 빈 대화 행 수에 상한이 없다(후속). 기억 20개 상한은 범위 단위 advisory 잠금(`pg_advisory_xact_lock`)으로 세운다. 상세 화면은 이 대화의 기억만 보여 주므로 범위 전체 기억 목록 화면은 후속이다. 턴 중복 방지·요약 자리는 서버 메모리 기준이라 다중 서버 잠금은 없다.

## 검증 경계

JUnit(`learning.conversation.*`, `AiClientSummarizeTest`)은 DB 없이 메모리 저장소와 SQL 문자열 검사로 확인한다(soft delete 필터·정리 SQL 모양·V21 컬럼/인덱스 문자열 포함, 실제 PostgreSQL 실행 계획은 확인 안 함). pytest `tests/test_agent_summarize.py`는 모델을 가짜로 바꿔 확인한다. node:test `tests/conversations.test.mjs`는 API 모듈과 화면 상태 함수다. 실제 PostgreSQL에서의 V21(CASCADE · 부분 UNIQUE) 적용, Playwright·Electron 실행, 실제 모델 요약 품질은 확인하지 않았다.
