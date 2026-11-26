# AI 워크플로(LangGraph) 경계와 공유 계약

- Jira: PT-83 [AI-001] LangGraph 도입 경계(A안) · 요구사항 NFR-SEC-11
- 결정: 2026-11-22, PL 전설민 (A안 확정)
- 2026-11-25 PT-85 §6·§7 추가 (Agent 컨텍스트 계약, `/v1/agent/chat` 요청·응답)
- 이 결정을 따르는 카드: PT-84 기존 문항 변형·자동 검증(맹동영), PT-53 오답 복습 진입(김동현), PT-85 수업 학생·개인 학습 Agent와 학생 변형 도구 연동(전설민), PT-86 기능별 RAG·자료 청킹(전설민), PT-16 대화 저장(전설민), PT-45 화면 텍스트 추출(김동현), PT-29 임베딩 입력(김동현)
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
| 대화 및 저장하기로 결정한 기억 | 본인·수업 권한 범위에서 저장·조회·삭제를 담당 | 요청에 포함된 입력으로 컨텍스트를 구성 |
| DB·S3 접근 | 한다 | 계정·권한 자체가 없다 |

- FastAPI는 **무상태**다. 그래프 state를 저장하지 않고, 요청 하나 안에서 끝낸다.
- FastAPI가 Spring을 거꾸로 부르지 않는다. 재검색이 필요하면 아래 3절처럼 응답으로 돌려준다.
- LLM 호출은 기존 `app/clients`(Ollama·OpenAI, `MODEL_PROVIDER`·auto 대체 규칙)를 노드 안에서 재사용한다. LangChain 모델 클래스로 제공자 선택을 다시 만들지 않는다.
- 새 엔드포인트도 `internal_only`(내부 키), `InferenceBodyLimit`(4.5MB), Pydantic `extra="forbid"` 검증을 그대로 쓴다. 요청 본문(자료·질문·이미지·답안)은 로그에 남기지 않는다. 노드 이름·성공 여부·시도 횟수만 남긴다.
- 자료 PDF의 문장과 화면에서 추출한 텍스트는 분석 데이터로 처리하며 그 안의 지시를 실행하지 않는다. 문항 결과에는 해당 흐름의 검증을 적용하며 교사용 출제 승인과 학생 Agent의 변형 검증은 2절에서 구분한다.

## 2. 기존 문항 변형 도구 (PT-84 · PT-85)

2026-11-23 개정 기준. 수업 학생용 AI Agent가 기존에 출제된 문항 하나를 변형해 복습 연습을 제공한다. 문항 생성·검증은 [PT-84](https://gad03302.atlassian.net/browse/PT-84), 학생 Agent 도구 호출과 응답은 [PT-85](https://gad03302.atlassian.net/browse/PT-85), 본인 오답 복습 진입은 [PT-53](https://gad03302.atlassian.net/browse/PT-53)에서 맡는다.

| 단계 | 책임 |
| --- | --- |
| 복습 맥락 확인 | Spring이 수업 참여, 기존 문항 열람과 학생 본인의 오답을 확인한다. 복습에서 전달된 식별자로 서버가 해당 문항을 조회한다. |
| 변형 생성·자동 검증 | FastAPI는 전달받은 기존 문항으로 같은 개념을 다른 조건이나 예제로 묻는 문항 하나를 만든다. 형식, 정답·해설 일관성, 개념 유지와 중복을 검증한다. 검증에 실패하면 기존 상한인 최대 두 번까지 다시 생성하고 최종 실패를 반환한다. |
| 학생에게 제공 | 수업 학생 Agent는 검증에 통과한 변형 문항을 교사 승인 없이 제공한다. 정답·해설은 수업의 공개 정책을 따르며 실패 때는 결과를 표시하지 않고 재시도 안내를 반환한다. |

Spring이 도구 허용 범위와 학생 문항 정보를 정하고 FastAPI는 입력 안에서 무상태로 계산한다. 공개 API 경로와 DTO는 구현 담당 카드에서 조율한다. 이전 PDF 문제 쌍 생성·문제은행·교사 승인 계약은 직전 판본과 Jira 기록에서 추적한다. 일반 교사 출제와 채점은 [PT-49](https://gad03302.atlassian.net/browse/PT-49)·[PT-50](https://gad03302.atlassian.net/browse/PT-50)의 흐름을 따른다.

[PT-73](https://gad03302.atlassian.net/browse/PT-73)에서 본인 오답, 다른 학생·수업, 정답·미응답, 재판정, 생성 실패, 정답 공개 정책을 확인한다.

## 3. 기능별 RAG와 그래프 재검색 (PT-85 · PT-86)

Spring의 pgvector 코사인 검색을 비교 기준으로 삼는다. [PT-86](https://gad03302.atlassian.net/browse/PT-86) 담당자는 실제 기능과 같은 질의 세트에서 BM25·GraphRAG 결합, RRF, 리랭킹과 문서 청킹을 필요한 범위로 비교한다. 근거 적중·출처 정합성·지연·자원 비용으로 채택을 설명하고 수업 및 임베딩 출처 격리, 근거가 없을 때의 전환, FR-MAT-04 출처 최대 3개를 검증한다. FR-MAT-05의 리랭킹과 청킹 개선은 선택한 기능 범위와 증거를 기록한다. 문서 구조·청킹은 [PT-86](https://gad03302.atlassian.net/browse/PT-86)에서, 모델과 문서·질의 입력 전처리 및 벡터 호환성·재인덱싱 기준은 [PT-29](https://gad03302.atlassian.net/browse/PT-29)에서 정한다.

다음 표에서 비교 후보를 채택했을 때의 실행 위치를 정한다. 검색과 자료 권한은 Spring이 담당한다.

| 단계 | 하는 곳 |
| --- | --- |
| 검색 전: 질문 재작성·HyDE·멀티쿼리 | FastAPI가 쿼리를 만들어 돌려주고 Spring이 검색 |
| 코사인 검색 또는 채택한 하이브리드 검색 | Spring이 같은 수업·출처 범위에서 검색 |
| 검색 후보의 순위 결합 | 채택한 검색 계약과 권한 범위에 따라 Spring이 적용 |
| 선택한 재순위화·근거 채점·압축 | FastAPI가 Spring이 넘긴 후보에 대해 요청 단위로 계산 |
| 반복·자기교정(CRAG·Self-RAG) | 그래프가 `{"status": "need_search", "query": "...", "state": {...}}`를 돌려주면 Spring이 권한을 확인하고 재검색한 뒤 결과와 `state`를 붙여 다시 호출. 최대 2회 |

- `state`는 FastAPI가 만든 JSON이다. Spring은 내용을 해석하지 않고 그대로 돌려준다. 다만 크기 상한(64KB)과 반복 횟수는 Spring이 센다.
- 튜터 답변은 마지막 노드만 NDJSON 스트리밍하고, 앱으로는 기존 SSE 계약을 유지한다.
- 세부 요청·응답 형식과 실행 한도는 PT-85·PT-86 구현 계약에서 확정한다. 튜터 Agent의 형식과 한도는 [7절](#7-agent-실행-요청응답-post-v1agentchat)에서 확정했다. 고정한 재검색 상한과 Spring 권한 경계를 적용한다.

### 3.1 검색 후보 재순위화 `/v1/rerank` (PT-86, 2026-11-25)

Spring이 같은 수업·같은 임베딩 모델 범위에서 고른 후보만 FastAPI cross-encoder가 점수화한다. FastAPI는 요청 하나 안에서 계산하고 아무것도 저장하지 않는다. 채택 여부는 `docs/PT-86_RAG_EVAL.md`의 사전 등록 기준(sealed 질의)으로 정하며, 기준을 넘기 전에는 기능 플래그를 끈 채로 병합한다.

**요청·응답**

```
POST /v1/rerank          헤더 X-Internal-Key (internal_only만 적용, 모델 제공자 검사 없음)
{"query": "…", "documents": ["…", "…"]}
200 {"model": "<모델 이름>", "scores": [1.2, -0.4]}
```

| 항목 | 규칙 |
| --- | --- |
| `query` | 문자열 1~1000자, 공백만 있는 값 거부 |
| `documents` | 문자열 1~2000자 항목 1~32개, 공백만 있는 항목 거부 |
| 빈 값 | 공백과 서식 문자(zero-width space, BOM 등)만 있는 `query`·항목도 422 |
| 추가 필드 | `extra="forbid"` → 422 (오류 본문에 입력값을 다시 넣지 않음) |
| `scores` | 입력과 같은 순서·같은 길이의 실수. 높을수록 관련. 범위는 모델마다 다르며(logit) Spring은 순서만 쓴다 |
| 503 | 모델 파일 없음·모델 읽기 실패, `RERANK_ENABLED`가 `true`가 아님, `RERANK_*` 숫자 설정이 잘못됨(재순위화만 꺼지고 앱은 정상 기동), 앞 요청을 `RERANK_QUEUE_TIMEOUT_MS`보다 오래 기다림(`"rerank busy"`), 기다리는 사이 클라이언트가 연결을 끊음 |
| 500 | 추론 실패·점수 개수 불일치 (본문은 고정 문구) |
| 로그 | 모델 이름·후보 수·소요 ms만. 질문·조각 원문은 남기지 않는다 |

**Spring 검색 흐름** (`RagSearchService.search(sessionId, query, limit)`, `RagHit` 형식 변경 없음, `similarity`는 항상 코사인 값)

| 설정 (`application.yaml`) | 기본값 | 의미 |
| --- | --- | --- |
| `tutipal.rag.rerank.enabled` | `false` | 끄면 기존과 똑같이 `limit`개 코사인 검색 → 0.5 미만 제외 |
| `tutipal.rag.rerank.candidates` | `10` (측정·동결값) | 켜면 가져올 후보 수 N. 1~32로 보정. N이 `limit`보다 작으면(1~2) 코사인으로 돌아가는 경로(min-candidates 미만·주소 없음·재순위화 실패)에서만 `limit`개로 한 번 더 읽어 꺼진 경로와 같은 수를 돌려준다. 재순위화가 성공하면 결과는 N개 이하다 |
| `tutipal.rag.rerank.timeout` | `2s` | 요청 전체 읽기 제한 (연결 제한 1s) |
| `tutipal.rag.rerank.min-candidates` | `2` | 0.5 필터 뒤 후보가 이보다 적으면 재순위화하지 않음 |

- 켜졌을 때: 후보 N개 → 0.5 미만 제외 → 0개면 빈 목록(일반 답변 전환, 재순위화 호출 없음) → `min-candidates` 미만이면 코사인 순서 상위 `limit` → 그 밖에는 점수 내림차순(같은 점수는 코사인 순서 유지) 상위 `limit`. 튜터는 `limit=3`(FR-MAT-04 출처 최대 3개).
- 결과는 항상 Spring이 고른 후보의 부분집합이다. FastAPI 응답은 순서를 바꾸는 데만 쓴다.
- 주소와 내부 키는 기존 `AiClient`와 같은 `app.ai.base-url`·`app.ai.internal-key`(`FAST_API_BASE_URL`·`AI_INTERNAL_KEY`)를 쓴다.
- `RagSearchService`에는 트랜잭션을 걸지 않는다. 재순위화 HTTP 대기 동안 DB 연결을 잡지 않기 위해서이며, 저장소 호출은 각각 독립된 읽기다.
- Spring은 보내기 전에 질문을 1000자, 조각을 2000자로 자른다(서로게이트 쌍은 나누지 않음). 응답의 `RagHit.content`는 자르지 않은 원문이다.
- `RagSearchService` 공개 생성자가 2개 인자에서 4개 인자(`RerankClient`, `RagRerankProperties` 추가)로 바뀌었다. Spring 주입은 그대로이고, 직접 생성하는 코드(PT-52/71 쪽)만 맞춰야 한다.

**코사인 순서로 전환하는 경우** (코사인 상위 `limit` 반환, 로그에는 사유 코드만, 사유마다 1분에 한 번 + 생략 횟수): 연결 실패(`connect`)·시간 초과(`timeout`)·중단(`interrupted`), 4xx/5xx(503 포함, `status`), 점수 개수 불일치(`size`), NaN/Infinity(`nonfinite`), 해석할 수 없거나 64KB를 넘는 본문(`body`, chunked 응답도 넘는 즉시 끊음), 켜져 있지만 주소가 비어 있음(`unconfigured`).

**FastAPI 설정**: `RERANK_ENABLED`(기본 `false`), `RERANK_MODEL_DIR`(Docker 이미지 `/models/reranker`), `RERANK_MODEL_NAME`, `RERANK_MAX_LENGTH`(기본 256 토큰, 측정·동결값. 질문·조각 쌍을 잘라 냄), `RERANK_THREADS`(기본 2, ONNX intra-op), `RERANK_QUEUE_TIMEOUT_MS`(기본 1000, Spring timeout 2s 안에 503이 도착하도록). 모델은 시작 시 한 번 읽고, 추론은 스레드에서 한 번에 하나씩(Semaphore 1) 실행한다. 앞 요청을 `RERANK_QUEUE_TIMEOUT_MS`보다 오래 기다리면 503을 돌려주고, 기다리는 사이 연결이 끊긴 요청은 추론하지 않는다. 1순위 모델은 `cross-encoder/mmarco-mMiniLMv2-L12-H384-v1`(Apache-2.0)의 양자화 ONNX이며 고정 revision과 sha256을 Dockerfile에서 확인한다. Docker 이미지에는 `--build-arg WITH_RERANKER=true`일 때만 모델이 들어간다(기본 false, 채택 전까지는 huggingface.co 접근 없음). torch는 쓰지 않는다.

**측정·동결 설정** (`docs/PT-86_RAG_EVAL.md` 3절): 모델 a, N=10, `RERANK_MAX_LENGTH=256`, 스레드 2, 대기 상한 1000ms. 리더 판정: 코드 기본값 = 측정값(`application.yaml` `candidates: 10`, `settings.py` 256/1000). compose·env 예시·Dockerfile도 같은 값이다. 측정하지 않은 20/512는 dev p95가 3.8~8.9s로 Spring timeout 2s를 넘었다.

**청킹 출처와 PT-29 재인덱싱**

- `material_chunks.chunker`(V20, 기본 `char800-v1`)에 조각을 만든 청커를 기록한다. 검색 필터가 아니며 출처 격리는 계속 `embedding_model`로 한다.
- `tutipal.materials.chunker`: `char800-v1`(기존 문단·800자 분할, 기본값) 또는 `section-v1`(제목 경로·문장 단위 분할). 바꾼 뒤 기존 자료는 `docs/RAG_REINDEX.md` 절차로 다시 인덱싱한다(강제하지 않음).
- `section-v1` 조각은 제목이 있으면 첫 줄이 `[1장 > 1.2 절]` 형태의 제목 경로이고 다음 줄부터 본문이다. 이 줄도 임베딩 입력에 포함되므로 PT-29의 문서 입력 전처리 기준에서 함께 다룬다. 튜터 프롬프트(PT-85)에 그대로 들어가며 `RagHit.content` 형식(문자열)은 바뀌지 않는다.

## 4. 수업 학생과 개인 학습의 채팅 Agent · 목표 설계

AI 채팅의 질문 제출을 LangGraph Agent 실행 진입점으로 삼는다. 현재 채팅은 Spring `TutorService`에서 FastAPI의 `/v1/chat`을 호출한다. 아래는 목표 동작이며 새 HTTP 경로나 DTO는 구현 계약에서 확정한다([6절 컨텍스트](#6-agent-컨텍스트-계약-pt-85--pt-106--pt-16) · [7절 요청·응답](#7-agent-실행-요청응답-post-v1agentchat)). [설계 원본](TUTIPAL_DESIGN.md#11-ai와-학습-지원-기능)

| 진입점 | Spring이 적용하는 범위 | FastAPI가 수행하는 일 |
| --- | --- | --- |
| 수업 학생의 채팅 또는 위젯 질문 | 로그인, 해당 수업의 학생 참여, 선생님이 설정한 난이도와 정답 공개 정책, 열람 가능한 수업 자료, 허용 도구, 분석 중인 선택 창 첨부 | 학생 Agent 실행. PT-85의 분류·힌트·점검·도움 요청 추천을 포함한다. |
| 개인 학습 채팅 또는 위젯 질문 | 로그인, 본인 학습 맥락, 허용 도구, 분석 중인 선택 창 첨부 | 개인 학습 Agent 실행. 질문·학습 맥락과 필요할 때 전달받은 화면을 사용한다. |
| 수업 학생 또는 개인 학습의 자동 화면 요약 | 로그인, 위젯에서 선택해 분석을 시작한 창, 요청 크기 및 호출 제한 | 이미지 분석과 요약 계산. Electron 위젯이 짧은 요약을 말풍선에 표시한다. |

Spring은 로그인·수업 권한으로 Agent 종류와 도구 범위를 확정하고, 필요한 질문·학습 맥락·자료 조각·선택 화면 이미지만 전달한다. 공통 도구는 `화면 분석`, `현재 화면 요약`, `질문 답변`이다. 수업 학생 Agent는 권한 확인된 복습 맥락에서 `기존 문항 변형` 도구도 실행한다. FastAPI 검증 단계에서 사용 가능 도구를 요청 범위로 제한하고, 화면 입력이 있을 때만 화면 도구를 실행한다. LangGraph는 허용 범위에서 사용할 도구와 실행 순서를 판단하며 도구 반복 횟수와 전체 시간을 제한한다.

자동 분석은 위젯에서 감지한 화면 변화가 시작 조건이다. 자동 분석은 화면 분석과 요약 계산을 재사용한다. 사용자가 요약을 열면 상세 모달에서 내용을 확인하고 질문을 입력할 수 있다. 직접 제출한 질문이 채팅 Agent를 실행한다. 수업 학생의 위젯 질문은 Spring에서 수업 참여와 자료 범위를 다시 검사한다. 화면 분석이 꺼지거나 일시정지되면 캡처를 중단하며 텍스트 질문은 해당 학습 맥락의 채팅으로 보낸다. 원본 화면은 저장하지 않는다.

수업 자료를 추가로 검색할 때는 3절의 `need_search` 왕복 계약을 적용한다. FastAPI는 최종 답변을 NDJSON으로 보내고 Spring이 Electron에 SSE로 전달한다. 선생님 화면은 별도의 AI 문제 생성 모달과 학생별 답안 관리 모달을 사용한다. 생성 초안 승인과 학생별 답안·채점 열람 및 판정 정정 권한은 Spring에서 처리한다. 학생 Agent의 기존 문항 변형 계약은 2절을 따른다.

검증 범위는 수업 학생·개인 학습 요청의 역할 분기, 수업·도구 범위와 선택 창·분석 상태 검사, 질문 없는 자동 요약, 위젯 질문과 SSE 표시, 중지·대기·재검색 상한이다. [PT-16](https://gad03302.atlassian.net/browse/PT-16)에서 채팅 SSE 흐름, [PT-85](https://gad03302.atlassian.net/browse/PT-85)에서 학생 Agent, [PT-45](https://gad03302.atlassian.net/browse/PT-45)에서 창 선택·미리보기·시작/중지 연계, [PT-17](https://gad03302.atlassian.net/browse/PT-17)에서 선택 화면·중지·권한 거절 검증을 추적한다.

### 데이터와 컨텍스트 설계 선택

| 작업 | 구현하면서 선택할 내용 | 확인할 증거 |
| --- | --- | --- |
| [PT-45](https://gad03302.atlassian.net/browse/PT-45) 화면 텍스트 추출 · [PT-17](https://gad03302.atlassian.net/browse/PT-17) 검증 | 선택 창의 전처리, DL 기반 OCR 또는 비전 모델 후보, 실행 위치 | 대표 한글·영문·코드 화면의 추출 품질·지연, 텍스트 없음·실패·중지 처리와 원본 미저장 |
| [PT-16](https://gad03302.atlassian.net/browse/PT-16) 대화 저장 | 사용자가 보는 대화 원문·요약과 Agent 단기 상태·장기 기억의 지속 저장 범위, Spring의 조회·삭제·재개 구조 | 본인·수업·개인 학습의 격리, 대화 재개, 삭제 후 기억 재사용 차단. 보관·외부 전송 정책은 [PT-13](https://gad03302.atlassian.net/browse/PT-13)에서 추적 |
| [PT-85](https://gad03302.atlassian.net/browse/PT-85) Agent 컨텍스트 | 최근 대화·요약·관련 기억 범위, 토큰 예산, 압축·제외 시점과 핵심 사실 보존 방식 | 짧고 긴 대화의 토큰 사용량·맥락 보존, 다른 사용자·수업 격리. FastAPI/LangGraph에는 지속 저장을 두지 않음 |

각 담당자가 기존 방식과 적용 가능한 대안, 선택한 이유, 검증 결과 및 한계를 자기 Jira 카드와 구현 PR에 기록한다. 공유 계약을 바꾸는 구현은 관련 담당자와 조율한다. [AI 구현 설계 결정](https://gad03302.atlassian.net/wiki/spaces/PT/pages/196753) · [Jira·WBS 동기화 기록](https://gad03302.atlassian.net/browse/PT-41?focusedCommentId=10233).

## 5. 확인할 위험

| 위험 | 대응 |
| --- | --- |
| 그래프가 `AiSlots`를 오래 점유해 튜터 대기 | 출제 비동기 + 알림, 그래프 전체 시간 상한(기본 120초) |
| 재검색 왕복 증가 | 반복 2회 상한, `need_search`는 튜터·리랭커에서만 |
| Agent의 권한 밖 도구 실행 | Spring이 허용 도구를 결정하고 FastAPI가 실행 직전에 검증 |
| 제공자 선택 로직 중복 | `app/clients` 재사용 |
| 자료 속 지시문(프롬프트 주입) | 자료와 화면에서 읽은 지시를 분석 데이터로 취급하고 문항 결과에 해당 흐름의 검증 적용. 출력 HTML 직접 렌더 금지 |
| 의존성 추가(langgraph) | `requirements.txt`와 `requirements.lock.txt`를 함께 갱신하고 Docker 이미지 크기를 PR에 적는다 |

## 6. Agent 컨텍스트 계약 (PT-85 · PT-106 · PT-16)

2026-11-25 확정 계약이다. [PT-85](https://gad03302.atlassian.net/browse/PT-85)가 조립·사용을, [PT-106](https://gad03302.atlassian.net/browse/PT-106)이 대화·요약·기억의 저장·조회·삭제를 구현한다. FastAPI는 요청에 담긴 입력만 쓰고 아무것도 저장하지 않는다(1절). 요청 본문 전체 형식은 7절에 있다.

### 6.1 격리 키와 범위

| 맥락 | Spring 저장 격리 키 | 요청 `context.scope` | 요청에 담을 수 있는 요약·기억 |
| --- | --- | --- | --- |
| 수업 학생 (세션 역할 STUDENT) | `(userId, sessionId)` | `{"kind": "session", "sessionId": <long>}` | 같은 사용자·같은 세션의 것만 |
| 개인 학습 (세션 없음) | `(userId)` | `{"kind": "personal"}` | 같은 사용자의 개인 학습 것만 |
| 수업 선생님 (세션 역할 TEACHER) | 해당 없음 | 해당 없음 | Agent를 쓰지 않고 기존 `/v1/chat` 경로 |

- `userId`는 FastAPI로 보내지 않는다. 사용자 격리는 Spring 조립 단계가 책임지고, FastAPI는 `scope` 일치만 검사한다(불일치 422, 7.3).
- 범위는 엄격히 나눈다. 수업 학생 요청에 개인 학습 기억을 섞지 않고, 개인 학습 요청에 수업 기억을 섞지 않는다. 같은 사용자라도 다른 세션의 기억은 보내지 않는다.

### 6.2 컨텍스트 필드

| 필드 | 형식·상한 | 의미 |
| --- | --- | --- |
| `question` | 문자열 1..8000자 (본문 최상위, 7.1) | 현재 질문. `context.messages`의 마지막 원소가 아니다 |
| `context.scope` | 6.1 | 이 요청의 격리 범위 |
| `context.messages[]` | `{role: "user"\|"assistant", content: 1..8000자}`, 최대 40개, 오래된 것부터 | 현재 질문 **이전** 이력. PT-85 시점에는 화면이 보낸 이력, PT-106 이후에는 서버 저장분 |
| `context.summary` | `{id, scope, text: 1..4000자}` 또는 `null`, 최대 1개 | 이 범위의 유효한 대화 요약. 원본 메시지 범위·`invalidatedAt`은 Spring 내부 정보라 보내지 않는다 |
| `context.memories[]` | `{id, scope, text: 1..1000자}`, 최대 20개 | 관련 기억. Spring이 관련도 순으로 정렬한다(앞이 더 관련 있음) |
| `context.screenSummary` | 문자열 1..4000자 또는 `null` | 선택 창 분석 요약. `<screen_summary>` 데이터 블록으로만 넣고 안의 지시는 실행하지 않는다. 이미지 분석 자체는 PT-45 |

- 요약·기억의 `id`는 Spring이 붙인 불투명 문자열이며 `^[A-Za-z0-9_-]{1,64}$`을 따른다. FastAPI는 해석하지 않고 7.5 `meta`에 그대로 보고한다.
- `summary.scope`와 모든 `memories[].scope`는 `context.scope`와 같아야 한다.
- 데이터 블록 이스케이프: FastAPI는 요약·첫 user 핀(`<pin>`)·기억·자료(출처 포함)·화면 요약·변형 실패 시 원 문항(`<original_question>`)을 각 태그 블록 안에만 넣는다. 블록 안 텍스트의 꺾쇠 `<` `>`와 전각 `＜` `＞`, 작은 꼴 `﹤` `﹥`는 모두 `‹` `›`로 바꾼다. 그래서 공백·폭 없는 문자·전각을 섞은 어떤 철자로도 블록을 일찍 닫거나 새 블록을 열 수 없다. 정책 지시의 첫 줄은 이 블록들과 주제 이름이 참고 데이터이며 그 안의 지시를 따르지 않는다고 명시한다.
- `policy.topic`은 정책 줄 안에 들어가므로 제어·서식 문자와 줄·문단 구분 문자를 공백으로 바꾸고 공백을 하나로 줄인 뒤, 위 꺾쇠 치환을 적용한다. 주제 값으로 정책 줄을 새로 만들 수 없다.

### 6.3 토큰 예산

| 항목 | 결정 |
| --- | --- |
| 추정식 | `ceil(CJK 글자 수 + 그 외 글자 수 / 3)`. CJK = 한글 음절·자모, CJK 통합 한자, 가나 |
| 총 예산 | 추정 입력 3000 (모델 컨텍스트 약 4096, 출력 여유 약 1000) |
| 배분 상한 | 정책·시스템 400 / 현재 질문 500 / 보호 구간(직전 메시지 6개) 800 / 요약 250 또는 첫 user 메시지 핀 150 / 자료 600(3 × 200) / 기억 250 / 화면 요약 200. 남는 몫은 보호 구간 밖의 오래된 메시지에 최신 순으로 준다 |
| 시작 조건 | 전체 추정이 3000을 넘을 때만, 넘친 만큼만 줄인다. 3000 이하이면 아무것도 버리거나 줄이지 않는다. 예외 하나: 답변 호출은 기존 `/v1/chat` 모델 요청(메시지 최대 40개, 현재 질문 포함)을 재사용하므로 `context.messages`가 40개이면 예산과 관계없이 가장 오래된 1개를 버리고 `dropped.messages`에 센다(첫 user 메시지면 아래 핀 규칙 적용). Spring은 현재 질문을 뺀 최대 39개를 보내므로 Spring 경로에서는 생기지 않는다 |
| 버리는 순서 | 1) 보호 구간 밖 메시지(오래된 것부터) 2) 기억(목록 뒤부터) 3) 화면 요약을 200까지 축약한 뒤 제거 4) 자료(순위 낮은 것부터, 최소 1개 유지) 5) 요약을 150까지 축약 6) 보호 구간 메시지를 오래된 것부터 각 120으로 축약 |
| 보존 | 정책 지시(`hideAnswers` 금지 문구 포함, 질문 분류와 무관하게 항상), 현재 질문(머리 300 + 꼬리 200 축약 허용), 보호 구간 6개(축약만, 제거 안 함), 요약이 있으면 요약, 없으면 첫 user 메시지 150 핀 |
| 결정성 | 질문 분류(`route`)·`hintLevel`·`suggestHelp`는 현재 질문과 보호 구간 6개의 **축약 전 원문**으로만 계산한다. 보호 구간이 같으면 대화 길이·요약·기억과 무관하게 같은 결과 |
| 턴 안 요약 | 턴 안에서 요약·기억을 만들지 않는다(추가 LLM 호출 금지). 넘치면 위 순서대로 버리고 줄이기만 한다 |

### 6.4 응답 보고

정상 종료의 마지막 줄 `{"meta": {...}}`(7.5)에 아래를 담는다.

| 필드 | 의미 |
| --- | --- |
| `meta.context.estimatedInputTokens` | 최종 지시문 전체의 추정 토큰 수 |
| `meta.context.budget` | 적용한 총 예산 (3000) |
| `meta.context.dropped` | `{messages, memories, materials, screenSummary}` 버린 개수. `screenSummary`는 0 또는 1 |
| `meta.context.truncated[]` | 축약한 항목 이름. `question`, `protected`, `summary`, `screenSummary`, `materials` 중에서. 첫 user 핀은 버린 메시지로 만드는 150 표현이라 truncated에 넣지 않는다(dropped.messages로 센다) |
| `meta.usedMemoryIds[]` | 지시문에 실제로 넣은 기억 id. 요청 `memories[].id`의 부분집합 |
| `meta.usedSummaryId` | 넣은 요약의 id (요청 `summary.id`) 또는 `null` |

### 6.5 요약·기억의 생성과 저장 주체

| 일 | 주체 |
| --- | --- |
| 대화 메시지·요약·기억 저장·조회·삭제 | Spring (PT-106). 격리 키는 6.1 |
| 요약 계산 | Spring이 턴 밖에서(예: 메시지가 쌓였을 때) 예약 경로 `POST /v1/agent/summarize`를 호출하고 결과를 저장. FastAPI는 무상태 |
| 기억 생성 기준 | PT-106이 정한다. 같은 scope 규칙(6.1)을 따르고, 요청에는 `{id, scope, text}`만 실린다 |
| 요약 원본 범위 | Spring이 요약마다 원본 메시지 범위(예: 처음·마지막 메시지 id)를 내부에 저장해 6.6 무효화에 쓴다 |

`/v1/agent/summarize`는 경로와 입출력만 예약하고 구현은 PT-106에서 한다. 공통 규칙(`internal_only`, 4.5MB, `extra="forbid"`, 본문 비로그)을 그대로 따른다.

| 방향 | 형식 |
| --- | --- |
| 요청 | `{"scope": <6.1 scope>, "messages": [{role, content}] (1..40개, 각 ≤8000자), "previousSummary": 문자열 ≤4000자 (선택)}` |
| 응답 | `{"summary": "<text ≤4000자>"}` (스트리밍 아님) |

### 6.6 삭제 반영

| 사건 | 반영 |
| --- | --- |
| 기억 삭제 | 즉시 조립 대상에서 빠진다. 소프트 삭제라면 조회에서 `deletedAt IS NULL` 조건으로 거른다 |
| 메시지 삭제 | 같은 트랜잭션에서, 그 메시지를 원본 범위에 포함한 요약에 `invalidatedAt`을 기록한다. 무효 요약은 다시 보내지 않는다 |
| 대화 삭제 | 그 대화의 메시지와 요약을 삭제한다 |
| 조립 | `AgentContextAssembler`는 무효 요약·삭제 기억·범위가 다른 기억을 보내지 않는다. 턴 사이 캐시를 두지 않고 요청마다 조회한다 |
| FastAPI | 무상태. 요청에 없는 기억·요약을 쓸 방법이 없고, `usedMemoryIds` ⊆ 요청 id를 테스트로 확인한다 |

### 6.7 PT-85 시점 구현과 PT-106 교체점

| 구성 | PT-85 | PT-106 |
| --- | --- | --- |
| `ConversationMemory` 포트 (Spring) | `recall(userId, scope, question, screenHistory)` → `Recall(messages, summary, memories)`. 출력: 이력 `messages`, 유효한 요약 0..1개, 관련도 순 기억 0..20개 | 포트 구현을 저장소 기반으로 교체 (TutorService 변경 없음) |
| 이력 `messages` | 포트가 이력도 돌려준다. PT-85 기본 구현은 화면 이력(`screenHistory`)을 그대로 돌려준다 | 서버 저장 메시지를 돌려줄 수 있음 (같은 형식·상한) |
| 저장 항목 소유자 | 저장 요약·기억(`StoredSummary`·`StoredMemory`)은 소유자 `ownerUserId`를 가진다 | 저장 시 소유자를 채운다 |
| 기본 구현 `NoStoredConversationMemory` | 요약·기억 없음 + 화면 이력. 다른 `ConversationMemory` 구현 빈이 있으면 등록되지 않는다(`@ConditionalOnMissingBean`) | 구현 빈만 추가하면 교체된다. 쓰기 지점·시점은 PT-106이 정한다 |
| `context.messages` 출처 | 포트가 준 이력. 화면 이력은 `ChatRequest.messages`에서 마지막 user 원소를 뺀 나머지 | 같음 |
| `AgentContextAssembler` | 포트 결과를 6.2 형식으로 조립 | 변경 없이 재사용 |

조립 규칙(PT-85 구현):

- 포트가 준 이력은 최근 39개(현재 질문 제외), 각 8000자로 자르고 role이 `user`·`assistant`가 아니거나 내용이 빈 메시지는 뺀다.
- 소유자 확인: `ownerUserId`가 요청 사용자와 다르거나 `null`인 요약·기억은 보내지 않는다. 6.1 scope 규칙에 더한 심층 방어다.

Java 메서드 이름은 PT-85 구현 PR 기준이며, 위 입출력 의미는 바꾸지 않는다.

### 6.8 `state` 제한

`need_search`의 `state`(3절)에는 기억·요약·자료 원문을 넣지 않는다. FastAPI는 버전이 붙은 내부 모델로 `state`를 다시 검증하며(실패 시 422), 크기는 UTF-8 JSON 기준 65,536바이트 이하다.

## 7. Agent 실행 요청·응답 (POST /v1/agent/chat)

2026-11-25 확정 계약이며 PT-85에서 구현했다. `/v1/chat` 요청·응답 형식은 바꾸지 않는다. 422 본문에서 입력을 지우는 규칙(7.3)만 앱 공통으로 적용된다. 이 경로는 `internal_only`(내부 키 없으면 401), `InferenceBodyLimit`(4.5MB 초과 413), 모든 수준에서 Pydantic `extra="forbid"`(422)를 적용한다. 422 본문에는 입력 원문을 넣지 않는다. 필드 이름은 모두 camelCase이며, 예외는 PT-84 결과를 그대로 싣는 `variant.failure_reasons` 하나다(7.5).

### 7.1 요청 본문

| 필드 | 형식·상한 | student | personal |
| --- | --- | --- | --- |
| `agent` | `"student"` \| `"personal"` | 필수 | 필수 |
| `question` | 문자열 1..8000자 | 필수 | 필수 |
| `policy.topic` | 문자열 1..30자 | 세션 설정 | 화면 주제 |
| `policy.difficulty` | 정수 1..5 | 세션 설정 | `3` 고정 (화면 요청에 난이도가 없다) |
| `policy.hideAnswers` | 불리언 (JSON `true`/`false`만, `"false"`·`0` 거절) | 세션 설정 | `false` 고정 |
| `policy.preferMaterials` | 불리언 (JSON `true`/`false`만) | 세션 설정 | `false` 고정 |
| `allowedTools[]` | 7.2 이름, 중복 없음 | `answer_question` 필수 + 선택 | `["answer_question"]`만 |
| `context` | 6.2 (`scope`, `messages`, `summary`, `memories`, `screenSummary`) | 필수, `scope.kind="session"` | 필수, `scope.kind="personal"` |
| `materials[]` | 최대 3개, 각 `{id, source, page?, content}` | 선택 | 빈 목록만 |
| `materials[].id` | `^[A-Za-z0-9_-]{1,64}$`, Spring이 붙인 불투명 id | | |
| `materials[].source` | 문자열 1..120자 (출처 표시명) | | |
| `materials[].page` | 정수 ≥1 또는 생략 | | |
| `materials[].content` | 문자열 1..1500자 | | |
| `variantSource` | PT-84 `OriginalQuestion` 그대로 (`type` CHOICE/TEXT, `question`, `choices`, `answerIndex`, `modelAnswer`) 또는 `null`. 학생 답안 필드 없음 | `question_variant`와 짝 | `null`만 |
| `search.round` | 정수 0..2 | 필수 | `0`만 |
| `search.state` | 객체 ≤65,536바이트 또는 `null` | `round>0`이면 필수, `round=0`이면 `null` | `null`만 |

Spring 매핑: `ChatRequest.messages`의 마지막 원소(role `user`)가 `question`, 그 앞이 `context.messages`가 된다. 마지막 원소가 user가 아니면 한도 예약 전에 400으로 거절한다. `materials`는 Spring이 권한 범위에서 검색한 조각이며 재검색 뒤에는 새 결과 3개로 교체한다.

### 7.2 도구 이름

| 이름 | 4절 도구 | student | personal | 비고 |
| --- | --- | --- | --- | --- |
| `answer_question` | 질문 답변 | 필수 | 필수 | 답변 노드 |
| `material_search` | (3절 재검색) | 허용 | 금지 | 있을 때만 `need_search`를 낼 수 있다 |
| `question_variant` | 기존 문항 변형 | 허용 | 금지 | PT-84 `generate_question_variant` 호출 |
| `screen_analyze`, `screen_summary` | 화면 분석, 현재 화면 요약 | 예약 | 예약 | PT-45에서 활성화. 지금 들어오면 422 |

도움 요청 추천은 도구가 아니라 student Agent 규칙이다. Spring은 추천 문구만 보내고 도움 요청을 만들지 않는다.

### 7.3 검증 규칙 (422)

| 조건 | 결과 |
| --- | --- |
| agent 종류에 허용되지 않은 도구, 예약·알 수 없는 도구 이름, `answer_question` 누락 | 422 |
| `question_variant`와 `variantSource` 중 하나만 있음 | 422 |
| personal에 `materials`(비어 있지 않음), `variantSource`, `hideAnswers=true`, `preferMaterials=true`, `search.round>0` 중 하나라도 있음 | 422 |
| `context.scope`가 agent와 맞지 않음(student↔session, personal↔personal), `summary.scope`·`memories[].scope`가 `context.scope`와 다름 | 422 |
| `search.round>0`인데 `state` 없음, `round=0`인데 `state` 있음, `state` 내부 모델 재검증 실패 또는 65,536바이트 초과 | 422 |
| 정의되지 않은 필드(어느 수준이든), 상한 초과, `variantSource`에 학생 답안 같은 추가 필드 | 422 |
| 노드 실행 직전 재검사에서 허용 범위 밖 도구 | 스트림 줄 `{"error": "tool_not_allowed"}` (7.5) |

422 본문은 `{"detail": [{"loc", "type", "msg"}]}`이며 클라이언트 값을 다시 싣지 않는다(이 앱의 모든 경로 공통).

- `input`·`ctx`는 넣지 않는다.
- `extra_forbidden`이면 `loc`에서 마지막 원소(클라이언트가 보낸 모르는 키 이름)를 빼고 부모 위치까지만 보인다.
- `msg`는 필드 상한이나 서버의 고정 문구로만 만들어지는 오류 종류(`missing`, `extra_forbidden`, `value_error`, `literal_error`, 타입·길이·범위·패턴 오류 등)일 때만 그대로 두고, 그 밖의 종류(예: 판별 태그 위조 `union_tag_invalid`, 입력 태그를 인용함)는 `"Invalid value"`로 바꾼다.
- `search.state.v`는 JSON 정수 `1`만 받는다(`true`·`1.0`·`"1"` 거절).

### 7.4 요청 예시

수업 학생 (첫 호출, 자료 2개):

```json
{
  "agent": "student",
  "question": "for문에서 i가 왜 4까지만 나와요? 정답 코드 알려줘",
  "policy": {"topic": "파이썬 반복문", "difficulty": 2, "hideAnswers": true, "preferMaterials": true},
  "allowedTools": ["answer_question", "material_search"],
  "context": {
    "scope": {"kind": "session", "sessionId": 42},
    "messages": [
      {"role": "user", "content": "range(5)는 뭐예요?"},
      {"role": "assistant", "content": "0부터 4까지의 수를 차례로 만들어요."}
    ],
    "summary": null,
    "memories": [],
    "screenSummary": null
  },
  "materials": [
    {"id": "c_1032", "source": "3주차 반복문.pdf", "page": 4, "content": "range(n)은 0부터 n-1까지..."},
    {"id": "c_1047", "source": "3주차 반복문.pdf", "page": 6, "content": "for 변수 in 반복가능객체:"}
  ],
  "variantSource": null,
  "search": {"round": 0, "state": null}
}
```

변형 요청이면 위에서 `allowedTools`에 `"question_variant"`를 넣고 `"variantSource": {"type": "CHOICE", "question": "...", "choices": ["...", "..."], "answerIndex": 1}`을 싣는다.

개인 학습 (화면 요약 포함):

```json
{
  "agent": "personal",
  "question": "이 에러 메시지가 무슨 뜻이에요?",
  "policy": {"topic": "자바", "difficulty": 3, "hideAnswers": false, "preferMaterials": false},
  "allowedTools": ["answer_question"],
  "context": {
    "scope": {"kind": "personal"},
    "messages": [],
    "summary": {"id": "s_77", "scope": {"kind": "personal"}, "text": "자바 컬렉션을 복습 중이다."},
    "memories": [{"id": "m_301", "scope": {"kind": "personal"}, "text": "제네릭 문법을 어려워한다."}],
    "screenSummary": "IDE 화면. NullPointerException at Main.java:12"
  },
  "materials": [],
  "variantSource": null,
  "search": {"round": 0, "state": null}
}
```

### 7.5 NDJSON 응답 줄

| 줄 | 언제 | Spring 처리 |
| --- | --- | --- |
| `{"ping": true}` | 출력이 없는 구간(판단·변형)에서 10초마다 | 연결 확인만. SSE로 보내지 않는다 |
| `{"start": {"materialIds": ["c_1032"]}}` | 첫 `delta` 직전 1회. 실제 지시문에 넣은 자료 id (없으면 `[]`) | 그 id의 출처만 `sources`로 한 번 보낸다. `[]`이면 `sources` 없음 |
| `{"delta": "..."}` | 답변 조각 | SSE `delta {"t"}` |
| `{"status": "need_search", "query": "...", "state": {...}}` | `delta` 전에만, 그 응답의 마지막 줄. `search.round<2`이고 `material_search` 허용일 때만 | 3절 재검색 후 `round+1`, `state`, 새 `materials`로 다시 호출 |
| `{"variant": {"status": "ok", "attempts": n, "variant": {...}}}` 또는 `{"variant": {"status": "failed", "attempts": n, "failure_reasons": [...]}}` | 변형 도구를 실행한 턴, `delta` 전 1회. PT-84 `VariantResult` 그대로(정답·해설 포함) | 내부 전용. 원본을 SSE로 보내지 않고 정책을 적용해 렌더(7.7) |
| `{"meta": {...}}` | 정상 종료의 마지막 줄 | 로그 한 줄과 SSE `agent` 이벤트 재료 |
| `{"error": "llm_unavailable" \| "agent_timeout" \| "tool_not_allowed"}` | 실패 종료의 마지막 줄 | SSE `error {"message"}` (사용자 문구는 Spring) |

`meta` 필드:

| 필드 | 형식 | 의미 |
| --- | --- | --- |
| `agent` | `"student"` \| `"personal"` | 실행한 Agent |
| `route` | `"concept"` \| `"answer_request"` \| `"answer_check"` \| `"code_debug"` \| `"variant"` | 규칙 분류 결과. 변형 도구를 실행한 턴은 `"variant"` |
| `nodes[]` | 노드 이름 문자열 | 거친 경로. 노드 이름만 담고 질문·자료 원문은 넣지 않는다 |
| `llmCalls` | 정수 | 이 요청에서 답변용 LLM 호출 수(일반 턴 1). 변형 내부 호출은 `variant.attempts`로 센다 |
| `hintLevel` | 1..3 또는 `null` | student 단계 힌트 수준. personal과 변형 성공 턴은 `null` |
| `suggestHelp` | 불리언 | 도움 요청 추천 여부. personal은 항상 `false` |
| `searchRound` | 0..2 | 요청의 `search.round` |
| `variant` | `null` \| `"ok"` \| `"failed"` | 변형 결과 요약 |
| `retryable` | 불리언 | 변형 실패 후 재시도 안내를 붙일지. 변형이 없거나 `invalid_original`이면 `false` |
| `context` | 6.4 | `estimatedInputTokens`, `budget`, `dropped`, `truncated` |
| `usedMemoryIds[]`, `usedSummaryId` | 6.4 | 실제로 쓴 기억·요약 id |

`need_search`로 끝나는 응답에는 `meta`가 없고 LLM을 호출하지 않는다.

`need_search` 조건과 `query` 규칙:

- `material_search` 허용, `search.round<2`, 요청 `materials`가 비어 있음, `policy.preferMaterials=true`, 변형 턴이 아님. 이 조건에 더해 아래 규칙으로 만든 새 `query`가 있을 때만 낸다.
- Spring의 첫 검색이 이미 질문 원문으로 0건이었으므로 어느 round도 질문 원문만으로 다시 검색하지 않는다.
- round 0: `"<policy.topic> <질문>"`.
- round 1: 보호 구간의 직전 user 메시지가 있으면 `"<policy.topic> <직전 user 메시지>"`, 없거나 쓸 수 없으면 `"<policy.topic>"`.
- 공백은 하나로 줄이고 500자에서 자른다. 후보가 질문 원문 또는 `state`의 이전 `query`와 같으면(공백·대소문자 무시) 다음 후보로 넘어가고, 남은 후보가 없으면 `need_search` 없이 자료 없이 답변한다.

예시:

```json
{"meta": {"agent": "student", "route": "answer_request", "nodes": ["classify", "hint", "compose", "respond"], "llmCalls": 1, "hintLevel": 2, "suggestHelp": false, "searchRound": 0, "variant": null, "retryable": false, "context": {"estimatedInputTokens": 812, "budget": 3000, "dropped": {"messages": 0, "memories": 0, "materials": 0, "screenSummary": 0}, "truncated": []}, "usedMemoryIds": [], "usedSummaryId": null}}
```

줄 순서:

| 경우 | 순서 (`ping`은 어디든 끼어들 수 있음) |
| --- | --- |
| 일반 답변 | `start` → `delta`… → `meta` |
| 재검색 요청 | `need_search` (끝) |
| 변형 성공 | `variant(ok)` → `meta` (변형 외 LLM 호출 없음) |
| 변형 실패, 일반 답변 조건 충족 | `variant(failed)` → `start` → `delta`… → `meta` |
| 변형 실패, 그 밖 | `variant(failed)` → `meta` |
| 실패 | (`variant`) → (`start` → `delta`…) → `error` |

답변 모델이 오류 없이 끝났는데 비어 있지 않은 `delta`가 하나도 없으면(빈 문자열 `delta`만 온 경우 포함, 빈 조각은 내보내지 않음) 일반 답변은 `start`·`meta` 없이 `{"error": "llm_unavailable"}`로 끝난다. 변형 실패 뒤 일반 답변이면 7.6 고정 안내 경로처럼 `variant(failed)` → `meta`로 끝난다.

### 7.6 변형 실패 처리와 한도 정산

| 항목 | 규칙 |
| --- | --- |
| 실패 문항 | 실패 문항·정답·해설은 SSE 어디에도 없다 (PT-84 실패 결과에는 문항이 없다) |
| 일반 답변 (LLM 1회) | 실패 사유가 검증 실패(`model_unavailable`·`invalid_original`이 아님)이고 첫 `delta` 마감까지 30초 이상 남았을 때. `hideAnswers`를 지키는 일반 답변을 스트리밍한다. 지시문에는 원 문항의 본문과 보기만 `<original_question>` 데이터 블록(추정 200토큰까지 축약)으로 넣고 `answerIndex`·`modelAnswer`는 넣지 않는다 |
| 고정 안내 | 그 밖(`model_unavailable`, 남은 시간 30초 미만). FastAPI는 `delta` 없이 `meta`로 끝내고 Spring이 고정 안내 문구를 `delta`로 보낸다. 일반 답변이 빈 스트림으로 끝난 경우(7.5)도 같다. `variant` 줄이 이미 나가 차감은 확정이므로 `error` 대신 이 경로로 빈 화면을 막는다 |
| 재시도 안내 | `meta.retryable=true`이면 Spring이 재시도 안내 문구를 `delta`로 덧붙인다. `invalid_original`이면 `retryable=false`, 안내 없음. 같은 `variantQuizId` 재요청은 새로 실행한다 |
| 변형 시간 | 변형 도구는 남은 마감 시간 안에서 실행하고, 시간이 다하면 `agent_timeout`이 아니라 `variant(failed, model_unavailable)` 줄을 낸다 |
| 한도 정산 (PT-97) | `variant` 줄(성공·실패) 또는 `delta`를 받은 턴은 CHAT 1회 차감 확정이다. 변형 실패 뒤 일반 답변까지 포함해 1회다. FastAPI 연결 실패처럼 `variant`·`delta` 전에 끝난 경우만 기존 규칙대로 반납한다. `ping`·`start`·`need_search`는 출력으로 세지 않는다 |

### 7.7 Spring SSE 매핑

기존 SSE 계약(`sources {"items"}` / `delta {"t"}` / `done` / `error {"message"}`)은 바꾸지 않고 `agent` 이벤트 하나를 추가한다. 화면은 모르는 이벤트를 무시하므로 프런트를 바꾸지 않아도 된다.

| 순서 | SSE 이벤트 | 내용 |
| --- | --- | --- |
| 1 | `sources` (있으면) | `start.materialIds`에 해당하는 출처, 최대 3개 |
| 2 | `delta` … | FastAPI 답변 조각, 그리고 Spring이 만든 사용자 문구: 변형 문항 텍스트(정책 적용), 도움 요청 추천 문구, 고정 안내·재시도 안내 |
| 3 | `agent` | `{route, suggestHelp, variant, answerHidden, retryable}`. `done` 직전 1회. TEACHER 경로(기존 `/v1/chat`)에서는 보내지 않는다 |
| 4 | `done` / 실패 시 `error` | 기존과 같음 |

| `agent` 필드 | 의미 |
| --- | --- |
| `route`, `suggestHelp`, `retryable` | `meta` 값 |
| `variant` | 정책 적용본 `{type, question, choices?, answerIndex?, modelAnswer?, explanation?}` 또는 `null`. `hideAnswers=true`이면 `type`, `question`, `choices`만 |
| `answerHidden` | 정답·해설을 정책으로 숨겼는지. student는 `policy.hideAnswers`, personal은 `false` |

- `hideAnswers=true`이면 변형 문항의 `answerIndex`·`modelAnswer`·`explanation`은 `delta`·`agent`를 포함해 SSE 어디에도 나가지 않는다. `false`이면 문제 뒤에 정답·해설 단락을 붙인다.
- Spring `ChatRequest` 추가 필드: `variantQuizId`(Long, 선택. `sessionId` 없이 오면 400, TEACHER면 403, 접근 확인 조건은 PT-85 AC-12), `screenSummary`(문자열 ≤4000자, 선택). 거절은 모두 한도 예약 전에 HTTP로 나간다.
- 동시 턴: 같은 사용자의 Agent 턴(student·personal)이 이미 진행 중이면 한도 예약 전에 HTTP 429 `{"status": 429, "message": "앞 질문의 답을 아직 만들고 있어요. 끝난 뒤 다시 물어봐 주세요"}`로 거절한다. SSE를 열지 않고 CHAT 한도를 쓰지 않는다. 자리는 턴이 끝나면(정상·오류·중지) 풀린다. TEACHER 경로와 `app.ai.agent.enabled=false` 경로에는 적용하지 않으며, 기존 한도 초과 429(`Retry-After`)와는 별개다.
- 거절 검사 순서: 400/403/404/409/410 → 동시 턴 429 → 한도 초과 429.
- Spring 내부에서 변형 원 문항은 JPA 엔티티 대신 값 객체(`VariantOriginal`)로 다룬다. FastAPI로 가는 `variantSource` JSON 형식(7.1)은 바뀌지 않는다.

### 7.8 실행 한도와 운영

| 항목 | 한도·규칙 |
| --- | --- |
| 첫 `delta` 전 단계 (판단·변형) | FastAPI 호출마다 120초, `AGENT_DEADLINE_SECONDS`(기본 120). 넘으면 `{"error": "agent_timeout"}` |
| 답변 스트리밍 | 기존 300초 상한 그대로 |
| 재검색 | 최대 2회(FastAPI 호출 최대 3회). 3번째 `need_search`, `delta` 뒤 `need_search`, `state` 65,536바이트 초과, `query` 빈 값·500자 초과는 검색 없이 `error`. 재검색 전마다 Spring이 수업 권한을 다시 확인 |
| `AiSlots` | 재검색 루프 전체에서 한 자리. `ticket.run` 하나 안에 `slots.run` 하나 |
| AI 사용 한도 (PT-97) | 질문 하나 = CHAT 1회 (재검색·변형 포함), 정산은 7.6 |
| 기능 플래그 | `app.ai.agent.enabled`(`AI_AGENT_ENABLED`, 기본 `false`). `false`면 모든 역할이 기존 `/v1/chat` 경로이고, 명시적으로 `true`를 줘야 학생·개인 학습이 Agent 경로를 쓴다. `application.yaml`, `TutorService`, infra compose, env 예제의 기본값이 모두 `false`다 |
| 배포 순서 | 1) FastAPI(`/v1/agent/chat` 포함)를 먼저 배포하고 엔드포인트가 응답하는지 확인한다. 2) 그 뒤에 `AI_AGENT_ENABLED=true`로 Spring을 다시 띄운다. 값을 주지 않으면 기본 `false`라서 Spring이 먼저 배포되거나 이전 FastAPI와 함께 돌아도 기존 `/v1/chat` 경로로 동작한다. 문제가 생기면 `false`로 되돌리면 기존 경로로 돌아간다 |
| 추적 | `LANGSMITH_TRACING`, `LANGCHAIN_TRACING_V2`, `LANGSMITH_TRACING_V2`, `LANGCHAIN_TRACING` 네 변수 중 하나라도 참(`true`/`1`/`yes`/`on`, 대소문자·앞뒤 공백 무시)이거나, 설치된 langsmith의 `tracing_is_enabled()`가 참이면 FastAPI가 시작을 거부한다(fail closed). langsmith는 `TRACING_V2`, `TRACING`을 `LANGSMITH_`·`LANGCHAIN_` 두 접두어로 모두 읽기 때문이다. Dockerfile은 네 변수 모두 `false`로 둔다 |
| 로그 | Spring은 턴마다 한 줄. 성공은 `result=ok`와 Agent 종류, 경로, 분류, 힌트, 추천, 도구, 재검색 횟수, 변형 결과, 소요 시간. 실패 턴도 한 줄 `result=error reason=status_<code>\|ai_unavailable\|ai_unavailable_after_output\|<예외 종류> tools=… searchRounds=… elapsedMs=…`. FastAPI는 노드 이름·성공 여부·횟수만. 질문·검색어·자료·답안 원문, 예외 메시지, `userId`는 남기지 않는다 |

### 7.9 알려진 한계 (PT-85)

| 항목 | 내용 | 후속 |
| --- | --- | --- |
| 화면 이력 위조 | PT-106 전에는 `context.messages`가 화면이 보낸 이력이다. 학생이 assistant 메시지를 꾸며 `hintLevel`·`suggestHelp` 계산을 바꾸거나 few-shot 탈옥을 시도할 수 있다. 정답 숨김은 정책 지시(프롬프트)에 의존한다. 기존 `/v1/chat`과 같은 한계다 | PT-106 서버 저장 이력으로 교체 |
| 짝 없는 서로게이트 | 질문에 짝 없는 UTF-16 서로게이트가 있으면 FastAPI가 422를 내고, Spring은 한도 예약 뒤라 CHAT 1회가 차감될 수 있다. 기존 `/v1/chat`과 같다 | Spring 입력 정리 검토 |
| 과목별 안내 | Agent 경로는 Spring `TopicPrompt`의 과목 지시·수준 문구를 쓰지 않고 `policy.topic`·`difficulty`만 쓴다 | 품질 비교 후 policy 확장 검토 |
| 의존성 해시 | `requirements.lock.txt`는 버전만 고정하고 해시는 고정하지 않는다(기존부터) | `--require-hashes` 도입 검토 |
| 규칙 분류 | 질문 분류 정규식은 선형 시간으로 제한했다. `코드` 뒤 붙은 낱말은 10자까지만 본다 | — |

## 8. 집중도 예측 `/v1/focus/predict` (PT-90, 2026-11-26)

학생이 동의하고 수업 화면에서 시작한 동안만 Windows 앱이 1분마다 한 줄(가장 오래 쓴 실행 파일 이름, 입력 없는 초, 창 전환 수)을 Spring에 보낸다. Spring은 저장하고, 실행 파일 이름을 카테고리로 바꾼 뒤 카테고리 피처만 FastAPI LightGBM에 보내 **규칙 일치도 기반 참고 상태(정확도 아님)**를 받는다. FastAPI는 요청 하나 안에서 계산하고 아무것도 저장하지 않는다.

**요청·응답**

```
POST /v1/focus/predict      헤더 X-Internal-Key (internal_only만 적용, 모델 제공자 검사 없음)
{"rows": [{"minutes_ago": 1, "category": "ide", "idle_sec": 5, "switch_count": 1}, …], "hour": 12.27, "weekday": 3}
200 {"status": "ok", "state": "focus", "probabilities": {"break": 0.1, "distracted": 0.2, "focus": 0.7}, "model_version": "focus-lgbm-<sha256 앞 12자>"}
200 {"status": "insufficient_data"}
```

| 항목 | 규칙 |
| --- | --- |
| `rows` | 0~15개. `minutes_ago` 1~15 정수(1 = 목표 분 바로 앞 분), 중복이면 422(고정 문구). `category` ∈ ide·browser·terminal·game·chat·other, `idle_sec` 0~60, `switch_count` 0~120 정수 |
| `hour` · `weekday` | 목표 분(가장 최근 행 다음 분)의 `app.focus.zone`(Asia/Seoul) 시각. `hour` = 시 + 분/60, [0, 24). `weekday` 월=0 … 일=6 |
| 보내지 않는 것 | 실행 파일 이름, userId, sessionId, 원래 시각. 추가 필드는 `extra="forbid"` → 422 |
| NaN/Infinity | 422. 422 본문에 입력값을 다시 넣지 않는다(`safe_error`) |
| 본문 크기 | 이 경로만 8KB 초과 413 (다른 `/v1/*`는 기존 4.5MB) |
| 판정 | 15분 창의 행이 13개 미만이면 모델을 부르지 않고 `insufficient_data`. 13개 이상이면 확률이 가장 큰 클래스 |
| 503 | 모델 미설정·파일 없음·해시 불일치·사이드카/부스터 피처 순서·클래스 불일치·읽기 실패. 본문은 사유와 무관하게 `{"detail": "focus model unavailable"}` 하나(경로·예외 노출 없음). 다른 엔드포인트는 정상 |
| 500 | 추론 실패·확률 개수/합/유한성 이상 (본문 고정 문구) |
| 로그 | status·행 수·소요 ms·예외 종류만. 피처값·확률·경로는 남기지 않는다 |

**피처** (`app/services/focus_features.py`, 28개, 원본 `focus_features.build_features()` 순서): 5분 창(`minutes_ago` 1~5)과 15분 창(1~15)의 카테고리 비율·창 전환 평균·유휴 평균·활동 비율(있는 행의 평균, 창이 비면 NaN), 직전 행(`minutes_ago` ≤ 2 중 가장 최근, 없으면 NaN)의 유휴·카테고리 one-hot, `hour_sin`·`hour_cos`, `weekday`. 원본 모델은 누적 유휴 초로 학습했으므로 FastAPI가 연속 분끼리 누적값을 복원한다: `running[m] = idle[m]`(60 미만) / `running[m+1] + 60`(60이고 앞 분 있음) / `60`(앞 분 없음). 연속 구간은 원본과 같고 창 첫 행만 하한값이다. `tests/fixtures/focus_features_golden.json`(원본 pandas로 1회 생성)과 1e-9 안에서 같다.

**모델 로딩**: `FOCUS_MODEL_PATH`(LightGBM 텍스트 `.txt`)의 바이트를 한 번만 읽어(64MB 상한) SHA-256이 `FOCUS_MODEL_SHA256`(대소문자 무시)과 같을 때만, 해시를 확인한 그 바이트를 UTF-8로 풀어 `lightgbm.Booster(model_str=…)`로만 로드한다(경로를 다시 열지 않아 확인과 로드 사이 틈이 없고, LightGBM 네이티브 오류 출력에도 경로가 나오지 않는다. UTF-8이 아니면 503). 옆 `.json`(`features` 28개·`classes` `["break","distracted","focus"]`)과 `Booster.feature_name()`·클래스 수가 코드 상수와 같아야 한다. pickle/joblib은 쓰지 않는다. 시작할 때 한 번 읽고 실패해도 앱은 뜬다. 모델 파일은 이미지에 넣지 않으며 compose·env 예제의 기본값은 빈 값이다(그때 화면 상태는 "알 수 없음"이 정상). Docker 이미지는 lightgbm용 `libgomp1`을 설치한다. lightgbm 4.7.0(+scipy 1.18.1, narwhals 2.29.0)을 `requirements.lock.txt`에 고정했다.

**Spring** (`com.my.tutipal_back.learning.focus`)

| API | 권한 | 결과 |
| --- | --- | --- |
| `POST /api/sessions/{id}/focus` `{rows:[{exeName, idleSec, switchCount}]}` | 진행 중 수업의 STUDENT 본인 + 현재 버전 동의 | 200 `{state, updatedAt}`. 비참여 404, 종료 410, 교사 403, 동의 없음 403("저장 동의가 필요해요"), 50초 안 재제출 429, 형식 오류 400(입력값 미포함) |
| `GET /api/sessions/{id}/focus` | 그 수업 교사 | 현재 STUDENT 전원 `[{userId, state, updatedAt}]`. 실행 파일 이름·카테고리·원시값 없음 |
| `GET /api/sessions/{id}/focus/me` | 그 수업 학생 본인 | `{state, updatedAt}` |
| `GET` · `PUT {version}` · `DELETE /api/focus/consent` | 로그인 사용자 | 동의 조회·저장(다른 버전 409)·철회(204, 그 사용자의 consent를 먼저 지운 뒤 samples·states 즉시 삭제). 화면: 수업 화면 집중도 카드와 수업과 무관한 `설정 > 집중도 체크`(브라우저 포함) |

- 행 `exeName`은 null 또는 `^[a-z0-9._-]{1,64}\.exe$`, 1~15행, 오래된 순. 분은 서버 시각으로 정한다(n행이면 `floor(now) - (n-1-i)`분, 같은 분은 기존 행 유지).
- 흐름: 권한·동의 → 제출 간격 → 행 저장(짧은 트랜잭션, 세션 행 잠금 후 다시 확인, 동의 행은 `FOR SHARE`로 읽어 철회와 직렬화) → 최근 15분 행으로 FastAPI 호출(트랜잭션 밖, `app.focus.predict-timeout` 2s) → 상태 저장 → `events.toTeacher(sessionId, "focus", studentId)`. FastAPI 실패(상태 코드·시간 초과·연결·형식·8KB 초과 응답)는 UNKNOWN, `insufficient_data`는 PENDING. 모델 실패와 관계없이 제출은 200이다. 단, 모델을 기다리는 동안 학생이 나갔거나(404 "방을 찾을 수 없어요") 수업이 끝났거나(410 "이미 닫힌 방이에요") 동의를 철회했으면(403 "저장 동의가 필요해요") 상태를 저장하지 않고 일반 거절과 같은 오류를 돌려준다(앱은 이 응답에 수집을 멈춘다).
- 상태: FOCUS · DISTRACTED · BREAK · UNKNOWN · PENDING 저장, 상태 행이 없거나 `app.focus.stale-after`(3분) 지나면 NOT_PARTICIPATING(저장 안 함).
- 보관: `ended_at`이 30일 지난 수업의 samples·states를 매시간 500행씩 삭제. 같은 배치에서 수업 종료 여부와 상관없이 `minute_at`이 `app.focus.max-sample-age-days`(31일 = 30일 + 수업 1일) 지난 samples와 그만큼 갱신 없는 states도 삭제한다(교사가 닫지 않은 수업은 `ended_at`이 없음). 31일은 PM 확인 대상 가정값. 탈퇴 때 `AccountCleanup`이 세 테이블을 먼저 지우고 FK도 ON DELETE CASCADE.
- 설정 `app.focus.*`: `consent-version` "2026-11-26", `retention-days` 30, `retention-batch-size` 500, `max-sample-age-days` 31, `submit-min-interval` 50s, `stale-after` 3m, `zone` Asia/Seoul, `predict-timeout` 2s. 잘못된 값이면 시작 실패. `GET`·`PUT /api/focus/consent` 응답에 `retentionDays`·`maxSampleAgeDays`·`staleAfterSeconds`가 함께 오고(학생 본인 배지는 마지막 응답이 `staleAfterSeconds`보다 오래되면 "알 수 없음 · 연결 확인 중") 화면은 이 값으로 동의 문구의 보관 기간을 만든다. 그래서 `retention-days`나 `max-sample-age-days`를 바꾸면 문구가 바뀐 것이므로 `consent-version`도 반드시 올린다(기존 동의는 다시 받음).

**사용 목적 (PM 결정 2026-11-26)**: 교사에게는 앱 이름 없이 상태만 보여 준다. 저장한 실행 파일 이름(basename)과 1분 기록은 수업 중 참고 상태 표시와 WBS 134행 규칙 일치도 평가(학생이 아니라 집중도 판정 기능 자체가 규칙과 얼마나 맞는지 보는 기능 평가)에만 쓰고, 모델 재학습·학생 평가·성적·출결에는 쓰지 않는다. 화면 안내(`FOCUS_NOTICE`)도 "학생을 평가하거나 성적 · 출결을 판단하는 데 쓰지 말아 주세요"로 대상을 학생으로 적는다. 보관은 그대로(수업 종료 30일, 31일 상한, 탈퇴·철회 즉시 삭제). 동의 문구(`focusLabels.consentText`)에 이 목적이 적혀 있으며, 목적이 바뀌면 `consent-version`을 올린다.

**데스크톱 수집 경계**: `docs/DESKTOP_SECURITY_CHECKS.md`의 "집중도 수집" 행.

**알려진 한계**: 카테고리 표는 서버 한 곳(`FocusCategories`, devdesk 학습 표 + 수업 도구)이며 규칙 일치도 정량 평가·보정은 후속이다. 제출 간격 제한은 서버 메모리라 서버가 여러 대면 대마다 따로 센다. 동의 철회와 제출이 겹치면 동의 행 잠금으로 직렬화된다: 저장이 먼저면 철회가 기다렸다가 그 행까지 지우고, 철회가 먼저면 저장이 403 또는 상태 저장 생략이 된다(실제 PostgreSQL 검사: `./gradlew focusDbTest` = `FocusStorePostgresTest`, CI `review-checks.yml`의 review-db job이 일회용 DB로 실행하고 실행 수 0이면 실패). 학습 표의 공백 있는 이름("league of legends", "winamax poker")과 `+`가 들어간 "notepad++"는 실행 파일 규칙에 맞지 않아 표에서 뺐다(그런 프로그램은 null → other).
