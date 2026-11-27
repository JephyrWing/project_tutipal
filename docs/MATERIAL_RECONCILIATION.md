# 자료 원본 저장소 정합성 복구

## 명령 실행

서버와 같은 Spring JAR로 운영자 명령을 실행한다. 실행 환경의 `DB_URL`, `DB_USER`, `DB_PW`, `MATERIAL_STORAGE`를 명시한다. S3에서는 서버와 동일한 `S3_BUCKET`과 `AWS_REGION`을, 로컬 저장소에서는 동일한 절대 `MATERIAL_LOCAL_PATH`를 사용한다. 실행 권한은 서버 셸과 DB·저장소 자격 증명으로 관리한다. 명령은 웹 서버, 스케줄러, Flyway를 기동하지 않는다. 서버 마이그레이션이 적용된 뒤 실행한다.

```text
java -jar tutipal_back/build/libs/server.jar material-ops list <session-id>
java -jar tutipal_back/build/libs/server.jar material-ops cleanup <candidate-id>
java -jar tutipal_back/build/libs/server.jar material-ops mark-missing <material-id>
java -jar tutipal_back/build/libs/server.jar material-ops restore <material-id>
```

- `list`는 지정한 수업의 정리 기록과 원본 확인 결과를 읽기 전용으로 보여준다. `cleanup` 행의 ID는 정리 기록 ID이고, `original` 행의 ID는 자료 ID다. `PENDING`은 업로드가 진행 중이거나 이전 프로세스가 정리를 끝내지 못한 기록이다. `PRESENT HIDDEN`은 바이트가 복구됐고 재색인 성공을 기다리는 자료다.
- `cleanup`은 선택한 정리 기록의 객체 키 잠금을 얻고 DB 참조를 다시 조회한다. 해당 키가 사용 중이면 `BUSY`, DB가 참조하면 `REFERENCED`를 반환한다. 지울 객체가 이미 없으면 기록만 정리한다. S3 오류 뒤에는 기록이 남아 같은 ID로 다시 실행할 수 있다.
- `mark-missing`은 선택한 자료의 원본이 없음을 다시 확인하고 다운로드·재사용·검색에서 제외한다. 자료 메타데이터와 기존 색인 조각을 보존한다. S3 `403`, 연결 오류, 타임아웃, 버킷 자체의 접근 불가는 누락 확정에 사용하지 않는다. S3의 객체 `404`를 확인하려면 해당 버킷을 조회할 권한이 필요하다.
- `restore`는 백업 등을 통해 같은 키에 원본을 되돌린 뒤 실행한다. Spring의 기존 색인 작업을 요청하고, 새 청크와 성공 상태가 함께 커밋될 때 검색과 다운로드에 다시 공개한다. 재색인이 실패하면 숨김을 유지한다.
- `DONE`과 `NO_CHANGE`는 종료 코드 0, 다른 처리 결과와 실행 오류는 1, 잘못된 인수나 설정은 2를 반환한다.

정리 기록은 이 버전부터 기록된다. 초기 버전에서 이미 유실된 정리 키는 별도 증거가 필요하다. 같은 물리 키를 참조하는 자료가 하나라도 있으면 개별 자료를 내린 뒤에도 객체를 보존한다. PT-109는 원본 누락과 관리자에 의한 비공개를 별도 조건으로 취급하고, 자료를 물리 삭제할 때 이 참조 보호와 정리 기록을 사용한다.

## 격리 검증

`gradlew materialDbTest`는 `MATERIAL_TEST_DB_URL`에 `jdbc:postgresql://127.0.0.1:<port>/tutipal_pt107_e2e`, `MATERIAL_TEST_DB_ALLOW_RESET`에 `disposable`, `MATERIAL_TEST_DB_PASSWORD`에 임시 DB의 암호를 지정해 실행한다. 테스트가 합성 자료를 넣으며 S3 HTTP 요청은 자체 루프백 저장소로 전달한다.
