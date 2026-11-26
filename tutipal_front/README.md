# Electron 앱

Mindot의 `src/components/기능/컴포넌트.jsx + CSS`, `src/utils` 구성을 바탕으로 한다. `electron/`은 PC 창과 OS 기능, `src/`는 내장 React 화면이다. 두 개의 서비스가 아니다. 웹 대시보드나 프론트 서버용 Dockerfile은 만들지 않는다.

```powershell
# Node 24.22.3 권장. 프로젝트 루트 .nvmrc 참고
npm ci
npm run dev
npm run build
npm run dist:win
```

`npm run dev`는 개발용 Vite와 Electron을 함께 실행한다. `npm run build`는 React 화면을 `dist`로 빌드하고, `dist:win`은 그 결과까지 포함한 Windows 설치 EXE를 `release`에 만든다. macOS/리눅스는 해당 OS에서 `dist:mac`/`dist:linux`를 사용한다. 앱 서명·공증과 자동 업데이트는 별도 설정이 필요하며 현재 미설정이다.

`package-lock.json`으로 전이 의존성을 고정하며 팀원과 CI는 `npm ci`를 사용한다. Electron 45의 실행 바이너리는 루트 `postinstall`의 `install-electron`이 설치한다. 최초 설치에는 npm 레지스트리와 Electron 배포본 다운로드 접근이 필요하다. CI도 Windows NSIS 설치 파일까지 빌드하지만 외부 업로드는 하지 않는다.

PL 구현의 로그인, 방 개설·참여·설정·핀, 개인/수업 챗봇, 이해도 질문, 자료·공지·게시판, 복습, 화면 분석 동의, 캐릭터 위젯, LiveKit 화면공유와 도움 요청 UI를 현재 단일 앱 구조로 이식했다. 화면은 `src/pages`, 서버 호출은 `src/api`, 캐릭터와 공통 UI는 `src/components`에 둔다. 실제 AI 응답은 서버에 구성한 모델 제공자가 준비돼 있어야 한다.

[AI 채팅·위젯 목표 설계](../docs/TUTIPAL_DESIGN.md#11-ai와-학습-지원-기능)에서 수업 학생과 개인 학습 사용자는 위젯의 자동 요약 말풍선과 상세 모달을 사용한다. 직접 질문의 Agent는 Spring이 학습 맥락에 맞춰 선택한다. 선생님 화면의 현재 `챗봇 미리보기` 탭은 목표 화면에서 제외하며, AI 문제 생성 모달과 학생별 답안 관리 모달을 사용한다.

renderer에서 Node 사용을 끄고 context isolation과 sandbox를 켰다. Main은 설치된 `dist`를 `127.0.0.1`의 임의 포트로 제공하고, 허용된 업무 경로만 Spring으로 중계한다. 각 요청에는 이 앱의 webContents만 주입할 수 있는 실행별 임의 헤더가 필요하다. Spring 로그인 쿠키는 Electron 세션의 서버 쿠키 저장소에만 두고, CSRF 값은 `/api/auth/csrf`에서 읽는다. 로그인 쿠키를 localStorage에 복사하지 않는다. `/ws`와 `/livekit` 연결도 같은 중계를 통과한다.

LiveKit 토큰 응답은 Spring의 세션에 묶인 관문 주소이며 실제 JWT는 renderer에 전달하지 않는다. 그림 좌표는 LiveKit 데이터 채널 대신 Spring API/STOMP를 사용한다. 화면 분석은 선택한 창만 캡처하고 이미지는 저장하지 않는다. 소셜 로그인은 시스템 브라우저와 `tutipal://auth` 딥링크의 일회용 코드 교환을 사용한다.

`TUTIPAL_SPRING_URL`은 Spring 주소이며 비밀값이 아니다. 개발 스크립트는 `.env`의 이 값만 읽으며, 설치 앱의 기본 주소는 `package.json`의 `tutipal.serverUrl`이다. 서버 비밀값과 LiveKit API secret을 앱에 넣지 않는다. 테스트 도구는 Playwright로 정했으며 테스트 코드는 추가하지 않았다.
