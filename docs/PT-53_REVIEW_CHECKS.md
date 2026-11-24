# PT-53 U1 복습 제안·삭제 경계 검사

기준: TUTIPAL-baseline-v2 / jira_drive-v3, FR-REV-01·02·03.
base develop: `7ba174d0664fd584b5896f9507eba293c54a1a9f`.

## 검사와 발견한 결함

고정 시계·모의 AI/저장소 단위 검사와 실제 SecurityConfig/Controller/Service 웹 검사, 실제 PostgreSQL/pgvector 검사를 구분한다. 외부 모델, 실제 화면 캡처, 운영 DB는 사용하지 않는다.

보완 전 실제 실패:

1. 이전 제안 후 정확히 24시간이 되면 `isBefore` 조건 때문에 재제안하지 않았다.
2. 같은 기존 묶음의 동일 조회 결과로 8개 요청이 동시에 제안하면 8개 모두 제안을 반환했다.
3. 조회 후 삭제된 기록의 오래된 결과로도 제안을 반환했다.

보완: 서비스의 24시간 경계는 포함한다. DB가 `suggested_at <= cutoff`와 현재 횟수/시간을 한 UPDATE에서 검사하고 갱신 성공한 요청만 제안을 반환한다. 행이 삭제되거나 집계가 초기화되거나 다른 요청이 이미 제안한 경우에는 갱신하지 않는다. API의 요청/응답과 스키마는 유지한다.

이 갱신은 이미 저장된 같은 묶음의 중복 제안을 방지한다. 동시에 새 요약을 최초 수집할 때 비슷한 묶음이 여러 개 생성되는 문제나 위젯에서 제안이 사용자에게 실제 노출됐는지에 대한 전달 보장은 이번 검사 범위가 아니다. 기존대로 서버가 제안을 발행할 때 시간을 기록한다.

## 유지하는 동작과 정책 한계

- 기본 제안 조건: 횟수 3회 이상 **또는** 시간 600초 이상.
- 재제안: 같은 묶음에서 직전 제안 후 24시간 이상. 한국 시간 자정으로 초기화하는 달력 날짜 정책으로 바꾸지 않는다.
- 기존 최근 7일 집계는 개별 관찰 이력이 없는 근사 창이다. first_seen이 창보다 오래되면 횟수/시간을 초기화한다. 정확한 rolling 7일 기록이나 새 테이블을 이번에 도입하지 않는다.
- 동일 화면 시간 추가는 마지막 관찰 이후 3분 이내에만 허용하고, 클라이언트 초는 0~120 범위로 제한한다.
- 임베딩/저장소 실패는 observe의 recorded=false로 처리한다. 실제 임베딩 품질과 모델 준비는 별도다.
- 본인 ID는 세션 principal에서만 얻는다. 목록·상세·삭제에 다른 userId를 보내도 대상을 바꾸지 않는다.
- 상세의 타인/없는 ID는 404. 개별/전체 DELETE는 기존대로 멱등 204이며, 타인/없는 ID는 실제 삭제 0행이다. 미인증과 CSRF 거절은 별도로 검사한다.
- 삭제는 본인 기록에만 적용하며 다른 사용자 행은 보존한다. 별도 복습 동의 UI/API를 다시 추가하지 않는다.

## 검사 실행

```powershell
# 외부 DB 없는 기본 검사(경계·인증·CSRF·principal 포함)
.\gradlew.bat test bootJar --no-daemon

# 실제 DB 검사, 이 검사만의 일회용 PostgreSQL/pgvector가 필요
.\gradlew.bat reviewDbTest --no-daemon
```

reviewDbTest 환경변수:

- REVIEW_TEST_DB_URL: `jdbc:postgresql://127.0.0.1:<port>/tutipal_review_e2e`
- REVIEW_TEST_DB_PASSWORD: 일회용 비밀값. 공개 로그/문서에 값을 기록하지 않는다.
- REVIEW_TEST_DB_ALLOW_RESET: `disposable`
- DB 사용자와 DB 이름은 모두 `tutipal_review_e2e`다. 로컬 .env를 읽지 않는다.

URL·명시적 초기화 허용·비밀번호가 없으면 검사는 실패한다. skip으로 통과시키지 않는다. 이 전용 DB에 Flyway V1~V14를 적용하고 합성 사용자 101/202의 복습 기록만 사용한다. 매 사례 전에 전용 DB의 review_items를 비우므로 개인/운영 DB를 지정하지 않는다.

기본 test는 ReviewStorePostgresTest를 제외하며 DB 없는 검사만 실행한다. reviewDbTest는 같은 컴파일 결과에서 해당 DB 클래스만 실행한다. 보고서는 각각 build/test-results/test와 build/test-results/reviewDbTest이며, 기본 검사와 DB 검사를 중복 집계하지 않는다. 새 라이브러리나 lockfile 변경은 없다.

CI의 Review PostgreSQL checks 워크플로는 기존 infra/postgres/Dockerfile로 일회용 DB를 만들고, 마스킹한 난수 비밀번호와 loopback 포트를 사용한다. 전용 task의 실제 수집·실행·실패·skip을 집계하고 보고서를 게시하며, 해당 실행의 컨테이너만 종료한다. 실행 결과와 전체 SHA·환경·UTC/KST 시각은 Jira PT-53·PT-73에서 추적한다.

## 범위와 인계

PT-54 PR #27의 history 목록·페이지·설정 변경과 별도다. ReviewService/ReviewStore가 공유 파일이므로 해당 변경을 보존하고 실제 병합 전 충돌을 대조한다. 위젯 육안 확인, 실계정 OAuth, 실제 모델 품질, 오답 조회·Agent/PT-85·변형 문제 종단 연결과 정확한 보관/삭제 정책은 이번 단위에서 완료 처리하지 않는다.

직접 Drive 산출물은 없다. 제안 경계/원자적 발행·추가 검사와 CI에 대한 파생 설계·검사 현황의 중앙 반영 필요는 Jira에서 Aileron에 인계한다. PM 완료와 실제 develop 병합/운영 적용은 별도다.
