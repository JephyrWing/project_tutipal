# PL 구현 이식 기록

## 기준

- 원본: https://github.com/jeonseolmin/tutipal
- 기준 브랜치: `main`
- 커밋: `d60f705748da927375d77aedc11165ca0ea1f49e` (2026-11-17 조회)
- 조회 당시 `develop`은 `main`보다 뒤에 있어 병합된 `main`을 기준으로 했다.
- 원본 README·AGENTS·HANDOFF와 해당 코드의 기능을 읽고 현재 설계에 맞춰 이식했다. 원본 저장소는 변경하지 않았다.

## 기능과 배치

| 기능 | 현재 Spring 위치 | 앱/추론 |
| --- | --- | --- |
| 사용자·소셜·앱 로그인 | users, common/auth, common/config | 외부 브라우저 + 코드/verifier 교환, 세션 쿠키 |
| 방 생성·참여·주제·고정 | sessions | 홈·개설·참여·학생/선생님 화면 |
| 챗봇·퀴즈 | learning/tutor, learning/quiz, ai/client | SSE 답변·자동 채점·AI 초안·주관식 보조 |
| 자료·RAG | materials | Spring 추출·분할·검색, Python 벡터 계산 |
| 공지·게시판 | learning/notice, learning/qna | 알림·글·댓글 |
| 화면 요약·복습 | learning/screen, learning/review | 동의·캡처·요약·삭제·설정 |
| 화면공유·도움·판서 | livekit, common/realtime | LiveKit 미디어, Spring 제어·판서, 앱 오버레이 |

## 원본에서 바꾼 부분

| 원본 | 현재 |
| --- | --- |
| Boot 4.1.2 | Java 21 / Boot 4.0.9 / Wrapper 9.5.1 유지 |
| frontend + desktop 별도 | tutipal_front 하나로 통합 |
| Spring이 React 화면 제공 | 패키지 dist를 Electron 내부 loopback에서 제공 |
| Redis 세션·로그인 코드 | Servlet 메모리 세션, 만료·일회용 코드 메모리 저장. 재시작 시 재로그인 |
| LiveKit 원본 제어 접속/JWT 전달 | 세션·수업 권한을 검사하는 같은 Spring의 /livekit 중계 |
| LiveKit 데이터 채널 판서 | Spring 도움 요청 권한 검사 및 STOMP 전달 |
| 자료 원본 DB bytea, Python PDF 추출 | Spring 객체 저장·PDFBox 추출. 개발 영속 볼륨/운영 S3 |
| Python DB 의존성 | 제거. Python은 추론·벡터 계산만 담당 |
| 원본 V1~V10 | 현재 pgvector V1 보존, 원본 스키마 V2~V11 |
| 전이 의존성 | npm/Gradle/Python 잠금 및 Docker·CI 적용 |

원본 캐릭터 이미지와 앱 아이콘을 포함하며 출처를 유지한다. 원본에 없는 라이선스나 권리 허락을 임의로 선언하지 않는다.

## 검증

최종 실행 결과는 [DEVELOPMENT.md](DEVELOPMENT.md)에 기록한다. 소셜 실계정·S3·임베딩 모델·외부 WebRTC는 코드가 있다는 이유만으로 검증 완료라고 표시하지 않는다. 원본 테스트 코드는 가져오지 않았고 새로운 Playwright/JUnit/pytest 코드도 만들지 않았다.
