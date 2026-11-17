# LiveKit 단일 노드 기본 설정

Compose의 `LIVEKIT_CONFIG`가 현재 단일 설정 원본이다. 같은 내용을 별도 YAML에 중복 관리하지 않는다. API key/secret은 `.env.local` 또는 `.env.prod`에서 Spring과 LiveKit에만 전달한다.

| 포트 | 목적 | 기본 공개 범위 |
| --- | --- | --- |
| 7880/tcp | 제어 WebSocket 및 서버 API | Docker realtime 네트워크 내부. 기본 호스트 포트 없음 |
| 7881/tcp | WebRTC TCP 미디어 | 호스트 공개. 실제 외부 접근은 방화벽 설정 필요 |
| 7882/udp | WebRTC UDP mux 미디어 | 호스트 공개. 실제 외부 접근은 방화벽 설정 필요 |

`LIVEKIT_NODE_IP`는 클라이언트에서 도달 가능한 미디어 호스트 IP이다. 기본 로컬 값 `127.0.0.1`은 같은 PC의 실행 확인용이며 외부 학생에게 사용할 수 없다. Docker Desktop 환경에서는 실제 ICE 연결 검증이 필요하다. TURN은 아직 구성하지 않았다.

Spring의 `/livekit/{grant}` 중계가 로그인 세션과 수업/도움 권한을 접속·재접속마다 확인한다. 기본 Compose는 LiveKit 제어 포트의 직접 접속을 열지 않는다. IDE override의 `127.0.0.1:7880`도 로컬 Spring 개발용이며 외부 공개용이 아니다.

내부 JWT의 `canPublishData=false`를 코드에서 강제한다. 판서는 Spring REST 권한 검사 후 STOMP로 전달한다. 세션 유효성을 주기적으로 확인하고 무효 연결은 신호 소켓 종료와 RoomService 참가자 제거를 수행한다. Spring 재시작 시 인증 매핑을 잃은 기존 참가자를 정리한다. 외부 미디어·TURN과 장비 간 실제 화면 전송은 별도 확인 대상이다.
