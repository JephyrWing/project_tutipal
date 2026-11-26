# PT-86 RAG 재순위화 · 청킹 평가

- Jira: [PT-86](https://gad03302.atlassian.net/browse/PT-86) [AI-004] · 계약: `docs/AI_WORKFLOW_CONTRACT.md` 3.1 · 재인덱싱: `docs/RAG_REINDEX.md`
- 요약 JSON(PT-89 연결용): `docs/eval/PT-86_rag_eval_results.json` · 질의별 원자료: `tutipal_back/build/rag-eval/results/*.jsonl` (커밋하지 않음, 아래 명령으로 다시 만든다)
- 기준 커밋: `9dab746` (develop) + 이 브랜치 작업본. 측정일 2026-11-25 (KST).

## 1. 환경과 방법

| 항목 | 값 |
| --- | --- |
| 기기 | 노트북 AMD Ryzen 9 5900HX, Windows 11, 메모리 15.4GB 중 여유 0.5~3GB. **다른 세션이 함께 돌아 CPU 사용률 30~65%였다** (지연 수치가 흔들린다) |
| Spring 경로 | 실제 `RagSearchService`(이 브랜치) + 실제 `RerankClient`(HTTP) + 실제 `MaterialTextExtractor`·`Chunker`. 저장소만 메모리 정확 코사인 대역(`InMemoryRagSearchRepository`, `findNearest`와 같은 조건: 수업·임베딩 모델·인덱싱 여부, 코사인 거리 오름차순, LIMIT). 두 수업의 조각을 함께 넣어 누출을 잰다 |
| 임베딩 | 로컬 FastAPI(이 브랜치) → Ollama `bge-m3`, 모델 문자열 `ollama:bge-m3:1024`. 조각·질의 임베딩을 한 번 계산해 `build/rag-eval/cache`에 (청커, 모델, sha256(text)) 키로 저장, 검색 단계는 네트워크 없이 캐시만 쓴다. 임베딩 뒤 `bge-m3`를 내린 다음 재순위화를 돌렸다 |
| 재순위화 | 로컬 FastAPI `/v1/rerank`, ONNX Runtime 1.32.0 CPU, `RERANK_THREADS=2`. Python 3.12.10 venv |
| 코퍼스 | `src/ragEval/resources/rag-eval/corpus/session-a`, `session-b`의 10개 문서(MD 6 · TXT 2 · PDF 2, 위키백과 5 · 팀 작성 5). `MANIFEST.md`·`SOURCES.md`는 넣지 않음 |
| 질의 | `queries-dev.jsonl` 20개(답 없음 4), `queries-sealed.jsonl` 30개(답 없음 6). dev만 보고 결정을 고정한 뒤 sealed를 한 번 돌린다 |
| 검색 설정 | 튜터와 같은 `limit=3`, 유사도 0.5 미만 제외, `min-candidates=2`. 측정용 재순위화 timeout 10s(모델 자체 품질을 보려고). 운영 timeout 2s였다면 전환됐을 질의 수는 `>2s` 열에 따로 적는다 |

**지표**

- Hit@3: 답 있는 질의 중 상위 3개에 정답 조각이 있는 비율. 정답 = 같은 파일(PDF는 같은 쪽)이고, 공백을 정규화한 anchor 문장이 조각 안에 통째로 들어 있음. `multi`는 정답 중 하나만 맞아도 적중.
- MRR@3: 상위 3개 안 첫 정답의 역순위 평균(답 있는 질의). MRR@N: 재순위화 뒤 후보 전체(N개)에서 같은 값. 하네스는 `search(…, limit=max(3,N))`로 부르고 앞 3개를 상위 3개로 쓴다. 두 경로 모두 앞부분이 `limit=3` 결과와 같다(코사인은 정확 정렬, 재순위화는 후보 N을 정렬한 뒤 자름).
- 출처 정합성: 답 있는 질의에서 돌려준 조각 중 (파일, 쪽)이 정답 출처인 비율.
- 답 없음 정확도: 답 없는 질의에서 빈 목록(일반 답변 전환)을 돌려준 비율.
- 누출: 다른 수업 조각이 섞인 질의 수.
- 지연: `search()` 전체와 재순위화 HTTP 호출만, 앞의 3개 질의를 한 번 더 돌린 예열은 빼고 잼. p50/p95.
- 자원: FastAPI 실제 Python 프로세스의 작업 집합(최대값)과 CPU 시간(Windows 카운터, `snap.ps1`). CPU-s/질의 = 한 행 동안 늘어난 CPU 시간 ÷ 검색 호출 수(예열 포함 23회).

**재현 명령** (`tutipal_back`에서, JDK 21)

```sh
# 1) 임베딩 서버 (tutipal_ai/.venv)
MODEL_PROVIDER=ollama MODEL_BASE_URL=http://127.0.0.1:11434 EMBEDDING_MODEL=bge-m3 AI_INTERNAL_KEY=local-eval-key \
  .venv/Scripts/python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 18086
export AI_INTERNAL_KEY=local-eval-key   # 하네스는 Spring처럼 환경 변수에서 내부 키를 읽는다 (--key는 로컬 덮어쓰기용)
./gradlew --no-daemon ragEval -PevalArgs="embed --base-url http://127.0.0.1:18086"
curl localhost:11434/api/generate -d '{"model":"bge-m3","keep_alive":0}'      # 임베딩 모델 내리기
# 2) 코사인 행
./gradlew --no-daemon ragEval -PevalArgs="search --split dev --chunker section-v1 --mode cosine"
# 3) 재순위화 서버 + 행 (모델 파일은 tutipal_ai/docker/fetch_reranker.py 로 받는다, 인자는 Dockerfile)
AI_INTERNAL_KEY=local-eval-key RERANK_ENABLED=true RERANK_THREADS=2 RERANK_MAX_LENGTH=256 RERANK_MODEL_DIR=<models/a> \
  .venv/Scripts/python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 18087
./gradlew --no-daemon ragEval -PevalArgs="search --split dev --chunker section-v1 --mode rerank --label rerank-a-len256 \
  --candidates 10 --rerank-url http://127.0.0.1:18087 --timeout 10s"
# 청커 출력 지문 (청커 코드가 바뀌어도 코퍼스 결과가 같은지 확인)
./gradlew --no-daemon ragEval -PevalArgs="chunks"
# 4) BM25 탐색 (dev만)
./gradlew --no-daemon ragEval -PevalArgs="bm25 --split dev --chunker char800-v1"
# 5) 요약 JSON
python src/ragEval/scripts/aggregate_results.py .. src/ragEval/scripts/PT-86_extras.json
```

`ragEval`은 `test`·`build`에 들어가지 않는다.

**sealed는 한 번만 점수를 낸다** (`SealedRunGuard`, Codex 2차 뒤 정리)

1. 사전 점검을 모두 통과해야 실행 표지 `build/rag-eval/results/<행>.done`을 쓴다. 표지는 첫 sealed 질의를 점수화하기 직전에 쓴다. 점검 항목:
   - `queries-sealed.jsonl` sha256이 `sealed.sha256`과 같을 것. 다르면 0이 아닌 종료 코드로 중단한다. 다시 동결하는 것은 결정 사항이다(예: 11/28 검토 뒤).
   - 이 문서에 동결 결정 절이 있을 것.
   - 그 청커의 모든 조각과 모든 sealed 질의의 임베딩이 캐시에 있을 것.
   - 재순위화 행이면, 평가 질의가 아닌 합성 쌍 하나로 `/v1/rerank` 응답을 받을 것.
2. 표지를 쓴 뒤 실행이 실패하면 `<행>.failed`에 시각, 예외 종류, 메모리에서 점수화한 질의 수, 결과 저장 여부를 남긴다.
3. 표지가 있는 행은 결과 파일 `<행>.jsonl`이 없고 마지막 실패 기록이 `results_persisted=false`일 때만 다시 실행할 수 있다. 결과는 실행 끝에 한 번만 저장하고, 중간 점수는 화면에도 내지 않는다. 다시 실행하면 이전 실패 기록을 `<행>.failed-<시각>`으로 남긴다. 표지는 있는데 실패 기록이 없으면(강제 종료 등) 다시 실행하지 않는다.
4. 이미 끝난 sealed 4행은 결과 파일이 있으므로 다시 실행할 수 없다.

같은 해시 검사만 따로 돌리는 명령은 `ragEval -PevalArgs="sealed-check [--root DIR]"`이다. 해시 중단과 위 규칙은 `./gradlew ragEvalTest`(`RagEvalGuardsTest`, CI에서 실행)가 임시 폴더로 확인한다. Codex 1차 뒤 수동으로도 확인했다: 실제 파일은 `match=true`·종료 0이었고, 질의 한 줄을 덧붙인 사본은 `IllegalStateException`·종료 1이었다.

**인덱싱(임베딩) 시간과 조각 수**

| 청커 | 조각 수 | 임베딩 시간 (bge-m3, 32개씩) |
| --- | ---: | ---: |
| char800-v1 | 95 | 6.6 s (모델은 직전 호출로 이미 올라와 있음) |
| section-v1 | 131 (+38%) | 8.0 s |

## 2. dev 결과 (20개, 답 있음 16 · 답 없음 4)

| 청커 | 방식 | N | Hit@3 | MRR@3 | MRR@N | 출처 정합성 | 답 없음 | 누출 | search p50/p95 ms | 재순위화 p50/p95 ms | >2s | CPU-s/질의 | FastAPI 최대 작업 집합 |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| char800-v1 | 코사인 | – | 12/16 | 0.750 | 0.763 | 0.842 | 0/4 | 0 | 2.8 / 4.2 | – | – | – | – |
| section-v1 | 코사인 | – | 14/16 | 0.813 | 0.828 | 0.825 | 0/4 | 0 | 2.8 / 5.0 | – | – | – | – |
| char800-v1 | a (512) | 20 | 13/16 | 0.813 | 0.813 | 0.711 | 0/4 | 0 | 1569 / 5141 | 2531 / 8877 | 9/16 | 4.4 | 1048 MB |
| section-v1 | a (512) | 20 | 13/16 | 0.781 | 0.804 | 0.775 | 0/4 | 0 | 1507 / 5632 | 1903 / 6873 | 8/17 | 4.0 | 1051 MB |
| char800-v1 | a (512) | 10 | 13/16 | 0.771 | 0.771 | 0.711 | 0/4 | 0 | 2052 / 4335 | 2507 / 4692 | 11/16 | 3.8 | 1052 MB |
| section-v1 | a (512) | 10 | 13/16 | 0.781 | 0.813 | 0.825 | 0/4 | 0 | 1703 / 3479 | 2249 / 3754 | 10/17 | 3.2 | 1052 MB |
| char800-v1 | a (256) | 10 | 12/16 | 0.719 | 0.728 | 0.711 | 0/4 | 0 | 872 / 1808 | 1016 / 1896 | 0/16 | 1.6 | 568 MB |
| section-v1 | a (256) | 10 | **15/16** | **0.823** | 0.823 | 0.825 | 0/4 | 0 | 1049 / 2079 | 1187 / 2155 | 5/17 | 1.9 | 570 MB |
| char800-v1 | b (512) | 10 | 12/16 | 0.750 | 0.766 | 0.763 | 0/4 | 0 | 14397 / 30014 | 17848 / 30012 (timeout 30s, 3회 초과) | 16/16 | ≈26 | 1217 MB |

- 모델 a = `cross-encoder/mmarco-mMiniLMv2-L12-H384-v1` `onnx/model_quint8_avx2.onnx`(113MB), 괄호는 `RERANK_MAX_LENGTH`. b = `onnx-community/bge-reranker-v2-m3-ONNX` `onnx/model_int8.onnx`(544MB). 둘 다 고정 revision, sha256 확인.
- FastAPI 기준(재순위화 꺼짐) 작업 집합 52.8MB. 모델 a 적재 직후 464MB, 최대 길이 512에서 후보 20개를 처리하면 ONNX 메모리 풀이 커져 약 1.05GB(사적 메모리 2.06GB)까지 늘었다. 길이 256에서는 570MB.
- 512 행의 CPU-s에는 ORT 스레드 스핀 대기가 들어 있다. 256 행부터 `session.intra_op.allow_spinning=0`을 켰다(`rerank_service.py`).
- 재순위화 지연은 0.5 필터를 통과한 후보 수에 거의 비례한다(512 토큰에서 조각당 약 0.35s). 그래서 N=10과 N=20의 차이가 작다. 0.5를 넘는 후보가 대개 10개 안팎이기 때문이다.
- **b의 N=20은 측정하지 않았다**: N=10에서 이미 p50 17.8s, 3/16 시간 초과. 후보 수에 비례하므로 N=20은 약 2배로 예상되며, 공유 노트북에서 2코어를 20분 넘게 점유해야 한다.
- **c(`BAAI/bge-reranker-base` fp32 → 동적 int8)는 측정하지 않았다(RAM)**: 오프라인 양자화는 1.1GB fp32 그래프를 메모리에 올리고 사본을 만든다. 필요한 여유(약 2.5GB 이상)가 측정 시점(0.5~1.4GB)에 없었다. XLM-R base(12층 · 768)는 a보다 토큰당 연산이 약 4배라, 512 토큰이면 a의 512 행보다 느릴 것으로 본다(추정, 측정 아님).
- 답 없음 정확도는 모든 행에서 0/4다. bge-m3는 답 없는 질문에도 같은 주제 조각에 0.5 넘는 유사도를 준다. 재순위화는 0.5 필터 뒤에 순서만 바꾸므로 이 값을 바꿀 수 없다(리더 위험 항목과 일치).
- 누출은 모든 행에서 0이다(대역 저장소의 수업 필터; 실제 SQL은 CI `ragDbTest`가 확인).

**BM25 탐색 (dev만, spec 11 — Spring에 넣지 않음)**: 글자 2-gram BM25(k1=1.2, b=0.75, 같은 수업 안)와 코사인 상위 20의 RRF(k=60).

| 청커 | 코사인 Hit@3 / MRR@3 | BM25 Hit@3 / MRR@3 | RRF Hit@3 / MRR@3 |
| --- | --- | --- | --- |
| char800-v1 | 12/16 / 0.750 | 15/16 / 0.875 | 14/16 / 0.813 |
| section-v1 | 14/16 / 0.813 | 15/16 / 0.865 | 15/16 / 0.875 |

용어·숫자 질의가 많은 코퍼스에서 BM25가 코사인보다 강했다. CPU 비용이 거의 없으므로(모델 없음) **후속 카드로 Spring 하이브리드(PostgreSQL 전문 검색 또는 pg_trgm + RRF)를 제안한다.** dev 20개만 본 결과라 채택 근거로 쓰지 않는다. 답 없음 판정은 BM25로도 해결되지 않는다(0/4).

## 3. 동결 결정 (dev만 보고 sealed 전에 기록, 2026-11-25 12:33 KST 이전 — sealed 첫 행 실행 12:33:42)

1. **청커 후보: `section-v1`.** dev 코사인에서 Hit@3 +2(규칙상 잡음 범위), MRR@3 +0.063. 출처 정합성은 0.842 → 0.825로 소폭 낮다. 채택 여부는 sealed에서 규칙으로 판정한다.
2. **재순위화 모델: a (`mmarco-mMiniLMv2-L12-H384-v1` quint8).** b는 지연이 예산(1.5s)의 10배를 넘고, c는 측정하지 못했다.
3. **후보 수 N=10, `RERANK_MAX_LENGTH=256`, 스레드 2.** dev에서 512는 p95 3.8~8.9s로 예산을 크게 넘었다. 256·N=10은 지연이 약 절반이고 section-v1에서 품질이 가장 좋았다(15/16, 0.823).
4. **sealed 실행**: 2×2 = {char800-v1, section-v1} × {코사인, a(256)·N=10}을 각 한 번. 측정 timeout 10s. 운영 2s에서 전환됐을 질의 수(`>2s`)도 함께 적는다.
5. **판정 방법 (spec 9를 그대로 적용, sealed만)**
   - 청커: 코사인 열에서 section-v1 vs char800-v1. MRR@3 +0.05 이상 또는 Hit@3 +3 질의 이상, 그리고 답 없음 정확도와 출처 정합성이 나빠지지 않을 것.
   - 재순위화: 채택된 청커(미채택이면 char800-v1)에서 a vs 코사인. 같은 품질 규칙에 더해 재순위화 p95 ≤ 1.5s(2스레드, 이 노트북), t3.large 보정 추가 지연 ≤ 2s, FastAPI 작업 집합 증가 ≤ 1.2GB를 모두 만족할 것.
   - 하나라도 못 넘으면 플래그를 끈 채(기본값 유지) 병합하고 수치를 그대로 보고한다.

## 4. sealed 결과 (30개, 답 있음 24 · 답 없음 6, 각 행 한 번)

- 해시 확인: `sealed.sha256` 기록값 = 실제 `queries-sealed.jsonl` sha256 = `a5a65b9f…98fc984b` (일치, 네 행 모두 기록).
- 실행 시각: 2026-11-25 12:33:42(char800 코사인), 12:34:05(section 코사인), 12:35:21(char800 a), 12:36:52(section a) KST.
- 재순위화 = 모델 a, `RERANK_MAX_LENGTH=256`, N=10, 스레드 2, 측정 timeout 10s. 코사인 행의 MRR@N은 N=10(dev 코사인 행은 N=20).

| 청커 | 방식 | Hit@3 | MRR@3 | MRR@N | 출처 정합성 | 답 없음 | 누출 | search p50/p95 ms | 재순위화 p50/p95 ms | >2s | CPU-s/질의 | FastAPI 최대 작업 집합 |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| char800-v1 | 코사인 | 20/24 | 0.708 | 0.726 | 0.806 | 0/6 | 0 | 2.7 / 3.7 | – | – | – | 52.8 MB (꺼짐 기준) |
| section-v1 | 코사인 | 20/24 | 0.771 | 0.790 | 0.806 | 0/6 | 0 | 1.7 / 3.3 | – | – | – | 52.8 MB |
| char800-v1 | a (256) N=10 | 22/24 | 0.778 | 0.778 | 0.806 | 0/6 | 0 | 663 / 1368 | 797 / 1366 | 0/26 | 1.3 | 586 MB |
| section-v1 | a (256) N=10 | 22/24 | 0.806 | 0.806 | 0.823 | 0/6 | 0 | 1004 / 2181 | 1345 / 2179 | 2/25 | 2.1 | 587 MB |

운영 timeout 2s를 가정해 2s를 넘긴 질의를 같은 청커의 코사인 결과로 바꿔 계산해도 sealed 값은 같다(section-v1: 바뀐 2개 질의의 적중 여부가 같았음).

## 5. 채택 판정 (3절 규칙을 그대로 적용)

**청커 — 채택 조건 충족: `section-v1`** (코사인 열, section-v1 vs char800-v1)

| 조건 | 값 | 결과 |
| --- | --- | --- |
| MRR@3 +0.05 이상 또는 Hit@3 +3 이상 | MRR@3 +0.063 (0.708 → 0.771), Hit@3 +0 | 충족 (MRR) |
| 답 없음 정확도가 나빠지지 않음 | 0/6 → 0/6 | 충족 |
| 출처 정합성이 나빠지지 않음 | 0.806 → 0.806 | 충족 |
| 참고 (지연 규칙 없음) | 조각 95 → 131 (+38%), 임베딩 6.6 → 8.0 s | – |

**재순위화 — 채택 조건 미충족: 플래그 `tutipal.rag.rerank.enabled=false` 유지** (채택된 청커 section-v1에서 a vs 코사인)

| 조건 | 값 | 결과 |
| --- | --- | --- |
| MRR@3 +0.05 이상 또는 Hit@3 +3 이상 | MRR@3 +0.035 (0.771 → 0.806), Hit@3 +2 (잡음 범위) | **미충족** |
| 답 없음 · 출처 정합성이 나빠지지 않음 | 0/6 → 0/6, 0.806 → 0.823 | 충족 |
| 재순위화 p95 ≤ 1.5 s (2스레드, 이 노트북) | 2.18 s | **미충족** |
| t3.large 보정 추가 지연 ≤ 2 s | 추정 p50 2.0~3.4 s, p95 3.3~5.4 s (6절) | **미충족** |
| FastAPI 작업 집합 증가 ≤ 1.2 GB | +534 MB | 충족 |

참고만: char800-v1 열에서는 a가 MRR@3 +0.070, 재순위화 p95 1.37 s로 노트북 기준을 넘었다. 규칙은 채택된 청커에서 판정하므로 결정에 쓰지 않는다. t3.large 보정 p95도 2.0~3.4 s로 추가 지연 기준을 넘을 가능성이 크다.

**코드 반영**

- 재순위화: 기본값 유지(꺼짐). `/v1/rerank`, `RerankClient`, Docker 모델 고정은 그대로 병합해 두고 나중에 다시 측정할 수 있게 한다. 코드 기본값은 리더 판정으로 측정값(N=10, 256, 대기 1000ms)과 같다. 후속: t3.large 실측(ORT 메모리 풀 끄기, `RERANK_THREADS` 1 대 2) 뒤 다시 판정하고, 채택하면 `--build-arg WITH_RERANKER=true` 빌드를 확인한다.
- 청커: **리더 판정(강지, 2026-11-25) — 전환 기각, 기본값 `char800-v1` 유지.** 규칙은 충족했지만 리더 자신의 spec 2·9와 다르게 진행하며 이 결정을 기록으로 남긴다. 근거: ① 판정 근거가 답 있는 질의 24개에서 MRR@3 +0.063 하나뿐이고 Hit@3는 +0이다. ② 수정 전후 지문이 같다는 것은 코퍼스가 리뷰에서 고친 경로(번호 목록, 코드 블록, 짧은 꼬리·절)를 한 번도 거치지 않았다는 뜻이다. 그 수정은 단위 테스트로만 검증됐다. ③ sealed는 11/28 동결과 PT-29 표본 전까지 잠정이다. ④ 한 수업에 두 청커 조각이 섞인 상태와 PT-85 답변 영향을 재지 않았다(마시로 소수 의견).
  - 후속 카드 조건 (청커 전환): 11/28 질의 동결과 PT-29 실제 수업 자료 표본이 나온 뒤 sealed 재측정 → PT-85 튜터 답변 확인(제목 접두어 영향) → `docs/RAG_REINDEX.md` 절차로 전환. 전환 때 `application.yaml`·compose·`.env` 예시·계약 3.1과 `RagRerankPropertiesTest.applicationYamlDefaultsChunkerToChar800` 기대값을 함께 바꾼다. 실제 자료에서 "1.1 배경\n1.2 목적\n본문"처럼 본문 없이 같은 깊이 제목이 이어지면 앞 제목이 본문으로 들어가는지도 확인한다.
  - 주의: 판정 근거는 MRR 차이 하나(+0.063)뿐이고 Hit@3은 같다. 답 있는 질의 24개, 같은 팀이 만든 코퍼스다(7절). 마시로의 소수 의견대로 제목 접두어 줄이 PT-85 튜터 답변에 주는 영향은 측정하지 않았다.

## 6. 비용

| 항목 | 값 | 근거 |
| --- | --- | --- |
| Docker 이미지 증가 (추정) | **약 +300 MB** = 패키지 설치 크기 163 MB + 모델 a 136 MB (`model.onnx` 118.6 MB + `tokenizer.json` 17.1 MB) | manylinux wheel 15개(onnxruntime 75.7 MB, numpy 52.8 MB, tokenizers 11.7 MB, hf-xet 11.7 MB, 나머지 11 MB)를 받아 압축 해제 크기를 합산. 로컬에 Docker가 없어 실제 이미지는 재지 않았다 |
| wheel 내려받기 크기 | 54.1 MB | 같은 wheel |
| FastAPI 작업 집합 | 꺼짐 52.8 MB → a 적재 직후 464 MB → a(256) 최대 587 MB / a(512) 최대 1052 MB / b(512) 최대 1217 MB | Windows `PeakWorkingSet64` |
| CPU-s/질의 (노트북) | a(256) 1.3~2.1, a(512) 3.2~4.4 (스핀 포함), b(512) 약 26 | Windows `TotalProcessorTime` 차이 ÷ 23~33회 호출 |
| 재순위화 지연 (노트북 실측) | a(256) N=10: p50 0.8~1.3 s, p95 1.4~2.2 s | 4절 |
| **t3.large 지연 (추정, 측정 아님)** | a(256) N=10 재순위화 — section-v1(판정 행): p50 2.0~3.4 s, p95 3.3~5.4 s / char800-v1: p50 1.2~2.0 s, p95 2.0~3.4 s (sealed 노트북 실측 × 1.5~2.5). CPU 크레딧이 바닥나면(기준 성능 30%) 그 몇 배 | 가정: t3.large 2 vCPU는 물리 코어 1개의 하이퍼스레드 2개(Xeon 2.5~3.1 GHz)이고, 노트북 2스레드는 서로 다른 물리 코어(최대 4.6 GHz)에서 돈다. 그래서 1.5~2.5배 느리다고 본다. 반대로 노트북 측정 때 다른 세션이 CPU를 30~65% 쓰고 있어 실측이 부풀었을 수 있다. 양쪽 불확실성을 모두 범위에 넣었다 |

| **t3.large CPU 크레딧 (추정)** | 기준 성능 30% × 2 vCPU = 시간당 약 2160 vCPU-s. 재순위화 1회 2.0~5.3 vCPU-s(노트북 1.3~2.1 × 1.5~2.5)라면, Spring·DB·임베딩을 빼고도 시간당 약 400~1000회에서 기준선을 다 쓴다 | 계산 |
| 사적 메모리 (Windows private bytes) | a(256) 1082 MB (작업 집합 587 MB), a(512) 2064 MB | ONNX 메모리 풀이 예약한 양. Linux RSS는 실제로 건드린 페이지만 세므로 작업 집합에 더 가까울 것으로 보지만 컨테이너에서 확인하지 않았다 |
| 동시 요청 | 재순위화는 한 번에 하나. 측정 뒤(리뷰 1차, 리더 판정) 대기 상한 `RERANK_QUEUE_TIMEOUT_MS=1000` → 503 → Spring 코사인 전환, 끊긴 요청은 추론하지 않음. 1500ms에서는 503이 약 2.2s에 도착해 Spring 2s를 넘었다(리제 재현). 남은 위험: 약 1s 기다린 뒤 시작한 요청 하나는 2s를 넘겨 계속 돌 수 있다(최대 1개) | 동시 채팅이 몰리면 순위 개선 없이 코사인으로 돌아간다. 동시성 지연은 재지 않았다 |
| section-v1 인덱싱 부하 | 조각 +38% → 임베딩 호출·AI 슬롯 사용·정확 스캔 행이 +38% | 전환할 때 인덱싱 시간과 검색 p95를 다시 기록한다 |

결론: 모델 a도 t3.large에서 채팅 요청마다 1~5 s를 더하고, Spring·DB와 같은 2 vCPU를 나눠 쓴다. 지금 품질 이득(+0.035 MRR)으로는 정당화되지 않는다. b·c 급 모델은 GPU 없이는 불가능하다.

## 6-1. 리뷰 1차 뒤 변경과 측정값의 유효성

- 청커 `section-v1`의 내용 손실 수정(번호 목록 줄, 코드 블록 안 `#`, 긴 문장 꼬리, 같은 쪽에서 닫힌 짧은 절, 제목 접두어 길이)을 적용한 뒤에도 코퍼스 청킹 결과는 바이트 단위로 같다: `ragEval chunks` 지문 char800-v1 95개 `664db060…`, section-v1 131개 `75363995…`가 수정 전후 동일. 그래서 캐시된 임베딩과 dev·sealed 수치를 그대로 쓴다(재실행 없음). 코퍼스 MD·TXT에는 번호 목록 줄과 코드 블록이 0개다. 짧은 절 유지 규칙은 처음 버전에서 PDF 쪽 머리의 꼬리말 5개를 새 조각으로 만들어서, "같은 쪽의 제목으로 시작한 절만"으로 좁힌 뒤 지문이 같아졌다.
- FastAPI 대기 상한·연결 끊김 처리와 ORT 스핀 끄기는 측정 방식(요청을 하나씩 순서대로 보냄)에 영향이 없다. 단 스핀 끄기는 256 행부터 적용돼 있었다(1절).
- 설정 기본값: 리더 판정으로 코드 기본값을 측정값과 같게 했다 — `application.yaml` `candidates: 10`, `settings.py` `RERANK_MAX_LENGTH=256`, `RERANK_QUEUE_TIMEOUT_MS=1000`. compose·env 예시·Dockerfile도 같은 값이다. 측정하지 않은 20/512는 dev p95 3.8~8.9s로 2s를 넘었다. 계약 테스트 기대값 20 → 10은 리더가 약화가 아니라고 승인했다.

## 7. 한계

- **메모리 대역 저장소**: 검색 순서는 정확 코사인 메모리 대역(`src/test/.../rag/repository/InMemoryRagSearchRepository`, 하네스와 테스트가 같은 클래스를 씀)에서 쟀다. 실제 SQL(세션·모델·인덱싱 필터, 코사인 순서, V20 기본값)은 `RagSearchRepositoryPostgresTest`(Gradle `ragDbTest`, CI 일회용 pgvector)가 테스트 자체의 참조 구현과 이 대역 클래스 둘 다와 순서가 같은지 확인한다. 이 PC에는 Postgres가 없어 CI에서 처음 돌게 된다.
- **코퍼스**: 위키백과 5 + 팀 작성 5, 약 5.6만 자의 작은 합성 코퍼스다. 실제 수업 자료(PT-29 표본)가 아직 없다. 질의와 정답도 같은 팀이 만들어 편향이 있을 수 있다.
- **sealed 해시는 잠정**: 11/28 사용자 검토에서 질의가 바뀌면 sealed를 다시 고정하고 다시 돌려야 한다. 이번 sealed 결과는 그 전까지의 잠정값이다.
- **표본 크기**: 답 있는 질의 dev 16, sealed 24. Hit 1~2개 차이는 잡음이다(규칙상 ≤2).
- **답 없음 0%**: bge-m3와 0.5 임계값으로는 답 없는 질문을 하나도 걸러내지 못했다(dev 0/4, sealed 0/6). 재순위화·청킹으로는 고칠 수 없다. 임계값 재조정(PT-29) 또는 재순위화 점수 임계값은 별도 과제다.
- **지연**: 공유 노트북(다른 세션 실행 중)에서 쟀다. t3.large 값은 추정이다. 512 행과 256 행 사이에 ORT 스핀 끄기가 들어가 CPU-s 비교에 섞였다.
- **측정하지 못한 것**: c(RAM), b의 N=20(지연), 임베딩 모델 적재 시간(이미 올라와 있던 상태에서 쟀음).
- **BM25**: dev 20개 탐색만 했고 Spring에는 넣지 않았다(spec 11). 후속 카드 제안.
