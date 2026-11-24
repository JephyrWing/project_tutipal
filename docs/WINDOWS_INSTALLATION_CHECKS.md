# Windows 설치본 검사 (PT-30)

기준 TUTIPAL-baseline-v2 / jira_drive-v3, FR-APP-01·02 / NFR-SEC-06.
패키징 성공, 실제 실행 파일 검사, 설치·사용자 흐름, PM 인수를 구분한다.

## 패키지 보안 검사

`tutipal_front`에서 잠금 파일 기반 `npm ci` 후 `npm run dist:win`을 실행한다.
NSIS 생성 뒤 `verify:win`이 실제 `release/win-unpacked/TUTIPAL.exe`를 읽어
다음 fuse 7값과 ASAR·창 추적 helper의 패키지 포함을 검사한다. 누락/불일치하면 exit 1이다.
기존 Windows CI도 같은 `dist:win` 명령을 사용하므로 설정이 빠진 바이너리를 성공으로 제출하지 않는다.

| Fuse | 기대값 |
| --- | --- |
| RunAsNode | 꺼짐 |
| EnableCookieEncryption | 켜짐 |
| EnableNodeOptionsEnvironmentVariable | 꺼짐 |
| EnableNodeCliInspectArguments | 꺼짐 |
| EnableEmbeddedAsarIntegrityValidation | 켜짐 |
| OnlyLoadAppFromAsar | 켜짐 |
| GrantFileProtocolExtraPrivileges | 꺼짐 |

검사는 빌드 설정을 다시 읽는 대신 실제 exe의 fuse wire를 읽는다.
기존 전이 도구 `@electron/fuses` 1.8.0을 정확한 버전의 직접 개발 의존성으로 선언한다.
이 도구가 이름을 제공하지 않는 추가 fuse는 임의로 판정하거나 변경하지 않는다.
개발용 Electron 바이너리와 설치된 exe를 직접 수정하지 않으며 새 패키지에 설정을 적용한다.
기존 설치본의 쿠키 암호화를 다시 끄지 않는다.

설치 후에도 같은 읽기 전용 검사를 실행할 수 있다.

```powershell
npm.cmd run verify:win -- "C:\Users\사용자\AppData\Local\Programs\TUTIPAL"
```

이 명령은 설치·레지스트리 변경·프로필 삭제를 수행하지 않는다.
결과는 fuse 7개/패키지 포함 2개 확인이며 업무 기능 검사 수가 아니다.
ASAR 무결성의 실제 훼손 거절, renderer/IPC 권한·외부 이동, 전체 Electron 보안 인수는 별도 확인한다.

## 실제 설치·실행 확인

1. 최신 Jira 작업의 기준 SHA, 설치 파일 SHA256, 기존 exe/ASAR 해시와 설치 경로를 기록한다.
   설치된 버전 문자열이 같아도 파일 내용이 같다고 판단하지 않는다.
2. 실행 중 앱과 사용자 프로필을 확인하고 설치 마법사로 업데이트한다. 기존 데이터·설정을 보존한다.
   사용자 PC 설치와 별도 VM의 깨끗한 설치는 서로 다른 증거다.
3. 설치 경로의 exe에서 기동된 메인/위젯을 확인하고 트레이 열기·위젯 표시/숨김·종료·재시작을 관찰한다.
4. `HKCU\Software\Classes\tutipal\shell\open\command`가 설치 exe를 가리키는지 읽는다.
   개발 앱 실행은 프로토콜을 개발 electron.exe로 재등록할 수 있으므로 매번 실제 대상을 확인한다.
   직접 레지스트리를 덮어써 설치 성공으로 대신하지 않는다.
5. 무효 딥링크는 로그인 성공을 만들지 않는지 확인한다. 실제 OAuth 만료·재사용·실계정 검사를 대신하지 않는다.
6. 로그인·화면 공유 동의는 사용자가 직접 진행한다. 화면 원본·쿠키·키는 증거로 수집하지 않는다.

실제 검사·실패·미실행과 시간(KST/UTC), SHA·명령·종료코드 및 사용자 보고/직접 관찰을
[PT-30](https://gad03302.atlassian.net/browse/PT-30)에 기록한다.
서명·자동 업데이트·다른 OS, 격리 Windows 설치 및 PM 직접 인수는 각각 별도 상태로 유지한다.
파생 문서는 DOC-CENTRAL 정책에 따라 중앙 반영 대기를 인계한다.
