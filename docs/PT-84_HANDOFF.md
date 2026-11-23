# PT-84 기존 문항 변형 · PT-85 인계

- 기준은 2026-11-23 FR-QZ-07 개정과 PT-85 연동 메모다. [PT-84 범위](https://gad03302.atlassian.net/browse/PT-84?focusedCommentId=10205) · [PT-85 연동 메모](https://gad03302.atlassian.net/browse/PT-85?focusedCommentId=10225)
- 작업 브랜치는 `feature/PT-84-question-variant`, 기준 `develop`은 `569f326197a8ebe7040768d6c2abcc08142f8660`이다. [편집 예약](https://gad03302.atlassian.net/browse/PT-84?focusedCommentId=10231)
- WBS-TUT-0125의 담당 맹동영, 계획 2026-11-23~27은 Jira의 담당·시작일·기한과 일치한다. [WBS 95행](https://docs.google.com/spreadsheets/d/1R_36VcUfEy8HmA9li32YrnD1mLRUa9_G1F8grW-LYiQ/edit#gid=1395984131&range=A95:P95)

## 호출

Spring이 참여 수업과 본인 오답 접근을 확인한 뒤 원래 문항 데이터만 전달한다. 학생이 작성한 답안 원문은 입력 스키마에 없으며, 알 수 없는 필드가 들어오면 모델 호출 전에 `invalid_original`을 반환한다.

- 진입점은 [`generate_question_variant(original)`](../tutipal_ai/app/services/question_variant_service.py)의 비동기 Python 함수다.
- [입출력 모델](../tutipal_ai/app/models/question_variant.py)은 Spring에서 쓰는 `CHOICE`, `TEXT`, `question`, `choices`, `answerIndex`, `modelAnswer`를 사용한다. `answerIndex`는 0부터 시작하고 객관식은 보기 2~5개를 받는다.
- [모의 모델 테스트](../tutipal_ai/tests/test_question_variant.py)는 같은 진입점으로 검사한다.

```python
from app.services.question_variant_service import generate_question_variant

result = await generate_question_variant(original)
payload = result.model_dump(exclude_none=True)
```

### 객관식 입력

```json
{
  "type": "CHOICE",
  "question": "시속 60 km로 2시간 동안 달렸을 때 이동 거리는 얼마인가요?",
  "choices": ["30 km", "60 km", "120 km", "180 km"],
  "answerIndex": 2
}
```

### 객관식 성공 예시

```json
{
  "status": "ok",
  "attempts": 1,
  "variant": {
    "type": "CHOICE",
    "question": "시속 24 km로 30분 동안 달리는 자전거는 몇 km를 이동하나요?",
    "choices": ["8 km", "12 km", "24 km", "48 km"],
    "answerIndex": 1,
    "explanation": "30분은 0.5시간이며 거리 = 속력 × 시간이므로 24 × 0.5 = 12 km입니다."
  }
}
```

### 주관식 입력

```json
{
  "type": "TEXT",
  "question": "어떤 수의 25%가 15입니다. 그 수를 구하고 풀이를 설명하세요.",
  "modelAnswer": "15를 0.25로 나누면 60입니다."
}
```

### 주관식 성공 예시

```json
{
  "status": "ok",
  "attempts": 1,
  "variant": {
    "type": "TEXT",
    "question": "작년보다 20% 늘어난 도서관 방문자가 144명입니다. 작년 방문자 수를 구하세요.",
    "modelAnswer": "120명",
    "explanation": "작년 방문자의 120%가 144명이므로 144 ÷ 1.2 = 120명입니다."
  }
}
```

### 실패 예시

```json
{
  "status": "failed",
  "attempts": 3,
  "failure_reasons": ["concept_mismatch", "no_material_difference"]
}
```

`attempts`는 시작한 생성 횟수다. `failed` 응답에는 시도한 문항이 없고 마지막 실패 코드만 담긴다. 연동 시 `ok`일 때 `variant`를 읽고, 정답·해설을 표시하는 시점은 기존 수업 정책을 적용한다. `failed`일 때는 기존 학생 Agent 응답 흐름에서 재시도 안내를 제공한다.

| 실패 코드 | 의미 |
| --- | --- |
| `invalid_original` | 입력이 원래 문항 스키마에 맞지 않음. 모델 호출 0회 |
| `invalid_format` | 생성 문항의 JSON, 유형, 필드, 길이 또는 정답 번호가 유효하지 않음 |
| `duplicate_choices` | 객관식의 보기 텍스트가 정규화 후 중복 |
| `same_question` | 정규화한 문제 문장이 원래 문항과 동일 |
| `answer_explanation_mismatch` | 모델이 변형 문항의 풀이 가능성, 정답의 유일성 또는 해설과 정답의 일치를 확인하지 못함 |
| `concept_mismatch` | 모델이 원래 문항과 같은 핵심 개념이 적용된다고 확인하지 못함 |
| `no_material_difference` | 모델이 조건·사례에 실질적인 차이를 확인하지 못함 |
| `invalid_verification` | 모델 검증 응답이 지정된 불리언 JSON 형식에 맞지 않음 |
| `model_unavailable` | 모델 호출을 완료하지 못했거나 전체 시간 한도에 도달 |

## 구현과 검증

- 이전 PDF 초안에서 Pydantic 엄격 검사, 텍스트 중복 비교, 생성 → 형식 검사 → 독립 내용 검증, 실패 피드백 재시도와 모의 제공자 검사 방식을 가져왔다. 현재 `stream_chat`을 통해 Ollama/OpenAI JSON 모드와 기존 모델 선택·대체 정책을 재사용한다. [이전 구현 커밋](https://github.com/JephyrWing/project_tutipal/commit/a9816ec7b9d964a9a4d0345c236dd7ece086f170)
- PT-84 단독 호출은 Python 서비스 함수 안에서 실행된다. 형식·중복·원문 복사를 코드로 거른 뒤 모델 검증을 호출한다. 보기 비교는 유니코드 정규화와 앞뒤 공백 제거를 적용하며 대소문자는 유지한다. 검증 실패 이유는 다음 생성 프롬프트에 반영된다. 생성은 최대 3회이며 전체 실행은 120초 한도를 적용한다. 제공자 장애나 시간 초과 시 `model_unavailable`을 반환한다.
- `MODEL_PROVIDER=auto`는 공통 정책에 따라 OpenAI로 전환될 수 있다. 로컬 확인은 `MODEL_PROVIDER=ollama`와 loopback 주소를 지정해 실행했다.

### 로컬 검사 명령

`D:/TutiPal/tutipal_ai`에서 Windows · Python 3.12.6으로 실행했다. 의존성은 `requirements.lock.txt` 기준으로 임시 폴더에 설치했다.

```powershell
python -B -m unittest discover -s tests -v
python -B -m pip check
python -B -c 'from pathlib import Path; from app.main import app; paths=list(Path("app").rglob("*.py")); [compile(p.read_bytes(), str(p), "exec") for p in paths]; print(f"{app.title}: compiled {len(paths)} app modules")'
```

2026-11-23 15:55:12–15:55:16 KST, 명령 3개 모두 종료 코드 0. 모의 모델 검사 17건과 기존 `/v1/chat` 대체 동작 회귀 검사 1건이 통과했다. 실패·건너뜀은 각각 0건이다. 의존성 검사와 FastAPI import, Python 모듈 22개 컴파일은 별도 기본 검사다. 기준 `develop`의 AI 디렉터리에 기존 자동 기능 테스트가 없어서 채팅 경로 회귀를 이번 검사에 추가했다.

### 실제 Ollama 확인

같은 객관식·주관식 예시를 `127.0.0.1:11434`의 Ollama 0.40.16으로 보냈다. 정상 설정 검사는 서비스와 공통 모델 전송 코드를 그대로 실행했다. 전송 대상은 예시 문항과 이 PC의 Ollama로 제한했다.

| 모델과 실행 시각 (KST) | 객관식 | 주관식 |
| --- | --- | --- |
| `gemma4:e2b`, 15:50:19–15:54:15, 최종 코드 이전의 기본 옵션 | 120초 한도 후 `failed / model_unavailable` | 1회 생성으로 `ok`. 40%가 50인 수를 묻는 문제의 정답 125와 해설 계산이 일치 |
| `gemma2:latest`, 15:55:16–15:59:31, 최종 코드의 기본 옵션 | 시간 한도 후 `failed / model_unavailable` | 시간 한도 후 `failed / model_unavailable` |
| `gemma4:e2b`, 16:00:12–16:02:13, 최종 코드의 로컬 진단 옵션 | 2번째 시도에서 시간 한도 후 `failed / model_unavailable` (120.01초) | 객관식 예시만 실행 |

기본 옵션 실행 명령은 `MODEL_PROVIDER=ollama`, `MODEL_BASE_URL=http://127.0.0.1:11434`와 표의 `MODEL_NAME`을 지정한 뒤 `python -B <임시 폴더>/smoke.py`였다. 첫 프로세스의 종료 코드 0은 두 응답을 정상적으로 출력했다는 뜻이다. 재실행 스크립트는 문항 생성·검사가 하나라도 실패하면 종료 코드 1을 반환하도록 했고, `gemma2:latest`의 종료 코드는 1이었다.

같은 예시를 재검사하려면 의존성이 설치된 Python 환경에서 아래 명령으로 두 결과를 출력한다. 이 PC의 CPU 실행 시간은 모델과 설정에 따라 달라진다.

```powershell
$env:MODEL_PROVIDER = 'ollama'
$env:MODEL_BASE_URL = 'http://127.0.0.1:11434'
$env:MODEL_NAME = 'gemma4:e2b'
@"
import asyncio
import sys

sys.path.insert(0, "tests")
from app.services.question_variant_service import generate_question_variant
from test_question_variant import CHOICE, TEXT

async def main():
    failed = False
    for source in (CHOICE, TEXT):
        result = await generate_question_variant(source)
        print(source["type"], result.model_dump_json(exclude_none=True))
        failed |= result.status != "ok"
    return int(failed)

raise SystemExit(asyncio.run(main()))
"@ | python -B -
```

로컬 진단은 `python -B <임시 폴더>/smoke_gemma4_local_options.py`로 실행했다. 이 검사용 스크립트는 Ollama 요청에만 `think=false`와 `num_predict=512`를 추가했다. 종료 코드는 1이었다. 실제 모델에서 확인한 문항 품질은 주관식 1건이며, 객관식 품질과 PT-85에서 사용할 모델의 실행 시간은 후속 확인 항목이다.

## 후속 연결

- PT-85에서 전설민 PL이 호출 방식과 Agent 코드의 연결 위치를 결정한다. 성공 문항과 정답·해설을 분리하여 정답 공개 정책을 적용하고, 실패 시 재시도 안내를 기존 응답 흐름에 연결한다. [PT-85](https://gad03302.atlassian.net/browse/PT-85)
- PT-53 복습 화면에서 시작하는 종단 검증은 PT-73에서 수행한다. 수업·본인 오답 접근 확인, 다른 학생·수업 접근 거절, 문항 표시와 정답 공개 정책, 모델 실패 응답을 확인한다. [PT-53](https://gad03302.atlassian.net/browse/PT-53) · [PT-73](https://gad03302.atlassian.net/browse/PT-73)
