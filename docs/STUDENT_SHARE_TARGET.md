# 학생 공유 대상과 판서 좌표 (PT-92)

기준 TUTIPAL-baseline-v2 / jira_drive-v3, FR-SHR-04·05·06.
기존 주 모니터 고정 도움 공유를 사용자 선택 대상으로 제한하는 후속 구현이다.
판서의 정규화 좌표와 Spring POST/STOMP 권한 계약은 변경하지 않는다.

## 선택·동의·캡처

학생은 수락된 도움에서 창/화면 목록을 열고 로컬 썸네일을 확인한다.
대상을 선택해야 공유 동의를 체크할 수 있으며 명시 시작 전에는 LiveKit에 게시하지 않는다.
선택 변경·목록 갱신·중지·재접속은 선택/동의를 초기화한다.
공유 중에는 실제 대상 이름과 로컬 영상, 중지 버튼을 표시한다.
썸네일·영상은 메모리에서만 사용하며 저장·로그·서버 전송 산출물로 만들지 않는다.

Main은 공유 시작 직전 desktopCapturer를 다시 조회하여 동일 sourceId만 승인한다.
사라진 창을 이름이 같은 다른 창이나 전체 화면으로 대체하지 않는다.
비동기 Main 준비가 완료되기 전 캡처하지 않으며 중지·언마운트된 요청은 게시하지 않는다.
승인 대상은 요청한 WebContents에 묶이고 다른 위젯/오버레이가 소비할 수 없다.
창 종료·최소화·모니터 연결 해제 시 Main이 해당 학생 renderer에 중지를 알린다.

## 좌표 경계

Windows x64는 읽기 전용 C# helper가 선택한 HWND의 프로세스·존재·가시성과
실제 window/frame/client 경계만 200ms 간격으로 읽는다. 명령 실행·입력·이미지 획득은 하지 않는다.
실행 경로와 인자는 Main의 고정 파일/검증된 숫자 handle이며 shell을 사용하지 않는다.
프로세스 교체·최소화·창 종료·추적 오류는 공유를 종료한다.

renderer는 화면상의 사각형을 공급하지 않는다. 실제 캡처 영상의 크기만 승인된 sourceId에 전달한다.
Main이 네이티브 경계 중 영상 종횡비에 맞는 경계를 선택하고 물리 픽셀을 Electron DIP로 변환한다.
크기가 없거나 맞는 경계가 없으면 오버레이를 숨긴다.
선택 창이 전경이 아닐 때도 오버레이를 숨겨 다른 프로그램 위에 판서가 표시되지 않게 한다.
창 이동·크기 변경에 맞춰 배치하며 학생의 수업 앱을 앞에 열면 판서는 숨겨진다.
모니터 공유는 선택한 displayId의 bounds만 사용한다.
화면공유에서 제외하는 기존 contentProtection과 클릭 통과·sandbox 설정을 유지한다.

Windows 기본 .NET Framework C# compiler로 helper를 빌드한다.
생성 exe는 Git에 넣지 않고 `electron/native/bin`의 고정 경로를 ASAR unpack으로 패키징한다.
helper 미존재/창 추적 미지원 OS에서는 창 공유를 막고 위치가 확인되는 모니터만 선택 가능하다.
Windows 외 OS의 실제 패키징/권한/다중 모니터·혼합 DPI 인수는 별도 확인 대상이다.

## 변경된 Electron 경계

| 경로 | 역할 |
| --- | --- |
| `help-share-sources` | 우리 앱 출처에서만 일시적인 목록·썸네일 조회 |
| `help-share-prepare` | 우리 앱 출처에서만 같은 대상 재검증·읽기 전용 추적 준비 |
| `help-share-size` | 동일 sender/sourceId의 유효한 영상 크기만 적용 |
| `help-share-ended` | Main이 창/모니터 종료·숨김을 renderer에 알림 |

공통 화면 분석 IPC·저장 설정·DB·웹 API·LiveKit DataChannel 권한은 바꾸지 않는다.
선택한 창의 텍스트/이미지에 적힌 지시를 실행하지 않는다.

## 검사

`tutipal_front`에서 다음을 실행한다. Node 24.22.3/기존 lock을 사용한다.

```powershell
node --test tests/*.test.mjs
npm.cmd run test:window-target
npm.cmd run test:share-ui
npm.cmd run test:native-capture
npm.cmd run check
npm.cmd run dist:win
```

Node 상태/경합 검사, Windows 실제 합성 창 geometry 검사, 합성 API/LiveKit renderer 검사와
실제 사용자의 공유·판서 인수는 서로 다르다. 빌드/패키징을 기능 검사 수에 더하지 않는다.
`test:native-capture`는 대화형 Windows 데스크톱에서 별도 합성 창만 캡처한다.
대상 영상·오버레이 재캡처 제외·sender 격리·창 크기 변경·최소화 종료를 확인한다.
화면/사용자 창을 캡처하거나 이미지를 저장하지 않으며 무인 CI 실행과 구분한다.
실제 선택 창만 전송되는지, 판서 위치/이동/resize/배율, 오버레이 재캡처 제외,
닫힘/최소화/재접속 후 재동의는 현재 앱과 실제 사용자 확인으로 별도 기록한다.
PM 직접 확인과 develop 병합, Drive 중앙 파생 문서 반영은 Jira PT-92에서 추적한다.

설계 근거: [Electron desktopCapturer](https://www.electronjs.org/docs/latest/api/desktop-capturer),
[Electron screen 좌표](https://www.electronjs.org/docs/latest/api/screen),
[Microsoft GetWindowRect/DPI 경계](https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-getwindowrect).
