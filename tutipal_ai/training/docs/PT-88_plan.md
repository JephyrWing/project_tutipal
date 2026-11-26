# PT-88 plan (planner 마시로) — acceptance criteria

Goal: new output folder derived/grading_v3 (v2 kept as fallback), rerun v2 pipeline with: translation glossary + numeric/comparison/negation checks; writer gets English originals + SAF hint, explanation checks; label: Mohler 10-scale normalization, grader-disagreement rule, German drop, human-review exclusion list. Output new 30-row review CSV + estimated error rate.

## Cache / run
1. tkey and text_key get suffix `#p=<sha256(TG_PROMPT + glossary + check version)[:8]>`; wkey gets `#w=<sha256(WRITER_SYSTEM + user template)[:8]>`. Any prompt change -> different key; old cache lines unmatched (append only, never delete). Hashes recorded in stats.json. Test: monkeypatch TG_PROMPT -> tkey changes; with a cache holding only old keys, --assemble-only marks all rows `missing`.
2. Science row selection still uses old-key (v1) cache presence (_ok_cached uses old-key functions). After selection science rows are translated/written with new prompts too. Test: only science rows with old-key cache are selected.
3. Ollama: no keep_alive inside per-row requests. After translation stage send one unload request (`keep_alive: 0`) for translator; after writing stage one for writer. Re-translation happens inside translation stage (one model loaded at a time). Test: mocked requests order is translate* -> unload(translator) -> write* -> unload(writer); chat request bodies don't contain keep_alive=0.
4. Run with --out derived/grading_v3 --cache-from derived/grading_v2; v2 read-only (existing overlaps/seed_caches). New code never reads sealed set path.

## Translation check (`trans_check`, new pure function, per field)
5. Glossary: only entries whose English term appears in the source are appended to the prompt. Initial: memory -> 메모리 (forbidden 기억); free -> 비어 있는/사용 가능한/여유/해제 (forbidden 무료); unicast/multicast/broadcast -> 유니캐스트/멀티캐스트/브로드캐스트 (forbidden 단일 방향). Same table used by check: English term present -> one allowed Korean form required, forbidden form -> fail. Test: "free memory" -> "무료 기억" fails, "사용 가능한 메모리" passes.
6. Acronyms: English tokens `\b[A-Z]{2,}[A-Za-z0-9]*\b` (RPB, RPF, IPv4, TCP) must appear verbatim (case-sensitive) in Korean. Test: "RPB works like RPF" -> "RPF는 RPF처럼" fails.
7. Numbers: English numbers (`\d+(\.\d+)?`, zero-twelve, twice=2, half=0.5) and Korean numbers (arabic, 한/두/세/네, 둘/셋 ...) must match as sets. Test: "3 hosts" -> "세 호스트" passes, "3 hosts" -> "4개" fails.
8. Comparison/negation (English -> Korean direction only):
   - at most / no more than / up to / maximum -> 최대|이하|까지|넘지
   - at least / minimum / no less than -> 최소|이상|적어도
   - more than / greater than / exceed(s) -> 초과|보다 많|보다 크|넘 ; and if English has no "at least" but Korean has "이상" -> fail
   - less than / fewer than -> 미만|보다 적|보다 작 ; if English has no "at most" but Korean has "이하" -> fail
   - only / solely / just -> 만|뿐|오직|단지
   - not / n't / never / no / none / without / cannot / neither / nor -> 않|안|못|없|아니|말고|불가|금지
   Test: "more than 3" -> "3개 이상" fails; "at most" -> "거의 동일" fails.
9. Failed field: re-translate once with a different seed (glossary included). Fails again -> row stored in trans_cache with error="trans_check:<rule>:<field>" and dropped. TextMemo stores only passing translations. --assemble-only re-runs the check (like existing `again = check(...)`). Test: fake translator wrong-then-right passes; wrong twice -> rejected reason correct.
10. Regression fixtures: the 5 rule-catchable TRANS rows (E12.Q01.A10, E12.Q10.A21, SAF-43a0597d, SAF-523bc5e9, SAF-6267c2f8) EN/KO pairs as fixtures -> all must fail.

## Writer + explanation check (extend check(); existing 2-attempt retry applies)
11. Writer user message = Korean 3 fields + English 3 fields + for SAF `hint` (original grader feedback, English, reference). WRITER_SYSTEM adds: don't call missing what the student already wrote; don't contradict original grader feedback when given; output Korean. KIND_DESC unchanged (shared with v1). Test: mocked generate receives English originals and hint; rows with empty hint have no hint block.
12. Already-present-called-missing (False rows): a sentence in reason/feedback with a missing-cue (빠졌|빠져|빠뜨|누락|언급하지 않|설명하지 않|포함되지 않): its content tokens (Hangul words >=2 chars with particles stripped, alnum tokens, stopwords removed) count >=2 and >=60% of them appear in ans_ko -> fail `expl:already_present`.
13. Unsupported quantity: numbers and comparison expressions (Korean cues of 7/8) in reason/feedback must appear somewhere in q_ko/ref_ko/ans_ko, else fail `expl:unsupported_quantity`. Test: feedback "최대 log2(n)" with no log2 in ref -> fail.
14. Label contradiction: True rows whose reason/feedback contain missing/error cues (빠졌|누락|부족|틀렸|잘못) fail. Only add what TRUE_BAD/FALSE_BAD don't already cover.

## Label rules (build_grading_train, v2 only; v1 output unchanged)
15. Mohler scale: if max grader score within a question (id prefix before last '.', e.g. E11.Q01) > 5, divide that question's g1,g2 by 2 before mohler_label_v2. Test: real-data-gated test that normalized questions are exactly E11/E12 (20); synthetic 10/10 -> True, 9/5 -> None.
16. Boundary: keep rule (normalized avg >= 4.5 and both graders >= 4 -> True; 3.5 < avg < 4.5 -> drop). Exactly 4.5 kept. Test: (5,4,avg4.5) True; (5,3.5,avg4.25) None.
17. Grader disagreement: False candidates with |g1-g2| >= 3 (normalized) dropped (~203 rows). Test: (4,1) None, (3,1) False.
18. No answer: English student answer matching `^(not answered|no answer|n/?a|i don'?t know|idk)\.?$` (case-insensitive, trimmed) -> kind=unknown, reason/feedback from fixed template (like INJ_REASON), no writer call. Template: no answer given; does not judge understanding. Test: "Not answered." -> unknown + template, writer not called.
19. German: SAF student answer with `[äöüßÄÖÜ]` or >=3 distinct German stopwords (und, der, die, das, ist, nicht, ein, eine, mit, für, werden, sind, auch, wenn, oder) as whole words -> dropped. Test: SAF-880da168 text dropped; "the processes die and are not ..." English kept. Report actual count (~46).
20. Human-review exclusion: `--exclude-review <csv>` ids with verdict=LABEL_ERR removed before selection. Missing columns -> error exit (like review_keep_ids). TRANS/EXPL rows are regenerated.

## Reporting / outputs
21. stats.json and rejected.jsonl count drops per check per source (trans_check:*, expl:*, german, disagree, mohler_10scale_flip, exclude_review, unknown_template). After clean -> augment -> trainmix -> leakcheck (exit 0), report remaining rows per source (with/without tricks).
22. Checker performance: run new checks on the old 30-row reviewed sheet: recall on 13 known errors, false positives on 17 OK. Translation-check failure rate on whole v2 trans_cache. If >30%, tune before generation.
23. review_sheet make with new seed on new train_aug (30 rows, SAF>=8, same columns) -> copy to C:\Users\junse\Downloads\review_sheet_pt88_r2.csv. Report: each of 13 old error rows dropped or regenerated (+ new text), estimated residual error = 43% x (1 - recall) as a range; true rate only by human review.
24. pytest -q passes (787 + new); trainmix coding >= 0.6, tricks <= 0.15.

Assumptions: no LLM judge; SAF share not cut unless SAF trans-check fail rate > 2x Mohler or new sheet has >=3/8 SAF errors (then --cap-by-source SAF=6); boundary not tightened; if disagreement rule pushes true share > 0.45 raise threshold to 3.5 (re-assemble only); new sheet not excluding old ids.
Out of scope: LLM judge, semantic-change detection (E10.Q04, d5300), KO->EN negation check, PT-86 files, sealed set, training run, relaxing to hit SAF>=200.
Note: E11/E12 score_avg inconsistent with (g1+g2)/4 in 214 rows, but normalized avg within 0.5 of score_avg for all rows.
