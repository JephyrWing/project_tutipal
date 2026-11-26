# TUTIPAL 스타일 가이드 (공통 스타일 · CSS)

- WBS 57행 "디자인 정의 (공통 스타일, CSS)" 산출물
- 원본: `tutipal_front/src/styles/global.css` (토큰·공통 조각), `tutipal_front/src/styles/app.css` (화면 공통)
- 기준: develop `b50079d` 이후 · 2026-11-26

화면 시안은 스토리보드 v3(Drive `TUTIPAL_스토리보드_v3.pptx`)이고, 이 문서는 그 시안을 코드로 옮길 때 지킬 규칙이다.

## 1. 원칙

1. **색은 토큰으로만 쓴다.** 화면 CSS(`*.css`)에 hex 색을 새로 쓰지 않는다. 필요한 색이 없으면 `global.css` `:root`에 의미 이름으로 추가하고 이 문서 표에 적는다.
2. 토큰 이름은 **용도**로 짓는다 (`--danger-text` ○, `--red-700` ✕). 같은 용도에 비슷한 색을 둘 만들지 않는다.
3. 예외는 §5의 브랜드 색뿐이다.
4. 간격·글자 크기는 §3·§4 스케일 값을 쓴다. 새 코드부터 적용하고, 기존 px 값은 그 화면을 고칠 때 함께 바꾼다.

확인 명령 (예외 4줄만 나와야 한다):

```sh
grep -rnE "#[0-9a-fA-F]{3,8}\b" tutipal_front/src --include=*.css | grep -v styles/global.css
```

## 2. 색상 토큰

### 기본

| 토큰 | 값 | 용도 |
| --- | --- | --- |
| `--bg` | `#eef3fa` | 앱 배경 |
| `--surface` | `#ffffff` | 카드·입력·패널 바탕 |
| `--surface-2` | `#f5f8fc` | 버튼 hover, 보조 바탕 |
| `--line` | `#e2e8f0` | 테두리·구분선 |
| `--text` | `#1f2633` | 본문 글자 |
| `--text-2` | `#6b7585` | 보조 글자(설명·캡션) |
| `--text-3` | `#a3acba` | 비활성·자리표시 글자 |
| `--fill` | `#e9edf3` | 아바타·미리보기 빈 바탕 |
| `--track` | `#e3e8ef` | 진행 막대 바탕 |
| `--media-bg` | `#0f172a` | 화면공유·미리보기 프레임 |

### 강조

| 토큰 | 값 | 용도 |
| --- | --- | --- |
| `--primary` | `#1a73f0` | 주요 버튼·선택 상태 |
| `--primary-hover` | `#1463d6` | 주요 버튼 hover |
| `--primary-weak` | `#e4eefe` | 배지·선택 행 바탕 |
| `--primary-line` | `#b9d3fb` | 선택 테두리 |
| `--on-primary` | `#ffffff` | 주요·위험 색 위 글자 |

### 상태

| 상태 | 바탕 | 글자 | 테두리·기타 | 쓰는 곳 |
| --- | --- | --- | --- | --- |
| 성공 | `--success-weak` `#e8f8ee` | `--success-text` `#15803d` | `--success-line` `#86d9a5`, `--success-bg` `#effaf3`, `--green` `#22c55e` | 진행 중·정답·정상 배지 |
| 위험 | `--danger-weak` `#fff5f5` | `--danger-text` `#b42318` | `--danger-line` `#f5b5b5`, `--red` `#ef4444`, `--red-hover` `#dc2626` | 오류 말풍선, 실패 배지, 삭제 버튼, LIVE 표시 |
| 경고 | `--warn-weak` `#fef3c7` | `--warn-text` `#92400e` | `--warn` `#facc15` | 데이터 부족 안내, 주의 배지 |
| 고정 | — | `--pin` `#f5b301` | — | 고정(핀) 버튼 켜짐 |

글자/바탕 조합의 명도 대비 (WCAG AA 본문 기준 4.5:1): `--success-text`/`--success-weak` 4.56, `--danger-text`/`--danger-weak` 6.15, `--warn-text`/`--warn-weak` 6.37, `--text-2`/`--surface` 4.66. `--text-2`를 `--bg` 위에 쓰면 4.18로 기준에 못 미치므로 본문 글자에는 쓰지 않는다 (큰 글자·보조 표시만).

## 3. 간격

4px 단위. `gap`, `padding`, `margin`에 쓴다.

| 토큰 | 값 | 예 |
| --- | --- | --- |
| `--space-1` | 4px | 아이콘과 글자 사이 |
| `--space-2` | 8px | 버튼 안 간격, 배지 사이 |
| `--space-3` | 12px | 목록 행 사이 |
| `--space-4` | 16px | 카드 안쪽 여백 |
| `--space-5` | 24px | 카드 사이, 섹션 안 |
| `--space-6` | 32px | 페이지 섹션 사이 |

## 4. 글자

글꼴은 `--font` (Pretendard → 맑은 고딕 → 시스템). 본문 15px, 줄 간격 1.55.

| 토큰 | 값 | 용도 |
| --- | --- | --- |
| `--text-xs` | 12px | 캡션, 미리보기 설명 |
| `--text-sm` | 13px | 작은 버튼, 표 보조 |
| `--text-md` | 15px | 본문 |
| `--text-lg` | 17px | 카드 제목 |
| `--text-xl` | 20px | 화면 소제목 |
| `--text-2xl` | 24px | 화면 제목 |

굵기는 본문 400, 버튼·제목 700만 쓴다.

## 5. 모양

| 토큰 | 값 | 용도 |
| --- | --- | --- |
| `--radius-sm` | 10px | 버튼·입력 |
| `--radius` | 16px | 카드 |
| `--radius-lg` | 22px | 큰 패널·모달 |
| `--shadow` | 2단 약한 그림자 | 카드 |
| `--shadow-float` | 떠 있는 그림자 | 위젯·드롭다운 |

## 6. 공통 조각 (클래스)

| 클래스 | 정의 위치 | 설명 |
| --- | --- | --- |
| `.card` | global.css | 바탕·테두리·모서리·그림자 |
| `.btn`, `.btn-primary`, `.btn-sm`, `.btn-danger` | global.css / app.css | 높이 44px(작은 버튼 34px), 비활성 opacity 0.5 |
| `.badge`, `.badge-primary/-live/-ok/-bad/-warn` | global.css / app.css | 상태 배지 (§2 상태 색) |
| `.seg` | app.css | 세그먼트 탭 (`aria-selected`) |
| `.switch` | app.css | 켜기/끄기 |
| `.bar` | app.css | 진행 막대 |
| `.screen-frame` | app.css | 화면공유·미리보기 프레임 |

상태는 클래스 대신 ARIA 속성(`aria-selected`, `aria-pressed`)으로 표시하고 CSS가 그 속성을 본다.

## 7. 예외: 브랜드 색

소셜 로그인 버튼은 각 회사 브랜드 가이드의 지정값을 그대로 쓴다. 토큰으로 바꾸지 않는다 (`app.css` `.social.*`).

| 버튼 | 바탕 | 글자 | 비고 |
| --- | --- | --- | --- |
| 카카오 | `--yellow` `#fee500` | `#191919` | 카카오 로그인 디자인 가이드 |
| 네이버 | `--naver` `#03c75a` | `#fff` | 네이버 로그인 가이드 |
| 구글 | `#fff` | `#1f1f1f`, 테두리 `#cfd6e0`, 로고 `#4285f4` | Google Sign-In 가이드 |

## 8. 변경 관리

- 토큰을 추가·변경하면 같은 PR에서 이 문서의 표를 고친다.
- 2026-11-26 정리: 화면 CSS에 흩어져 있던 hex 색 46곳 중 브랜드 예외 4줄을 뺀 나머지를 토큰으로 바꿨다. 비슷한 색은 하나로 합쳤다 (위험 바탕 `#fdecec`→`--danger-weak`, 경고 `#fff8e1`·`#7a5b00`·`#9a6700`→`--warn-weak`·`--warn-text`, 미리보기 `#edf2f8`→`--fill`, 캡션 `#52647a`→`--text-2`).
