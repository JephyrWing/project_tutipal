# PT-65 실제 LiveKit 미디어 검사

## PT-114 종료 회귀 보완

PT-30 병합 07c483a의 CI45551908245에서 기존 Disconnected 조건이20초 안에 충족되지 않았다.
진단 source `9b9e01b669f59fd2102c316e57727ef9672e64a3`의
[실패 시점 JSON](https://github.com/JephyrWing/project_tutipal/actions/runs/45570917461/artifacts/15002386202)에서
SDK는 reconnecting이었지만 실제 참가자0·옛매핑409·원격참가자0·로컬트랙 없음이 확인되었다.
서버 권한 회수와 독립 SDK의 자동 재시도 종료 시점을 혼동한 검사 구성이 간헐 실패의 원인이다.
Spring 내부 소켓/참가자 제거의 정확한 실행 순서는 직접 계측하지 않았다.

주입 SDK에만 `reconnectPolicy.nextRetryDelayInMs=()=>null`을 지정한다. 신호 연결을 잃으면 재시도하지 않고
기존20초 내 Disconnected 이벤트와 상태를 요구한다. 서버 참가자 수를 별도로 조회해 도움종료 후0명,
학생 로그아웃 직후에는 교사만1명임을 필수 검증하고, 이전 매핑의409/401 거절도 요구한다.
제품 SDK 재접속 정책은 변경하지 않으며 이 검사는 기본 SDK 재시도 경로의 검증 증거가 아니다.
제품 UI 재접속·권한 재승인 검사는 `HELP_ACCESS_CHECKS.md` 등의 별도 범위로 유지한다.

테스트 정리의 room.disconnect() 호출 **전**에 SDK 상태/이벤트,
실제 LiveKit 참가자 수, 기존 매핑의 Spring HTTP 상태를 JSON으로 첨부한다.
서버 참가자 조회의 관리자 JWT는 Node에서만 매 실행의 일회용 키로 서명하고 renderer/보고서에 노출하지 않는다.
조회 실패는 null/unavailable이며 참가자0으로 간주하지 않는다.
간헐성을 확인하기 위해 고유2사례를 각각3회 실행한다(수집/실행 예정6, 고유기능2).
자동 retry는0이고, 실패를 삭제하거나 반복 성공만 선택하지 않는다.
진단 JSON은 실제 파일로 저장한 뒤 첨부한다. 이전 JUnit 전용 보고서에서 메모리 첨부가 누락된 실행과
과거 실패는 Jira PT-114에 보존하며 새 성공으로 소급 변경하지 않는다.

FR-SHR-04·06 / NFR-SEC-04 / AC-SHARE-001-3 중 단일 CI runner의 실제 SDK·미디어 경로를 확인한다.
U4 합성 제어 서버 검사는 `HELP_ACCESS_CHECKS.md`에 별도로 보존한다.

## 실제 사용하는 구성

- 저장소 지정 Electron/Node/Java, LiveKit SDK 2.24.1 및 서버 v1.13.11.
- 일회용 PostgreSQL/pgvector·최신 Flyway·실제 Spring 28080, 기존 합성 AI 28081.
- 실제 LiveKit 제어 28082, TCP 28084, UDP 28085. 같은 runner의 loopback 주소만 사용한다.
- 임시 독립 Electron 프로필 두 개와 실제 앱의 제한된 HTTP/WS 브리지·Spring 시그널링 중계.

테스트는 장치 권한을 요청하지 않고 자체 생성한 320×180 canvas 영상만 `screen_share` source로 송신한다.
교사 SDK의 `framesDecoded`가 2보다 커야 영상 수신 성공이다. 단순 연결 성공으로 대체하지 않는다.
도움 종료 뒤 두 연결의 Disconnected 이벤트와 상태, 로그아웃 뒤 발행자 퇴장·원격 구독 해제 및 트랙 비활성화,
이전 로그인 세션의 접속 주소 재사용 거절을 확인한다. 참가자 제거는 실제 LiveKit 서버가 처리한다.
캡처 자원 정리는 테스트의 합성 트랙에만 적용되며 제품 OS 캡처 해제 증거로 쓰지 않는다.
고정 SDK의 `RemoteTrack.stop()`은 재사용할 transceiver를 보존하기 위해 트랙을 disable한다.
따라서 원격 `MediaStreamTrack.readyState=ended`를 요구하지 않고 실제 수신 이력·구독 해제 이벤트·
모든 수신 트랙의 `enabled=false`와 참가자 제거를 함께 요구한다. 테스트의 detach는 표시 요소 정리이며
트랙을 직접 stop해서 SDK 비활성화 검사를 통과시키지 않는다.

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
