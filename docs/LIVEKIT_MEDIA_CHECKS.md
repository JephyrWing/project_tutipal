# PT-65 실제 LiveKit 미디어 검사

FR-SHR-04·06 / NFR-SEC-04 / AC-SHARE-001-3 중 단일 CI runner의 실제 SDK·미디어 경로를 확인한다.
U4 합성 제어 서버 검사는 `HELP_ACCESS_CHECKS.md`에 별도로 보존한다.

## 실제 사용하는 구성

- 저장소 지정 Electron/Node/Java, LiveKit SDK 2.24.1 및 서버 v1.13.11.
- 일회용 PostgreSQL/pgvector·최신 Flyway·실제 Spring 28080, 기존 합성 AI 28081.
- 실제 LiveKit 제어 28082, TCP 28084, UDP 28085. 같은 runner의 loopback 주소만 사용한다.
- 임시 독립 Electron 프로필 두 개와 실제 앱의 제한된 HTTP/WS 브리지·Spring 시그널링 중계.

테스트는 장치 권한을 요청하지 않고 자체 생성한 320×180 canvas 영상만 `screen_share` source로 송신한다.
교사 SDK의 `framesDecoded`가 2보다 커야 영상 수신 성공이다. 단순 연결 성공으로 대체하지 않는다.
도움 종료 뒤 두 연결의 Disconnected 이벤트와 상태, 로그아웃 뒤 발행자 퇴장·원격 트랙 종료,
이전 로그인 세션의 접속 주소 재사용 거절을 확인한다. 참가자 제거는 실제 LiveKit 서버가 처리한다.
캡처 자원 정리는 테스트의 합성 트랙에만 적용되며 제품 OS 캡처 해제 증거로 쓰지 않는다.

## 실행

CI `.github/workflows/livekit-media-checks.yml`은 매 실행 새 테스트 키와 일회용 DB를 생성한다.
비밀값을 mask하고 실제 `.env`·팀 DB·개인 앱 프로필은 읽지 않는다. 고정 컨테이너와 미디어 포트를
준비한 뒤 bootJar, npm ci, renderer build, `xvfb-run -a npm run test:livekit-media`를 실행한다.
로컬 실행도 명시적인 전용 `E2E_DB_URL`/`E2E_DB_PASSWORD`와 64자리 hex `E2E_LIVEKIT_SECRET`가 필요하다.
실제 서버 구성은 workflow를 따른다. 전용 DB guard를 해제하거나 팀 DB를 사용하지 않는다.

실제 미디어 모드는 `E2E_LIVEKIT_MEDIA=true`일 때만 활성화하며 U4 합성 업스트림 모드와 동시 사용을 거절한다.
일반 E2E의 포트9 업스트림 동작과 제품 설정은 유지한다. 테스트 Electron만 loopback ICE를 허용한다.
contextIsolation/sandbox/webSecurity는 그대로 유지하며 fake-device·권한 자동 허용·no-sandbox 옵션은 쓰지 않는다.
서버의 loopback 후보·IP 필터 설정은 [해당 고정 버전의 공식 구성 예제](https://github.com/livekit/livekit/blob/v1.13.11/config-sample.yaml)를 기준으로 한다.

JUnit XML의 실제 수집·실행·실패·건너뜀 수를 CI에서 집계하고 실패/보고서 누락/실행0은 성공으로 표시하지 않는다.
영상·화면 이미지·원문 토큰/쿠키/접속 주소는 보고하지 않는다. 합성 영상의 디코딩 프레임 수만 첨부한다.

## 별도 미검증

같은 runner 두 프로필은 물리적인 두 장비·다른 네트워크가 아니다. PT-19 외부 WebRTC/TURN,
실제 OS 창 선택·새 화면 동의·오버레이·혼합 DPI/다른 OS·설치본 전체 인수는 별도다.
테스트 SDK 인스턴스는 제품 UI의 공유 수명주기 구현과 구분한다. 이 검사로 PT-65 전체 완료나 PM 승인을 대신하지 않는다.
