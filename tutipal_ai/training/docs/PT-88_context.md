# PT-88 data quality — shared context for team agents

Project: C:\CODE-SPACE\project_tutipal\.claude\worktrees\feature+ai-eval-harness (git worktree, branch worktree-feature+ai-eval-harness, merged with origin/develop)
Code: tutipal_ai/training/data_tools/ (build_grading_c.py = generation, compare_hybrid.py = prompts TG_PROMPT/WRITER_SYSTEM + translate()/generate(),
build_grading_train.py = source loading + label rules (mohler_label_v2, SAF_KIND, validate()), clean_grading_c.py, augment_tricks.py, review_sheet.py)
Tests: tutipal_ai/training/tests (pytest, 787 pass baseline). Run: cd tutipal_ai/training && .venv/Scripts/python.exe -m pytest -q
README: tutipal_ai/training/README.md documents the v2 pipeline (build --v2 -> clean --sources v2 -> augment_tricks -> trainmix/leakcheck -> review_sheet make/check).
Data (outside repo): C:\Users\junse\tutipal_datasets\derived\grading_v2\ (trans_cache.jsonl, write_cache.jsonl, train*.jsonl, train_aug.jsonl = 1489 rows),
raw parquet in C:\Users\junse\tutipal_datasets\datasets. Human review sheet: C:\Users\junse\Downloads\review_sheet.csv (30 rows, columns
id,source,q_en,ref_en,ans_en,q_ko,ref_ko,ans_ko,label,kind,reason,feedback,verdict,note). Do NOT touch docs/PT-86_*, tutipal_back/src/ragEval/.
Sealed eval set must never be used for generation; leakcheck (eval/leakcheck.py) must pass.

## Human review result: OK 17, TRANS_ERR 7, EXPL_ERR 4, LABEL_ERR 2 (13/30 = 43%). Mohler 8/22 errors, SAF 5/8.

## Root cause trace (orchestrator verified)
Verified: for all 13 rows, q_ko/ref_ko/ans_ko in the sheet are byte-identical to trans_cache.jsonl output (translategemma:4b, no post-processing),
and reason/feedback are identical to write_cache.jsonl (gemma3:4b). So stage attribution below is confirmed, not guessed.

Pipeline facts (code):
- translate(): TG_PROMPT, each field (question, model_answer, student_answer) translated separately, no glossary, no context, no checks beyond validate() (hangul ratio, han chars, length).
- writer: WRITER_SYSTEM + user message with ONLY Korean text + KIND_DESC label text. Writer never sees English originals, and never sees SAF answer_feedback (human grader feedback, available in source as x['hint']).
  KIND_DESC['partial'] = '오답 (핵심 일부만 맞거나 빠짐)' pushes the writer to claim something is "missing".
- check()/validate(): keyword rules only (praise in first sentence of wrong-answer feedback, contradiction words in reason). No check for "already-present content called missing".
- Mohler label: mohler_label_v2(avg,g1,g2): True if avg>=4.5 and both graders>=4; None if 3.5<avg<4.5; else False. kind partial if avg>=2 else wrong.
  BUG (verified): questions E11.Q01-Q10 and E12.Q01-Q10 (20 of 87 questions) have grader scores on a 0-10 scale (score_avg is on 0-5).
  The ">=4" grader check is then meaningless for them. Normalizing graders (/2) for those questions flips 20 True rows to None (drop).
  Stricter policy "True needs both normalized graders >= 4.5" would drop another 290 True rows (of ~1529 Mohler True).
- SAF label: verification_feedback Correct->True, Partially correct->False(partial), Incorrect->False(wrong). Also 'score' column (varies per question) and answer_feedback text.
  SAF "english" dataset contains German answers: heuristic count ~46 of 2127 rows (umlauts / German stopwords).

Per-row attribution:
| id | verdict | stage (verified) | detail |
|---|---|---|---|
| MohlerASAG-E04.Q02.A18 | EXPL | generation | feedback repeats student confusion; does not mention null terminator (core of ref). Writer had no English, partial KIND_DESC. Scores 3/1 avg2.0 |
| MohlerASAG-E07.Q07.A00 | LABEL | source annotation (graders 5/5 avg5.0) | "twice as much memory" accepted by both graders; conversion rule is not at fault. Fix = human-review exclusion list |
| MohlerASAG-E08.Q03.A06 | EXPL | generation | says student missed size management which the student wrote. Graders 4/1 (avg2.5) strongly disagree |
| MohlerASAG-E10.Q04.A09 | TRANS (+label boundary) | translation; label boundary | "2 joints, left and right" -> "좌우 각각 2개". Graders 5/4 avg exactly 4.5 = boundary |
| MohlerASAG-E11.Q05.A02 | EXPL | generation | "not answered" -> reason claims student did not understand concept. kind=wrong (avg 0) though it is really unknown/no-answer |
| MohlerASAG-E12.Q01.A10 | TRANS | translation | ref "memory" -> "기억" (10-scale question: graders 10/10) |
| MohlerASAG-E12.Q10.A06 | EXPL | generation (writer hallucination) | feedback states BST search is at most log2(n). Graders 7.5/0 on 10 scale (=3.75/0) strongly disagree |
| MohlerASAG-E12.Q10.A21 | TRANS | translation | "at most" -> "거의 동일" |
| SAF-43a0597d... | TRANS | translation | ref "free" (medium) -> "무료"; ans near/far subject swapped |
| SAF-523bc5e9... | TRANS + EXPL | translation + generation | "more than 3" -> "3개 이상"; reason says event B wrong while SAF answer_feedback explicitly says B justification is correct (writer never saw hint) |
| SAF-6267c2f8... | TRANS (+label doubt) | translation | "unicast" -> "단일 방향"; ref_ko "RPB works like RPF" became "RPF works like RPF" |
| SAF-880da168... | LABEL | source + language | German student answer, SAF says Correct (score 1.5); reviewer says pros/cons reversed. Policy: drop non-English answers |
| SAF-d5300a0f... | TRANS | translation | dropped "overcome size limitation"; "(like we have to in IPv4)" negation flipped |

Totals by stage: translation 7 (one also generation), generation 5 (incl. 523bc), source-label 2 (+2 TRANS rows with label doubt, 1 boundary 4.5).

## Other facts
- Generation speed seen: translation ~1-2 s/row (3 fields), writing ~1.7 s/row on RTX 3060 6GB. Ollama local http://127.0.0.1:11434, models translategemma:4b, gemma3:4b available.
- Memory constraint: one model at a time, keep_alive 0 after use (current code does not send keep_alive).
- Composition targets (README): science <=250, total 1300-1800, true share 0.30-0.45, Mohler non-trick >=600, SAF non-trick >=200 (currently 176 SAF non-trick, 840 Mohler, total 1489).
- Training starts 2026-11-27 night. Today 2026-11-25.
