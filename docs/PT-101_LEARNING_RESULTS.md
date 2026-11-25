# PT-101 U1 — 진행 중인 참여 수업의 내 제출 기록

요구 FR-MY-03 / FR-QZ-06, TUTIPAL-baseline-v2, jira_drive-v3.
착수: https://gad03302.atlassian.net/browse/PT-101?focusedCommentId=10452
사용자 2026-11-25 지시의 최소 구현이며 전체 과거 기록 정책 승인이 아니다.

## 동작과 공개 범위

홈의 **내 제출 기록** → `/stats`. 공통 AppShell은 PT-103 활성 예약으로 수정하지 않았다.
현재 학생으로 참여하는 진행 중 수업에서 본인이 제출한 문항만 조회한다.
수업/판정(전체·채점 대기·정답·오답) 필터, 최근 제출 순 20개 페이지, 로딩/빈 상태/실패 재시도를 제공한다.
판정의 ‘정답’은 본인 답안의 정오 상태이며 객관식 정답 번호나 모범답안 공개가 아니다.
문항·보기·내 답안은 React 텍스트로 렌더한다.

새로고침, 재진입, 창 focus 시 서버를 다시 읽는다. 권한·판정 변경의 실시간 push나 이미 받은 내용을
화면에서 즉시 회수하는 기능은 이번 범위가 아니다. 요청 시작 시 이전 목록을 비우고 늦은 이전 응답은 무시한다.
필터/권한 변경으로 마지막 페이지가 사라지면 유효한 마지막 페이지 또는 첫 페이지로 복구한다.

## API 계약 — PT101-U1-v1

`GET /api/learning-results?page=0&size=20&sessionId=<선택>&verdict=ALL`

- 로그인 principal만 사용자 식별자로 사용한다. 요청 userId로 바꿀 수 없다.
- page: 0~2147483647, size: 1~100, sessionId: 양의 long 또는 생략.
- verdict: ALL/PENDING/CORRECT/INCORRECT. 생략/빈 값은 기본 ALL. 잘못된 값/타입은 400.
- 응답: items, sessions(조회 가능한 제출 기록이 있는 수업 옵션), totalCount, page, size, totalPages.
- item 허용 필드: answerId, quizId, sessionId, sessionTitle, topic, type, question, choices,
  choiceIndex(내 선택), text(내 서술 답안), submittedAt, verdict.
- answerIndex·modelAnswer·feedback·다른 학생 식별자/답안은 응답 필드 자체가 없다.
- graded_by 또는 correct가 NULL이면 PENDING. 확정된 현재 correct 값으로 CORRECT/INCORRECT를 판정한다.
- 비로그인401. 조회 대상이 없거나 권한 없는 sessionId 필터는 같은 빈 결과200.

새 Spring controller → service → JDBC repository를 사용한다. 모든 SQL은
`a.user_id=principal AND p.user_id=a.user_id AND p.role='STUDENT' AND s.ended_at IS NULL`로 제한한다.
답안→문항→수업의 실제 관계로 조회하고, 동일 조건을 목록/개수/수업 옵션에 적용한다.
필터 값은 바인딩하며 정렬은 a.created_at DESC, a.id DESC로 고정한다.
REPEATABLE_READ 읽기 트랜잭션으로 한 응답의 목록·개수·옵션을 동일 스냅샷에서 읽는다.
새 요청은 변경된 참여/종료/판정을 읽는다. 기존 수업 API의 권한을 완화하지 않는다.

## 저장·의존 범위

기존 quiz_answers/quizzes/study_sessions/session_participants만 읽는다. 제출 시각은 답안 created_at.
DB migration·답안 쓰기·채점·모델·env 변경이 없다. 신규 의존성도 없다.
기존 출제/학생 단일문항 조회·피드백 반환 동작은 이번 수정 대상이 아니다.
AI 원판정·교사 최종판정 별도 보존은 PT-50, 교사용 종합 조회는 PT-105, 오답 변형은 PT-102다.

## 검사와 미확정

- LearningResultsWebTest: 실제 Spring MVC/Security/service + mock store. 인증·본인ID 위조 방지·허용필드·필터/페이지 경계·long offset 4개.
- learning-results.spec.js: 실제 Spring/PostgreSQL/Electron, 합성 답안. 본인/타인·종료·미참여(퇴장)·교사역할·미제출 제외,
  수업 옵션/필터/페이지·민감 필드 부재·텍스트 렌더·판정 갱신·실제 나가기 뒤 재조회·종료 후 재조회·빈 상태·실패 재시도·늦은 응답 무시 3개 시나리오.
- 실행 명령/시각/최종 SHA/실패와 재실행/CI는 PT-101 원댓글을 기준으로 한다. 빌드는 기능 검사 수에 합산하지 않는다.

종료·자진퇴장·강제퇴장 후 과거 기록, 정답/모범답안/해설/피드백 공개와 보관·삭제는 미확정으로 제외한다.
퇴장 뒤 다시 STUDENT로 참여한 경우에는 현재 참여 상태를 기준으로 기존 답안을 다시 조회한다.
탈퇴 이력 자체의 영구 차단 정책은 추가하지 않는다. 이전 PT-54 제안10262는 승인으로 승계하지 않는다.
필요한 API/스토리보드/설계·검사 현황의 중앙 파생 반영은 Aileron에 인계하며 제품 완료/PM 승인은 별도다.
