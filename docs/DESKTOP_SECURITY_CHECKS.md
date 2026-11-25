# Electron runtime 보안 검사 (PT-30)

NFR-SEC-06 / AC-APP-001-2 / CHK-INSTALLER-SECURITY의 실행 중 경계를
`tutipal_front/tests/e2e/desktop-security.spec.js`에서 확인한다.
제품 동작이나 권한 정책은 변경하지 않는다.

검사는 실제 Main/Preload와 renderer를 사용한다. 임시 독립 프로필과 숨김 창을
사용하는 기존 E2E entry가 프로토콜 등록을 차단한다. 사용자 설치 앱·프로필과
팀 DB를 사용하지 않으며, 외부 브라우저 실행만 검사에서 가로챈다.
장치 권한 검사는 합성 미디어 장치를 사용하며 권한 자동 허용 옵션은 사용하지 않는다.

| 사례 | 관찰 기준 |
| --- | --- |
| 메인·위젯 격리 | 실제 webPreferences의 contextIsolation/sandbox/webSecurity=true, nodeIntegration=false. 두 renderer에 require/process가 노출되지 않는다. |
| 불필요한 장치 권한 | 카메라·마이크는 NotAllowedError, 위치는 PERMISSION_DENIED, 알림은 denied. 화면 공유를 요청하거나 사용자의 동의를 대신 누르지 않는다. |
| 외부 이동·새창 | 실제 navigation handler가 외부 HTTPS를 앱 안에 표시하지 않고 OS 브라우저 경로로 전달한다. 검사는 OS 호출을 가로채 실제 접속하지 않는다. file/custom scheme 새창도 거절한다. 같은 앱 origin의 내부 이동은 허용한다. |

기존 Playwright 설정에서 `npm run test:e2e -- desktop-security.spec.js`로 실행한다.
이 설정은 격리된 `tutipal_e2e` DB와 테스트 프로필만 허용한다. CI에서 전체 앱
회귀 검사와 함께 실행하며 실제 수집·실행 수, SHA와 실행 시각은 Jira에 기록한다.

로컬 합성 서버 실행은 운영/팀 DB 검증과 구분한다. Electron 개발 실행의 runtime
검사는 설치된 NSIS 패키지 전체 보안 검증이 아니다. 실제 설치본의 ASAR 변조 거절,
외부 PC·실 OAuth/WSS, 서명·업데이트·다른 OS는 별도 인수 항목이다.
산출물 fuse/ASAR/helper 확인은 기존 `npm run verify:win`의 별도 검사다.
