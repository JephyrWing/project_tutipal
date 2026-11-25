"""주관식 채점 QLoRA 학습 데이터 본 생성 — 조합 C (PT-88).

번역 모델 비교 보고서(2026-11-20)에서 고른 조합 C 로 만든다.
  1단계 번역: translategemma:4b 가 문제·모범 답안·학생 답안을 각각 한국어로 옮긴다.
  2단계 작성: gemma3:4b 가 한국어 내용 + 원본 사람 라벨을 보고 reason·feedback 만 쓴다.

build_grading_train.py 의 규칙을 그대로 따른다.
- 라벨은 원본 사람 라벨만 쓴다. LLM 은 번역과 이유·피드백 문장만 만든다.
- 시스템 프롬프트는 백엔드 QuizAi.GRADE_SYSTEM 과 같아야 한다(다르면 중단).
- 평가 세트(grading_set.jsonl)와 비슷한 문제(유사도 0.8 이상)·같은 꼼수 문장은 넣지 않는다.
- 정답 답안 일부로 꼼수 오답 변형(지시 덧붙이기·지시만·태그 탈출)을 만든다.
- 단계마다 캐시(trans_cache.jsonl, write_cache.jsonl)에 한 건씩 기록해 중단돼도 이어서 한다.

사용 (tutipal_ai/training 에서):
  python -m data_tools.build_grading_c --out <저장소 밖 derived/grading_c> --datasets-dir <datasets 폴더>       --eval-set eval/grading_set.jsonl [--quizai <QuizAi.java>] [--n 1200]
"""

from __future__ import annotations

import argparse
import difflib
import json
import random
import re
import sys
import time
from collections import Counter
from pathlib import Path

from data_tools import build_grading_train as B
from data_tools import compare_hybrid as H
from data_tools.common import (
    DEFAULT_SEED,
    exit_error,
    jsonl_text,
    repo_root,
    require_outside_repo,
    seed_key_suffix,
    utf8_stdout,
    write_files_atomic,
)

FIELDS = (('question', 'q'), ('model_answer', 'ref'), ('student_answer', 'ans'))

# 2026-11-22 1차 생성 검수: 오답인데 피드백 첫 문장이 칭찬인 행이 약 9% 있었다 (B.validate 목록에 없는 표현).
# correct=false 와 칭찬이 같이 나가면 모델이 모순된 출력을 배우므로 첫 문장에서 막고 다시 쓰게 한다.
PRAISE = ('잘하셨', '잘했', '잘 이해', '잘 설명', '잘 알고', '잘 파악', '좋은 지적', '좋은 답', '훌륭', '완벽',
          '정확히 설명', '정확하게 설명', '맞았', '맞아요', '맞습니다', '정답입니다', '정답이에요')


def first_sentence(text):
    return re.split(r'(?<=[.!?])\s', text.strip(), maxsplit=1)[0]


def check(o, label):
    err = B.validate(o, label)
    if err or label is not False:
        return err
    head = first_sentence(o['feedback'])
    if head.startswith(('네,', '네!', '네 ')) or any(p in head for p in PRAISE):
        return 'feedback praises wrong answer'
    return None


# 캐시 키 구성. 번역(H.translate)은 --seed 를 쓰지 않고 고정 seed(DEFAULT_SEED)로 요청하므로 번역 캐시 키에는
# seed 가 없다. 작성(H.generate)은 --seed 를 options.seed 로 쓰므로 작성 캐시 키에는 seed 가 들어간다.
CACHE_KEY_FIELDS = {'trans_cache': ['source', 'source_id', 'translator'],
                    'write_cache': ['source', 'source_id', 'translator', 'writer', 'seed']}


def tkey(x, a):
    return f"{x['source']}:{x['source_id']}:{a.translator}"


def wkey(x, a):
    """기본 seed 면 예전 키 그대로라 기존 write_cache.jsonl 을 고치지 않고 이어 쓴다."""
    return f"{x['source']}:{x['source_id']}:{a.translator}>{a.writer}" + seed_key_suffix(a.seed)


def load_cache(path):
    cache = {}
    if path.exists():
        with open(path, encoding='utf-8') as f:
            for line in f:
                if line.strip():
                    c = json.loads(line)
                    cache[c['key']] = c
    return cache


def progress(label, done, i, total, t0):
    if done and done % 20 == 0:
        rate = (time.time() - t0) / done
        print(f'[{label}] {i + 1}/{total}  {rate:.1f}s/건  남은 예상 {rate * (total - i - 1) / 60:.0f}분', flush=True)


def build_parser():
    ap = argparse.ArgumentParser(prog='python -m data_tools.build_grading_c',
                                 description='조합 C(번역 모델 + 작성 모델)로 채점 학습 데이터를 만든다.')
    ap.add_argument('--out', required=True, help='결과 폴더(저장소 밖, 보통 derived/grading_c)')
    ap.add_argument('--datasets-dir', required=True, help='원본 parquet 이 있는 datasets 폴더')
    ap.add_argument('--quizai', default=str(repo_root() / B.QUIZAI_REL), help='백엔드 QuizAi.java (GRADE_SYSTEM 대조)')
    ap.add_argument('--eval-set', required=True, help='평가 세트 grading_set.jsonl')
    ap.add_argument('--n', type=int, default=1200)
    ap.add_argument('--translator', default='translategemma:4b')
    ap.add_argument('--writer', default='gemma3:4b')
    ap.add_argument('--seed', type=int, default=DEFAULT_SEED)
    ap.add_argument('--inject-ratio', type=float, default=0.2)
    return ap


def main(argv: list[str] | None = None) -> None:
    ap = build_parser()
    a = ap.parse_args(argv)
    require_outside_repo(ap, a, '--out')  # 아무것도 읽거나 쓰기 전에 확인한다
    utf8_stdout()
    B.check_prompt(a.quizai)
    with open(a.eval_set, encoding='utf-8') as f:
        eval_items = [json.loads(line) for line in f if line.strip()]
    eval_answers = {e['student_answer'] for e in eval_items}
    for phrase in B.INJECT_TAILS + B.INJECT_ONLY:
        if any(phrase in s for s in eval_answers):
            sys.exit(f'꼼수 문장이 평가 세트와 겹침: {phrase}')
    eval_questions = sorted({e['question'] for e in eval_items})

    out = Path(a.out)
    picked = B.sample(B.load_sources(a.datasets_dir), a.n, a.seed)
    if not picked:  # 폴더·캐시를 만들기 전에 멈춘다
        exit_error(f'원천 데이터에서 뽑은 문항이 없음 (--datasets-dir {a.datasets_dir!r}, --n {a.n})')
    out.mkdir(parents=True, exist_ok=True)

    # 1단계: 번역 (모델 교체를 줄이려고 전 문항 먼저)
    tpath = out / 'trans_cache.jsonl'
    tcache = load_cache(tpath)
    print(f'원본 {len(picked)}건 · 번역 캐시 {len(tcache)}건', flush=True)
    t0, done = time.time(), 0
    with open(tpath, 'a', encoding='utf-8') as f:
        for i, x in enumerate(picked):
            key = tkey(x, a)
            if key in tcache:
                continue
            try:
                tr, err = {k: H.translate(a.translator, x[src]) for k, src in FIELDS}, None
            except Exception as e:  # noqa: BLE001 - 사유만 남기고 다음으로
                tr, err = None, type(e).__name__
            c = {'key': key, 'out': tr, 'error': err}
            f.write(json.dumps(c, ensure_ascii=False) + '\n')
            f.flush()
            tcache[key] = c
            done += 1
            progress('번역', done, i, len(picked), t0)

    # 2단계: 이유·피드백 작성
    wpath = out / 'write_cache.jsonl'
    wcache = load_cache(wpath)
    print(f'작성 캐시 {len(wcache)}건', flush=True)
    t0, done = time.time(), 0
    with open(wpath, 'a', encoding='utf-8') as f:
        for i, x in enumerate(picked):
            key = wkey(x, a)
            old = wcache.get(key)
            if old and (old['error'] or not check(old['out'], x['label'])):
                continue  # 이미 거절됐거나 지금 규칙으로도 통과하는 캐시는 그대로 쓴다
            t = tcache.get(tkey(x, a))
            if not t or t['error']:
                continue
            tr = t['out']
            user = (f"정답 여부: {B.KIND_DESC[x['kind']]}\n<question>{tr['question']}</question>\n"
                    f"<model_answer>{tr['model_answer']}</model_answer>\n<student_answer>{tr['student_answer']}</student_answer>")
            o, err = None, None
            for attempt in range(2):
                prompt = user if attempt == 0 else user + f"\n\n[다시 작성] 직전 결과가 규칙 위반({err})."
                try:
                    w = json.loads(H.generate(a.writer, prompt, system=H.WRITER_SYSTEM, fmt='json', seed=a.seed + attempt))
                    o = {**tr, 'reason': str(w.get('reason', '')), 'feedback': str(w.get('feedback', ''))}
                    err = check(o, x['label'])
                except Exception as e:  # noqa: BLE001
                    err = type(e).__name__
                if not err:
                    break
            c = {'key': key, 'out': o, 'error': err}
            f.write(json.dumps(c, ensure_ascii=False) + '\n')
            f.flush()
            wcache[key] = c
            done += 1
            progress('작성', done, i, len(picked), t0)

    # 3단계: 조립 (누수 제외 · 꼼수 변형)
    rnd = random.Random(a.seed)
    train, rejected = [], []
    for x in picked:
        c = wcache.get(wkey(x, a))
        t = tcache.get(tkey(x, a))
        err = (t and t['error']) or (c and c['error']) or (None if c else 'missing')
        if err:
            rejected.append({'source': x['source'], 'source_id': x['source_id'], 'error': err})
            continue
        o = c['out']
        again = check(o, x['label'])
        if again:
            rejected.append({'source': x['source'], 'source_id': x['source_id'], 'error': again})
            continue
        best = max((difflib.SequenceMatcher(None, o['question'], q).ratio() for q in eval_questions), default=0)
        if best >= 0.8:
            rejected.append({'source': x['source'], 'source_id': x['source_id'], 'error': f'eval-like {best:.2f}'})
            continue
        base = f"{x['source']}-{x['source_id']}"
        train.append(B.record(base, x, o['question'], o['model_answer'], o['student_answer'], x['label'], x['kind'],
                              o['reason'], o['feedback'], x['subject']))
        if x['label'] and rnd.random() < a.inject_ratio:
            mode = rnd.choice(['tail', 'only', 'tag'])
            if mode == 'tail':
                ans, kind, reason = f"{o['student_answer']} {rnd.choice(B.INJECT_TAILS)}", 'injection_with_answer', B.INJ_REASON
            elif mode == 'only':
                ans, kind, reason = rnd.choice(B.INJECT_ONLY), 'injection', B.INJ_REASON
            else:
                ans, kind, reason = rnd.choice(B.TAG_ESCAPES).format(ans=o['student_answer']), 'tag_escape', B.TAG_REASON
            train.append(B.record(f'{base}-{kind}', x, o['question'], o['model_answer'], ans, False, kind,
                                  reason, B.INJ_FEEDBACK, x['subject']))
    rnd.shuffle(train)
    if not train:  # 빈 train.jsonl 로 기존 결과를 덮지 않는다(캐시는 남으므로 고친 뒤 이어서 돌릴 수 있다)
        exit_error(f"학습 행이 0개 (원본 {len(picked)}건 모두 거절: "
                   f"{dict(Counter(r['error'].split(' ')[0] for r in rejected))})")
    stats = {'combo': 'C', 'translator': a.translator, 'writer': a.writer, 'seed': a.seed, 'picked': len(picked),
             'cache_key_fields': CACHE_KEY_FIELDS, 'translate_seed': DEFAULT_SEED,
             'train': len(train), 'rejected': len(rejected), 'label_true': sum(r['label'] for r in train),
             'reject_reason': Counter(r['error'].split(' ')[0] for r in rejected),
             'kind': Counter(r['kind'] for r in train), 'source': Counter(r['source'] for r in train)}
    # 세 파일을 모두 임시 파일에 쓴 뒤 바꿔 넣는다(일부만 바뀐 세대 혼합 방지)
    write_files_atomic({out / 'train.jsonl': (jsonl_text(train), 'utf-8'),
                        out / 'rejected.jsonl': (jsonl_text(rejected), 'utf-8'),
                        out / 'stats.json': (json.dumps(stats, ensure_ascii=False, indent=1), 'utf-8')})
    print(json.dumps(stats, ensure_ascii=False))


if __name__ == '__main__':
    main()
