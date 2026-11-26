# TUTIPAL 개발 시작 안내

## 1. 현재 준비한 범위

기본 골격의 설치·실행 확인 이후 PL 저장소의 로그인·방·챗봇·퀴즈·자료·공지·게시판·복습·화면공유를 현재 패키지와 FastAPI MVC에 이식했다. 원본과 설계 차이는 [PORTING.md](PORTING.md)를 참고한다. 실제 확인 범위와 외부 자격 증명·모델이 필요한 부분은 최종 검증 기록에서 구분한다.

## 2. 기준 도구

| 도구 | 기준 |
| --- | --- |
| JDK | 21 |
| Spring Boot / Gradle | 4.0.9 / Wrapper 9.5.1 |
| Node | 24.22.3 (`.nvmrc`) |
| Electron / React / Vite | 45.3.3 / 19.3.1 / 8.3.8 |
| Python | 3.12.6 + venv (`tutipal_ai/.python-version`) |
| DB | PostgreSQL 17.12 + pgvector 0.8.7 |
| 서버 실행 | Docker Engine + Compose v2 이상. 설계 기준은 Engine 29.9.3 / Compose 5.7.1 |

Spring Boot 4.0.9는 Java 21 및 Gradle 9.x를 지원한다. Java 21에서 Gradle을 실행하는 지원 범위는 8.5 이상이다. Gradle 9.5.1은 Mindot의 Wrapper 버전과 동일하게 선택했다. 이전 설계의 Maven 선택은 Gradle로 변경한다. 호환성 기준은 [Spring 공식 요구사항](https://docs.spring.io/spring-boot/4.0/system-requirements.html)과 [Gradle 공식 호환성 표](https://docs.gradle.org/current/userguide/compatibility.html)를 참고한다.

## 3. 서버 전체 실행

프로젝트 루트에서 다음을 실행한다.

```powershell
./infra/init-local.ps1
# 기존 값은 보존. 새 파일에는 DB/LiveKit/AI 내부 키를 생성하고 기존 파일에는 누락된 AI 내부 키만 추가한다.
docker compose --env-file infra/.env.local -f infra/docker-compose.local.yml config --quiet
docker compose --env-file infra/.env.local -f infra/docker-compose.local.yml up -d --build --wait
docker compose --env-file infra/.env.local -f infra/docker-compose.local.yml ps
```

처음에는 Docker 이미지, Gradle/Maven Central 의존성, Python 패키지, pgvector 소스를 내려받으므로 인터넷이 필요하다. DB 이미지는 PostgreSQL 패치 버전과 pgvector 버전을 모두 고정하기 위해 빌드한다. pgvector 활성화 자체는 Spring의 Flyway migration이 수행한다.

Spring 상태는 `http://127.0.0.1:8080/actuator/health`로 확인한다. 서버/DB 상태와 모델 준비 상태를 구분한다. 로컬 자료 저장은 Spring 전용 `tutipal_materials` 볼륨, 운영은 S3다. S3 모드에는 실제 버킷·권한·자격 증명이 필요하다. Python에 DB/S3 정보는 주입하지 않는다.

개발용 로그인은 `.env.local`에 `DEV_LOGIN=true`를 설정한 개인 PC에서만 사용한다. 운영 Compose는 이를 false로 고정한다. Google/Naver/Kakao는 설정된 공급자만 표시하며 미설정 상태에서도 서버가 기동한다.

Ollama는 호스트에서 실행한다. 이 PC는 `gemma4-ally:latest`와 `bge-m3`를 사용한다. 모델 파일은 저장소나 이미지에 넣지 않는다. `MODEL_PROVIDER=auto`는 설치된 로컬 모델을 우선하고, 없거나 Ollama가 응답하지 않으면 설정된 OpenAI API를 사용한다. 키가 없으면 조용히 가짜 답을 만들지 않고 준비 실패를 반환한다.

| 환경변수 | 용도 |
| --- | --- |
| `MODEL_PROVIDER` | `auto`(로컬 우선·OpenAI 대체), `ollama`, `openai` |
| `MODEL_NAME`, `MODEL_BASE_URL` | 팀원별 Ollama 채팅·비전 모델명과 서버 URL |
| `EMBEDDING_MODEL` | Ollama 임베딩 모델, 기본 `bge-m3` |
| `OPENAI_API_KEY` | 외부 API 키. FastAPI에만 주입하며 Git/앱에 포함하지 않음 |
| `OPENAI_MODEL` | OpenAI 채팅·비전·JSON 응답 모델명, 예제 `gpt-5.4-mini` |
| `OPENAI_EMBEDDING_MODEL` | OpenAI 임베딩 모델명, 예제 `text-embedding-3-small` |
| `OPENAI_MAX_COMPLETION_TOKENS` | OpenAI 최대 출력 토큰, 기본 4096 |
| `EMBEDDING_DIMENSIONS` | 현재 DB 기준 1024. 다른 차원은 스키마 변경 필요 |

자동 대체는 이미지·질문·자료 조각을 OpenAI로 보낼 수 있고 API 비용이 발생한다. 로컬 처리만 원하면 `MODEL_PROVIDER=ollama`를 선택한다. health는 유료 호출을 하지 않으며 `fallback_configured`는 키의 유효성 확인 결과가 아니다. 임베딩 제공자/모델이 바뀌면 기존 자료를 다시 인덱싱해야 같은 모델 공간에서 검색된다.

```powershell
# 로그 확인
docker compose --env-file infra/.env.local -f infra/docker-compose.local.yml logs --tail 100 backend fastapi
# 중지: DB 볼륨은 보존된다.
docker compose --env-file infra/.env.local -f infra/docker-compose.local.yml down
```

로컬과 운영 Compose는 프로젝트 이름과 DB 볼륨이 분리된다. 운영 데이터에 개발용 스키마를 덮어쓰지 않도록 구분한다.

## 4. Electron 실행과 패키징

```powershell
Set-Location tutipal_front
if (!(Test-Path .env)) { Copy-Item .env.example .env }
npm ci
npm run dev
# 앱 종료 후
npm run dist:win
```

`dev`는 개발용 Vite를 띄우고 Electron 창을 함께 연다. `dist:win`은 React 빌드까지 수행한 뒤 `release/TUTIPAL Setup 0.0.1.exe` 설치 파일을 만든다. 서버 Compose에 프론트 컨테이너는 없다. `package-lock.json`을 포함했으며 팀원과 CI는 `npm ci`를 사용한다. Electron 45의 실행 바이너리는 `postinstall`에서 `install-electron`으로 준비한다. 앱 서명은 미설정이다.

전역 Node가 다른 버전이면 `.nvmrc`의 24.22.3을 먼저 선택한다. 이번 검증은 공식 ZIP의 SHA-256을 확인한 뒤 프로젝트 내부 `.tools/node-v24.22.3-win-x64`에 압축 해제하여 수행했다. 이 경로는 Git에서 제외하며 전역 설치를 변경하지 않는다. 같은 로컬 도구를 사용할 때는 프로젝트 루트에서 다음처럼 현재 셸의 PATH만 변경한다.

```powershell
$env:PATH = (Join-Path $PWD '.tools/node-v24.22.3-win-x64') + ';' + $env:PATH
node --version
```

## 5. IDE에서 Spring만 직접 실행할 때

Spring 컨테이너를 띄우지 않고 DB/FastAPI/LiveKit만 실행한다.

```powershell
docker compose --env-file infra/.env.local -f infra/docker-compose.local.yml -f infra/docker-compose.dev.yml up -d --build tutipal-db fastapi livekit
```

`tutipal_back/.env.example`를 참고해 IDE 환경변수를 등록한 뒤 Java 21로 `TutipalBackApplication`을 실행한다. `.env`를 복사하기만 해서는 Spring이 읽지 않는다. IDE 실행은 `SPRING_PROFILES_ACTIVE=local`, DB/AI/LiveKit 주소는 loopback으로 지정한다. 이 override가 공개하는 내부 포트는 모두 `127.0.0.1` 한정이다. 외부 클라이언트용 설정으로 사용하지 않는다.

## 6. 기능을 추가할 위치

| 기능 | Spring 패키지 | 앱 위치 | Python 위치 |
| --- | --- | --- | --- |
| 로그인·사용자 | `users`, `common/auth`, `common/config` | `src/utils/auth`, 관련 모달 | 사용하지 않음 |
| 수업·참여 권한 | `sessions` | 기능별 components | 사용하지 않음 |
| PDF·S3 자료 | `materials` | 자료 모달 | S3 접근 금지 |
| 질문·퀴즈 등 | `learning` | 기능별 components | 확정된 추론에 한해 service 사용 |
| 제어 연결·화면공유 | `livekit` | `electron`, `src/utils/realtime` | 사용하지 않음 |
| RAG·벡터 검색 | `rag` | Spring API 호출 | 검색 금지, 전달받은 입력의 수치 계산만 가능 |
| 모델 호출 연동 | `ai/client` | Spring API 호출 | controllers → services → models/clients → views |

각 Spring 기능은 Mindot과 같은 하위 계층을 사용한다. PL에서 가져온 API와 엔티티는 현재 폴더에 맞춰 배치했으며 기능별 실제 위치는 PORTING.md를 참고한다.

## 7. 검증 범위

프론트 Playwright, Spring JUnit, Python pytest를 사용할 계획이다. 지금은 상세 테스트 시나리오와 테스트 코드를 만들지 않는다. CI의 JS 구문 확인, JAR/앱 빌드, Python import, Compose 설정 검사는 기본 골격의 연결 확인용이며 업무 테스트를 대신하지 않는다.

실제 설치·빌드 결과는 아래처럼 구분한다. 설정 파일이 존재한다는 이유만으로 실행 성공이나 보안 중계 완성을 주장하지 않는다.

## 8. 기본 골격 검증 이력 · 2026-11-17 (PL 이식 전)

| 항목 | 결과 |
| --- | --- |
| 실행 도구 | Windows, Temurin Java 21.0.13, Wrapper 9.5.1, Node 24.22.3 / npm 11.19.1, Python 3.12.6 |
| Docker | Desktop 4.95.0 / Engine 29.8.2 / Compose 5.6.0에서 실제 빌드·기동 완료. 설계에 기록한 Engine/Compose 버전과 구분 |
| 환경 설정 | `infra/.env.local`과 프론트 `.env` 생성. DB/LiveKit 값은 각각 암호학적 난수 사용. 초기화 재실행 시 기존 파일 보존 확인 |
| npm 설치 / 잠금 파일 | `package-lock.json` 생성 후 지정 Node에서 `npm ci` 재설치 통과. Electron 실행 바이너리 설치 완료 |
| Electron 화면 / 패키징 | `npm run check`, Vite 8.3.8 화면 빌드 및 `npm run dist:win` 통과. Windows x64 NSIS EXE 생성(미서명) |
| 패키징 앱 실행 | `release/win-unpacked/TUTIPAL.exe` 실행. `tutipal://app/index.html`에서 한글 위젯·CSS 렌더링 확인. 렌더러 런타임 오류 없음, `require` 미노출 확인. 설치 마법사의 실제 PC 설치는 수행하지 않음 |
| Spring JAR / 잠금 파일 | Boot 4.0.9로 `build/libs/server.jar` 생성. `gradle.lockfile` 생성 및 잠금 적용 재빌드 통과. JUnit 테스트 실행 안 함 |
| Python 설치 / 잠금 파일 | `.venv`의 Python 3.12.6에 설치, `pip check` 통과, `requirements.lock.txt` 생성. Docker/CI도 이 파일 사용 |
| FastAPI 직접 실행 | venv의 Uvicorn 0.54.0 기동 후 `/internal/ai/health` HTTP 200, `status=ok`, `model_ready=false` 확인 |
| Docker 이미지 | Spring/Python 잠금 파일을 적용한 backend·fastapi 이미지 및 PostgreSQL 17.12 + pgvector 0.8.7 이미지 빌드 통과 |
| 서버 전체 실행 | local Compose `up -d --wait` 통과. backend/fastapi/DB healthy, LiveKit 1.13.11 실행 확인(LiveKit 자체 healthcheck는 없음) |
| Spring HTTP / 기본 보안 | `/actuator/health` 및 `/actuator/health/readiness` HTTP 200, `UP`. 상태 확인 외 임의 GET 요청 HTTP 403 |
| 컨테이너 간 통신 | backend 컨테이너에서 `http://fastapi:8000/internal/ai/health` HTTP 200 확인 |
| Flyway / pgvector | V1 `enable pgvector` 성공 이력 1건. `vector` 확장 0.8.7 및 벡터 거리 연산 결과 1 확인. 업무 테이블은 없고 `flyway_schema_history`만 존재 |
| 기존 DB 재기동 | Spring 재시작 후 migration 검증 통과 및 `No migration necessary` 확인. 이력은 성공 1건으로 유지되고 health `UP` 복귀 |
| 네트워크·설정 경계 | DB/FastAPI/LiveKit 제어 7880 호스트 포트 없음. Spring은 127.0.0.1:8080, LiveKit 미디어 7881/tcp·7882/udp 공개. Python은 `/opt/venv`, DB/S3/AWS 환경변수 없음 |
| 업무 기능 및 자동 테스트 | 미구현. Playwright/JUnit/pytest 테스트 실행 결과 없음 |

이전 세션의 npm/Gradle/Python 다운로드 제한과 Docker named pipe 접근 거부는 이번 세션에서 해당 실행 명령의 권한 승인으로 해소했다. 제한된 샌드박스에서는 여전히 DNS/소켓/Docker 접근이 차단될 수 있으므로 명령 자체의 실패와 프로젝트 오류를 구분한다. 비밀값이 포함되는 `docker compose config` 전체 출력이나 `docker inspect` 전체 출력은 공유하지 않는다.

현재 남은 실행 장애는 없다. npm은 일부 전이 의존성의 deprecated 경고와 이번 NSIS 대상에서 사용하지 않는 `electron-winstaller` 설치 스크립트 관련 경고를 출력한다. 기준 Node 배포본의 npm 11로 재설치와 NSIS 빌드는 통과했다. 앱 서명·아이콘·업데이트, macOS/Linux 앱 패키징, 외부 WebRTC/Cloudflare/S3/모델 연동과 실제 CI/CD 실행은 이번 검증 범위 밖이다.

## 9. 잠금 파일과 상태 재확인

프론트는 `npm ci`, Spring은 `gradle.lockfile`을 포함한 Wrapper 빌드, Python은 다음 명령을 사용한다. 잠금 파일은 저장소에 포함하고 `.env.local`, `.venv`, `.tools`, 빌드 결과는 포함하지 않는다.

```powershell
Set-Location tutipal_ai
.\.venv\Scripts\python.exe --version  # 3.12.6
.\.venv\Scripts\python.exe -m pip install -r requirements.lock.txt
.\.venv\Scripts\python.exe -m pip check
.\.venv\Scripts\python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8000
```

프로젝트 루트에서 실행 중인 서버를 확인한다. DB 사용자/이름을 변경했다면 마지막 명령의 인자를 실제 로컬 값으로 맞춘다.

```powershell
Invoke-RestMethod http://127.0.0.1:8080/actuator/health
docker compose --env-file infra/.env.local -f infra/docker-compose.local.yml ps
docker compose --env-file infra/.env.local -f infra/docker-compose.local.yml exec -T backend curl --fail --silent http://fastapi:8000/internal/ai/health
docker compose --env-file infra/.env.local -f infra/docker-compose.local.yml exec -T tutipal-db psql -U tutipal -d tutipal_db -c "SELECT version, description, success FROM flyway_schema_history; SELECT extname, extversion FROM pg_extension WHERE extname='vector';"
```

위 기록은 기본 골격 검증 당시의 결과다. PL 이식 이후 결과는 다음 장을 기준으로 한다. 과거 기록의 `tutipal://app` 화면 원점은 현재 내장 dist를 제공하는 제한된 loopback 원점으로 변경했다.

## 10. PL 이식 후 최종 검증 · 2026-11-17

이전 장은 골격 단계의 기록이며 현재 상태는 이 장을 기준으로 한다.

| 항목 | 실제 결과 |
| --- | --- |
| 버전/빌드 | Java21 / Boot4.0.9 / Gradle9.5.1 유지. JAR·Docker backend/fastapi·Vite·Windows NSIS 패키징 통과 |
| Electron | 설치된 dist를 제한된 loopback에서 제공. 로그인 화면·위젯 렌더링, renderer require 미노출. 개발 Vite 화면도 확인 |
| 서버 | local Compose4개 실행. Spring health/readiness UP, FastAPI HTTP200 |
| DB | 기존 pgvector V1 보존, V2~V12 적용. Flyway12건 성공, pgvector0.8.7 |
| 실제 API 흐름 | 임시 개발 계정3개로43항목 확인: 로그인, 방 개설/참여, 참가자/선생님 권한403·404, 객관식 자동채점/중복409, 공지·글·댓글, 도움 수락·판서 권한, LiveKit 신호 중계, 자료 업로드/인덱싱/다운로드/삭제, 복습 동의/임베딩/철회삭제, 챗봇 SSE, 로그아웃401 |
| 실제 앱 업무 흐름 | 정상 개발 로그인 버튼 → 홈 → 방 개설 → 선생님 모드/STOMP 실시간 표시 → 주제·난이도 저장 → 앱 설정 → 실제 로그아웃 확인. JS에서 세션 쿠키 미노출, 브리지 비인증 접근403 |
| 소켓 권한·종료 | 별도18항목 통과: STOMP 판서 수신, 학생 송신403, 로그아웃 시 STOMP/LiveKit 종료, 인증 없는 연결·만료 쿠키 재접속401. 로컬 관측 종료시간17ms/44ms는 최대 지연 보장이 아님 |
| 로컬 AI | 사용자 요청대로 gemma4-ally:latest 설정, bge-m3(약1.2GB) 다운로드 완료. 실제 채팅 스트리밍·자료/복습 임베딩 확인 |
| AI 준비 상태 | local_chat_ready=true, local_embedding_ready=true, model_ready=true. OpenAI 키는 미설정 |
| 자동 OpenAI 대체 | 임시 로컬 HTTP 제공자로 모델 부재/연결 실패 전환, SSE·비전·JSON·임베딩1024 규약 확인. 실제 유료 OpenAI 호출은 API 키가 없어 미실행 |
| 벡터 출처 | 제공자·모델·차원 태그 저장 및 동일 출처만 검색. 다른 모델 벡터를 함께 비교하지 않음 |
| Compose/환경 예제 | local / local+dev / prod+cloudflare 설정 해석 통과. 기존 DB/LiveKit 키 보존, 누락 AI 내부 키만 추가 |
| 공개 파일 점검 | 실제 env·키·캐시·venv·node_modules·설치 파일·모델 제외, 실행에 필요한 소스와 잠금 파일 포함. 캐릭터 작업 ZIP은 PC에 보존하고 업로드 제외 |

Windows 설치 파일은 미서명이며 설치 마법사를 PC에 적용하지 않았다. 소셜 실계정 로그인·실제 S3·외부 장비 간 WebRTC/TURN·다른 OS 앱 패키징·실제 운영 배포는 미검증이다. Playwright/JUnit/pytest 코드와 원본 테스트 파일은 추가하지 않았다. API 점검은 제외된 임시 진단 파일로 수행했다.

현재 PC의 infra/.env.local에는 사용자 지정 모델명과 auto 제공자를 저장했다. OpenAI를 쓰는 팀원은 자신의 파일에 OPENAI_API_KEY와 OPENAI_MODEL을 설정한다. 비밀값은 채팅이나 공개 저장소에 넣지 않는다. 모델을 바꿨다면 Compose를 다시 적용해야 한다.

점검 후 개발용 로그인은 다시 false로 적용했다. 로컬 서버와 사용자가 요청한 Ollama 모델은 사용할 수 있는 상태로 두며, 임시 Electron/CDP/Vite/Uvicorn 진단 프로세스는 종료했다. 소스는 사용자의 요청으로 JephyrWing/project_tutipal 공개 저장소에 올리고 외부 서버 배포·이미지 push는 하지 않는다.

## 11. 공개 저장소와 GitHub Actions

공개 저장소: https://github.com/JephyrWing/project_tutipal

최초 CI의 ubuntu-latest가 Ubuntu26.04를 선택해 Python3.12.6 설치 단계가 실패했다. Python 버전을 바꾸지 않고 해당 job을 공식 배포본이 있는 ubuntu-24.04로 고정했다.

수정 후 [CI 실행 44055950618](https://github.com/JephyrWing/project_tutipal/actions/runs/44055950618)은 전체 성공했다. Windows Electron NSIS 패키징, Java21 Spring JAR 빌드, Python3.12.6 설치/import/pip check, Compose 설정 검사를 통과했다. Docker Hub 업로드와 SSH 배포 job은 비활성 상태로 건너뛰었다. 실제 환경 파일·비밀값·설치 파일·모델은 원격 저장소에 포함하지 않았다.

## 12. AI 채팅·화면 위젯 개발 기준

기존 개발 경로는 `POST /api/tutor/chat`의 Spring SSE와 `POST /api/screen`의 요약·화면 질문이다. Electron에서 자동 요약을 켜면 40초 간격으로 선택한 화면의 변경을 확인한다. 위 10장의 로컬 채팅·임베딩 확인은 당시 구현에 해당한다.

수업 학생과 개인 학습의 Agent 채팅, 두 모드에서 사용하는 화면 분석 상세 모달과 직접 질문 연결은 [설계서 11~12장](TUTIPAL_DESIGN.md#11-ai와-학습-지원-기능)과 [AI 워크플로 계약](AI_WORKFLOW_CONTRACT.md)의 구현 대상이다. Agent 분기와 자료 권한은 Spring에서 확인하고, FastAPI는 전달받은 입력으로 LangGraph와 도구를 실행한다. 새 흐름 구현 후에는 역할 분기, 수업 범위, 화면 동의와 분석 중지, 질문 없는 자동 요약, 직접 질문 SSE를 각각 확인한다.
