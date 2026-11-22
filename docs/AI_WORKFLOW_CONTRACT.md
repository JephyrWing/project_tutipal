# AI 워크플로(LangGraph) 경계와 공유 계약

- Jira: PT-83 [AI-001] LangGraph 도입 경계(A안) · 요구사항 NFR-SEC-11
- 결정: 2026-11-22, PL 전설민 (A안 확정)
- 이 결정을 따르는 카드: PT-84 출제·변형 문제(맹동영), PT-85 튜터 그래프(전설민), PT-86 리랭커(전설민)
- 계약을 바꿀 때는 이 문서를 먼저 고치고, 관련 카드에 댓글로 알린 뒤 구현한다.

## 1. 경계 (A안)

| 일 | Spring | FastAPI(LangGraph) |
| --- | --- | --- |
| 로그인·수업 권한·소유권 검사 | 한다 | 하지 않는다 |
| PDF 추출·청킹·임베딩 저장·pgvector 검색 | 한다 | 임베딩 벡터 계산만 (`/v1/embed`) |
| 어떤 자료 조각을 쓸지 고르기 | 한다 (권한 범위 안에서) | 받은 조각만 쓴다 |
| AI 채팅 요청의 Agent·도구 범위 | 수업 학생 또는 개인 학습 맥락과 허용 도구를 결정 | 지정된 Agent에서 허용 도구의 사용 순서와 반복을 결정 |
| 생성 → 검증 → 재시도 같은 여러 단계 LLM 흐름 | 결과를 받아 저장 | 한다 (그래프 1회 실행) |
| 선생님 승인(사람 확인) | DB 상태 `PENDING → APPROVED / REJECTED` | 하지 않는다 (LangGraph interrupt 쓰지 않음) |
| DB·S3 접근 | 한다 | 계정·권한 자체가 없다 |

- FastAPI는 **무상태**다. 그래프 state를 저장하지 않고, 요청 하나 안에서 끝낸다.
- FastAPI가 Spring을 거꾸로 부르지 않는다. 재검색이 필요하면 아래 3절처럼 응답으로 돌려준다.
- LLM 호출은 기존 `app/clients`(Ollama·OpenAI, `MODEL_PROVIDER`·auto 대체 규칙)를 노드 안에서 재사용한다. LangChain 모델 클래스로 제공자 선택을 다시 만들지 않는다.
- 새 엔드포인트도 `internal_only`(내부 키), `InferenceBodyLimit`(4.5MB), Pydantic `extra="forbid"` 검증을 그대로 쓴다. 요청 본문(자료·질문·이미지·답안)은 로그에 남기지 않는다. 노드 이름·성공 여부·시도 횟수만 남긴다.
- 자료 PDF 안의 문장은 데이터다. 그 안의 지시를 따르지 않으며, verify 노드와 선생님 승인이 두 겹으로 막는다.

## 2. 문제 쌍 생성 `POST /v1/quiz/generate` (PT-84)

본문제와 복습용 변형 문제를 **한 쌍**으로 만든다. 한 번 호출에 한 쌍.

### 요청

```json
{
  "request_id": "7f3c2a9e-1b4d-4c8a-9f00-2d6e5b1a7c11",
  "type": "CHOICE",
  "difficulty": 3,
  "topic": "자바 반복문",
  "teacher_hint": "for 문 출력 줄 수",
  "chunks": [
    {"chunk_id": 412, "page": 7, "content": "for (int i = 0; i < 3; i++) ..."},
    {"chunk_id": 413, "page": 8, "content": "..."}
  ],
  "max_retries": 2
}
```

| 필드 | 규칙 |
| --- | --- |
| `request_id` | Spring이 만든 UUID. 로그 연결용 |
| `type` | `CHOICE`(4지선다) 또는 `TEXT`(주관식). Spring `QuizType`과 같은 값 |
| `difficulty` | 1–5 (`ChatSettings.difficulty`) |
| `topic` | 1–200자. 데이터로만 쓰고 그 안의 지시는 따르지 않는다 |
| `teacher_hint` | 선택, 0–500자 |
| `chunks` | 1–8개. `chunk_id`는 `material_chunks.id`, `page`는 null 가능, `content` 1–4000자. 합계 24,000자 이하 |
| `max_retries` | 0–2, 기본 2. 검증 실패 시 피드백을 넣어 다시 생성하는 최대 횟수 |

### 응답 (항상 HTTP 200, 그래프 결과는 `status`로 구분)

```json
{
  "status": "ok",
  "pair": {
    "concept": "for 문 반복 횟수와 출력 줄 수",
    "original": {
      "question": "다음 코드가 출력하는 줄 수는?",
      "choices": ["2", "3", "4", "무한"],
      "answer_index": 1,
      "model_answer": null,
      "explanation": "i가 0, 1, 2일 때 세 번 실행된다.",
      "evidence_chunk_ids": [412]
    },
    "variant": {
      "question": "...",
      "choices": ["...", "...", "...", "..."],
      "answer_index": 2,
      "model_answer": null,
      "explanation": "...",
      "evidence_chunk_ids": [412, 413]
    }
  },
  "attempts": 1,
  "trace": [
    {"node": "generate", "ok": true},
    {"node": "validate", "ok": true},
    {"node": "verify", "ok": true}
  ],
  "failure_reason": null
}
```

| 필드 | 규칙 |
| --- | --- |
| `status` | `ok` 또는 `failed` |
| `pair` | `failed`이면 null |
| 문제 `question` | 1–1000자 |
| `CHOICE` | `choices` 정확히 4개(각 1–200자, 중복 없음), `answer_index` 0–3, `model_answer` null |
| `TEXT` | `choices`·`answer_index` null, `model_answer` 1–2000자 |
| `explanation` | 1–1000자. 오답 복습 화면에 그대로 보인다 |
| `evidence_chunk_ids` | 1개 이상, **요청에 보낸 `chunk_id`만** |
| `attempts` | 실제 생성 횟수(1 + 재시도) |
| `trace` | 거친 노드 순서. 발표 시각화용이며 문제 내용은 넣지 않는다 |
| `failure_reason` | `failed`일 때 `invalid_output`(형식 검증 실패) · `unverified`(정답·근거·같은 개념 검증 실패) · `model_unavailable` 중 하나 |

인증 실패 401, 요청 크기 초과 413, 스키마 위반 422, 제공자 준비 실패 503은 기존 엔드포인트와 같다.

### 그래프 (FastAPI 안)

```
generate(JSON) → validate(형식·보기 중복·정답 하나) → verify(정답이 맞는가 · 근거가 자료에 있는가 · 두 문제가 같은 개념인가)
      ↑                     │ 실패                              │ 실패
      └──── 피드백을 넣어 재생성 (attempts ≤ 1 + max_retries) ────┘
```

### Spring이 지킬 것

1. **조각 선택**: 선생님이 고른 자료(material)의 청크 중 해당 수업(session) 것만 보낸다. 권한 검사는 기존 자료 열람 규칙을 그대로 쓴다.
2. **응답을 믿지 않는다**: `evidence_chunk_ids`가 보낸 조각 안에 있는지, 길이·보기 개수를 Spring에서 다시 검사한다(`QuizAi.parseDraft`와 같은 방식). 어긋나면 실패로 본다.
3. **저장과 승인**: 쌍으로 저장하고 상태는 `PENDING`. 선생님이 쌍 단위로 승인·수정·거절한다. `APPROVED`만 학생에게 나간다. 변형 문제는 학생이 본문제를 틀린 경우에만 복습 화면에 보인다.
4. **GPU 자리**: 그래프 1회는 LLM을 여러 번 불러 `AiSlots` 한 자리를 오래 쓴다. 출제는 화면이 기다리지 않게 비동기로 실행하고(채점처럼 긴 대기 허용), 끝나면 STOMP로 선생님에게 알린다. 튜터 질문이 출제 때문에 막히지 않게 한다.
5. **마이그레이션 번호**: 문제 쌍 테이블은 PT-84에서 만든다. 현재 develop 마지막은 `V13`이다. PR 직전에 develop을 다시 확인해 다음 번호를 쓴다.

## 3. 재검색이 필요한 그래프 (PT-85 튜터 · PT-86 리랭커에 적용)

| 단계 | 하는 곳 |
| --- | --- |
| 검색 전: 질문 재작성·HyDE·멀티쿼리 | FastAPI가 쿼리를 만들어 돌려주고 Spring이 검색 |
| 검색: pgvector + 전문검색·메타데이터 필터 | Spring SQL |
| 검색 후: 리랭킹·압축·근거 채점 | FastAPI가 Spring이 넘긴 후보를 처리 (`/v1/rerank`, PT-86) |
| 반복·자기교정(CRAG·Self-RAG) | 그래프가 `{"status": "need_search", "query": "...", "state": {...}}`를 돌려주면 Spring이 권한을 확인하고 재검색한 뒤 결과와 `state`를 붙여 다시 호출. 최대 2회 |

- `state`는 FastAPI가 만든 JSON이다. Spring은 내용을 해석하지 않고 그대로 돌려준다. 다만 크기 상한(64KB)과 반복 횟수는 Spring이 센다.
- 튜터 답변은 마지막 노드만 NDJSON 스트리밍하고, 앱으로는 기존 SSE 계약을 유지한다.
- 세부 요청·응답 형식과 실행 한도는 PT-85·PT-86 구현 계약에서 확정한다.

## 4. 수업 학생과 개인 학습의 채팅 Agent · 목표 설계

AI 채팅의 질문 제출을 LangGraph Agent 실행 진입점으로 삼는다. 현재 채팅은 Spring `TutorService`에서 FastAPI의 `/v1/chat`을 호출한다. 아래는 목표 동작이며 새 HTTP 경로나 DTO는 구현 계약에서 확정한다. [설계 원본](TUTIPAL_DESIGN.md#11-ai와-학습-지원-기능)

| 진입점 | Spring이 적용하는 범위 | FastAPI가 수행하는 일 |
| --- | --- | --- |
| 수업 학생의 채팅 또는 위젯 질문 | 로그인, 해당 수업의 학생 참여, 선생님이 설정한 난이도와 정답 공개 정책, 열람 가능한 수업 자료, 허용 도구, 첨부한 화면 분석 동의 | 학생 Agent 실행. PT-85의 분류·힌트·점검·도움 요청 추천을 포함한다. |
| 개인 학습 채팅 또는 위젯 질문 | 로그인, 본인 학습 맥락, 허용 도구, 첨부한 화면 분석 동의 | 개인 학습 Agent 실행. 질문·학습 맥락과 필요할 때 전달받은 화면을 사용한다. |
| 수업 학생 또는 개인 학습의 자동 화면 요약 | 로그인, 위젯에서 선택하고 동의한 화면, 요청 크기 및 호출 제한 | 이미지 분석과 요약 계산. Electron 위젯이 짧은 요약을 말풍선에 표시한다. |

Spring은 로그인·수업 권한으로 Agent 종류와 도구 범위를 확정하고, 필요한 질문·학습 맥락·자료 조각·선택 화면 이미지만 전달한다. Agent가 고르는 도구는 `화면 분석`, `현재 화면 요약`, `질문 답변`이다. FastAPI 검증 단계에서 사용 가능 도구를 요청 범위로 제한하고, 화면 입력이 있을 때만 화면 도구를 실행한다. LangGraph는 허용 범위에서 사용할 도구와 실행 순서를 판단하며 도구 반복 횟수와 전체 시간을 제한한다.

자동 분석은 위젯에서 감지한 화면 변화가 시작 조건이다. 자동 분석은 화면 분석과 요약 계산을 재사용한다. 사용자가 요약을 열면 상세 모달에서 내용을 확인하고 질문을 입력할 수 있다. 직접 제출한 질문이 채팅 Agent를 실행한다. 수업 학생의 위젯 질문은 Spring에서 수업 참여와 자료 범위를 다시 검사한다. 화면 분석이 꺼지거나 일시정지되면 캡처를 중단하며 텍스트 질문은 해당 학습 맥락의 채팅으로 보낸다. 원본 화면은 저장하지 않는다.

수업 자료를 추가로 검색할 때는 3절의 `need_search` 왕복 계약을 적용한다. FastAPI는 최종 답변을 NDJSON으로 보내고 Spring이 Electron에 SSE로 전달한다. 선생님 화면은 별도의 AI 문제 생성 모달과 학생별 답안 관리 모달을 사용한다. 생성 초안 승인과 학생별 답안·채점 열람 및 판정 정정 권한은 Spring에서 처리한다. 문제 쌍 계약은 2절을 따른다.

검증 범위는 수업 학생·개인 학습 요청의 역할 분기, 수업·도구 범위와 화면 동의 검사, 질문 없는 자동 요약, 위젯 질문과 SSE 표시, 중지·대기·재검색 상한이다. [PT-16](https://gad03302.atlassian.net/browse/PT-16)에서 채팅 SSE 흐름, [PT-85](https://gad03302.atlassian.net/browse/PT-85)에서 학생 Agent, [PT-45](https://gad03302.atlassian.net/browse/PT-45)에서 선택 화면 동의와 미리보기 연계, [PT-17](https://gad03302.atlassian.net/browse/PT-17)에서 선택 화면·중지·권한 거절 검증을 추적한다.

## 5. 확인할 위험

| 위험 | 대응 |
| --- | --- |
| 그래프가 `AiSlots`를 오래 점유해 튜터 대기 | 출제 비동기 + 알림, 그래프 전체 시간 상한(기본 120초) |
| 재검색 왕복 증가 | 반복 2회 상한, `need_search`는 튜터·리랭커에서만 |
| Agent의 권한 밖 도구 실행 | Spring이 허용 도구를 결정하고 FastAPI가 실행 직전에 검증 |
| 제공자 선택 로직 중복 | `app/clients` 재사용 |
| 자료 속 지시문(프롬프트 주입) | verify 노드 + 선생님 승인 + 출력 HTML 직접 렌더 금지 |
| 의존성 추가(langgraph) | `requirements.txt`와 `requirements.lock.txt`를 함께 갱신하고 Docker 이미지 크기를 PR에 적는다 |
