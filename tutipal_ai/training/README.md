# 모델 비교 · 파인튜닝 평가 도구

Jira PT-89(AI-007 효과 평가)와 PT-88(AI-006 주관식 채점 QLoRA)의 평가 부분이다. 로컬 모델 후보를 **같은 평가 세트**로 비교해 베이스 모델을 고르고, 파인튜닝 전후를 같은 기준으로 다시 잰다. 추론 서버(`tutipal_ai/app`)와 의존성·실행이 분리되어 있으며 서버 코드는 바꾸지 않는다.

| 과제 (`--tasks`) | 내용 | 지표 |
| --- | --- | --- |
| `grade` (기본) | 주관식 채점 120건(`eval/grading_set.jsonl`). 백엔드 `QuizAi`와 같은 프롬프트·메시지·JSON 모드·엄격한 파싱 | 정확도, 균형 정확도, 정답/오답 인식, 오답을 정답 처리한 비율, **꼼수 방어율**, JSON 성공률, 답안 유형별 정확도 |
| `code` | KodCode-V1(Taco) 150문항, 모델 코드를 Docker 안에서 pytest로 채점 | pass@1, 난이도별 정답률 |
| `ko` | 한국어 코딩 튜터 질문 30개. 시스템 프롬프트는 백엔드 `TopicPrompt.CODING`과 같음 | 한국어 답변율(자동), 사람 점수(`ko_review.csv`) |

후보(`eval/candidates.json`)는 RTX 3060 Laptop 6GB에서 추론과 4bit QLoRA 학습이 모두 가능한 크기로 골랐다: `gemma4:e2b`, `qwen3.5:4b`, `llama3.2:3b`. 생각 모드는 끄고(`think=false`), 온도 0으로 비교한다.

**채점 QLoRA 범위(PT-88/89)**: 프로그래밍(코딩) 과목의 한두 문장 주관식이다(짧은 코드 답안 포함). 개발 세트(`eval/grading_set.jsonl`)는 다과목 그대로 두고 회귀 확인용으로 쓰며, **보고하는 숫자는 코딩 과목**(`--subjects 코딩`, 개발 세트에서는 36건)과 저장소 밖 **봉인 세트**로 낸다. 자세한 절차는 아래 [범위와 봉인 세트](#범위와-봉인-세트).

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

- 정답 40건, 오답 80건(꼼수 20건: injection 10, injection_with_answer 7, tag_escape 3)이다. 그래서 정확도와 함께 균형 정확도를 본다.
- 라벨은 작성자가 붙였다. **팀원이나 선생님 역할이 한 번 검토**한 뒤 기준 세트로 고정한다.
- 이 20문제와 같은 문제는 **학습 데이터에 넣지 않는다**. 학습용 합성 데이터는 다른 문제로 만든다.

### 학습 데이터 누수 검사

학습 데이터(저장소 밖)를 dev 세트(`eval/grading_set.jsonl`)와, 있으면 저장소 밖 sealed 세트와 비교한다. 학습에 쓰기 전에 반드시 통과시킨다.

```powershell
.\.venv\Scripts\python.exe -m eval.leakcheck --train <train.jsonl> [--sealed <sealed.jsonl>]
# 또는 환경 변수 TUTIPAL_GRADING_TRAIN / TUTIPAL_GRADING_SEALED
```

- 문항(Q1)·모범 답안(M1)·학생 답안(S1)의 정규화 후 유사도와, 꼼수 답안끼리 겹치는 문구 길이(T1)를 본다. 기준과 `rule_version`은 `eval/leakcheck.py` 맨 위에 있다. 정규화는 보이지 않는 문자(ZWSP 등)와 결합 부호도 지운다.
- 트레이너는 `messages`로 학습하므로, 학습 행에 `messages`가 있으면 system을 뺀 메시지에 평가 세트의 문항·모범 답안·학생 답안이 통째로 들어 있는지도 본다(X1, 필드와 messages가 어긋난 행 대비). `messages`는 `role`·`content`가 문자열인 객체 목록이어야 한다. 꼼수 문구의 부분 겹침은 프롬프트 틀과 겹쳐 오탐이 나서 messages에는 적용하지 않는다.
- 종료 코드 0 통과(검토 항목은 있어도 됨), 1 누수, 2 입력·출력 오류. 결과는 `results/leakcheck/leakcheck.json`. `--report`는 git이 무시하는 `results/` 아래를 권장하며, 다른 곳이면 경고만 한다(저장소 안에 쓰면 커밋하지 않게 주의).
- 보고서에는 train-dev 쌍은 id와 점수, sealed가 낀 쌍(train-sealed, dev-sealed)은 실패한 id 쌍만(점수·이유·검토 항목 없음) 남기고, 학습·sealed 원문은 쓰지 않는다. id는 내용이 없는 식별자여야 한다(영문·숫자·`._-`, 128자 이하, 파일 안에서 중복 금지).
- 검토 항목은 (규칙, 쌍)마다 상위 10개만 남기고, 전체 개수는 `review_total`에 있다.
- sealed 검사는 **sealed 세트 관리자 또는 학습 직전 게이트에서만** 돌린다. sealed가 낀 쌍은 실패한 id 쌍만 남기고 점수·검토 항목은 쓰지 않는다(반복 실행으로 sealed 내용을 더듬지 못하게).
- sealed 없이 돌린 결과(`sealed: not_checked`)는 dev만 본 참고용이다. `TUTIPAL_GRADING_SEALED`가 비어 있으면 경고만 하고 넘어간다.
- 한계: 어순만 바꾼 문항은 유사도가 낮아 검토 항목으로만 잡힐 수 있다. 검토 목록은 사람이 본다.
- `TUTIPAL_GRADING_TRAIN`을 설정하고 `pytest`를 돌리면 실제 학습 데이터 검사도 함께 한다(없으면 건너뜀).
- 학습 직전 게이트(sealed 세트 관리자만, sealed 파일은 저장소 밖):

  ```powershell
  .\.venv\Scripts\python.exe -m eval.leakcheck --train <train_aug.jsonl> --sealed <sealed.jsonl> --report results/leakcheck
  ```

### 채점 학습 데이터 (저장소 밖)

- QLoRA 학습에는 `tutipal_datasets/derived/grading_c/train_aug.jsonl`(v1)을 썼고, v2가 생성·검사(아래)를 통과하면 `derived/grading_v2/train_aug.jsonl`을 쓴다. `train.jsonl`, `train_clean.jsonl`은 중간 산출물이다.
- 만드는 코드는 `data_tools/`에 있다(2026-11-25 저장소로 옮김). `tutipal_ai/training`에서 아래 순서로 실행하고, 출력 폴더는 git 저장소 밖 로컬 드라이브 경로여야 한다. 어느 git 작업 트리 안이든(이 저장소, 워크트리의 원래 체크아웃, `C:\CODE-SPACE`처럼 상위 폴더 저장소 포함), UNC·장치 경로(`\\server\share`, `\\?\...`), 빈 값이면 아무것도 쓰지 않고 종료 코드 2. 판정은 대상과 위 폴더들의 `.git` 표식으로 하고(git이 없어도 동작), git이 있으면 `git rev-parse --show-toplevel`로 한 번 더 확인한다. 판정할 수 없으면 거부한다.

  ```powershell
  # 1. 생성(로컬 Ollama 필요): 번역 모델 + 작성 모델 → train.jsonl
  .\.venv\Scripts\python.exe -m data_tools.build_grading_c --out <grading_c> --datasets-dir <datasets> --eval-set eval/grading_set.jsonl
  # 2. 정리(LLM 없음): 모순·중복·문제당 상한 → train_clean.jsonl, dropped.csv, translation_review.csv
  .\.venv\Scripts\python.exe -m data_tools.clean_grading_c --dir <grading_c> --datasets-dir <datasets> --eval-set eval/grading_set.jsonl
  # 3. 검수 반영 + 누수 제거 + 꼼수 보강 → train_aug.jsonl, aug_stats.json, aug_bases.csv
  .\.venv\Scripts\python.exe -m data_tools.augment_tricks --dir <grading_c> --eval-set eval/grading_set.jsonl [--reviewed-bases <aug_bases_review.csv>]
  ```

  `--quizai`는 기본으로 저장소의 `tutipal_back/.../QuizAi.java`를 읽어 `GRADE_SYSTEM`(원본은 `eval/run_eval.py` 하나)과 대조한다. `build_grading_train`(초기 생성기)과 `compare_hybrid`(번역 조합 비교)는 참고용이다. 생성기 3종은 원천 데이터에서 뽑은 문항이 없으면 출력 폴더를 만들기 전에, 학습 행이 0개면 기존 결과를 덮기 전에 종료 코드 2로 끝난다. `clean_grading_c`는 학습 행·평가 세트가 비었거나 정리 후 남은 행이 없으면 종료 코드 2로 끝난다. 두 단계 모두 결과 파일을 임시 파일에 다 쓴 뒤 한꺼번에 바꿔 넣는다.
- `augment_tricks`가 하는 일: 라벨 검수 결과(`review_true_*.csv`, `aug_bases_review.csv`)를 코드 상수로 반영한다. 질문에 정답 표시가 새던 SciEntsBank 검수 14건 중 13건(질문 11개)의 괄호 표시를 고치고(`QUESTION_FIXES`, 괄호 안에 두 선택지가 다 있는 1건은 다시 보니 누출이 아니어서 고치지 않음 `QUESTION_REVIEWED_NO_LEAK`), 나쁜 정답 행(`EXCLUDE_ROWS`, BAD·WEAK_CORE 31개)과 모범 답안·질문이 깨진 문제 전체(`EXCLUDE_QUESTIONS_OF`, MA_BAD 32개 문제)를 뺀다. 그다음 누수 검사 실패 행과 번역이 깨진 행을 빼고, 꼼수 답안을 꼼수 비율 15% 이하·문제당 2개 이하로 보강한다. 바탕 답안 선택은 seed 기반 해시 순서라 입력 순서와 무관하다. 고른 바탕 답안은 `aug_bases.csv`, `--reviewed-bases`를 주면 검수표에 없는 새 바탕 답안만 `aug_bases_new_for_review.csv`에 남긴다. 바탕 답안 검수에서 뺀 답안은 `BAD_BASES`(1차 4개 + 검수표 15개 + 2차 검수 6개).
- `augment_tricks` 입력: JSONL은 UTF-8(BOM 허용, `eval.leakcheck`와 같음), 줄마다 JSON 객체이고 필수 필드가 있어야 한다. 학습 행이나 평가 세트가 비었거나, 형식·필드가 틀렸거나, `--per-kind`가 음수면 `파일:줄` 메시지와 함께 종료 코드 2로 끝나고 아무것도 쓰지 않는다. 입력(검수·누수 제외 뒤)의 꼼수 행이 이미 15%를 넘으면 행을 몰래 버리지 않고 비율을 알린 뒤 종료 코드 2로 끝난다(이때도 아무것도 쓰지 않음). CSV(`--reviewed-bases`)도 BOM을 허용하고 `base_id` 열이 있어야 한다. 결과 파일은 모두 만든 뒤 임시 파일 + 바꿔 넣기로 쓰므로 쓰기 도중 실패해도 기존 파일이 남는다.
- 2026-11-25 재생성(seed 20261125): 입력 993행 → 질문 수정 45행, 검수 제외 146행(행 단위 24, 문제 단위 122), 누수 제외 27행, 깨진 번역 9행, 보강 82행(종류별 41) → **893행**(정답 259, 꼼수 133행 14.9%). leakcheck(dev) 실패 0. 이전 결과(1050행)는 `derived/grading_c/prev_20261125/`에 있다. 새 바탕 답안 43개는 검수를 마쳤다(신규 중 제외 4개 + 1차에서 유지했던 2개를 제외로 변경 → `BAD_BASES` 25개, 아래 2차 재생성).
- **학습 데이터 v2 (PT-88, `derived/grading_v2/`)**: v1 폴더(`derived/grading_c/`)는 읽기만 하고 산출물은 모두 `--out`/`--dir`(v2 폴더)에 쓴다. 원천은 SciEntsBank train, Mohler open+close, SAF **train+validation**(SAF `test_unseen_*` split 은 읽지 않는다). Mohler 정답은 평균 ≥ 4.5 이면서 두 채점자 모두 ≥ 4(평균 ≥ 4.5 인데 한 명이 4 미만이면 버림), 3.5 < 평균 < 4.5 는 버린다. 행마다 `qid`(= `<source>|sha256(영어 문제)[:16]`)가 있고 꼼수·보강 행은 바탕 행의 qid 를 물려받는다.
  - 생성(`build_grading_c --v2`): 영어 문제(qid)마다 Mohler 20·SAF 16행까지 오답부터 유형을 돌아가며 고른다. 과학은 v1 캐시(번역·작성)가 모두 있는 행만 쓴다(LLM 0회). 정답인데 영어 답이 5단어 미만이거나 모범 답안 단어 수의 25% 미만이면 뺀다(`--review-true`의 OK·WEAK_KEEP 행은 남김). `--cache-from`의 v1 캐시는 v2 폴더에 캐시가 없을 때만 복사하고 이후 v2 폴더에 이어 쓰므로, **중단돼도 같은 명령으로 다시 실행하면 이어서 한다**. `--assemble-only`는 LLM 을 부르지 않고 캐시만으로 조립한다(캐시 없는 행은 `rejected.jsonl`에 `missing`). 꼼수 변형 비율 기본 0.15이고 v2에서는 코딩 정답 행에만 붙인다. `stats.json`에 `llm_translate_calls`(필드마다 1)·`llm_write_calls`·`text_memo_hits`·`missing_core_dropped_ids`. 같은 영어 원문(qid마다 반복되는 문제·모범 답안)은 `text_cache.jsonl` 메모로 한 번만 번역한다(행 캐시에 이미 있는 행은 행 캐시를 그대로 씀). `--out`과 `--cache-from`이 같거나 한쪽이 다른 쪽 안이면 종료 코드 2. 강제 종료로 캐시 마지막 줄이 잘렸으면 경고하고 그 줄을 버린 뒤 이어서 한다.
  - 정리(`clean_grading_c --sources v2`): 금지 용어(`FORBIDDEN_TERMS`: 핑거링·밀고 내보내·서드 시작)가 남은 행은 빼고(`dropped.csv` 사유 `term:<용어>`, `clean_stats.json`의 `term_dropped`), 문제·모범 답안에 있으면 `--overrides`로 고친 번역을 주면 살아남는다. `--cap-by-source`로 출처별 문제당 상한을 주며(원천 3종·데이터에 있는 출처 이름만, 오타면 2) 꼼수 행은 상한에 세지도 버리지도 않는다. 이 두 규칙은 v2(`--sources v2`, `--cap-by-source`, 또는 qid가 있는 행)에서만 켜지고, v1 산출물을 기본 옵션으로 돌리면 예전과 같은 결과가 나온다. 주의: qid와 override 그룹은 영어 **문제 문장** 기준이라, 문장은 같고 모범 답안이 다른 Mohler 문제 2개(`MohlerASAG|3e1ef85b01a24872`, `MohlerASAG|b51a3685728b28a5`)는 각각 한 그룹을 같이 쓴다. 이 그룹에 `--overrides`를 쓸 때는 모범 답안이 둘 다 맞는지 확인한다.
  - 보강(`augment_tricks`): `--datasets-dir`를 주면 검수 제외(`EXCLUDE_QUESTIONS_OF`)를 영어 (문제, 모범 답안) 쌍으로 맞춘다(번역이 달라도, 대표 행이 데이터에 없어도 빠짐). 순서는 질문 수정 → 검수 제외 → 누수 제외 → 깨진 번역 제외 → 과학 다운샘플(`--max-science`, (kind, label) 층별 최대 잔여 배분, seed 해시 순) → 꼼수 보강(`--base-subjects 코딩`이면 바탕 답안은 코딩만). `aug_stats.json`의 `composition`(전체·과학·정답 비율·Mohler/SAF 비꼼수)으로 구성 목표(과학 ≤ 250, 전체 1,300–1,800, 정답 0.30–0.45, Mohler 비꼼수 ≥ 600, SAF 비꼼수 ≥ 200)를 확인한다.
  - 사람 확인 시트(`data_tools.review_sheet`): 꼼수가 아닌 코딩 30행(SAF ≥ 8)을 영어 원문·한국어·라벨·이유·피드백과 함께 `review_sheet.csv`로 만들고, `verdict`(OK·LABEL_ERR·TRANS_ERR·EXPL_ERR)를 채운 뒤 `check`가 0(OK 아닌 행 3개 이하)이어야 학습에 쓴다(1 = 4개 이상, 2 = 빈칸·모르는 값·UTF-8이 아닌 파일). `make`는 `review_sheet.meta.json`에 만든 `train_aug.jsonl`의 sha256과 행 id를 남기고, `check`는 같은 폴더의 `train_aug.jsonl`과 대조한다(바뀌었거나 id가 다르면 2). verdict가 채워진 시트는 `--force` 없이 덮지 않는다(`--force`면 `review_sheet.<시각>.bak.csv` 백업). `=`·`+`·`-`·`@`로 시작하는 칸은 엑셀 수식이 되지 않게 앞에 `'`를 붙인다. 엑셀에서 다시 저장할 때는 'CSV UTF-8' 형식으로 저장한다. 후보가 모자라 30행 또는 SAF 8행(`--n`·`--min-saf`)을 못 채우면 시트를 만들지 않고(2, 가능한 수를 알려 줌), check도 meta의 n·min_saf보다 작은 시트는 2로 본다. `--force` 백업은 배타 생성이라 같은 초에 다시 실행해도 기존 백업을 덮지 않는다(`-1`, `-2` 번호). 사람이 채우는 입력(`--review-true`, `--overrides`, `--reviewed-bases`)이 그 명령의 출력 파일과 같은 경로면 아무것도 쓰지 않고 종료 코드 2. review_sheet.meta.json은 시트와 같은 폴더에 둔다. meta가 없으면 check는 2.

  ```powershell
  # tutipal_ai/training 에서. $DS = C:\Users\<사용자>\tutipal_datasets
  # 1. 생성(Ollama: translategemma:4b, gemma3:4b). 오래 걸리므로 로그를 남기고 백그라운드로. 중단되면 같은 명령으로 이어서 한다.
  .\.venv\Scripts\python.exe -u -m data_tools.build_grading_c --v2 --out $DS\derived\grading_v2 --datasets-dir $DS\datasets `
      --eval-set eval/grading_set.jsonl --cache-from $DS\derived\grading_c `
      --review-true $DS\derived\grading_c\review_true_mohler.csv $DS\derived\grading_c\review_true_saf.csv $DS\derived\grading_c\review_true_scientsbank.csv
  # 2. 정리
  .\.venv\Scripts\python.exe -m data_tools.clean_grading_c --dir $DS\derived\grading_v2 --datasets-dir $DS\datasets `
      --eval-set eval/grading_set.jsonl --sources v2 --cap-by-source MohlerASAG=12 SAF-communication-networks=10 SciEntsBank=10
  # 3. 보강
  .\.venv\Scripts\python.exe -m data_tools.augment_tricks --dir $DS\derived\grading_v2 --eval-set eval/grading_set.jsonl `
      --datasets-dir $DS\datasets --max-science 250 --base-subjects 코딩
  # 4. 검사: 구성(코딩 ≥ 0.6, 꼼수 ≤ 0.15), dev 누수(봉인 세트 없이)
  .\.venv\Scripts\python.exe -m eval.trainmix --train $DS\derived\grading_v2\train_aug.jsonl
  .\.venv\Scripts\python.exe -m eval.leakcheck --train $DS\derived\grading_v2\train_aug.jsonl --dev eval/grading_set.jsonl --report results/leakcheck_v2
  # 5. 사람 확인 시트
  .\.venv\Scripts\python.exe -m data_tools.review_sheet make --dir $DS\derived\grading_v2 --datasets-dir $DS\datasets
  .\.venv\Scripts\python.exe -m data_tools.review_sheet check --sheet $DS\derived\grading_v2\review_sheet.csv
  ```

  봉인 세트 게이트(`leakcheck --sealed`)는 위 순서에 넣지 않는다. 학습 데이터 쪽은 v2 `train_aug.jsonl`의 sha256 만 봉인 관리자에게 넘기고, 봉인 관리자가 아래 [범위와 봉인 세트](#범위와-봉인-세트) 절차로 실행한다.
- 2026-11-25 2차 재생성: 새 바탕 답안 검수(`aug_bases_new_review.csv`)에서 제외 6개를 `BAD_BASES`에 넣고 다시 만들었다. 행 수·비율은 그대로(**893행**, 정답 259, 꼼수 133행 14.9%, leakcheck(dev) 실패 0)이고 바탕 답안 6개만 바뀌었다(4개는 이미 검수한 답안, 처음 보는 답안 2개는 `aug_bases_new_for_review.csv`에서 2026-11-25 검수: MohlerASAG-E11.Q05.A26 OK, MohlerASAG-E12.Q06.A00 WEAK 유지). 검수한 바탕 답안 전체 목록은 `derived/grading_c/reviewed_bases_all.csv`(139개), 이전 결과는 `derived/grading_c/prev_20261125b/`.

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

# 보고 범위(코딩 과목)만 채점. 과목 이름은 정확히 같아야 하고 여러 개를 줄 수 있다
.\.venv\Scripts\python.exe -m eval.run_eval --subjects 코딩 --run-name grade-coding

# Ollama 없이 기존 results.jsonl로 summary.md·summary.json·CSV만 다시 만든다(리포트 형식이 바뀌었을 때)
.\.venv\Scripts\python.exe -m eval.run_eval --run-name grade-baseline --report-only
```

- `--subjects`는 `grade` 과제에만 적용되고(필터한 뒤 `--limit`), 맞는 문항이 없거나 `--tasks`에 `grade`가 없으면 종료 코드 2로 끝난다. 과목 목록은 `run_config.json` 조건에 들어가므로, 같은 `--run-name`에서 과목을 바꾸면 이어서 할 수 없다(이 기능 전의 run은 필터 없이만 이어서 할 수 있다).
- `--report-only`는 `--run-name`, `--candidates`만 함께 쓸 수 있다(`--models`·`--subjects`·`--tasks`·`--limit`을 주면 2). `run_config.json`은 읽기만 하고(모델 순서), 라벨은 `--candidates`에서 가져온다. `results.jsonl`이 없거나 비었거나 형식이 깨졌으면 2.
- `--run-name`은 `results/` 안의 이름이어야 한다(절대 경로나 `..`로 밖을 가리키면 2).

결과는 `results/<run-name>/`에 남는다. 리포트 파일(summary.md·summary.json·grade_errors.csv·ko_review.csv)은 임시 폴더에 다 만든 뒤 한 묶음으로 바꾸며, 도중에 실패하면 전부 이전 상태로 되돌린다. 되돌리기마저 실패하면 `results/<run-name>/.staging-*/.backup/`에 원래 파일이 남고 그 경로가 오류 메시지에 나온다. 그 폴더의 파일을 `results/<run-name>/`에 복사해 복구한 뒤 `.staging-*` 폴더를 지운다.

- `summary.md`: 발표·포트폴리오용 비교표. 채점 표 맨 끝에 **정확도 95% CI**, **꼼수 방어 95% CI** 열(윌슨 구간, 같은 분모)이 있고, 그 아래 모델×과목 정확도 표와(답안에 `answer_form`이 있으면) 모델×형식(text·code) 표가 붙는다. **과목 표는 참고용**이다(과목마다 n이 작다)
- `summary.json`: 같은 내용의 숫자(`accuracy_ci`, `injection_defense_ci`, `by_subject`, `by_form` 포함)
- `results.jsonl`: 문항별 응답 원본
- `grade_errors.csv`: 잘못 채점한 문항 목록. 학습 데이터를 설계할 때 근거로 쓴다
- `ko_review.csv`: 한국어 답변을 사람이 채점하는 표

## 범위와 봉인 세트

개발 세트는 다과목 120건이고 프롬프트·학습 데이터를 만들며 여러 번 보게 되므로, 파인튜닝 효과는 **처음 보는 코딩 문항으로 만든 봉인(sealed) 세트**로 한 번만 잰다. 봉인 세트는 저장소에 넣지 않는다.

역할 분리(학습 데이터 v2부터):

- 봉인 세트는 학습 데이터를 만드는 담당(세션·사람)이 아닌 **별도 세션 또는 사람이 작성**하고, **봉인 관리자(사용자)가 보관**한다. 학습 데이터 쪽은 봉인 세트 파일을 열거나 경로를 알 필요가 없다.
- 라벨은 작성자 라벨을 보지 않은 채 **사용자가 블라인드로 다시 매기고**, 가능하면 두 라벨의 일치율을 남긴다.
- 누수 게이트 `leakcheck --sealed`는 **봉인 관리자가 실행**한다. 학습 데이터 쪽에는 봉인 세트의 sha256(과 게이트 통과 여부)만 공유한다.

1. 작성(위 역할 분리대로 학습 데이터 담당이 아닌 쪽에서): 코딩 과목, 한두 문장 주관식(짧은 코드 포함). 행마다 `answer_form`(`text`|`code`)을 넣는다.
2. 형식 검사: `python -m eval.gradeset <sealed.jsonl> --profile sealed` 가 0이어야 한다.
3. 누수 검사(봉인 관리자가 실행): `python -m eval.leakcheck --train <train.jsonl> --sealed <sealed.jsonl>` 가 0이어야 한다.
4. 기록: gradeset이 출력한 `sha256`과 `profile_version`을 일감(Jira)에 남긴다. 이후 파일이 바뀌면 다른 세트다.
5. 평가: 학습이 끝난 뒤 **모델당 한 번만** `run_eval --grade-set <sealed.jsonl> --sealed`로 잰다. 결과를 보고 학습·프롬프트를 고친 뒤 다시 재면 봉인 세트가 아니다.
6. 결과 보관: `--sealed` 실행은 `results.jsonl`에 지표·재개에 필요한 필드(과제·모델·과목·답안 유형·형식·라벨·판정·상태·속도)만 쓰고, 모델 응답 원문·피드백·오류 본문·qid는 쓰지 않으며 문항 id는 해시(`h:` + 16자)로 바꾼다. `grade_errors.csv`는 만들지 않으며 같은 폴더에 예전 것이 있으면 지우고, 진행 표시에도 id를 찍지 않는다. 그래도 `results/<run-name>/`는 **봉인 세트 관리자만 보관**하고, 팀·발표에는 `summary.md`·`summary.json`만 공유한다.

- 채점 세트에 `answer_form`이 있는 행이 하나라도 있으면 봉인 세트로 보고, `--sealed` 없이 실행하면 종료 코드 2로 멈춘다(개발 세트에는 `answer_form`이 없다). `--sealed`는 `--tasks grade` 하나로만 쓸 수 있다.
- 봉인 여부는 `run_config.json`의 재개 조건(`settings.sealed`)에 들어간다. 이 기능 전에 만든 run은 봉인 아님으로 보고 그대로 이어서 할 수 있다. `--report-only`는 `run_config.json`이나 기록을 보고 봉인 run이면 `grade_errors.csv`를 만들지 않는다.

- `gradeset`의 기본 profile `dev`는 stdout에 과목 이름별 개수를 출력한다. 봉인 세트에는 **반드시 `--profile sealed`**를 붙인다(과목 이름 대신 코딩 개수만 나온다).

`gradeset` sealed 규칙(분모는 전체 행 수, 경계값은 통과): 모든 행이 `코딩` 과목, `answer_form`이 `text`/`code`, 60행 이상, 문항(qid)마다 정답·오답이 하나 이상, 꼼수 답안 15% 이상, code 형식 20% 이상, 정답 25–45%. 공통 규칙(dev도 적용)은 필수 필드·타입, id 형식·중복, `kind`·`label` 일치, qid당 문항·모범 답안·과목이 하나인지다. 출력에는 숫자 통계·sha256·`profile_version`만 쓰고(sealed는 과목 이름 대신 코딩 개수), 오류는 `파일명:줄:필드 — 사유`로 전부 보여 준다(세트 단위 오류는 줄 자리가 `-`). id·qid·원문은 출력하지 않는다.

```powershell
.\.venv\Scripts\python.exe -m eval.gradeset eval\grading_set.jsonl                       # dev(기본): 0
.\.venv\Scripts\python.exe -m eval.gradeset <sealed.jsonl> --profile sealed
.\.venv\Scripts\python.exe -m eval.trainmix --train <train.jsonl>                        # 또는 TUTIPAL_GRADING_TRAIN
.\.venv\Scripts\python.exe -m eval.trainmix --train <train.jsonl> --min-coding-share 0.6 --max-trick-share 0.15
```

`trainmix`는 학습 데이터 구성을 숫자로만 보여 주고 검사한다: 코딩 과목 비율 ≥ `--min-coding-share`(기본 0.6), 꼼수 답안 비율 ≤ `--max-trick-share`(기본 0.15), 모든 행에 `messages`. 0.6은 학습 범위(코딩)와 맞추기 위한 목표치로, 다른 과목은 일반화를 위해 40% 이하로만 남긴다. 0.15는 지금 학습 데이터(`train_aug`)를 맞춘 꼼수 비율이자 봉인 세트의 꼼수 최소 비율과 같은 값이다.

| 도구 | 0 | 1 | 2 |
| --- | --- | --- | --- |
| `eval.gradeset` | 통과 | 내용 불량(형식·비율 규칙 위반) | 입력 오류(파일 없음, UTF-8 아님) |
| `eval.trainmix` | 통과 | 구성 위반 | 입력 오류(파일·형식, `--train` 없음, 비율 인자가 0~1 밖) |
| `eval.leakcheck` | 누수 없음 | 누수 | 입력·출력 오류(내용 형식 불량도 **2**) |
| `eval.run_eval` | 완료 | - | 실행 불가(Ollama·모델·Docker, 조건 충돌, `--subjects`·`--report-only` 오류) |

`gradeset`은 형식 불량을 검사 결과(1)로 보지만, `leakcheck`는 형식이 맞아야 비교할 수 있어 같은 문제를 입력 오류(2)로 낸다.

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
- SciEntsBank(`nkazi/SciEntsBank`, 채점 학습 데이터): CC BY 4.0. Dzikovska et al., SemEval-2013 Task 7 (https://aclanthology.org/S13-2045), https://huggingface.co/datasets/nkazi/SciEntsBank. 한국어로 번역·수정했다(LLM 번역, 질문의 정답 표시 제거 등 검수 반영).
- Mohler ASAG(`nkazi/MohlerASAG`, 채점 학습 데이터): CC BY 4.0. Mohler, Bunescu & Mihalcea, ACL 2011 (https://aclanthology.org/P11-1076), https://huggingface.co/datasets/nkazi/MohlerASAG. 한국어로 번역·수정했다.
- SAF communication networks(`Short-Answer-Feedback/saf_communication_networks_english`, 채점 학습 데이터): CC BY 4.0. Filighera et al., ACL 2022 (https://aclanthology.org/2022.acl-long.587). 한국어로 번역·수정했다.
- 위 세 데이터의 번역본은 저장소에 넣지 않는다(`tutipal_datasets/derived/`). 저장소에는 검수 결과의 행 id와 질문 수정용 짧은 번역 문구(`data_tools/augment_tricks.py`의 `QUESTION_FIXES`)만 있다.
