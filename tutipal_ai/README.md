# FastAPI 추론 서버 · MVC

Mindot의 단일 `app.py`/에이전트 파일 구조를 복사하지 않고 MVC로 나눴다.

| 폴더 | 역할 |
| --- | --- |
| `app/controllers` | HTTP 요청을 받아 service를 호출하고 view로 응답 |
| `app/models` | 추론 입력과 상태 등 내부 데이터 모델 |
| `app/views` | JSON 표현과 Pydantic 요청/응답 스키마. HTML 화면을 만들지 않음 |
| `app/services` | 모델 추론·이미지 분석·임베딩 계산 등 처리 |
| `app/clients` | 향후 확정할 외부 모델 API 또는 로컬 모델 서버 어댑터 |
| `app/core` | 설정과 공통 유틸리티 |

PL 저장소 `jeonseolmin/tutipal`의 main `d60f705748da927375d77aedc11165ca0ea1f49e`에서 채팅·비전·임베딩 계산을 현재 MVC로 옮겼다. 원본의 자료 추출·DB 점검 엔드포인트와 PostgreSQL/pgvector 드라이버는 옮기지 않았다. PDF 추출·청킹·DB 검색·S3는 Spring이 담당한다.

수업 학생과 개인 학습의 LangGraph 채팅 Agent는 [AI 워크플로 계약](../docs/AI_WORKFLOW_CONTRACT.md)의 구현 대상이다. Spring이 Agent와 허용 도구를 정하고 FastAPI는 전달받은 범위에서 실행한다. 화면 분석·요약 계산은 두 모드의 위젯 자동 요약에도 사용한다.

| 경로 | 입력·출력 | 인증 |
| --- | --- | --- |
| `GET /internal/ai/health`, `/health` | `status=ok`, 실제 Ollama 모델 파일을 확인한 `model_ready` | 없음, 내부 네트워크 전용 |
| `POST /v1/chat` | `{system,messages:[{role,content,images?}],json_mode?}` → NDJSON `{delta}` 또는 `{error:"llm_unavailable"}` | `X-Internal-Key` |
| `POST /v1/embed` | `{texts:[...]}` → `{model,vectors}` | `X-Internal-Key` |

`status=ok`는 HTTP 프로세스 생존이다. health에는 `local_chat_ready`, `local_embedding_ready`, `fallback_configured`도 표시한다. `model_ready=true`는 두 로컬 모델이 설치됐거나 대체 OpenAI 키가 설정되어 요청을 시도할 수 있다는 뜻이며, GPU 성능이나 OpenAI 자격 증명 유효성을 보장하지 않는다. health에서는 유료 OpenAI 요청을 호출하지 않는다. 모델 미접속·미설치 및 대체 키 미설정에도 HTTP 200과 `model_ready=false`로 응답하므로 컨테이너 자체는 healthy 상태를 유지할 수 있다.

이미지는 요청당 JPEG/PNG base64 한 장(문자열 3,000,000자 이하)만 받아 모델로 전달하며 저장하지 않는다. 메시지는 최대 40개, 임베딩은 요청당 최대 64개(각 4,000자)다. 수신 본문은 4.5MB로 제한한다. 채팅은 최대 5분, 임베딩은 최대 125초로 제한하며 클라이언트 중단 시 모델 연결을 닫는다. 오류 응답과 로그에는 질문·이미지·키를 넣지 않는다.

## 로컬 개발

Python 3.12.6을 설치한 뒤 이 폴더에서 실행한다. PC의 기존 Python 설치를 자동 변경하지 않는다.

```powershell
python --version  # 3.12.6인지 먼저 확인
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.lock.txt
.\.venv\Scripts\python.exe -m pip check
.\.venv\Scripts\python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8000 --reload
```

`requirements.txt`는 직접 의존성, `requirements.lock.txt`는 Python 3.12.6에서 설치한 전체 버전 잠금 파일이다. Docker와 CI도 잠금 파일을 사용한다. 의존성 변경 시 깨끗한 3.12.6 venv에서 직접 의존성을 설치하고 `pip freeze`로 잠금 파일을 갱신한 뒤 Windows와 Linux 컨테이너를 확인한다.

설정은 프로세스 환경변수로 전달한다. 현재 `.env.example`는 변수 안내이며 Uvicorn의 `--env-file`을 사용하려면 별도 `python-dotenv` 설치가 필요하다. Compose 실행에는 필요하지 않다.

Spring과 Python에 같은 `AI_INTERNAL_KEY`를 지정한다. 빈 키나 다른 키는 401이다. `MODEL_PROVIDER`·`MODEL_NAME`·`MODEL_BASE_URL`이 비어 있지 않으면 원본 별칭 `LLM_PROVIDER`·`OLLAMA_MODEL`·`OLLAMA_BASE_URL`보다 우선한다. 기본값은 각각 `auto`, `gemma4-ally:latest`, `http://host.docker.internal:11434`이며 `EMBEDDING_MODEL` 기본값은 `bge-m3`다. 모델 이름은 PC마다 `.env.local`에서 설정한다. venv 직접 실행에서는 Ollama 주소를 `http://127.0.0.1:11434`로 바꾼다. 모델 다운로드는 자동으로 수행하지 않는다.

`MODEL_PROVIDER=auto`는 요청 종류별로 Ollama `/api/tags`의 설치 상태를 확인한다. 해당 모델이 없거나 Ollama 호출이 실패하면 `OPENAI_API_KEY`가 있을 때 OpenAI로 전환한다. 이미 채팅 조각을 전송한 뒤의 실패에는 다른 모델 출력을 덧붙이지 않고 오류로 끝낸다. `ollama`는 로컬 전용, `openai`는 OpenAI 전용이다. 키가 없거나 모든 제공자가 실패하면 채팅은 기존 NDJSON 오류, 임베딩은 503을 반환한다.

OpenAI 설정은 `OPENAI_MODEL=gpt-5.4-mini`, `OPENAI_EMBEDDING_MODEL=text-embedding-3-small`, `OPENAI_BASE_URL=https://api.openai.com/v1`, `OPENAI_MAX_COMPLETION_TOKENS=4096`이 기본이다. 텍스트·이미지 채팅은 Chat Completions SSE를 NDJSON으로 변환하며 JSON 채점 모드를 지원한다. 외부 전환 시 질문·컨텍스트·이미지가 OpenAI로 전송되고 사용량이 청구될 수 있다. API 키는 Python에만 전달하고 저장소나 Electron에 포함하지 않는다.

`EMBEDDING_DIMENSIONS=1024`는 Spring의 `vector(1024)` 스키마와 맞춰 고정한다. OpenAI 임베딩에는 `dimensions=1024`를 보내고 양쪽 제공자 응답의 실제 차원과 유한 숫자를 검사한다. `/v1/embed`의 `model`에는 `ollama:bge-m3:1024` 또는 `openai:text-embedding-3-small:1024`처럼 실제 제공자·모델·차원을 돌려준다. 차원이 같아도 서로 다른 모델의 벡터는 비교할 수 없으므로 Spring은 이 값을 저장하고 정확히 같은 모델의 조각·복습 기록만 비교한다. 모델·가중치를 바꾼 자료는 재색인이 필요하다.

처리 순서는 `controllers → services → clients`이고, 요청 검증은 `models`, 응답 표현은 `views`에 둔다. 이전 예약 변수 `MODEL_API_KEY`는 OpenAI 인증에 쓰지 않는다. 키는 로그나 상태 응답에 포함되지 않는다.

RAG 검색·문서 추출·청킹·DB 조회·S3 접근은 Spring의 책임이다. 이 서버에는 PostgreSQL 드라이버나 S3 SDK, 해당 자격 증명을 넣지 않는다. Compose에서 호스트 포트를 공개하지 않고 Spring 전용 모델 네트워크와 내부 키 인증을 사용한다. health만 내부 키 없이 상태를 확인할 수 있다.

## 추론 계약 회귀검사 · PT-26

Python 3.12.6에서 실행한다. 운영 의존성 잠금은 유지하고 검사 의존성은 별도 파일에 고정한다.
`pytest==8.3.5`는 검사 실행기이며 기존 AnyIO의 pytest 플러그인으로 asyncio 검사를 실행한다.
`requirements-test.txt`는 직접 검사 의존성, `requirements-test.lock.txt`는 운영 잠금을 포함한 전체 검사 잠금이다.
Windows 전용 colorama는 환경 마커로 구분한다. 새 검사 의존성은 Docker 운영 이미지에 설치하지 않는다.

```powershell
python -m venv .venv-test
.\.venv-test\Scripts\python.exe -m pip install -r requirements-test.lock.txt
.\.venv-test\Scripts\python.exe -m pip check
.\.venv-test\Scripts\python.exe -m pytest -q --junitxml=test-results/pytest.xml
```

Linux CI는 같은 잠금과 명령에서 실행기만 `.venv-test/bin/python`으로 바꾼다.
`python-test` job은 검사 개수·실행/통과/실패/오류/건너뜀을 요약하고 JUnit XML을 보관한다.
수집 없음·전부 건너뜀·검사 실패는 CI 성공으로 처리하지 않는다.

| 검사 파일 | 관찰하는 계약 |
| --- | --- |
| `test_inference_api.py` | 내부키, 입력/이미지/수신량 검증, 오류 원문 미노출, NDJSON |
| `test_inference_service.py` | 제공자 선택·대체, 부분 출력 후 전환 금지, 실패/시간초과, 벡터 출처 |
| `test_provider_clients.py` | 실제 어댑터의 모의 HTTP 해석, SSE/NDJSON 완료, JSON/이미지 변환, 1024차원·개수·유한값 |
| `test_inference_cancel.py` | 첫 토큰 전/후 ASGI 연결 해제 시 Ollama/OpenAI HTTP 스트림 정리 |

검사는 `.env`를 읽지 않고 테스트 키·환경을 사용한다. 모델의 실제 HTTP transport는 fixture가 차단한다.
모의 응답의 계약 검사이며 실제 모델 품질·GPU 성능·실제 OpenAI 자격 증명/비용 검증을 뜻하지 않는다.
인젝션 문구를 user 데이터로 전달하는 검사는 모델이 해당 지시를 따르지 않는다는 품질 증명이 아니다.
사용자별 호출 제한과 정책/프롬프트 구성은 Spring·기능별 검사와 연결한다.
Spring `AiSlotsTest`는 정상·예외/취소·동시 실행 한도·인터럽트의 자리 반납을 별도로 검사한다.
실제 앱→Spring SSE→FastAPI의 연결 해제 전파·자리 반납 지연, 자동 요약 대기 5초,
실제 채점/Agent·권한 흐름은 별도 통합 검증이 필요하다. 특히 Spring 채팅은 다음 응답 조각에서 중지를 확인하므로
모델이 응답하지 않는 동안의 즉시 자리 반납을 보장한다고 해석하지 않는다.

## 이식 후 확인 · 2026-11-17

Python 3.12.6 venv에서 소스 compile/import, 잠금 파일 재설치와 `pip check`를 통과했다. 임시 Uvicorn 프로세스로 health HTTP 200/모델 미준비, 내부 키 누락·오류 401, 입력 검증 422, 4.5MB 초과 413을 확인했다. 연결할 수 없는 모델 서버에서는 채팅이 NDJSON `llm_unavailable`, 임베딩이 503으로 끝나는 것을 확인했다. `/v1/extract`와 `/v1/db-check`는 404이며 DB/S3 의존성은 없다. 임시 프로세스와 포트는 종료했다. 이 검증은 pytest 코드 없이 수행했으며 실제 모델별 품질·성능 검증을 대신하지 않는다.

자동 전환은 실제 과금 API 대신 임시 로컬 HTTP 제공자로 확인했다. Ollama 호출 실패와 임베딩 모델 미설치 각각에서 OpenAI 규약으로 전환하고, SSE·이미지·JSON 모드와 1024차원·모델 출처 반환을 확인했다. health 조회만으로 외부 추론 요청을 보내지 않으며, 일부 채팅 출력 후 실패에는 다른 모델로 전환하지 않는 것과 잘못된 차원은 503으로 거절하는 것을 확인했다. 실제 OpenAI 키의 유효성·과금 호출은 이 로컬 검증에 포함하지 않았다.

OpenAI 형식과 모델 기본값은 [Chat Completions API](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create), [GPT-5.4 mini](https://developers.openai.com/api/docs/models/gpt-5.4-mini), [이미지 입력](https://developers.openai.com/api/docs/guides/images-vision), [임베딩 차원](https://developers.openai.com/api/docs/guides/embeddings)을 확인해 적용했다.
