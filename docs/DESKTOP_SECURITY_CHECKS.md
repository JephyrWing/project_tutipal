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
| 집중도 수집 경계 (PT-90) | 동의 후 학생이 수업 화면에서 시작한 동안만 수집한다. 시작 전에는 helper 실행·유휴 조회·전송이 없다. helper(`focus-probe.exe`)는 전경 창 프로세스의 실행 파일 basename만 읽고 창 제목 API(GetWindowText·WM_GETTEXT·UI Automation)를 쓰지 않는다. 출력은 `^[a-z0-9._-]{1,64}\.exe$`만 받고 그 밖은 null, 16KB 초과·4초 동안 완성된 줄이 없으면 helper를 끝낸다. helper는 stdout 쓰기(WriteFile)가 실패하면 스스로 끝난다(앱이 죽어도 남지 않음). 값은 메인 프로세스가 로그인 쿠키·CSRF·Origin으로 Spring에 직접 보낸다. 한 번 전송은 15초 안에 끝나지 않으면 끊고 다음 분에 다시 보내며, 중지하면 진행 중 요청도 끊는다. 다른 창이 같은 수업으로 시작해도 처음 시작한 화면이 계속 owner다. preload는 `focusStart(id)`·`focusStop()`·`focusState()`·`onFocusState(cb)`만 공개하고 화면에서 수집 값을 보내는 채널은 없다. 모든 핸들러는 `fromOurPage`를 확인하고 시작은 양의 안전 정수 id만 받는다. Windows가 아니거나 helper가 없으면 unsupported. 중지·화면 이탈·창 파괴·renderer 종료·다시 읽기·로그인 쿠키 변경·절전·화면 잠금·앱 종료·3시간·서버 401/403/404/410이면 helper를 끝내고 모은 값을 버린다. 단위 검사 `tests/focus*.test.mjs`(이 E2E에는 아직 없음) |

기존 Playwright 설정에서 `npm run test:e2e -- desktop-security.spec.js`로 실행한다.
이 설정은 격리된 `tutipal_e2e` DB와 테스트 프로필만 허용한다. CI에서 전체 앱
회귀 검사와 함께 실행하며 실제 수집·실행 수, SHA와 실행 시각은 Jira에 기록한다.

로컬 합성 서버 실행은 운영/팀 DB 검증과 구분한다. Electron 개발 실행의 runtime
검사는 설치된 NSIS 패키지 전체 보안 검증이 아니다. 실제 설치본의 ASAR 변조 거절,
외부 PC·실 OAuth/WSS, 서명·업데이트·다른 OS는 별도 인수 항목이다.
산출물 fuse/ASAR/helper 확인은 기존 `npm run verify:win`의 별도 검사다.

## 집중도 Main·Preload 통합 검사 (PT-30 / PT-90)

`npm run test:desktop-lifecycle -- focus.spec.js`로 집중도 셸만 실행한다.
기존 Windows desktop-lifecycle CI는 새 spec도 자동 수집하고 같은 JUnit 보고서에
실제 실행·실패·건너뜀을 기록한다. 위 표의 "이 E2E에는 아직 없음"은 기존
`tests/e2e/desktop-security.spec.js`만 뜻하며, 이 절의 별도 검사가 이를 보완한다.

`tests/desktop/focus-entry.cjs`는 제품 Main·Preload·수집기·HTTP 전송기를 그대로
실행한다. 개인 PC 정보가 수집되지 않도록 시험 프로세스에서만 helper/유휴값과
수집기의 시계·분 타이머를 제어한다. 실제 IPC 핸들러와 전송기를 대체하지 않는다.
임시 독립 프로필, 동적 loopback HTTP 제공자, 합성 HttpOnly 쿠키/CSRF를 사용하며
설치 앱·OS 프로토콜 등록·실 DB·운영 서버에는 접근하지 않는다.

| 사례 | 확인 범위 |
| --- | --- |
| 시작 전·잘못된 id | 실제 sandbox renderer의 Node 미노출, 집중도 API 4개만 공개, 잘못된 id에 helper/유휴 조회/타이머/전송 0 |
| 비신뢰 페이지 | 같은 제품 preload를 가진 data: 페이지의 시작/중지 요청이 기존 수집을 변경하지 못함. 상태 조회/중지 반환은 null. 시작 거절 시 현재 상태를 반환하는 기존 계약은 유지 |
| 정상 전송·중지·재시작 | 실제 Electron session fetch가 요약 행만 CSRF/Origin/합성 쿠키와 전송. renderer document.cookie에는 세션 쿠키 미노출. 중지 이벤트와 타이머/helper 종료, 새 수업에 이전 버퍼 미전송 |
| 대기 응답 | 요청 중지 시 AbortSignal 발생, 늦은 합성 응답 뒤에도 off 유지 |
| 서버 거절 4종 | 실제 HTTP 401/403/404/410에서 중지. 403은 실제 전송기의 CSRF 갱신 후 1회 재시도 포함 |
| 쿠키 제거 | 실제 Electron cookies.remove 이벤트로 수집 중지, 자동 재시작 없음 |
| owner 이동·파괴·크래시 | 실제 widget 탐색/destroy/forcefullyCrashRenderer 각각 중지. 다른 창의 같은 수업 시작이 owner를 바꾸지 않음 |

helper의 실제 Windows 프로세스 실행·정확한 분 간격·장치 절전/화면 잠금 자체,
Spring의 동의/수업 권한 판정·실모델, 설치본/Windows11 인수는 이 검사의 범위 밖이다.
쿠키 제거는 로그아웃 이후의 Main 경계 검사이며 실제 Spring 로그아웃 성공 검사가 아니다.
UI의 동의 문구·버튼 흐름은 별도 화면 검사로 확인한다. 같은 검사를 단위 검사나
실제 서비스 검사 수에 중복 합산하지 않는다.

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
