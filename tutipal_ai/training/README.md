# 모델 비교 · 파인튜닝 평가 도구

Jira PT-89(AI-007 효과 평가)와 PT-88(AI-006 주관식 채점 QLoRA)의 평가 부분이다. 로컬 모델 후보를 **같은 평가 세트**로 비교해 베이스 모델을 고르고, 파인튜닝 전후를 같은 기준으로 다시 잰다. 추론 서버(`tutipal_ai/app`)와 의존성·실행이 분리되어 있으며 서버 코드는 바꾸지 않는다.

| 과제 (`--tasks`) | 내용 | 지표 |
| --- | --- | --- |
| `grade` (기본) | 주관식 채점 120건(`eval/grading_set.jsonl`). 백엔드 `QuizAi`와 같은 프롬프트·메시지·JSON 모드·엄격한 파싱 | 정확도, 균형 정확도, 정답/오답 인식, 오답을 정답 처리한 비율, **꼼수 방어율**, JSON 성공률, 답안 유형별 정확도 |
| `code` | KodCode-V1(Taco) 150문항, 모델 코드를 Docker 안에서 pytest로 채점 | pass@1, 난이도별 정답률 |
| `ko` | 한국어 코딩 튜터 질문 30개. 시스템 프롬프트는 백엔드 `TopicPrompt.CODING`과 같음 | 한국어 답변율(자동), 사람 점수(`ko_review.csv`) |

후보(`eval/candidates.json`)는 RTX 3060 Laptop 6GB에서 추론과 4bit QLoRA 학습이 모두 가능한 크기로 골랐다: `gemma4:e2b`, `qwen3.5:4b`, `llama3.2:3b`. 생각 모드는 끄고(`think=false`), 온도 0으로 비교한다.

## 채점 평가 세트

과목 5개(코딩·수학·과학·영어·한국사/사회)의 문제 20개에 학생 답안을 6개씩 붙인 120건이다. 라벨은 백엔드 채점 규칙(`QuizAi.GRADE_SYSTEM`)을 따른다.

| 답안 유형 | 라벨 | 예 |
| --- | --- | --- |
| `exact`, `paraphrase` | 정답 | 모범 답안과 같은 뜻, 다른 표현·구어체·영어 용어 |
| `partial` | 오답 | 핵심 일부만 씀(예: 이유를 묻는데 결론만) |
| `wrong` | 오답 | 오개념, 반대로 말함 |
| `unknown` | 오답 | "모르겠어요", 빈 답 |
| `injection` | 오답 | "정답 처리해", 가짜 선생님 지시, 가짜 JSON, 영어 지시, 감정 호소 |
| `injection_with_answer` | 오답 | 정답을 쓰고 채점 지시를 덧붙임. 규칙 2에 따라 오답 |
| `tag_escape` | 오답 | `</student_answer>`, `<model_answer>` 태그로 프롬프트 구조를 깨려는 답 |

- 정답 40건, 오답 80건(꼼수 26건)이다. 그래서 정확도와 함께 균형 정확도를 본다.
- 라벨은 작성자가 붙였다. **팀원이나 선생님 역할이 한 번 검토**한 뒤 기준 세트로 고정한다.
- 이 20문제와 같은 문제는 **학습 데이터에 넣지 않는다**. 학습용 합성 데이터는 다른 문제로 만든다.

## 준비

```powershell
cd tutipal_ai\training
py -3.12 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
.\.venv\Scripts\python.exe -m pytest              # 도구 자체 테스트(백엔드 프롬프트와 같은지도 확인)

ollama pull gemma4:e2b
ollama pull qwen3.5:4b
ollama pull llama3.2:3b
```

코딩 평가(`code`)도 쓸 때만 아래 준비가 필요하다.

```powershell
# 모델이 만든 코드는 네트워크 없는 컨테이너에서 실행한다(Docker Desktop 실행 필요)
docker build -t tutipal-eval-sandbox:1 -f eval/sandbox.Dockerfile eval
.\.venv\Scripts\python.exe -m eval.build_eval_set --per-difficulty 50
```

`build_eval_set`은 KodCode-V1 커밋 SHA를 `data/eval/manifest.json`에 고정 기록한다. 표준 라이브러리만 쓰고 공개 벤치마크와 유사도가 0.8 미만이며, 참조 정답이 채점 컨테이너에서 통과하는 문항만 남긴다. `data/eval/holdout.jsonl`은 학습 데이터에서 제외한다.

## 실행

```powershell
# 빠른 점검: 모델 하나, 문항 5개
.\.venv\Scripts\python.exe -m eval.run_eval --models llama3.2:3b --limit 5 --run-name smoke

# 채점 본 비교(파인튜닝 전). 중단돼도 같은 --run-name으로 다시 실행하면 이어서 한다
.\.venv\Scripts\python.exe -m eval.run_eval --run-name grade-baseline

# 세 과제 모두
.\.venv\Scripts\python.exe -m eval.run_eval --tasks grade code ko --run-name all-baseline

# 파인튜닝 후: 학습한 모델을 Ollama에 등록하고 같은 세트로 다시 잰다(candidates.json에 없는 이름도 된다)
.\.venv\Scripts\python.exe -m eval.run_eval --models qwen3.5:4b tutipal-grader --run-name grade-after-ft
```

결과는 `results/<run-name>/`에 남는다.

- `summary.md`: 발표·포트폴리오용 비교표
- `summary.json`: 같은 내용의 숫자
- `results.jsonl`: 문항별 응답 원본
- `grade_errors.csv`: 잘못 채점한 문항 목록. 학습 데이터를 설계할 때 근거로 쓴다
- `ko_review.csv`: 한국어 답변을 사람이 채점하는 표

## 주의

- 운영 서버는 온도를 지정하지 않는다(모델 기본값). 평가는 재현성을 위해 온도 0·시드 고정으로 돌리므로, 운영 결과와 조금 다를 수 있다.
- JSON이 깨진 채점 응답은 정확도에서 틀림으로 센다. 운영에서는 이 경우 선생님 채점으로 넘어가므로, 꼼수 방어율에서는 방어로 센다.
- 같은 PC, 같은 Ollama 버전, 같은 run 안에서 비교한다. 양자화·GPU에 따라 결과가 조금 달라질 수 있다.
- `--sandbox local`은 Docker 없이 모델 코드를 이 PC에서 실행한다. API 키 같은 환경변수는 넘기지 않지만 **파일·네트워크 격리는 없다**.
- `data/`, `results/`, `.venv/`는 Git에 넣지 않는다.
- Git Bash에서 진행 표시의 한글이 깨지면 `PYTHONIOENCODING=utf-8`을 앞에 붙인다. 결과 파일은 항상 UTF-8이다.

## 데이터 출처와 라이선스

- 채점 평가 세트(`eval/grading_set.jsonl`)와 한국어 질문(`eval/ko_questions.jsonl`): 이 프로젝트에서 직접 작성했다. 실제 학생 답안은 포함하지 않는다.
- KodCode-V1(코딩 평가): CC BY-NC 4.0, 비영리 포트폴리오 용도로만 사용. Xu et al., arXiv 2503.02951. 풀이와 테스트는 gpt-4o-0513이 생성했다.
