# PT-119 화면 분석 세션 경계 검사

기준 요구: PT-119 / WBS-TUT-0148 / FR-SHR-08. 구현 기준 develop:
`8a021a596407f89c8dbf47cb7c4cfcd2a7c88979`.
최종 head·통합 SHA와 실행 시각/종료 코드는 Jira PT-119에 기록한다.

## 선택한 방식과 이유

- 로그아웃·계정 삭제·개발 로그인 요청 전, Spring 세션 쿠키 변경, 현재 인증 세대의 HTTP 401에서 Main이 캡처와 미리보기 토큰을 회수한다. renderer 화면만 숨기면 OS 캡처나 늦은 응답이 남을 수 있기 때문이다.
- 인증 경계의 세대를 구분해 이전 세션의 늦은 401이 새 세션을 회수하지 않도록 한다. 쿠키 값은 renderer에 전달하거나 기록하지 않는다.
- Main의 선택 상태와 위젯의 요청 revision을 함께 무효화한다. 재로그인 후에는 자동 재개하지 않고 새 명시적 미리보기와 시작을 요구한다. 재시작 시 in-memory 실행 상태가 사라지는 기존 동작을 유지한다.
- 같은 대상의 새 화면 요약은 분석 결과만 교체하고 질문 초안을 유지한다. 실제 계정·대상·수업 맥락 변경/중지는 질문과 결과를 지운다. 다른 맥락으로 이전 질문이 전송되는 것을 방지한다.
- 수업 경로는 Main이 신뢰된 현재 URL에서 `/sessions/<양의 정수>`만 기억한다. renderer가 임의 복귀 URL을 지정하지 않으며 분석 시작 후 해당 수업으로 복귀한다.

## 검사 명령과 구분

`tutipal_front`에서:

```text
node --test tests/*.test.mjs
npm run check
npm run build
node tests/analysis-session-ui.mjs
node tests/analysis-consent-ui.mjs
node tests/run-native-analysis.mjs
```

- 단위 검사: 인증 경계 세대, 중지 시 미리보기 권한·진행 캡처 폐기와 기존 회귀.
- 신규 세션 UI 6개: 동일 창 갱신 중 모달/초안 유지, 실제 bridge 로그아웃과 늦은 응답 폐기, 재로그인 후 새 미리보기, 실제 bridge 401, Electron 쿠키 교체, 원래 수업 복귀. 실제 Main/Preload/bridge/controller를 실행하고 OS 창/픽셀과 HTTP 제공자만 모의한다.
- 기존 consent UI 5개: 실제 OS 선택 창의 미리보기/시작/토큰 무효화/일시정지, AI API 모의.
- 기존 native capture 10개: 목록의 이미지 미수집, 시작 전 캡처 금지, 선택 창 한정, 중지·종료·미리보기 취소.
- 로컬 Node 24.22.1 / Electron 45.3.3. CI는 저장소 고정 Node 24.22.3을 사용한다. CI에 신규 세션 UI 단계를 추가하며 배포 workflow는 변경하지 않는다.

## 실패 이력과 검증 한계

2026-11-27 1차 기존 consent UI는 명시적 미리보기에서 OS 캡처 10초 취소로 실패했다. 분리 재실행에서 5/5 통과했다. 최초 실패는 보존하며 제품/검사 제한을 끄거나 재시도 로직으로 숨기지 않았다. 최종 SHA 실행 결과는 Jira에 별도로 기록한다.

실 OAuth 공급자 로그인, 실제 서비스 장애 복구, 다른 OS, 실제 OCR·모델 정확도·교육 품질은 이번 검사 범위가 아니다. 기존 사용자 프로필/서버/DB/모델/env/배포 설정을 변경하지 않고 폐기용 프로필·loopback 모의 서버를 사용한다. 상세 설명 생성 계약과 PT-102/PT-85 API는 변경하지 않는다.
