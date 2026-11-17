# Spring Boot 서버

Mindot처럼 `com.my.tutipal_back` 아래에 기능별 패키지를 두고, 기능 안에서 `controller`, `service`, `repository`, `entity`, `dto`로 나눈다. 필요한 외부 호출은 해당 기능의 `client`에 둔다. `common`에는 공통 설정과 예외 처리만 둔다.

PL 저장소 `jeonseolmin/tutipal`의 main `d60f705748da927375d77aedc11165ca0ea1f49e` 구현을 이 구조로 옮겼다. `users`는 소셜 사용자, `common/auth`는 쿠키 세션·앱 로그인 교환, `sessions`는 방·참가자 권한, `learning` 하위는 챗봇·퀴즈·공지·게시판·화면 요약·복습, `materials`는 원문 저장과 RAG, `livekit`은 공유·도움 요청과 내부 제어 중계다. 별도 Gateway나 Redis는 사용하지 않는다.

Java 21과 Wrapper를 사용한다.

```powershell
.\gradlew.bat bootJar --no-daemon
# DB 등 .env.example의 값을 IDE/현재 프로세스 환경변수에 먼저 등록
.\gradlew.bat bootRun --args='--spring.profiles.active=local'
```

실행형 JAR: `build/libs/server.jar`. 최초 실행은 Gradle과 의존성 다운로드를 위해 인터넷이 필요하다. Spring은 `.env` 파일을 자동으로 읽지 않으므로 전체 서버 실행에는 루트 안내의 Compose 방식을 권장한다.

전이 의존성은 `gradle.lockfile`로 고정하며 Docker 빌드에도 같은 파일을 복사한다. 의존성을 의도적으로 변경할 때 `./gradlew dependencies bootJar --write-locks --no-daemon`으로 갱신한다. 이 명령은 JUnit 테스트를 실행하지 않는다.

Flyway가 pgvector 확장을 활성화하고 JPA는 스키마를 검증한다. 업무 스키마를 추가할 때 적용된 migration을 수정하지 말고 새 `V2__...sql`부터 추가한다. 현재 모델과 벡터 차원은 미정이다.

쿠키는 `TUTIPAL_SESSION`이며 인증 상태와 60초 앱 로그인 교환 코드는 Spring 메모리에만 둔다. 서버 재시작 후 다시 로그인해야 한다. `DEV_LOGIN`은 기본 false이고, 선택한 소셜 공급자의 ID/secret을 쌍으로 설정할 때만 해당 로그인이 활성화된다. `/api/auth/config`는 화면에 활성 로그인 방식을, `/api/auth/csrf`는 CSRF 헤더/토큰을 알려 준다. 운영에 개발 로그인을 켜지 않는다.

LiveKit API 응답은 특정 로그인 세션에 묶인 임의 grant 경로를 반환한다. `/livekit/{grant}/rtc` 제어 중계와 `/rtc/validate`는 연결·재연결마다 로그인과 방 권한을 확인하고 내부 JWT를 주입한다. 모든 JWT의 `canPublishData`는 false다. 판서는 선생님만 `POST /api/help/{id}/draw`로 보내며 학생은 `/user/queue/draw`로 받는다. STOMP와 LiveKit은 로그아웃·세션 만료 시 최대5초 안에 닫고, LiveKit은 참가자 제거도 재시도한다. Spring 시작 시 남아 있는 전용 LiveKit의 `session-*` 참가자를 정리하기 전까지 새 grant를 거절한다.

자료 원문은 `MATERIAL_STORAGE=local` 개발 저장소 또는 `s3`에 보관하고 DB에는 객체 키만 저장한다. PDF/text 추출·청킹·검색은 Spring에서 수행하며 Python은 임베딩 계산만 한다. `embedding_model`에 실제 공급자·모델·차원을 기록하고 같은 모델의 벡터만 비교한다. 모델을 바꾸면 이전 자료는 다시 올려 색인해야 하며 다른 모델의 벡터를 섞지 않는다. 저장소 모드를 바꿀 때 원본 파일 이동도 별도로 수행해야 한다.

V1 마이그레이션은 기존 pgvector 활성화 내용을 유지한다. 새 업무 스키마는 V2~V11, 임베딩 출처는 V12다. Playwright/JUnit/pytest 테스트 코드는 이번 이식에 추가하지 않았다. 실제 기동 확인 결과는 루트 개발 안내를 참고한다.
