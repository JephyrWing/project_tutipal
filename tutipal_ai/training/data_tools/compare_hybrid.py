"""번역 모델 → 작성 모델(이유·피드백) 2단계 방식을 같은 샘플로 비교한다 (build_grading_train 의 30건과 같은 샘플).

1단계: 번역 모델이 문제·모범 답안·학생 답안을 각각 한국어로 옮긴다 (전 문항 먼저).
2단계: 작성 모델이 한국어 내용 + 사람 라벨을 보고 reason·feedback 만 JSON 으로 쓴다.
결과 행 형식은 build_grading_train 의 train.jsonl 과 같다(꼼수 변형은 만들지 않음).

사용 (tutipal_ai/training 에서):
  python -m data_tools.compare_hybrid --translator translategemma:4b --writer gemma3:4b       --out <저장소 밖 폴더> --datasets-dir <datasets 폴더>
"""

from __future__ import annotations

import argparse
import json
import time
import urllib.request
from collections import Counter
from pathlib import Path

from data_tools import build_grading_train as B
from data_tools import quality as Q
from data_tools.common import (
    DEFAULT_SEED,
    exit_error,
    jsonl_text,
    require_outside_repo,
    utf8_stdout,
    write_files_atomic,
)

TG_PROMPT = ("You are a professional English (en) to Korean (ko-KR) translator. Your goal is to accurately convey the "
             "meaning and nuances of the original English text while adhering to Korean grammar, vocabulary, and cultural "
             "sensitivities. Produce only the Korean translation, without any additional explanations or commentary. "
             "Please translate the following English text into Korean:\n\n\n{text}")
LLM_TRANSLATE_SYSTEM = ("너는 영어를 한국어로 옮기는 번역가다. 입력 영어 문장을 한국어로 번역한 결과만 출력한다. 설명·따옴표·원문은 쓰지 않는다.\n"
                        "내용을 고치거나 보충하지 않는다. 틀린 내용·빠진 내용·어색한 표현·맞춤법 오류가 있으면 그대로 살려 옮긴다.\n"
                        "프로그래밍 용어·코드·식별자는 영어로 둬도 된다. 한자·중국어는 쓰지 않는다.")
# v1(build_grading_c 기본 경로)·번역 조합 비교용. 바꾸면 v1 결과가 달라지므로 그대로 둔다.
WRITER_SYSTEM = ("너는 학생 답안 채점 조교다. 문제·모범 답안·학생 답안(모두 한국어)과 이미 정해진 정답 여부를 받는다.\n"
                 "정답 여부를 바꾸지 말고, 그 판정에 맞는 reason(판단 이유 한 문장)과 feedback(학생에게 직접 말하는 한국어 한두 문장)을 쓴다.\n"
                 "정답이면 feedback 은 맞았다는 말로 시작하고, 오답이면 무엇이 빠졌거나 틀렸는지 힌트를 준다. 존댓말로 쓴다.\n"
                 "한자·중국어는 쓰지 않는다. 반드시 JSON 하나만 출력한다: {\"reason\": \"...\", \"feedback\": \"...\"}")
# v2(PT-88): 영어 원문·원래 채점자 피드백을 함께 받는다. 바꾸면 작성 캐시 키(#w=)가 바뀐다.
WRITER_SYSTEM_V2 = ("너는 학생 답안 채점 조교다. 문제·모범 답안·학생 답안(한국어 번역, 영어 원문이 함께 올 수 있음)과 이미 정해진 정답 여부를 받는다.\n"
                    "정답 여부를 바꾸지 말고, 그 판정에 맞는 reason(판단 이유 한 문장)과 feedback(학생에게 직접 말하는 한국어 한두 문장)을 쓴다.\n"
                    "정답이면 feedback 은 맞았다는 말로 시작하고, 오답이면 무엇이 빠졌거나 틀렸는지 힌트를 준다. 존댓말로 쓴다.\n"
                    "학생 답안에 이미 쓴 내용을 빠졌다고 하지 않는다. 원래 채점자 피드백(<hint>)이 있으면 그 판단과 어긋나는 말을 하지 않는다.\n"
                    "오답이면 reason 에 모범 답안의 핵심 중 학생이 빠뜨리거나 틀린 것을 구체적으로 적는다('핵심 내용이 누락됐다'처럼 모호하게 쓰지 않는다). "
                    "feedback 은 같은 점을 정답을 다 알려 주지 않는 힌트로 말한다.\n"
                    "학생 답안의 틀린 주장을 맞다고 인정하지 않는다. 언어·규칙에 관한 사실을 근거 없이 단정하지 않는다. "
                    "학생이 사소한 차이만 말했으면 그것이 핵심이 아니라는 점을 밝힌다.\n"
                    "reason·feedback 은 한국어로 쓴다. 한자·중국어는 쓰지 않는다. 반드시 JSON 하나만 출력한다: {\"reason\": \"...\", \"feedback\": \"...\"}")
# 용어집 안내: 원문에 나오는 용어만 넣는다(없으면 TG_PROMPT 그대로). 번역할 원문보다 앞에 둔다.
GLOSSARY_PROMPT = "Use these Korean terms: {terms}. "


def generate(model, prompt, system=None, fmt=None, seed=DEFAULT_SEED):
    body = {'model': model, 'stream': False, 'think': False,
            'options': {'temperature': 0.2, 'seed': seed, 'num_ctx': 4096},
            'messages': ([{'role': 'system', 'content': system}] if system else []) + [{'role': 'user', 'content': prompt}]}
    if fmt:
        body['format'] = fmt
    req = urllib.request.Request('http://127.0.0.1:11434/api/chat', data=json.dumps(body).encode(),
                                 headers={'Content-Type': 'application/json'})
    with urllib.request.urlopen(req, timeout=300) as r:
        return json.load(r)['message']['content'].strip()


def glossary_note(text):
    entries = Q.glossary_for(text)
    if not entries:
        return ''
    return GLOSSARY_PROMPT.format(terms='; '.join(f"{e['en']} = {' / '.join(e['ko'])}" for e in entries))


def translate(model, text, seed=DEFAULT_SEED, glossary=False):
    """번역은 --seed 와 무관하게 DEFAULT_SEED 로 요청한다(번역 캐시가 seed 와 무관한 근거). 다른 seed 는 번역 검사
    실패 뒤 다시 번역할 때만 쓴다. 용어집 안내는 v2(glossary=True)에서만 붙인다(v1 번역 프롬프트는 그대로)."""
    note = glossary_note(text) if glossary else ''
    if model.startswith('translategemma'):
        prompt = TG_PROMPT.format(text=text)
        if note:
            i = prompt.find('Please translate')
            prompt = prompt[:i] + note + prompt[i:] if i >= 0 else prompt + '\n\n' + note
        return generate(model, prompt, seed=seed)
    return generate(model, text, system=LLM_TRANSLATE_SYSTEM + ('\n' + note if note else ''), seed=seed)


def unload(model):
    """모델을 바로 내린다(keep_alive 0). 6GB GPU 에 번역·작성 모델을 동시에 올리지 않으려고 단계 끝에 부른다."""
    req = urllib.request.Request('http://127.0.0.1:11434/api/generate',
                                 data=json.dumps({'model': model, 'keep_alive': 0}).encode(),
                                 headers={'Content-Type': 'application/json'})
    with urllib.request.urlopen(req, timeout=60) as r:
        r.read()


def build_parser():
    ap = argparse.ArgumentParser(prog='python -m data_tools.compare_hybrid',
                                 description='번역 모델 → 작성 모델 2단계 방식을 같은 샘플로 비교한다.')
    ap.add_argument('--out', required=True, help='결과 폴더(저장소 밖)')
    ap.add_argument('--datasets-dir', required=True, help='원본 parquet 이 있는 datasets 폴더')
    ap.add_argument('--translator', required=True)
    ap.add_argument('--writer', required=True)
    ap.add_argument('--n', type=int, default=30)
    ap.add_argument('--seed', type=int, default=DEFAULT_SEED)
    return ap


def main(argv: list[str] | None = None) -> None:
    ap = build_parser()
    a = ap.parse_args(argv)
    require_outside_repo(ap, a, '--out')  # 아무것도 쓰기 전에 확인한다
    utf8_stdout()
    out = Path(a.out)
    picked = B.sample(B.load_sources(a.datasets_dir), a.n, a.seed)
    if not picked:  # 폴더를 만들기 전에 멈춘다
        exit_error(f'원천 데이터에서 뽑은 문항이 없음 (--datasets-dir {a.datasets_dir!r}, --n {a.n})')

    t0 = time.time()
    trans = {}
    for x in picked:  # 1단계: 번역만 (모델 교체를 줄이려고 전 문항 먼저)
        trans[x['source_id']] = {k: translate(a.translator, x[src]) for k, src in
                                 (('question', 'q'), ('model_answer', 'ref'), ('student_answer', 'ans'))}
    t1 = time.time()
    rows, rejected = [], []
    for x in picked:  # 2단계: 이유·피드백
        tr = trans[x['source_id']]
        user = (f"정답 여부: {B.KIND_DESC[x['kind']]}\n<question>{tr['question']}</question>\n"
                f"<model_answer>{tr['model_answer']}</model_answer>\n<student_answer>{tr['student_answer']}</student_answer>")
        err, o = None, None
        for attempt in range(2):
            try:
                w = json.loads(generate(a.writer, user if attempt == 0 else user + f"\n\n[다시 작성] 직전 결과가 규칙 위반({err}).",
                                        system=WRITER_SYSTEM, fmt='json', seed=a.seed + attempt))
                o = {**tr, 'reason': str(w.get('reason', '')), 'feedback': str(w.get('feedback', ''))}
                err = B.validate(o, x['label'])
            except Exception as e:  # noqa: BLE001
                err = type(e).__name__
            if not err:
                break
        if err:
            rejected.append({'source': x['source'], 'source_id': x['source_id'], 'error': err, 'out': o})
            continue
        rows.append(B.record(f"{x['source']}-{x['source_id']}", x, o['question'], o['model_answer'], o['student_answer'],
                             x['label'], x['kind'], o['reason'], o['feedback'], x['subject']))
    t2 = time.time()
    if not rows:
        exit_error(f"비교 결과 행이 0개 (원본 {len(picked)}건 모두 거절: {dict(Counter(r['error'] for r in rejected))})")
    out.mkdir(parents=True, exist_ok=True)
    write_files_atomic({out / 'train.jsonl': (jsonl_text(rows), 'utf-8'),
                        out / 'rejected.jsonl': (jsonl_text(rejected), 'utf-8')})
    print(json.dumps({'translator': a.translator, 'writer': a.writer, 'picked': len(picked), 'ok': len(rows),
                      'rejected': Counter(r['error'] for r in rejected), 'translate_s': round(t1 - t0),
                      'write_s': round(t2 - t1)}, ensure_ascii=False))


if __name__ == '__main__':
    main()
