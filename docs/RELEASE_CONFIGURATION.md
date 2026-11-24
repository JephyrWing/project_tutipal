# 운영 Windows 설치본 구성 (PT-103)

기준 TUTIPAL-baseline-v2 / jira_drive-v3, FR-AUTH-02 / NFR-SEC-06·NFR-OPS-02.
운영 서버 주소와 외부 인수는 PT-110·PT-14·PT-103의 실제 결정/검사에 연결한다.
이 문서에 운영 주소를 임의로 확정하거나 서버 배포·외부 PC 인수 성공을 기록하지 않는다.

## 로컬 패키지와 운영 릴리스

`npm run dist:win`은 기존 로컬 패키지를 만든다. 기본 서버는 localhost이며
개발 환경변수 `TUTIPAL_SPRING_URL` 사용도 유지한다. 기존 PT-30 설치본 검사는 이 범위다.

`npm run dist:win:release`는 별도 운영 빌드다. 릴리스 담당자가 팀에서 확정한 공개 DNS HTTPS
origin을 빌드 셸의 `TUTIPAL_RELEASE_SERVER_URL`에 지정해야 한다. 사용자 PC에는 환경변수가 필요 없다.
주소 미지정·로컬/숫자 주소·placeholder·인증정보·경로/쿼리/fragment는 거절한다.
공개 주소 선택과 유효한 TLS/서버 준비를 이 구문 검사가 대신하지 않는다.
운영 주소가 확정되지 않으면 운영 설치본 생성을 진행하지 않는다.

1. 최신 develop과 작업의 확정 주소·서버 SHA/계약을 조회한다. 필요한 변경은 커밋하여 clean source를 사용한다.
2. Node 24.22.3에서 lock 기반 `npm ci` 후 해당 빌드 셸에만 확정 주소를 지정한다.
3. `npm.cmd run dist:win:release -- --check-only`로 계획을 확인한다. 이 명령은 아티팩트를 만들지 않는다.
4. Windows에서 `npm.cmd run dist:win:release`를 실행한다.
5. 산출물은 `release/production/<전체 source SHA>/`에 생성된다.
   설치 파일은 `TUTIPAL-Setup-<version>-production-<SHA 앞12자리>.exe`로 로컬 파일과 구분한다.

빌드 소스는 기존 package.json을 수정하지 않는 임시 builder config로 운영 메타데이터를 넣는다.
코드가 커밋되지 않았거나 빌드 중 변경되면 제출을 중단한다.
패키지의 `tutipal.serverUrl`과 `tutipal.release`에는 schemaVersion=1·channel=production·serverOrigin·sourceSha를 기록한다.
쿠키·키·DB/S3 자격 증명이나 서버 비밀값은 넣지 않는다.
생성 후 실제 ASAR의 metadata를 기대 주소/SHA와 대조하고 PT-30의 실제 exe fuse7값/필수파일 검사를 실행한다.
검사가 끝나야 `release-manifest.json`에 출처와 installer/exe/ASAR SHA256을 기록한다.
빌드 과정에서 외부 서버에 연결하거나 배포하지 않는다.

별도로 검사할 때는 다음 세 인자를 모두 제공한다.

```powershell
npm.cmd run verify:release -- "실제 win-unpacked 경로" "확정 HTTPS origin" "전체 source SHA"
```

실제 운영 빌드 전체는 확정 주소가 준비된 뒤 실행·검사해야 한다. `--check-only`와
로컬 NSIS 성공을 운영 NSIS/외부 OAuth·WSS 성공으로 기록하지 않는다.

## 설치 후 서버 선택 경계

운영 메타데이터가 있는 설치본은 그 주소로 고정된다. 사용자 PC에 남아 있는
개발용 `TUTIPAL_SPRING_URL`이 localhost나 다른 서버를 가리켜도 운영 대상을 바꾸지 않는다.
채널/schema/SHA/주소가 빠지거나 서로 다르면 시작을 거절하며 localhost로 대체하지 않는다.
운영 패키지의 설치 정보 파일을 읽지 못해도 시작을 거절한다.
기존 로컬 패키지·개발 실행의 주소 선택과 화면공유/분석·쿠키·Spring 권한 계약은 그대로다.

## 남은 실행·인수

팀이 확정한 HTTPS 주소·운영 서버 SHA와 PT-110의 프록시/쿠키/OAuth 준비,
환경변수 없는 깨끗한 외부 Windows PC 설치, 실제 제공자별 OAuth·딥링크·업무 WSS,
401/취소·만료 코드/서버 재시작/503·오프라인 복구를 별도로 검증한다.
인증·서버 장애를 서로 구분하는 화면 UX와 공통 권한 회수는 PT-100·PT-14 계약과 대조한다.
빌드/주소 고정/출처 검사와 아래의 로컬 인증 복구 UI는 준비 단위이며 전체 운영 인수 완료가 아니다.
실제 결과와 실패·미실행은 [PT-103](https://gad03302.atlassian.net/browse/PT-103)에 기록한다.
PM 직접 완료와 중앙 Drive 파생 문서 동기화는 별도 상태로 유지한다.

## 연결·인증 실패 복구 UI (U2)

| 받은 결과 | 화면의 처리 |
| --- | --- |
| 로그인 상태 조회의 네트워크 실패/502·504 | 연결 확인 안내와 다시 연결. 인증 만료로 표시하지 않는다. |
| 서버 500·503 | 서버 일시 오류 안내와 다시 연결. 정상 익명 응답과 구분한다. |
| 로그인 설정 조회 실패 | 로그인 수단이 없는 것으로 표시하지 않고 설정 재조회 버튼을 제공한다. |
| 공통 `request`의 현재 인증 세대 업무 요청 401 | 해당 renderer의 보호 화면을 내려 재로그인을 안내한다. 초기 익명 조회는 만료로 표시하지 않는다. |
| 서버 로그아웃 실패 | 로그아웃 완료로 표시하지 않고 기존 로그인 화면에서 실패 안내·재시도를 제공한다. 401은 이미 인증이 해제된 상태로 처리한다. |

인증 상태·로그인 설정·개발 로그인·로그아웃 요청에는 10초 기한을 둔다.
로그아웃 등 인증 요청의 CSRF 조회도 같은 기한을 사용하며 자료 업로드/AI 스트리밍의 시간 제한은 바꾸지 않는다.
로컬 분석/공유 동의나 서버 쿠키·CSRF·Origin·세션 TTL을 이 UI 작업에서 변경하지 않는다.

클라이언트 인증 상태는 세대별로 관리한다. 이전 조회/이름 변경 응답이 로그아웃 뒤 사용자를 복원하지 않는다.
이전 세대 요청의 늦은 401은 새 로그인 상태를 지우지 않고, 이전 변경 요청의 403을 새 계정으로 재전송하지 않는다.
동일 세대의 기존 CSRF 403 갱신·한 번 재시도는 유지한다.
오류 화면은 원본 서버 응답·키·쿠키 대신 공개 안내문만 표시한다.

상태/경합 Node 검사와 격리 Electron E2E 시나리오는 `tests/authSession.test.mjs` 및
`tests/e2e/auth-recovery.spec.js`에 있다. E2E는 기존 일회용 `tutipal_e2e` DB와 테스트 프로필만 사용하고
실제 사용자 프로필·팀 DB·서버를 중단하거나 재설정하지 않는다. 네트워크/503 응답 제어와
격리 테스트 쿠키 제거는 실제 외부망 장애·운영 세션 만료 시간·서버 전체 권한 회수 검증과 구분한다.
별도 SSE 요청·실제 OAuth의 취소·만료 콜백, 다른 창의 즉시 정리와 운영 WSS/LiveKit 회수는 PT-14·PT-100·PT-110 연계 인수에 남는다.
