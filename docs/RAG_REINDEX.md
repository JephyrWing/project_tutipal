# RAG 자료 재인덱싱 절차 (PT-86 · PT-29)

- 대상: 청커(`tutipal.materials.chunker`)나 임베딩 입력 전처리를 바꾼 뒤 이미 인덱싱된 자료의 조각을 새 방식으로 다시 만드는 경우.
- 계약: `docs/AI_WORKFLOW_CONTRACT.md` 3.1. 임베딩 모델·문서 입력 전처리·벡터 호환성 기준은 [PT-29](https://gad03302.atlassian.net/browse/PT-29)를 따르고, 이 문서는 PT-86에서 추가한 청커 전환 절차만 다룬다.
- 강제 절차가 아니다. 청커를 바꾸지 않으면 할 일이 없고, 바꾼 뒤에도 기존 조각은 계속 검색된다. 새로 올리는 자료부터 새 청커가 적용된다.

## 1. 알아 둘 동작

| 항목 | 동작 |
| --- | --- |
| 청커 기록 | `material_chunks.chunker` (V20, 기존 행 기본값 `char800-v1`). 검색 필터가 아니다 |
| 출처 격리 | 검색은 계속 같은 수업(`session_id`) + 같은 `embedding_model` + `materials.indexed_at IS NOT NULL` 조건으로만 한다 |
| 다시 인덱싱 표시 | `materials.status`를 `PROCESSING`으로 바꾼다. `indexed_at`은 지우지 않으므로 교체 전까지 기존 조각이 그대로 검색된다 |
| 교체 | `MaterialIndexWorker`가 한 트랜잭션에서 자료를 `DONE`으로 바꾸고 `DELETE` → 새 조각 `INSERT`를 한다. 실패하면 롤백되어 기존 조각이 남는다 |
| 실패 | 임베딩 일시 장애는 10초·30초 뒤 다시 시도하고, 그래도 실패하면 `FAILED`(`index_retryable=true`). 이때도 기존 조각과 `indexed_at`은 남아 검색된다 |
| 부하 | 작업자는 1초마다 최대 32개 자료를 순서대로 처리하고 임베딩은 AI 슬롯(`app.ai.max-concurrent`)을 같이 쓴다. 수업 시간에는 피한다 |

## 2. 절차

1. **청커 설정을 바꾸고 배포한다.** `TUTIPAL_MATERIALS_CHUNKER=section-v1` (또는 `application.yaml`의 `tutipal.materials.chunker`). 허용 값이 아니면 Spring이 시작하지 않는다.
2. **대상 수를 확인한다.**

   ```sql
   SELECT c.chunker, count(*) AS chunks, count(DISTINCT c.material_id) AS materials
   FROM material_chunks c GROUP BY c.chunker;
   ```

3. **자료를 다시 인덱싱 대기로 돌린다.** 둘 중 하나를 쓴다.
   - 자료별: 선생님 계정으로 `POST /api/materials/{id}/index` (기존 "다시 인덱싱" 동작, 수업 선생님 권한 확인 포함).
   - 운영 일괄 (DB 관리자, 작은 묶음으로 나눠 실행). `SET` 절은 `MaterialRepository.queueIndex`와 같아야 한다. 그 SQL이 바뀌면 여기도 함께 고친다:

     ```sql
     UPDATE materials
     SET status = 'PROCESSING', error = NULL, index_retryable = FALSE, index_retry_count = 0,
         index_retry_at = NULL, updated_at = statement_timestamp()
     WHERE status = 'DONE' AND id IN (
         SELECT m.id FROM materials m
         WHERE m.status = 'DONE'
           AND EXISTS (SELECT 1 FROM material_chunks c WHERE c.material_id = m.id AND c.chunker <> 'section-v1')
         ORDER BY m.id LIMIT 50);
     ```

4. **진행을 지켜본다.** `PROCESSING`이 0이 될 때까지 기다린다. `FAILED`가 생기면 `error`를 보고 3단계 자료별 방법으로 다시 시도한다.

   ```sql
   SELECT status, count(*) FROM materials GROUP BY status;
   ```

5. **완료를 확인한다.** 아래 값이 0이면 끝이다 (`'section-v1'`은 목표 청커로 바꿔 쓴다).

   ```sql
   SELECT count(*) FROM material_chunks WHERE chunker <> 'section-v1';
   ```

## 3. 되돌리기

- 설정을 `char800-v1`로 되돌리고 같은 절차를 목표 청커만 바꿔 실행한다. 스키마 변경은 없다.
- 재순위화(`tutipal.rag.rerank.enabled`)는 청커와 독립이다. 따로 끄고 켤 수 있다.

## 4. 임베딩 모델을 바꿀 때 (PT-29)

- 모델이 바뀌면 `embedding_model` 값이 달라지고 검색은 질문을 임베딩한 모델과 같은 값의 조각만 찾는다. 교체 중에는 아직 바뀌지 않은 자료가 검색되지 않으므로 PT-29의 전환 기준(공지·시간대)을 먼저 정한다.
- 위 3~5단계를 그대로 쓰되 완료 확인은 `SELECT count(*) FROM material_chunks WHERE embedding_model <> '<새 모델>'`로 한다.
