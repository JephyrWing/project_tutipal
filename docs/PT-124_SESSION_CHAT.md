# PT-124 수업 기본 챗봇 저장·재개 U2

FR-TUT-05. 기준 develop `438737bed1dc58d6f222f0ef0029afd980cce6ed`.
학생 SessionPage의 챗봇 진입만 SessionStoredChat으로 연결한다. ChatPage/기존 ChatPanel,
개인 U1, 위젯/PT-45, PT-102 변형·공통 API·Spring/DB·배포 설정은 보존한다.

첫 질문에서 기존 createConversation({sessionId})를 호출하고 같은 ID로 sendMessage를 쓴다.
기록 링크에서 기존 상세 화면으로 이동하면 같은 ID로 이전 이력을 사용해 계속 질문한다.
새 진입/탭 재진입은 새 대화이며 이전 대화는 기록에서 연다. 자동 최근 대화 병합은 하지 않는다.
수업 주제·학생/진행 상태·계정 소유권·정답 공개 정책은 기존 서버 계약으로 검사한다.

동일 질문 재시도는 같은 clientMessageId, 동시 제출은 flight ref로 차단한다.
중지는 즉시 입력 복원·부분 응답 폐기, 늦은 응답 무시, 생성 중지 후 재시도는 같은 생성 Promise 재사용.
계정/수업/주제 변경은 key로 이전 요청과 화면 상태를 폐기한다. 403/404/410은 전송을 막고
현재 대화 내용과 링크를 비운다. 계정·퇴장·종료의 수업 화면 교체 때 unmount로 취소한다.

실시간 sources와 정상 저장된 답변의 helpDraft는 이번 마운트 동안만 표시한다.
도움 버튼은 기존 StudentHelp의 학생 수정·확인 후 전송 흐름을 사용한다. 자동 전송은 없다.
재생/replaced 답변에 이전 추천을 덧붙이지 않는다. 기록 API에는 이 메타데이터가 없으므로
과거 출처·도움 추천 복원은 완료로 주장하지 않는다. 새 영속 저장·DTO·마이그레이션은 없다.

검사: `npm run check`, `npm run build`, `node --test tests/*.test.mjs`.
`tests/e2e/session-stored-chat.spec.js`: 실제 Spring/폐기용 PostgreSQL/모의 AI,
동일 ID 저장·기록 재개/재접속·중복, saved 유실 재생, 503 재시도·중지·계정 전환,
생성 중지, 실제 자진퇴장·강제퇴장·종료 뒤 신규/계속 질문 거절과 기존 쌍 보존.
기존 help-escalation 회귀는 수업의 새 전송 경로에만 모의 응답을 맞추고 인수 조건은 유지한다.
종료/퇴장 후 기존 대화의 소유자 읽기는 현행 ConversationService 계약이며 학습 결과 권한과 별개다.
실제 모델 교육 품질·OAuth·운영 배포·실행 중 서버의 즉시 권한 회수는 별도이다.
생성 응답 완전 유실의 빈 대화 한계와 저장 직후 중지의 불확실성은 개인 U1과 같다.
실행 결과·최초 실패·최종 SHA·실제 병합·정리는 Jira 원작업에 기록한다.
