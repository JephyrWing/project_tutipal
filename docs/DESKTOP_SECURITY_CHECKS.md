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

## Windows 패키지 ASAR 변조 거절

Windows에서 `npm run dist:win` 다음 `npm run test:win-integrity`를 실행한다.
검사는 `release/win-unpacked`를 읽어 전용 임시 폴더의 복사본만 변경한다.
헤더를 교체한 ASAR와 기존 헤더를 유지한 메인 파일 내용 변경을 각각 확인한다.
원본 업무 코드를 실행하지 않도록, 실행 전 ASAR를 독립적으로 다시 읽어
메인 진입점 전체가 표시 파일 기록 후 종료하는 안전한 코드인지 확인한다.
네트워크·창·프로토콜 등록·사용자 세션 동작은 없다.

검사 전용 대조 복사본에서만 ASAR 검사 fuse를 끄고 안전한 진입점이 실행됨을
확인한다. 원본과 보호 복사본의 fuse는 유지한다. 보호 복사본은 같은 변조를
무결성 오류로 거절하고 표시 파일을 만들지 않아야 한다. 비정상 종료만으로
통과시키지 않는다. 대조 복사본의 보호 완화는 제품이나 배포 설정에 적용하지 않는다.

대조 실행2·변조 거절2·원본 exe/ASAR SHA256 전후 일치1의 검사5개를 기록한다.
실행 기한과 숨김 프로세스, 독립 프로필을 사용하고 생성한 임시 폴더만 정리한다.
결과는 `test-results/win-integrity.json`에 저장하며 Windows CI에서 보존한다.
이는 빌드된 패키지 복사본의 무결성 검증이며 설치 마법사·업데이트·실운영 접속이나
모든 파일의 변조를 전수 검사한 결과는 아니다. 실제 결과와 원본 SHA는 Jira에 연결한다.

설계 근거: [Electron ASAR integrity](https://www.electronjs.org/docs/latest/tutorial/asar-integrity).
