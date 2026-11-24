# PT-54 U2 복습 기록 조회

기준: TUTIPAL-baseline-v2 / jira_drive-v3, FR-REV-01·03 및 FR-MY-01의 본인 기록 조회.
기준 develop: `7ba174d0664fd584b5896f9507eba293c54a1a9f`.

## 사용자 동작

- 복습 화면은 최근 본 순으로 20건씩 표시하고 이전·다음 페이지로 이동한다.
- 요약, 본 횟수, 누적 시간(분·초), 최근 본 날짜를 함께 표시한다. 날짜는 PC의 현지 시간으로 표시하며 API는 UTC Instant를 반환한다.
- 횟수·시간은 기존 묶음의 현재 집계값이다. 기존 7일 근사 집계가 초기화되면 값도 초기화되며 평생 학습량을 뜻하지 않는다. 집계·제안 알고리즘은 변경하지 않는다.
- 전체 개수는 현재 로그인 사용자의 모든 복습 묶음 수다. 화면 이미지·전체 학습 세션 수나 질문 수가 아니다.
- 로딩·빈 기록·조회 실패를 구분하며 조회 실패 후 다시 불러올 수 있다. 이전 요청의 늦은 응답은 새 페이지나 다른 설정 탭을 덮지 않는다.
- 기존 개별/전체 삭제 이후 목록과 개수를 재조회한다. 마지막 페이지가 없어지면 유효한 마지막 페이지로 이동한다. 삭제 실패 시 기존 기록을 남기고 오류를 표시한다.
- 설명 듣기는 기존 개인 챗봇 연결을 유지한다. 위젯이 요청한 상세를 읽지 못하면 오류를 표시한다.

## 추가 API

`GET /api/review/history?page=0&size=20`

- 세션 principal의 본인 ID만 사용한다. 클라이언트 userId는 대상 선택에 사용하지 않는다.
- page: 0 이상의 int, size: 1~100, 기본 20. 잘못된 값은 400, 미인증은 401.
- 응답: `{items, totalCount, page, size, totalPages}`. items는 기존 Item의 안전한 필드만 반환한다(id, summary, seenCount, seenSeconds, firstSeen, lastSeen, suggestedAt). 임베딩과 소유자 ID는 반환하지 않는다.
- 정렬: `last_seen DESC, id DESC`. 같은 시각도 ID로 순서를 정한다. OFFSET 계산은 long으로 수행한다.
- 범위 밖 페이지는 요청한 page와 전체 개수를 유지하고 빈 items를 반환한다. 빈 기록은 totalCount=0, totalPages=0이다.
- 개수와 목록은 읽기 전용 REPEATABLE_READ 트랜잭션의 동일 스냅샷에서 조회한다. 페이지 사이에는 새 수집·삭제로 결과가 이동할 수 있다. 전체 페이지를 하나의 고정 스냅샷으로 묶는 API가 아니다.
- 기존 `GET /api/review/items`는 최대100건의 중요도순 배열 계약을 유지한다. 상세·삭제·observe 계약과 본인 소유권은 유지한다.
- 설정은 history의 size=1 응답 totalCount로 개수를 표시한다. 조회 실패를 0개로 표시하지 않는다.
- 기존 users/review_items와 migration을 그대로 사용한다. 제공자·모델 설정, 보관 정책, 기존 기록을 변경하지 않는다.

## 검증 범위

- ReviewHistoryServiceTest: 페이지 계산, 빈 기록, 잘못된 범위, 정수 오버플로 방지.
- ReviewHistoryWebTest: 실제 controller/service/security와 모의 저장소로 미인증·ID위조·잘못된 쿼리·타인 상세404·기존 배열 계약 확인.
- review-history.spec.js: 실제 Spring·격리 PostgreSQL·Electron에서 105건 전체 순회 및 소유권/전체 개수, 오류와 재시도, 로딩·삭제 실패·마지막 페이지 복구·빈 목록 확인. 합성자료는 검사용 일회용 DB 컨테이너에만 SQL로 입력한다. AI 호출 없이 조회 경계를 검증한다.
- 실행 수·환경·시각·최종 SHA·실패/재실행·원격 CI 결과는 PT-54와 PT-74에 기록한다. 검사 코드의 존재만으로 실행 통과를 뜻하지 않는다.

## 제외·인계

이름 수정은 별도 PR #26이다. 학습 결과 종합 조회(FR-MY-03), 오답 변형 연습(PT-53), 대화 지속 저장(PT-16), 소셜 관리·탈퇴·보관기간은 이번 단위에서 구현하지 않는다. 실계정 OAuth·실운영 DB·앱 배포·PM 완료는 별도다.

직접 Drive 산출물은 없다. 새 조회 API·복습/설정 화면·검사 범위의 파생 문서 반영은 docs-sync-pending으로 Aileron에 인계한다.
