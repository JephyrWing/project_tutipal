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
현재 단위는 빌드/주소 고정/출처 검사 준비이며 이 후속 기능 전체 완료가 아니다.
실제 결과와 실패·미실행은 [PT-103](https://gad03302.atlassian.net/browse/PT-103)에 기록한다.
PM 직접 완료와 중앙 Drive 파생 문서 동기화는 별도 상태로 유지한다.
