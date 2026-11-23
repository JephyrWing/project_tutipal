# Electron UI 회귀 검사 (PT-24)

이 검사는 빌드한 React 화면, 실제 Electron Main/Preload/loopback 브리지와 실제 Spring HTTP 서버를 사용한다. API를 모의 응답으로 바꾸지 않는다. `@playwright/test`는 `1.65.1`로 고정하며 브라우저 대신 저장소의 Electron 바이너리를 실행한다.

## 실행 환경

- Node와 Java는 `.nvmrc`와 `AGENTS.md` 기준을 따른다.
- CI DB는 기존 `infra/postgres/Dockerfile`의 PostgreSQL 17.12 + pgvector 0.8.7이다.
- DB는 별도 컨테이너와 loopback 포트 25432를 사용한다. DB 이름과 역할은 `tutipal_e2e`이며, 비밀번호는 매 실행 새로 생성한다.
- Spring은 테스트 동안에만 `127.0.0.1:28080`에서 시작한다. 기존 8080 서버나 실제 `.env`를 읽지 않는다.
- 각 앱은 임시 `userData/sessionData`를 사용한다. 사용자의 로그인, 프로토콜 등록과 실행 중인 앱을 유지한다.
- Linux에서는 Xvfb와 Electron sandbox 실행에 필요한 시스템 라이브러리가 필요하다. CI는 non-root 프로세스와 공식 Electron의 sandbox helper를 사용하며 `--no-sandbox`를 사용하지 않는다.

## 로컬 실행

먼저 프로젝트와 분리한 PostgreSQL + pgvector를 준비한다. 기존 개인/팀/운영 DB를 지정하면 안 된다. Docker가 있는 환경의 예시는 다음과 같다(각 실행마다 새 컨테이너를 만든다).

```bash
export E2E_DB_PASSWORD="$(openssl rand -hex 24)"
export E2E_DB_URL=jdbc:postgresql://127.0.0.1:25432/tutipal_e2e
container="tutipal-pt24-local-$(date +%s)"
docker build -f infra/postgres/Dockerfile -t tutipal-pt24-postgres .
docker run --detach --rm --name "$container" -p 127.0.0.1:25432:5432 \
  -e POSTGRES_DB=tutipal_e2e -e POSTGRES_USER=tutipal_e2e \
  -e POSTGRES_PASSWORD="$E2E_DB_PASSWORD" tutipal-pt24-postgres
# pg_isready 성공을 확인한다.
docker exec "$container" pg_isready -h 127.0.0.1 -U tutipal_e2e -d tutipal_e2e
```

Java 21 환경에서 Spring 실행 JAR를 만들고 프론트에서 검사를 실행한다.

```bash
cd tutipal_back
./gradlew bootJar --no-daemon
cd ../tutipal_front
npm ci
npm run build
npm run test:e2e:list
npm run test:e2e
# Linux 그래픽 세션이 없다면: xvfb-run -a npm run test:e2e
```

Windows에서는 `gradlew.bat`, `npm.cmd`를 사용할 수 있다. 격리 DB 준비 후 PowerShell 현재 프로세스에 `E2E_DB_PASSWORD`와 `E2E_DB_URL`을 설정한다. 별도 Chromium 다운로드는 필요하지 않으며, Electron 실행 파일은 프로젝트 `postinstall`에서 준비한다. DB/앱 서버를 이미 사용 중인 포트에서 재사용하지 않는다.

검사 종료 후 위 실행에서 직접 만든 컨테이너만 종료한다.

```bash
docker stop "$container"
```

`scripts/e2e-server.mjs`는 loopback의 `tutipal_e2e` 또는 `tutipal_e2e_<접미사>` DB만 허용한다. Flyway와 `ddl-auto=validate`로 새 스키마를 확인하며 원래 migration 파일을 수정하지 않는다. AI, LiveKit, 외부 OAuth, AWS 자격 증명은 사용하지 않는다.

## 실제 검사 범위

1. 익명 상태의 보호 경로 차단, 임의 HTTP 요청의 브리지 거절, Electron sandbox/contextIsolation/webSecurity 및 nodeIntegration 설정.
2. UI 개발 로그인, 교사의 방 생성, 학생의 비참가 방 접근 거절, 초대 코드 참여, 역할별 화면/학생의 교사 설정 API 거절, HttpOnly 세션 쿠키 비노출, 로그아웃 후 401와 보호 경로 차단, 다른 앱 로그인 유지.
3. 잘못된 초대 코드 거절, 실제 방 종료, 종료 안내와 닫힌 코드 재참여 거절.

수집된 테스트 수는 `npm run test:e2e:list`, 실제 결과는 JUnit/HTML 리포트에서 확인한다. assertion 개수를 테스트 수로 세지 않는다. 빌드·NSIS 검사와 UI 검사도 합산하지 않는다.

실계정 소셜 로그인·딥링크, 화면 캡처 동의·판서·WebRTC/TURN 미디어, 설치된 패키지의 fuse와 타 OS는 별도 검사다. 테스트용 개발 Electron의 자동화 성공을 설치 앱 보안/전체 제품 인수로 확대하지 않는다.

## CI 및 증거

`.github/workflows/front-e2e.yml`의 `electron-e2e` 작업이 develop 대상 PR과 develop push에서 별도로 실행된다. 기존 Spring 검사/패키징 CI는 유지한다. 수집 0건, 전체 skip, 누락된 리포트, 기능 실패는 성공으로 처리하지 않는다.

HTML: `tutipal_front/playwright-report/`, JUnit: `tutipal_front/test-results/electron-junit.xml`. 실패 화면은 임시 테스트 계정/방 데이터만 포함한다. trace/video는 끄고 실제 사용자 화면·env·Electron 쿠키/프로필은 업로드하지 않는다. Jira에는 요구/검사 기준, 전체 commit SHA, 환경, 명령/종료코드, 실제 실행 수와 CI/리포트 링크를 기록한다.
