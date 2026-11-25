"""조합 C 채점 학습 데이터 정리 (PT-88) — LLM 호출 없이 결정적으로 거른다.

derived/grading_c/train.jsonl 은 그대로 두고 train_clean.jsonl 을 새로 만든다.
  1. 모순 제거: 판정(correct)과 reason/feedback 결론이 반대인 행을 뺀다.
     - 정답인데 reason 마지막 절이 "부족·누락·언급하지 않음…"으로 끝나거나 feedback 첫 문장이 오답 투
     - 오답(wrong/partial/unknown)인데 reason 에 부정 표현이 하나도 없고 칭찬만 있거나, feedback 첫 문장이 칭찬
     - reason/feedback 에 JSON 조각·점수 표기가 섞임(생성 찌꺼기)
  2. 번역 통일: 같은 원문 문제의 한국어 번역이 띄어쓰기·문장부호만 다르면 자동으로 하나로 맞춘다.
     뜻이 다를 수 있는 차이는 translation_review.csv 에 올리고 자동으로 고르지 않는다
     (빈도로 고르면 틀린 번역이 다수일 때 맞는 번역을 덮어씀 — 2026-11-23 queue 문제 LIFO/FIFO 사례).
     --overrides JSON 에 사람이 고른 번역이 있으면 그 문제의 행을 모두 그 번역으로 다시 만든다.
  3. 중복 제거: 1·2 뒤 (문제, 학생 답안)이 같은 행은 하나만 남긴다. 라벨이 서로 다르면 모두 뺀다.
  4. 문제당 상한: 같은 원문 문제의 행이 --cap 개를 넘으면 답안 유형을 돌아가며 골라 줄인다.
뺀 행은 dropped.csv(엑셀용 UTF-8 BOM)에 사유와 함께 남겨 사람이 다시 볼 수 있게 한다.

overrides 형식 (translation_review.csv 의 group 열 값을 키로 쓴다):
  {"MohlerASAG|What is a queue?": {"question": "큐란 무엇인가요?", "model_answer": "먼저 들어온 요소가 먼저 나가는(FIFO) 자료 구조"}}

사용 (tutipal_ai/training 에서):
  python -m data_tools.clean_grading_c --dir <저장소 밖 derived/grading_c> --datasets-dir <datasets 폴더>       --eval-set eval/grading_set.jsonl [--cap 10] [--overrides overrides.json]
  python -m data_tools.clean_grading_c --self-test
"""

from __future__ import annotations

import argparse
import csv
import difflib
import io
import json
import random
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path

from data_tools import build_grading_train as B
from data_tools.common import (
    exit_error,
    jsonl_text,
    load_jsonl,
    require_args,
    require_outside_repo,
    utf8_stdout,
    write_files_atomic,
)

CHEAT = ('injection', 'injection_with_answer', 'tag_escape')
# 정답 판정과 어긋나는 "핵심이 빠졌다"류 결론 (정답 행은 마지막 절에 이게 있으면 모순)
NEG = ('부족', '누락', '언급하지 않', '언급이 없', '언급되지 않', '포함하지 않', '명시하지 않', '설명하지 않',
       '다루지 않', '설명하지 못', '제시하지 못', '파악하지 못', '충족하지 못', '이해하지 못', '정확하지 않',
       '불완전', '틀렸', '틀린', '오답', '잘못', '차이가 있', '오해', '혼동',
       # 2026-11-23 Codex 2차: "연결하지 못했습니다", "간과했습니다" 등이 빠져 정답 모순 29행이 남음.
       # '오류'는 넣지 않는다(문제 주제가 오류인 정상 행이 걸림).
       '하지 못', '못했', '못하고', '못한', '하지 않았', '하지 않아', '간과', '부정확', '놓쳤', '어렵', '미흡', '아쉽', '부적절',
       # Codex 3차: "모범 답안과 상반됩니다", "반대로 설명", "핵심 아이디어를 놓치고"
       '상반', '반대로', '놓치', '적절하지 않')
# 오답 행은 "부정 표현이 하나도 없을 때만" 모순으로 보므로 일반 부정어까지 넓게 센다.
# (2026-11-23 검수: "정확하게 계산하지 못했습니다", "오류가 있었고", "간과했습니다"가 좁은 목록에서 빠져 오탐)
NEG_ANY = NEG + ('하지 못', '못했', '못하고', '못한', '못하', '오류', '생략', '일치하지 않', '고려하지 않', '간과',
                 '관련이 없', '아닙니다', '아니', '부정확', '놓쳤', '않았', '않습니다', '않아', '않고', '없습니다',
                 '없어', '없으며', '없고', '어렵', '벗어', '머물')
# 오답 판정과 어긋나는 칭찬
POS = ('정확하게', '정확히', '올바르게', '올바른', '잘 설명', '잘 파악', '잘 이해', '잘 지적', '잘 제시',
       '일치', '같은 뜻', '같은 의미', '동일', '핵심을 담', '훌륭', '완벽')
# 오답인데 feedback 첫 문장이 칭찬 (build_grading_c.PRAISE + 검수에서 나온 "맞히셨네요").
# "맞히기 위해서는", "…하면 더 정확합니다" 같은 조언은 오답 피드백으로 정상이므로 '맞히'·'정확합니다'는 넣지 않는다.
PRAISE = ('잘하셨', '잘했', '잘 이해', '잘 설명', '잘 알고', '잘 파악', '좋은 지적', '좋은 답', '훌륭', '완벽',
          '정확히 설명', '정확하게 설명', '맞았', '맞아요', '맞습니다', '정답입니다', '정답이에요', '맞히셨', '맞혔',
          '설명해주셨', '설명해 주셨', '설명하셨')  # Codex 3차: "명확히 설명해주셨습니다" (조언형 "설명해주시면"은 제외)
# "잘 찾아내셨습니다", "잘 하셨어요", "잘 생각하셨습니다" 같은 '잘 + 동사' 칭찬 (2026-11-23 Codex 2차)
PRAISE_RE = re.compile(r'잘\s?(하|찾|짚|맞|이해|설명|파악|알|정리|표현|지적|답|생각|관찰|연결|제시)')
CONTRAST = re.compile(r'지만|으나|는데|다만|하지만|그러나|그렇지만')
JUNK = re.compile(r'["\']\s*\}|\{\s*["\']|\b[A-Z]:\s*\d\.\d')


def last_clause(text):
    return CONTRAST.split(text)[-1]


def first_sentence(text):
    return re.split(r'(?<=[.!?])\s', text.strip(), maxsplit=1)[0]


def contradiction(r):
    """판정과 설명이 어긋나면 사유 문자열, 아니면 None."""
    reason, feedback = r['reason'], r['feedback']
    if JUNK.search(reason) or JUNK.search(feedback):
        return 'junk text'
    if r['kind'] in CHEAT:
        return None  # 꼼수 행의 reason·feedback 은 고정 문장이다
    head = first_sentence(feedback)
    if r['label']:
        if any(w in last_clause(reason) for w in NEG):
            return 'true but reason negative'
        if any(w in head for w in B.TRUE_BAD + ('정확하지 않', '적절하지 않', '올바르지 않', '틀렸', '잘못')):
            return 'true but feedback negative'
        return None
    if head.startswith(('네,', '네!', '네 ')) or any(w in head for w in PRAISE) or PRAISE_RE.search(head):
        return 'false but feedback praises'
    if not any(w in reason for w in NEG_ANY) and any(w in reason for w in POS):
        return 'false but reason praises'
    return None


def round_robin(rows, cap, rnd):
    """답안 유형별로 돌아가며 cap 개를 고른다(유형 다양성 보존, seed 로 재현)."""
    by_kind = defaultdict(list)
    for r in rows:
        by_kind[r['kind']].append(r)
    for v in by_kind.values():
        rnd.shuffle(v)
    kinds = sorted(by_kind)
    out = []
    while len(out) < cap:
        for k in kinds:
            if by_kind[k] and len(out) < cap:
                out.append(by_kind[k].pop())
    return out, [r for v in by_kind.values() for r in v]


def surface(text):
    """띄어쓰기·문장부호를 뺀 형태. 이것까지 같으면 뜻이 같은 번역으로 보고 자동 통일한다."""
    return re.sub(r'[\s.,!?\'"“”‘’()\[\]·:;~\-]', '', text)


def pick(counter):
    return min(counter.items(), key=lambda t: (-t[1], t[0]))[0]  # 빈도 우선, 동률이면 문자열 순(재현성)


def group_id(r, origin):
    return f"{r['source']}|{origin.get((r['source'], r['source_id']), r['question'])}"


def rebuild(r, q, ref):
    return B.record(r['id'], r, q, ref, r['student_answer'], r['label'], r['kind'], r['reason'], r['feedback'], r['subject'])


def clean(rows, origin, cap, seed, overrides=None):
    """(남은 행, 뺀 행[(행, 사유)], 번역 검토 목록, 덮어쓴 행 수). 입력 rows 는 바꾸지 않는다."""
    overrides = overrides or {}
    dropped = []

    # 1. 모순 제거 (중복 제거보다 먼저: 정상 행이 모순 행의 중복으로 버려지지 않게)
    ok = []
    for r in rows:
        why = contradiction(r)
        if why:
            dropped.append((r, why))
        else:
            ok.append(r)

    # 2. 번역 검토 목록 + 사람이 고른 번역 적용
    groups = defaultdict(list)
    for r in ok:
        groups[group_id(r, origin)].append(r)
    review, applied, out = [], 0, []
    for gid in sorted(groups):
        g = groups[gid]
        qs, refs = Counter(r['question'] for r in g), Counter(r['model_answer'] for r in g)
        if gid in overrides:
            q, ref = overrides[gid]['question'], overrides[gid]['model_answer']
            for r in g:
                if (r['question'], r['model_answer']) != (q, ref):
                    r = rebuild(r, q, ref)
                    applied += 1
                out.append(r)
            continue
        if len(qs) == 1 and len(refs) == 1:
            out += g
            continue
        if len({surface(t) for t in qs}) == 1 and len({surface(t) for t in refs}) == 1:
            q, ref = pick(qs), pick(refs)  # 띄어쓰기·문장부호 차이뿐: 뜻이 같으므로 자동 통일
            for r in g:
                if (r['question'], r['model_answer']) != (q, ref):
                    r = rebuild(r, q, ref)
                    applied += 1
                out.append(r)
            continue
        review.append({'group': gid, 'rows': len(g), 'question_variants': dict(qs), 'model_answer_variants': dict(refs)})
        out += g

    # 3. 중복 제거
    seen = defaultdict(list)
    for r in out:
        seen[(r['question'], r['student_answer'])].append(r)
    kept = []
    for dup in seen.values():
        if len({r['label'] for r in dup}) > 1:
            dropped += [(r, 'duplicate with different label') for r in dup]
            continue
        dup = sorted(dup, key=lambda r: r['id'])
        kept.append(dup[0])
        dropped += [(r, 'duplicate') for r in dup[1:]]

    # 4. 문제당 상한
    rnd = random.Random(seed)
    groups = defaultdict(list)
    for r in kept:
        groups[group_id(r, origin)].append(r)
    final = []
    for gid in sorted(groups):
        g = sorted(groups[gid], key=lambda r: r['id'])
        if len(g) > cap:
            g, extra = round_robin(g, cap, rnd)
            dropped += [(r, f'over cap {cap}') for r in extra]
        final += g
    rnd.shuffle(final)
    return final, dropped, review, applied


def check_input(rows):
    """입력 행의 messages 가 구조화 필드와 정확히 같은지 확인한다(다르면 사유 목록)."""
    bad = []
    for r in rows:
        if r['messages'] != rebuild(r, r['question'], r['model_answer'])['messages']:
            bad.append(r['id'])
    return bad


def self_test():
    def row(i, label, kind, reason, feedback='좋아요.', q='문제', ref='모범', ans=None, sid=None):
        x = {'source': 'S', 'source_id': sid or str(i)}
        return B.record(f'S-{i}', x, q, ref, ans or f'답{i}', label, kind, reason, feedback, '과학')

    # 모순 판정
    assert contradiction(row(1, True, 'paraphrase', '학생 답안은 통제 변수를 언급하지 않았습니다.')) == 'true but reason negative'
    assert contradiction(row(2, True, 'paraphrase', '표현은 다르지만 핵심 개념은 모범 답안과 같습니다.')) is None
    assert contradiction(row(3, True, 'paraphrase', '핵심 용어는 빠졌지만 뜻은 일치합니다.')) is None
    assert contradiction(row(4, False, 'wrong', '문제의 핵심 내용을 잘 파악하고 정확하게 설명하고 있습니다.', '다시 보세요.')) == 'false but reason praises'
    assert contradiction(row(5, False, 'wrong', '방향은 정확히 설명했지만 W 표시가 누락되었습니다.', '다시 보세요.')) is None
    assert contradiction(row(6, False, 'partial', '반복의 공통점을 정확히 설명하지 못했습니다.', '다시 보세요.')) is None
    assert contradiction(row(61, False, 'partial', '사건 B의 확률을 정확하게 계산하지 못했습니다.', '다시 보세요.')) is None
    assert contradiction(row(62, False, 'wrong', '정확히 짚었으나 핵심 원리와 일치하지 않습니다.', '다시 보세요.')) is None
    assert contradiction(row(63, False, 'partial', '통제 변수를 간과했고 결과를 정확하게 해석했다.', '다시 보세요.')) is None
    assert contradiction(row(64, False, 'wrong', '핵심이 누락되었습니다.', '정답을 맞히셨네요! 다만 더 보세요.')) == 'false but feedback praises'
    assert contradiction(row(65, False, 'wrong', '핵심이 누락되었습니다.', '네, 좋은 시도예요.')) == 'false but feedback praises'
    assert contradiction(row(68, False, 'wrong', '핵심이 누락되었습니다.', '닫힌 형태라는 점을 잘 찾아내셨습니다.')) == 'false but feedback praises'
    assert contradiction(row(69, False, 'partial', '핵심이 누락되었습니다.', '학생님, 잘 하셨어요!')) == 'false but feedback praises'
    assert contradiction(row(10, True, 'paraphrase', '나이와 시간을 명확히 연결하지 못했습니다.')) == 'true but reason negative'
    assert contradiction(row(11, True, 'paraphrase', '핵심 단점인 백 포인터를 간과했습니다.')) == 'true but reason negative'
    assert contradiction(row(12, True, 'paraphrase', '경계를 벗어난 인덱스 오류는 실행 시 발생하는 오류라는 점과 일치합니다.')) is None
    assert contradiction(row(13, True, 'paraphrase', '학생 답안은 모범 답안과 상반됩니다.')) == 'true but reason negative'
    assert contradiction(row(14, True, 'paraphrase', '같은 뜻입니다.', feedback='표현은 정확하지 않습니다. 다시 보세요.')) == 'true but feedback negative'
    assert contradiction(row(70, False, 'wrong', '핵심이 누락되었습니다.', '원리를 명확히 설명해주셨습니다.')) == 'false but feedback praises'
    assert contradiction(row(71, False, 'wrong', '핵심이 누락되었습니다.', '원리를 명확히 설명해주시면 좋겠습니다.')) is None
    assert contradiction(row(66, False, 'wrong', '핵심이 누락되었습니다.', '정답을 맞히기 위해서는 원리를 설명해야 합니다.')) is None
    assert contradiction(row(67, False, 'wrong', '핵심이 누락되었습니다.', '화학 반응이라는 용어로 설명하면 더 정확합니다.')) is None
    assert contradiction(row(7, True, 'paraphrase', '같은 뜻입니다.', feedback='좋아요."} S: 0.5, Q: 1.0')) == 'junk text'
    assert contradiction(row(8, False, 'injection', B.INJ_REASON, B.INJ_FEEDBACK)) is None
    assert contradiction(row(9, True, 'paraphrase', '같은 뜻입니다.', feedback='틀렸어요. 다시 보세요.')) == 'true but feedback negative'

    # 번역이 갈리면 자동으로 바꾸지 않고 검토 목록에 올린다
    origin = {('S', 'a'): 'Q1', ('S', 'b'): 'Q1', ('S', 'c'): 'Q1', ('S', 'd'): 'Q2', ('S', 'e'): 'Q2'}
    rows = [row(1, True, 'paraphrase', '같은 뜻입니다.', q='문제가', ref='틀린번역', ans='X', sid='a'),
            row(2, True, 'paraphrase', '같은 뜻입니다.', q='문제가', ref='틀린번역', ans='Y', sid='b'),
            row(3, True, 'paraphrase', '같은 뜻입니다.', q='문제가', ref='맞는번역', ans='W', sid='c'),
            row(4, True, 'paraphrase', '같은 뜻입니다.', q='Q2번역', ans='Z', sid='d'),
            row(5, False, 'wrong', '핵심이 누락되었습니다.', '다시 보세요.', q='Q2번역', ans='Z', sid='e')]
    snapshot = json.dumps(rows, ensure_ascii=False)
    final, dropped, review, applied = clean(rows, origin, cap=10, seed=1)
    assert json.dumps(rows, ensure_ascii=False) == snapshot, '입력을 바꾸면 안 된다'
    assert applied == 0 and [v['group'] for v in review] == ['S|Q1']
    assert {r['model_answer'] for r in final} == {'틀린번역', '맞는번역'}
    assert Counter(why for _, why in dropped) == {'duplicate with different label': 2}

    # 사람이 고른 번역은 그 문제 전체에 적용하고 messages 도 다시 만든다
    final, _, review, applied = clean(rows, origin, cap=10, seed=1,
                                      overrides={'S|Q1': {'question': '문제가', 'model_answer': '맞는번역'}})
    assert applied == 2 and review == []
    for r in final:
        if r['source_id'] in 'abc':
            assert r['model_answer'] == '맞는번역' and '<model_answer>맞는번역</model_answer>' in r['messages'][1]['content']
    assert check_input(final) == []

    # 띄어쓰기·문장부호만 다른 번역은 자동 통일 (검토 목록에 올리지 않음)
    spaced = [row(1, True, 'paraphrase', '같은 뜻입니다.', q='큐란 무엇인가요?', ref='선입 선출 구조', ans='A', sid='a'),
              row(2, True, 'paraphrase', '같은 뜻입니다.', q='큐란 무엇인가요?', ref='선입선출 구조.', ans='B', sid='b'),
              row(3, True, 'paraphrase', '같은 뜻입니다.', q='큐란 무엇인가요 ?', ref='선입 선출 구조', ans='C', sid='c')]
    final, _, review, applied = clean(spaced, {('S', s): 'Q' for s in 'abc'}, cap=10, seed=1)
    assert review == [] and applied == 2
    assert {(r['question'], r['model_answer']) for r in final} == {('큐란 무엇인가요?', '선입 선출 구조')}

    # 모순 행이 먼저 빠져야 같은 답의 정상 행이 살아남는다
    pair = [row(1, True, 'paraphrase', '핵심을 언급하지 않았습니다.', ans='X', sid='a'),
            row(2, True, 'paraphrase', '같은 뜻입니다.', ans='X', sid='b')]
    final, dropped, _, _ = clean(pair, {('S', 'a'): 'Q', ('S', 'b'): 'Q'}, cap=10, seed=1)
    assert [r['id'] for r in final] == ['S-2'] and [why for _, why in dropped] == ['true but reason negative']

    # 상한: 유형을 돌아가며 고르고, 같은 seed 면 결과가 같다
    many = [row(i, i % 2 == 0, 'paraphrase' if i % 2 == 0 else 'wrong',
                '같은 뜻입니다.' if i % 2 == 0 else '핵심이 누락되었습니다.', '다시 보세요.', sid=f'm{i}') for i in range(30)]
    org = {('S', f'm{i}'): 'QM' for i in range(30)}
    final, dropped, _, _ = clean(many, org, cap=10, seed=1)
    again, _, _, _ = clean(list(reversed(many)), org, cap=10, seed=1)
    assert len(final) == 10 and Counter(r['kind'] for r in final) == {'paraphrase': 5, 'wrong': 5}
    assert [r['id'] for r in final] == [r['id'] for r in again], '입력 순서와 무관하게 재현돼야 한다'

    # 입력 messages 가 필드와 어긋나면 잡는다
    broken = row(99, True, 'paraphrase', '같은 뜻입니다.')
    broken['messages'][1]['content'] = '<question>다른 문제</question>'
    assert check_input([broken]) == ['S-99']
    print('self-test OK')


def build_parser():
    ap = argparse.ArgumentParser(prog='python -m data_tools.clean_grading_c',
                                 description='조합 C 채점 학습 데이터를 LLM 없이 결정적으로 정리한다.')
    ap.add_argument('--dir', help='train.jsonl 이 있는 폴더(저장소 밖). train_clean.jsonl 등을 여기에 쓴다')
    ap.add_argument('--datasets-dir', help='원본 parquet 이 있는 datasets 폴더(원문 문제로 묶기)')
    ap.add_argument('--eval-set', help='평가 세트 grading_set.jsonl (비슷한 문제 재검사)')
    ap.add_argument('--overrides', help='사람이 고른 번역 JSON (translation_review.csv 의 group 값을 키로)')
    ap.add_argument('--cap', type=int, default=10)
    ap.add_argument('--seed', type=int, default=20261120)
    ap.add_argument('--self-test', action='store_true', help='내장 자체 검사만 실행')
    return ap


def main(argv: list[str] | None = None) -> None:
    ap = build_parser()
    a = ap.parse_args(argv)
    if a.self_test:
        self_test()
        return
    require_args(ap, a, '--dir', '--datasets-dir', '--eval-set')
    require_outside_repo(ap, a, '--dir')  # 아무것도 읽거나 쓰기 전에 확인한다
    utf8_stdout()

    d = Path(a.dir)
    rows = load_jsonl(d / 'train.jsonl')
    if not rows:
        exit_error(f"학습 데이터({d / 'train.jsonl'})에 행이 없음")
    origin = {(x['source'], x['source_id']): x['q'] for x in B.load_sources(a.datasets_dir)}
    missing = sum((r['source'], r['source_id']) not in origin for r in rows)
    if missing:
        sys.exit(f'원본에서 찾지 못한 행 {missing}개 — 원본 데이터가 바뀌었는지 확인')
    if any(r['messages'][0]['content'] != B.GRADE_SYSTEM for r in rows):
        sys.exit('시스템 프롬프트가 백엔드 QuizAi.GRADE_SYSTEM 과 다른 행이 있음')
    bad = check_input(rows)
    if bad:
        sys.exit(f'messages 와 필드가 어긋난 행 {len(bad)}개: {bad[:5]}')
    overrides = json.loads(Path(a.overrides).read_text(encoding='utf-8')) if a.overrides else {}

    final, dropped, review, applied = clean(rows, origin, a.cap, a.seed, overrides)
    groups = {group_id(r, origin) for r in rows}
    unknown = sorted(set(overrides) - groups)
    if unknown:
        sys.exit(f'overrides 키가 데이터에 없음: {unknown[:3]}')
    if check_input(final):
        sys.exit('출력 messages 검증 실패')

    if not final:
        exit_error(f'정리 후 남은 학습 행이 없음 (입력 {len(rows)}행 모두 제외: {dict(Counter(why for _, why in dropped))})')
    eval_q = sorted({e['question'] for e in load_jsonl(a.eval_set)})
    if not eval_q:
        exit_error(f'평가 세트({a.eval_set})에 문제가 없음 (비슷한 문제 재검사를 할 수 없음)')
    worst = max((difflib.SequenceMatcher(None, r['question'], q).ratio(), r['id']) for r in final for q in eval_q)
    if worst[0] >= 0.8:
        sys.exit(f'평가 세트와 비슷한 문제가 남음: {worst}')

    dropped_buf = io.StringIO()
    w = csv.writer(dropped_buf)
    w.writerow(['drop_reason', 'id', 'label', 'kind', 'question', 'student_answer', 'reason', 'feedback'])
    for r, why in sorted(dropped, key=lambda t: (t[1], t[0]['id'])):
        w.writerow([why, r['id'], r['label'], r['kind'], r['question'], r['student_answer'], r['reason'], r['feedback']])
    review_buf = io.StringIO()
    w = csv.writer(review_buf)
    w.writerow(['group', 'rows', 'field', 'count', 'korean'])
    for v in review:
        for field in ('question', 'model_answer'):
            variants = v[f'{field}_variants']
            if len(variants) > 1:
                for text, n in sorted(variants.items(), key=lambda t: -t[1]):
                    w.writerow([v['group'], v['rows'], field, n, text])
    stats = {'input': len(rows), 'output': len(final), 'dropped': len(dropped),
             'translation_review_groups': len(review), 'override_rows_rewritten': applied,
             'cap_per_question': a.cap, 'seed': a.seed, 'max_eval_similarity': round(worst[0], 2),
             'drop_reason': Counter(why for _, why in dropped),
             'label_true': sum(r['label'] for r in final), 'kind': Counter(r['kind'] for r in final),
             'subject': Counter(r['subject'] for r in final), 'source': Counter(r['source'] for r in final),
             'questions': len({group_id(r, origin) for r in final})}
    # 네 파일을 모두 임시 파일에 쓴 뒤에야 바꿔 넣는다(중간 실패 시 이전 세대가 그대로 남는다)
    write_files_atomic({d / 'train_clean.jsonl': (jsonl_text(final), 'utf-8'),
                        d / 'dropped.csv': (dropped_buf.getvalue(), 'utf-8-sig'),
                        d / 'translation_review.csv': (review_buf.getvalue(), 'utf-8-sig'),
                        d / 'clean_stats.json': (json.dumps(stats, ensure_ascii=False, indent=1), 'utf-8')})
    print(json.dumps(stats, ensure_ascii=False, indent=1))


if __name__ == '__main__':
    main()
