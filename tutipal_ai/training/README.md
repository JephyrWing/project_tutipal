# 모델 비교 · 파인튜닝 평가 도구

로컬 모델 후보를 **같은 평가 세트**로 비교해 베이스 모델을 고르고, 파인튜닝 전후를 같은 기준으로 다시 잰다. 추론 서버(`tutipal_ai/app`)와 의존성·실행이 분리되어 있으며 서버 코드는 바꾸지 않는다.

| 평가 | 내용 | 지표 |
| --- | --- | --- |
| 코딩 | KodCode-V1(Taco) 문항을 난이도별 같은 수로 고정 추출. 모델 코드를 pytest로 채점 | pass@1(온도 0, 문항당 1회), 난이도별 정답률 |
| 한국어 튜터 | 직접 만든 질문 30개(개념·디버깅·힌트·코드리뷰·입문). 시스템 프롬프트는 백엔드 `TopicPrompt.CODING`과 같음 | 한국어 답변율(자동), 사람 점수(`ko_review.csv`) |
| 속도 | 같은 요청의 응답 시간과 생성 속도 | 응답 중앙값(초), 토큰/초 |

후보(`eval/candidates.json`)는 RTX 3060 Laptop 6GB에서 추론과 4bit QLoRA 학습이 모두 가능한 크기로 골랐다: `gemma4:e2b`, `qwen3.5:4b`, `llama3.2:3b`. 생각 모드는 끄고(`think=false`) 비교한다.

## 준비

```powershell
cd tutipal_ai\training
py -3.12 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
.\.venv\Scripts\python.exe -m pytest              # 도구 자체 테스트

# 모델이 만든 코드는 네트워크 없는 컨테이너에서 실행한다(Docker Desktop 실행 필요)
docker build -t tutipal-eval-sandbox:1 -f eval/sandbox.Dockerfile eval

ollama pull gemma4:e2b
ollama pull qwen3.5:4b
ollama pull llama3.2:3b
```

## 1. 평가 세트 만들기

```powershell
.\.venv\Scripts\python.exe -m eval.build_eval_set --per-difficulty 50
```

- KodCode-V1의 현재 커밋 SHA를 `data/eval/manifest.json`에 고정 기록한다. 같은 SHA·시드(`--revision`, `--seed`)로 다시 만들면 같은 문항이 나온다.
- 표준 라이브러리만 쓰는 문항, 공개 벤치마크 유사도 0.8 미만 문항만 남긴다. 참조 정답이 채점 컨테이너에서 실제로 통과하는 문항만 쓴다.
- `data/eval/holdout.jsonl`(문항 ID·지문 해시)은 **학습 데이터에서 반드시 제외**한다. 평가 문항을 학습하면 점수가 부풀려진다.

## 2. 후보 비교 (파인튜닝 전)

```powershell
# 빠른 점검: 모델 하나, 문항 5개
.\.venv\Scripts\python.exe -m eval.run_eval --models llama3.2:3b --limit 5 --run-name smoke

# 본 비교: 후보 전부. 중단돼도 같은 --run-name으로 다시 실행하면 이어서 한다
.\.venv\Scripts\python.exe -m eval.run_eval --run-name baseline
```

결과는 `results/<run-name>/`에 남는다.

- `summary.md`: 포트폴리오에 넣을 비교표
- `summary.json`: 같은 내용의 숫자
- `results.jsonl`: 문항별 응답·채점 결과 원본
- `ko_review.csv`: 한국어 답변을 사람이 1~5점으로 채점하는 표(엑셀에서 열림). 문항마다 확인 항목이 있다

## 3. 파인튜닝 후 다시 재기

학습한 모델을 Ollama에 등록한 뒤 같은 명령으로 잰다. `candidates.json`에 없는 이름도 받는다.

```powershell
.\.venv\Scripts\python.exe -m eval.run_eval --models gemma4:e2b tutipal-coder --run-name after-ft
```

## 주의

- `--sandbox local`은 Docker 없이 이 PC에서 모델 코드를 실행한다. API 키 같은 환경변수는 넘기지 않지만 **파일·네트워크 격리는 없다**. Docker를 쓸 수 없을 때 참고용으로만 쓴다.
- 자동 한국어 지표는 "한국어로 답했는가"만 본다. 설명의 정확성과 튜터 태도는 `ko_review.csv`의 사람 점수로 판단한다.
- 온도 0·시드 고정이어도 Ollama 버전·양자화·GPU에 따라 결과가 조금 달라질 수 있다. 비교는 같은 PC에서 같은 run 안에서 한다.
- `data/`, `results/`, `.venv/`는 Git에 넣지 않는다.
- Git Bash에서 진행 표시의 한글이 깨지면 `PYTHONIOENCODING=utf-8`을 앞에 붙인다. 결과 파일은 항상 UTF-8이다.

## 데이터 출처와 라이선스

- KodCode-V1: CC BY-NC 4.0, 비영리 포트폴리오 용도로만 사용. Xu et al., *KodCode: A Diverse, Challenging, and Verifiable Synthetic Dataset for Coding*, arXiv 2503.02951. 풀이와 테스트는 gpt-4o-0513이 생성했다.
- 한국어 질문 30개(`eval/ko_questions.jsonl`): 이 프로젝트에서 직접 작성했다.
