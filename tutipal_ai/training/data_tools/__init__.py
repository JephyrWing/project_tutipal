"""채점 학습 데이터 파이프라인(PT-88). 실행은 tutipal_ai/training 에서 `python -m data_tools.<name>`.

순서: build_grading_c(생성) → clean_grading_c(정리) → augment_tricks(꼼수 보강 → train_aug.jsonl).
build_grading_train·compare_hybrid 는 초기 생성기·번역 조합 비교용이다.
모든 출력은 저장소 밖 데이터 폴더에만 쓴다(저장소 안을 가리키면 종료 코드 2).
"""
