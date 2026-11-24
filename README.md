# TUTIPAL

팀 작업은 [AGENTS.md](AGENTS.md)와 [Jira 협업 시작 안내](https://gad03302.atlassian.net/wiki/spaces/PT/pages/589825)를 먼저 읽고 진행한다. 업무·명세·결정·검증 이력은 Jira, 코드와 PR은 GitHub, 파일 산출물은 [Drive](https://drive.google.com/drive/folders/1r7AnAbWxKyroED1DeWxHz5c5dG0fDdn2)에서 관리한다. 이해 확인 정책도 Jira 하네스의 최신 본문을 따른다. 현재 운영 기준은 jira_drive-v3이며 작성자는 체크/PM 확인 대기로 제출하고 최종 확인·완료 전이는 PM이 직접 수행한다. 계정은 Jira의 실제 accountId와 확인된 한국어·영문 별칭을 대조한다.

WBS-SYNC-20261118-v1에 따라 WBS의 담당·계획일·범위·선행 변경은 같은 작업에서 Jira 보드의 실제 담당자·지원 날짜 필드·연결 업무에 반영하고 재조회한다. 공동 담당은 역할별 하위 작업으로 나누며 실제 진행·검사·PM 완료는 Jira 증거로 구분한다.

기본·팀 통합 브랜치는 `develop`이다. 작성자는 작업을 구현·검사한 뒤 `develop` 대상 PR을 열고 저장소 보호와 필수 검사 조건을 충족한 자기 PR을 직접 병합한다. 실제 merge SHA를 Jira에 남기고 역할이 끝난 작업 브랜치를 정리한 뒤 체크와 PM 확인 대기로 제출한다. 삭제 전 PR·현재 head·추가 커밋·진행 중 사용과 복구 SHA를 확인하고 삭제 후 원격 목록을 재조회한다. `main`·`develop`과 진행·미병합 작업은 보존한다. 세부 기준은 [GIT-CLEANUP-20261124-v2 / PT-79](https://gad03302.atlassian.net/browse/PT-79)를 따른다.

DOC-CENTRAL-20261118-v1 / PT-80에 따라 팀원은 코드·필수 원본문서·검사를 Git에 반영해 해당 PR의 통합을 확인하고 직접 만든 산출물을 지정 Drive에 같은 파일 ID로 게시한다. 실제 Git 통합 및 게시 증거를 Jira에 남겨 체크와 PM 확인 대기로 제출한다. 중앙 반영이 필요한 경우에만 `docs-sync-pending`으로 인계하며 무변경·파생 영향이 없는 작업에는 대기 라벨을 의무로 남기지 않는다. 전체 Drive 검색·영향 검토·기존 산출물 파생 갱신은 Aileron(최진우 PM의 보조 AI)이 사용자 요청 시 중앙 처리한다. 중앙 반영 대기로 팀원의 활성 편집을 유지하지 않으며 원작업 완료와 문서 반영 상태는 PM이 각각 판단한다. 산출물 게시 자체가 해당 업무의 인수 기준이면 그 조건을 유지한다. 세부 인계 항목·파일 링크는 Jira 하네스를 따른다.

프로젝트는 워터폴과 애자일을 함께 적용한다. 단계별 산출물·검토 상태를 관리하면서 Jira의 작은 작업을 반복 구현·검증한다. 구체적인 방법론과 산출물 적용 기준은 [Jira 운영 규칙](https://gad03302.atlassian.net/wiki/spaces/PT/pages/425986)을 따른다.

React/Vite 화면을 내장한 Electron 학습 앱, Spring 업무 서버, FastAPI 추론 서버다. [PL 구현](https://github.com/jeonseolmin/tutipal)의 로그인·방·챗봇·퀴즈·자료·공지·게시판·복습·화면공유를 현재 구조와 통신 정책에 맞게 이식했다. 기준과 변경 내역은 [PORTING.md](docs/PORTING.md)에 기록한다.

## 구성과 책임

| 경로 | 역할 |
| --- | --- |
| `tutipal_front/` | JavaScript React + Vite + Electron. 내장 화면·위젯·창 선택·캡처·그림 오버레이 |
| `tutipal_back/` | Spring 기능별 controller/service/repository/entity/dto. 인증·DB·RAG·자료 저장·LiveKit 중계 |
| `tutipal_ai/` | FastAPI MVC. Spring이 전달한 입력의 채팅·비전·임베딩 계산 |
| `infra/` | Spring·FastAPI·PostgreSQL/pgvector·LiveKit Compose와 환경변수 예제 |
| `.github/workflows/` | 빌드 확인과 선택적으로 활성화하는 Docker Hub → SSH → Compose 배포 |

로그인은 **Spring 세션 쿠키**이며 Redis는 사용하지 않는다. Electron 내부 loopback 브리지는 패키지의 화면과 Spring 요청만 연결한다. Spring은 React 화면을 배포하지 않는다. LiveKit 제어는 같은 Spring이 승인·중계하고 판서도 Spring을 통해 전달한다. LiveKit 데이터 발행은 `canPublishData=false`다.

## 시작하기

Java 21, Node 24.22.3, Python 3.12.6, Docker Desktop Linux 컨테이너 모드가 필요하다. Spring Boot 4.0.9와 Gradle Wrapper 9.5.1을 유지한다.

```powershell
./infra/init-local.ps1
docker compose --env-file infra/.env.local -f infra/docker-compose.local.yml up -d --build --wait
Invoke-RestMethod http://127.0.0.1:8080/actuator/health

Set-Location tutipal_front
npm ci
npm run dev
# Windows 설치 파일
npm run dist:win
```

초기화는 기존 비밀값을 보존한다. 새 파일이면 DB·LiveKit·AI 내부 키를 안전한 난수로 생성하고, 기존 파일에는 누락된 AI 내부 키만 추가한다. 실제 `.env` 파일은 Git에 포함하지 않는다. 서버 비밀값을 `VITE_` 변수에 넣지 않는다.

개발용 로그인은 기본 꺼져 있다. 개인 PC에서만 `infra/.env.local`에 `DEV_LOGIN=true`를 설정하고 Compose를 다시 적용한다. 운영 Compose는 항상 개발용 로그인을 끈다. 소셜 로그인은 Google/Naver/Kakao 실제 앱 등록과 환경변수가 필요하다.

AI는 `MODEL_PROVIDER=auto`에서 설치된 Ollama 모델을 우선하고, 해당 모델이 없거나 Ollama에 연결할 수 없으면 OpenAI로 전환한다. 팀원마다 `.env.local`의 `MODEL_NAME`과 `EMBEDDING_MODEL`을 지정한다. 이 PC는 `gemma4-ally:latest`와 다운로드한 `bge-m3`를 사용한다. 모델 파일은 저장소에 포함하지 않는다.

로컬 모델이 없는 팀원은 `OPENAI_API_KEY`, `OPENAI_MODEL`, `OPENAI_EMBEDDING_MODEL`을 설정한다. 예제 모델은 `gpt-5.4-mini`, `text-embedding-3-small`이다. 키는 FastAPI에만 전달한다. 모델과 API 키가 모두 없으면 준비되지 않은 상태로 응답한다. OpenAI로 전환하면 입력이 외부 API로 전송되고 비용이 발생할 수 있다. 임베딩은 DB에 맞춰 1024차원이며 모델 출처가 다른 벡터는 함께 비교하지 않는다.

자료 원본은 로컬 개발에서 Spring 전용 영속 볼륨을 사용한다. 운영은 `MATERIAL_STORAGE=s3`와 버킷/권한 설정을 사용한다. 자료 읽기·PDF 추출·RAG·DB·S3 접근은 Spring만 담당한다.

## 안내

- [설계 원본](docs/TUTIPAL_DESIGN.md)
- [수업 학생·개인 학습 Agent와 AI 워크플로 계약](docs/AI_WORKFLOW_CONTRACT.md)
- [설치·개발·검증 결과](docs/DEVELOPMENT.md)
- [PL 기능 이식 범위](docs/PORTING.md)
- [외부 배포 준비](docs/DEPLOYMENT.md)

npm/Gradle/Python 잠금 파일을 사용한다. Playwright/JUnit/pytest 테스트 코드는 이번 이식에 추가하지 않는다. 앱 서명, 소셜 실계정, 외부 WebRTC·TURN, 실제 S3, 외부 배포는 별도 확인 대상이다.
