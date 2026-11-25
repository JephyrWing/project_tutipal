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

v2 (--v2, 2026-11-25): 원천을 B.load_sources_v2(SAF train+validation, Mohler 새 정답 규칙)로 읽고,
영어 문제(qid)마다 상한을 두어 고른다(select_v2). 정답인데 답이 너무 짧은 행(missing_core)은 검수 OK·WEAK_KEEP
행만 남긴다. 과학 행은 v1 캐시가 모두 있는 것만 쓴다(LLM 0회). --cache-from 의 v1 캐시를 --out 으로 복사해
이어 쓰고(v1 폴더는 읽기만), --assemble-only 면 LLM 을 부르지 않고 캐시만으로 조립한다.

사용 (tutipal_ai/training 에서):
  python -m data_tools.build_grading_c --out <저장소 밖 derived/grading_c> --datasets-dir <datasets 폴더>       --eval-set eval/grading_set.jsonl [--quizai <QuizAi.java>] [--n 1200]
  python -m data_tools.build_grading_c --v2 --out <derived/grading_v2> --datasets-dir <datasets 폴더>       --eval-set eval/grading_set.jsonl --cache-from <derived/grading_c> [--review-true <review_true_*.csv> ...]       [--assemble-only]
"""

from __future__ import annotations

import argparse
import csv
import difflib
import hashlib
import json
import os
import random
import re
import shutil
import sys
import time
from collections import Counter, defaultdict
from collections.abc import Callable, Iterable
from pathlib import Path

from data_tools import build_grading_train as B
from data_tools import compare_hybrid as H
from data_tools import quality as Q
from data_tools.common import (
    DEFAULT_SEED,
    exit_error,
    jsonl_text,
    refuse_input_as_output,
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


def check(o, label, expl: bool = False):
    """작성 결과 검사. expl=True(v2 작성·조립)일 때만 PT-88 설명 검사를 더한다.

    v1 경로와 v2 과학 행 선별(_ok_cached, 예전 키 캐시가 있는지만 본다)은 예전 규칙 그대로다.
    """
    err = B.validate(o, label)
    if err:
        return err
    if label is False:
        head = first_sentence(o['feedback'])
        if head.startswith(('네,', '네!', '네 ')) or any(p in head for p in PRAISE):
            return 'feedback praises wrong answer'
    return Q.expl_check(o, label) if expl else None


# 캐시 키 구성. 번역(H.translate)은 --seed 를 쓰지 않고 고정 seed(DEFAULT_SEED)로 요청하므로 번역 캐시 키에는
# seed 가 없다. 작성(H.generate)은 --seed 를 options.seed 로 쓰므로 작성 캐시 키에는 seed 가 들어간다.
CACHE_KEY_FIELDS = {'trans_cache': ['source', 'source_id', 'translator'],
                    'write_cache': ['source', 'source_id', 'translator', 'writer', 'seed']}


# v2 작성 모델 입력: 한국어 번역 + 영어 원문 + (SAF) 원래 채점자 피드백. 바꾸면 작성 캐시 키(#w=)가 바뀐다.
WRITER_USER_V2 = ("정답 여부: {kind}\n<question>{q_ko}</question>\n<model_answer>{ref_ko}</model_answer>\n"
                  "<student_answer>{ans_ko}</student_answer>\n[영어 원문 (참고)]\n<question_en>{q}</question_en>\n"
                  "<model_answer_en>{ref}</model_answer_en>\n<student_answer_en>{ans}</student_answer_en>{hint}")
WRITER_HINT = "\n<hint>{hint}</hint>"
RETRANSLATE_SEED = DEFAULT_SEED + 1  # 번역 검사에 걸린 칸을 한 번 더 번역할 때


def _sha8(*parts) -> str:
    return hashlib.sha256(json.dumps(parts, ensure_ascii=False).encode()).hexdigest()[:8]


def trans_prompt_hash(text: str = '') -> str:
    """번역 프롬프트 해시: TG_PROMPT·용어집 안내·검사 규칙 버전 + text 에 나오는 용어집 항목만.

    용어를 추가해도 그 용어가 든 원문만 키가 바뀐다(다시 번역). text 가 비면 기본 프롬프트 해시(stats 에 남김).
    모듈 속성을 호출 시점에 읽는다(테스트에서 바꿔 끼울 수 있게).
    """
    return _sha8(H.TG_PROMPT, H.LLM_TRANSLATE_SYSTEM, H.GLOSSARY_PROMPT, Q.CHECK_VERSION, Q.glossary_for(text))


def writer_prompt_hash() -> str:
    return _sha8(H.WRITER_SYSTEM_V2, WRITER_USER_V2, WRITER_HINT, B.KIND_DESC)


def old_tkey(x, a):
    """v1·v2(2026-11-25) 번역 캐시 키. v2 과학 행 선별(_ok_cached)은 이 키로 본다."""
    return f"{x['source']}:{x['source_id']}:{a.translator}"


def old_wkey(x, a):
    """기본 seed 면 예전 키 그대로라 기존 write_cache.jsonl 을 고치지 않고 이어 쓴다."""
    return f"{x['source']}:{x['source_id']}:{a.translator}>{a.writer}" + seed_key_suffix(a.seed)


def _row_en(x) -> str:
    return '\n'.join(x.get(src, '') for _, src in FIELDS)


def tkey(x, a):
    """v2 는 프롬프트 해시(#p=)를 붙인다. 프롬프트·용어집·검사 규칙이 바뀌면 다시 번역한다(예전 줄은 그대로 둠)."""
    if not getattr(a, 'v2', False):
        return old_tkey(x, a)
    return f"{old_tkey(x, a)}#p={trans_prompt_hash(_row_en(x))}"


def wkey(x, a):
    if not getattr(a, 'v2', False):
        return old_wkey(x, a)
    return f"{old_wkey(x, a)}#w={writer_prompt_hash()}"


def same_translation(c: dict, tr: dict) -> bool:
    """작성 캐시가 지금 번역으로 쓴 것인가. 번역이 바뀌면(키는 같아도) 이유·피드백을 다시 쓴다.
    작성이 예외로 끝나 out 이 없는 줄은 비교할 수 없어 그대로 둔다."""
    return c['out'] is None or all(c['out'].get(k) == tr.get(k) for k, _ in FIELDS)


# LLM 호출이 예외로 끝난 오류(Ollama 연결 끊김·타임아웃·GPU 메모리 부족으로 인한 HTTP 500 등).
# 검사 규칙 위반과 달리 내용 문제가 아니므로, 같은 명령으로 이어 돌리면 그 행을 다시 시도한다.
TRANSIENT_ERRORS = frozenset({'URLError', 'HTTPError', 'TimeoutError', 'timeout', 'OSError', 'ConnectionError',
                              'ConnectionResetError', 'ConnectionRefusedError', 'ConnectionAbortedError',
                              'RemoteDisconnected', 'IncompleteRead', 'BrokenPipeError'})


def transient(err: str | None) -> bool:
    return err in TRANSIENT_ERRORS


def trans_error(x: dict, tr: dict) -> str | None:
    """번역 세 칸을 trans_check 로 검사한다. 'trans_check:<규칙>:<칸>' 또는 None."""
    for k, src in FIELDS:
        rule = Q.trans_check(x[src], tr.get(k) or '')
        if rule:
            return f"trans_check:{rule}:{k}"
    return None


def no_answer(x: dict, a) -> bool:
    """v2 무응답 행: 작성 모델을 부르지 않고 고정 문장(Q.NO_ANSWER_*)을 쓴다."""
    return bool(getattr(a, 'v2', False)) and x['label'] is False and Q.is_no_answer(x['ans'])


def writer_prompt(x: dict, tr: dict, v2: bool) -> str:
    if not v2:
        return (f"정답 여부: {B.KIND_DESC[x['kind']]}\n<question>{tr['question']}</question>\n"
                f"<model_answer>{tr['model_answer']}</model_answer>\n<student_answer>{tr['student_answer']}</student_answer>")
    hint = WRITER_HINT.format(hint=x['hint']) if x.get('hint') else ''
    return WRITER_USER_V2.format(kind=B.KIND_DESC[x['kind']], q_ko=tr['question'], ref_ko=tr['model_answer'],
                                 ans_ko=tr['student_answer'], q=x['q'], ref=x['ref'], ans=x['ans'], hint=hint)


def unload(model: str) -> None:
    """모델 언로드(keep_alive 0). 실패해도 결과에는 영향이 없어 경고만 남긴다."""
    try:
        H.unload(model)
    except Exception as e:  # noqa: BLE001 - Ollama 가 꺼져 있어도 캐시·조립은 계속한다
        print(f'경고: {model} 언로드 실패 ({type(e).__name__})', file=sys.stderr, flush=True)


def _parse_cache_lines(path: Path) -> tuple[list[dict], bool]:
    """캐시 줄을 읽는다. (레코드, 마지막 줄이 깨졌는지). 강제 종료로 마지막 줄만 잘린 경우는 그 줄을 버린다.

    마지막이 아닌 줄이 깨졌으면 손상이므로 ValueError(조용히 버리지 않는다).
    """
    # 바이트로 읽는다: 한국어 기록 중 끊기면 마지막 줄이 UTF-8 문자 중간에서 잘려 텍스트 읽기가 통째로 실패한다.
    lines = [(no, line) for no, line in enumerate(path.read_bytes().split(b'\n'), 1) if line.strip()]
    out = []
    for i, (no, line) in enumerate(lines):
        try:
            out.append(json.loads(line.decode('utf-8')))
        except ValueError:  # UnicodeDecodeError, JSONDecodeError 모두 ValueError
            if i == len(lines) - 1:
                return out, True
            raise ValueError(f'{path.name}:{no}: 캐시 줄이 깨짐(마지막 줄이 아님)') from None
    return out, False


def load_cache(path):
    cache = {}
    if path.exists():
        records, broken_tail = _parse_cache_lines(path)
        if broken_tail:
            print(f'경고: {path.name} 마지막 줄이 깨져(강제 종료 흔적) 버리고 읽음', file=sys.stderr, flush=True)
        for c in records:
            cache[c['key']] = c
    return cache


def repair_tail(path: Path) -> bool:
    """이어 쓰기 전에, 깨진 마지막 줄을 잘라 내고 파일이 줄바꿈으로 끝나게 한다(이 실행이 쓰는 out 의 캐시만).

    잘린 줄 뒤에 새 레코드를 붙이면 두 줄이 한 줄로 합쳐져 다음 실행에서 못 읽는다. 고쳤으면 True.
    """
    if not path.exists() or path.stat().st_size == 0:
        return False
    data = path.read_bytes()
    _, broken_tail = _parse_cache_lines(path)
    if broken_tail:
        cut = data.rstrip(b'\r\n').rfind(b'\n') + 1  # 깨진 마지막 줄의 시작
        with open(path, 'r+b') as f:
            f.truncate(cut)
        print(f'경고: {path.name} 깨진 마지막 줄을 잘라 냄', file=sys.stderr, flush=True)
        return True
    if not data.endswith(b'\n'):
        with open(path, 'ab') as f:
            f.write(b'\n')
        return True
    return False


def progress(label, done, i, total, t0):
    if done and done % 20 == 0:
        rate = (time.time() - t0) / done
        print(f'[{label}] {i + 1}/{total}  {rate:.1f}s/건  남은 예상 {rate * (total - i - 1) / 60:.0f}분', flush=True)


# ---------------- v2 (PT-88 학습 데이터 v2) ----------------

# 영어 문제(qid) 하나에서 고를 최대 행 수. 정리 단계(clean --cap-by-source)의 상한보다 넉넉히 뽑아 둔다
# (번역·작성 거절, 중복, 금지 용어로 빠지는 행을 메우려고). 과학은 상한 없이 캐시가 있는 행만 쓴다.
V2_SELECT_CAPS = {"MohlerASAG": 20, "SAF-communication-networks": 16}
V2_INJECT_RATIO = 0.15
V1_INJECT_RATIO = 0.2
# 라운드로빈 순서: 오답을 먼저(정답 비율이 과하지 않게), 그다음 부분·무응답·정답.
V2_KIND_ORDER = ("wrong", "partial", "unknown", "paraphrase")
SCIENCE_SOURCE = "SciEntsBank"
CODING_SUBJECT = "코딩"
REVIEW_KEEP_VERDICTS = frozenset({"OK", "WEAK_KEEP"})
REVIEW_EXCLUDE_VERDICTS = frozenset({"LABEL_ERR"})
MISSING_CORE_MIN_WORDS = 5
MISSING_CORE_REF_RATIO = 0.25
CACHE_FILES = ("trans_cache.jsonl", "write_cache.jsonl")
# 이 명령이 --out 에 쓰는 파일(사람이 채운 입력과 같은 경로면 거부).
OUTPUT_FILES = ("train.jsonl", "rejected.jsonl", "stats.json", *CACHE_FILES, "text_cache.jsonl")


def item_id(x: dict) -> str:
    return f"{x['source']}-{x['source_id']}"


def _rank(seed: int, x: dict) -> str:
    return hashlib.sha256(f"{seed}\x1f{x['source']}\x1f{x['source_id']}".encode()).hexdigest()


def _kind_order(kinds: Iterable[str]) -> list[str]:
    known = [k for k in V2_KIND_ORDER if k in kinds]
    return known + sorted(k for k in kinds if k not in V2_KIND_ORDER)


def select_v2(items: list[dict], seed: int, cached: Callable[[dict], bool],
              caps: dict[str, int] | None = None) -> list[dict]:
    """영어 단계 선별. 입력 순서와 무관하게 결정적이고 입력을 바꾸지 않는다.

    - 과학(SciEntsBank)은 cached(x) 가 True 인 행만(LLM 을 새로 부르지 않는다). 상한 없음.
    - 그 밖의 출처는 qid 마다 caps[출처] 개까지, 답안 유형을 돌아가며(오답 먼저) 고른다.
      유형 안의 순서는 sha256(seed, source, source_id).
    """
    caps = V2_SELECT_CAPS if caps is None else caps
    groups: dict[str, list[dict]] = defaultdict(list)
    for x in items:
        if x["source"] == SCIENCE_SOURCE and not cached(x):
            continue
        groups[x["qid"]].append(x)
    out: list[dict] = []
    for qid in sorted(groups):
        g = groups[qid]
        cap = caps.get(g[0]["source"])
        by_kind: dict[str, list[dict]] = defaultdict(list)
        for x in sorted(g, key=lambda x: _rank(seed, x)):
            by_kind[x["kind"]].append(x)
        if cap is None or len(g) <= cap:
            out += sorted(g, key=lambda x: _rank(seed, x))
            continue
        order = _kind_order(by_kind)
        picked: list[dict] = []
        depth = 0
        while len(picked) < cap:
            for k in order:
                if depth < len(by_kind[k]) and len(picked) < cap:
                    picked.append(by_kind[k][depth])
            depth += 1
        out += picked
    return out


def missing_core(x: dict) -> bool:
    """정답 행인데 영어 학생 답이 너무 짧아 핵심이 빠졌을 가능성이 큰가(공백 기준 단어 수).

    5단어 미만이거나 모범 답안 단어 수의 25% 미만이면 True. 정답이 아닌 행은 항상 False.
    """
    if x["label"] is not True:
        return False
    n_ans = len(x["ans"].split())
    return n_ans < MISSING_CORE_MIN_WORDS or n_ans < len(x["ref"].split()) * MISSING_CORE_REF_RATIO


def drop_missing_core(items: list[dict], keep_ids: set[str]) -> tuple[list[dict], list[str]]:
    """missing_core 행을 뺀다(keep_ids 에 있는 사람 검수 통과 행은 남긴다). (남은 행, 뺀 id 정렬)."""
    kept, dropped = [], []
    for x in items:
        if missing_core(x) and item_id(x) not in keep_ids:
            dropped.append(item_id(x))
        else:
            kept.append(x)
    return kept, sorted(dropped)


def _review_ids(paths: Iterable[str | os.PathLike], verdicts: frozenset[str]) -> set[str]:
    out: set[str] = set()
    for path in paths:
        with open(path, encoding="utf-8-sig", newline="") as f:
            reader = csv.DictReader(f)
            missing = {"id", "verdict"} - set(reader.fieldnames or [])
            if missing:
                raise ValueError(f"{Path(path).name}: 열이 없음: {sorted(missing)}")
            out |= {row["id"] for row in reader if (row["verdict"] or "").strip() in verdicts}
    return out


def review_keep_ids(paths: Iterable[str | os.PathLike]) -> set[str]:
    """정답 검수표(review_true_*.csv, 열 id,scope,verdict,reason)에서 verdict 가 OK·WEAK_KEEP 인 id."""
    return _review_ids(paths, REVIEW_KEEP_VERDICTS)


def review_exclude_ids(paths: Iterable[str | os.PathLike]) -> set[str]:
    """사람 검수표(review_sheet.csv)에서 verdict 가 LABEL_ERR 인 id(원천 라벨이 틀려 학습에서 뺀다)."""
    return _review_ids(paths, REVIEW_EXCLUDE_VERDICTS)


def cache_source(out: Path, cache_from: Path | None, name: str) -> Path:
    """읽을 캐시 파일: --out 에 있으면 그것(이어 쓰기), 없으면 --cache-from 의 v1 파일."""
    own = out / name
    if own.exists() or cache_from is None:
        return own
    return cache_from / name


def seed_caches(out: Path, cache_from: Path | None) -> list[str]:
    """--out 에 없는 캐시만 --cache-from 에서 복사한다(임시 파일 + 바꿔 넣기, v1 파일은 읽기만). 복사한 이름 목록."""
    copied = []
    if cache_from is None:
        return copied
    for name in CACHE_FILES:
        src, dst = cache_from / name, out / name
        if dst.exists() or not src.exists():
            continue
        tmp = out / f".{name}.copy.tmp"
        shutil.copyfile(src, tmp)
        os.replace(tmp, dst)
        copied.append(name)
    return copied


def overlaps(a: str | os.PathLike, b: str | os.PathLike) -> bool:
    """두 폴더가 같거나 한쪽이 다른 쪽 안이면 True(실제 경로·대소문자 정규화)."""
    pa = os.path.normcase(os.path.realpath(os.path.abspath(a)))
    pb = os.path.normcase(os.path.realpath(os.path.abspath(b)))
    if os.path.splitdrive(pa)[0] != os.path.splitdrive(pb)[0]:
        return False
    common = os.path.commonpath([pa, pb])
    return common in (pa, pb)


TEXT_CACHE = 'text_cache.jsonl'


def text_key(translator: str, text: str) -> str:
    return f"{translator}|{hashlib.sha256(text.encode()).hexdigest()}#p={trans_prompt_hash(text)}"


class TextMemo:
    """v2: 같은 영어 원문(문제·모범 답안이 qid 마다 반복)을 한 번만 번역하는 텍스트 단위 메모.

    행 캐시(trans_cache.jsonl)의 키·레코드는 그대로 두고 그 위에 얹는다. 행 캐시에 이미 있는 행은 행 캐시를
    그대로 쓰므로 선별·조립 결과는 바뀌지 않는다. 번역 검사(trans_check)에 걸리면 다른 seed 로 한 번 더 번역하고,
    검사를 통과한 번역만 out/text_cache.jsonl 에 한 줄씩 이어 쓴다(메모에 든 번역은 검사를 통과한 것).
    """

    def __init__(self, path: Path, translate: Callable[[str, str], str]):
        self.path = path
        self.translate = translate
        repair_tail(path)
        self.memo = {c['key']: c['out'] for c in load_cache(path).values()}
        # 두 seed 모두 검사에 걸린 번역. 파일에는 쓰지 않고(메모 파일엔 통과한 번역만) 이번 실행 동안만 기억해서,
        # 같은 문제·모범 답안이 qid 의 다른 행에서 또 나와도 다시 번역하지 않는다(seed 고정이라 결과도 같다).
        self.failed: dict[str, str] = {}
        self.hits = 0

    def __call__(self, translator: str, text: str) -> str:
        key = text_key(translator, text)
        if key in self.memo:
            self.hits += 1
            return self.memo[key]
        if key in self.failed:
            self.hits += 1
            return self.failed[key]
        ko = self.translate(translator, text, seed=DEFAULT_SEED, glossary=True)
        if Q.trans_check(text, ko):
            ko = self.translate(translator, text, seed=RETRANSLATE_SEED, glossary=True)
            if Q.trans_check(text, ko):
                self.failed[key] = ko
                return ko  # 저장하지 않는다. 행은 trans_error 로 거절된다
        with open(self.path, 'a', encoding='utf-8') as f:
            f.write(json.dumps({'key': key, 'out': ko}, ensure_ascii=False) + '\n')
        self.memo[key] = ko
        return ko


class CallCounter:
    """LLM 함수 호출 수를 센다(H.translate / H.generate 를 부를 때마다 1)."""

    def __init__(self, fn: Callable):
        self.fn = fn
        self.calls = 0

    def __call__(self, *args, **kwargs):
        self.calls += 1
        return self.fn(*args, **kwargs)



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
    ap.add_argument('--inject-ratio', type=float, default=None,
                    help=f'정답 답안 중 꼼수 변형을 만들 비율 (기본 v1 {V1_INJECT_RATIO}, v2 {V2_INJECT_RATIO})')
    ap.add_argument('--v2', action='store_true', help='학습 데이터 v2 선별(qid 상한·SAF validation·과학은 캐시만)')
    ap.add_argument('--cache-from', help='v2: 재사용할 v1 캐시 폴더(읽기만). --out 에 캐시가 없을 때만 복사한다')
    ap.add_argument('--assemble-only', action='store_true', help='LLM 을 부르지 않고 캐시만으로 조립한다')
    ap.add_argument('--review-true', nargs='+', default=[],
                    help='v2: 정답 검수표 CSV(review_true_*.csv). OK·WEAK_KEEP 행은 짧아도 남긴다')
    ap.add_argument('--exclude-review', nargs='+', default=[],
                    help='v2: 사람 검수표 CSV(review_sheet.csv). verdict 가 LABEL_ERR 인 행은 선별 전에 뺀다')
    return ap


def _ok_cached(x: dict, a: argparse.Namespace, tcache: dict, wcache: dict) -> bool:
    """예전 키(old_tkey·old_wkey) 번역·작성 캐시가 모두 있고 오류가 없으며 예전 작성 검사도 통과하면 True.

    PT-88 설명 검사(expl)는 보지 않는다. 고른 행은 어차피 새 프롬프트로 다시 쓰므로 예전 설명으로 거르지 않는다.

    v2 과학 행 선별 기준(2026-11-25 v2 때 만든 캐시가 있는 행만). 고른 뒤에는 새 프롬프트로 다시 번역·작성한다.
    """
    t, c = tcache.get(old_tkey(x, a)), wcache.get(old_wkey(x, a))
    return bool(t and not t['error'] and c and not c['error'] and check(c['out'], x['label']) is None)


def pick_v2(a: argparse.Namespace, out: Path,
            cache_from: Path | None) -> tuple[list[dict], list[str], list[dict]]:
    """v2 선별: 원천(라벨 규칙) → 검수 LABEL_ERR 제외 → 핵심 누락 정답 제외 → qid 상한(과학은 예전 캐시가 있는 행만).

    (고른 항목, 핵심 누락으로 뺀 id, 라벨 규칙·검수로 뺀 행 {'source','source_id','error'}).
    """
    tcache = load_cache(cache_source(out, cache_from, 'trans_cache.jsonl'))
    wcache = load_cache(cache_source(out, cache_from, 'write_cache.jsonl'))
    excluded = review_exclude_ids(a.exclude_review)
    label_drops: list[dict] = []
    items = []
    for x in B.load_sources_v2(a.datasets_dir, dropped=label_drops):
        if item_id(x) in excluded:
            label_drops.append({'source': x['source'], 'source_id': x['source_id'], 'error': 'exclude_review'})
        else:
            items.append(x)
    items, dropped = drop_missing_core(items, review_keep_ids(a.review_true))
    return select_v2(items, a.seed, lambda x: _ok_cached(x, a, tcache, wcache)), dropped, label_drops


def main(argv: list[str] | None = None) -> None:
    ap = build_parser()
    a = ap.parse_args(argv)
    require_outside_repo(ap, a, '--out')  # 아무것도 읽거나 쓰기 전에 확인한다
    refuse_input_as_output(ap, {'--review-true': a.review_true, '--exclude-review': a.exclude_review},
                           [Path(a.out) / n for n in OUTPUT_FILES])
    if a.inject_ratio is None:
        a.inject_ratio = V2_INJECT_RATIO if a.v2 else V1_INJECT_RATIO
    if not a.v2 and (a.cache_from or a.review_true or a.exclude_review):
        ap.error('--cache-from, --review-true, --exclude-review 는 --v2 와 함께만 쓴다')
    cache_from = Path(a.cache_from) if a.cache_from else None
    if cache_from is not None and not cache_from.is_dir():
        ap.error(f'--cache-from 폴더가 없음: {a.cache_from!r}')
    if cache_from is not None and overlaps(a.out, cache_from):
        # 같은 폴더면 v1 캐시에 이어 쓰고 v1 train.jsonl·stats.json 을 덮는다(v1 폴더는 읽기만 해야 한다)
        ap.error(f'--out 과 --cache-from 은 서로 다른(포함 관계도 아닌) 폴더여야 한다: {a.out!r}, {a.cache_from!r}')
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
    missing_core_ids: list[str] = []
    label_drops: list[dict] = []
    if a.v2:
        try:
            picked, missing_core_ids, label_drops = pick_v2(a, out, cache_from)
        except (OSError, ValueError) as e:  # 검수표 없음·열 없음, 캐시 손상 등
            exit_error(str(e))
        where = f'--datasets-dir {a.datasets_dir!r} (v2 는 --n 을 쓰지 않음)'
    else:
        picked = B.sample(B.load_sources(a.datasets_dir), a.n, a.seed)
        where = f'--datasets-dir {a.datasets_dir!r}, --n {a.n}'
    if not picked:  # 폴더·캐시를 만들기 전에 멈춘다
        exit_error(f'원천 데이터에서 뽑은 문항이 없음 ({where})')
    out.mkdir(parents=True, exist_ok=True)
    copied = seed_caches(out, cache_from)
    if copied:
        print(f'v1 캐시 복사: {copied} ({cache_from} → {out})', flush=True)
    # 모듈 속성을 호출 시점에 찾아야 테스트의 가짜 LLM 이 들어간다. 호출 수는 stats 에 남긴다.
    translate, generate = CallCounter(H.translate), CallCounter(H.generate)
    memo = None
    if a.v2 and not a.assemble_only:
        memo = TextMemo(out / TEXT_CACHE, translate)
    translate_text = memo or translate

    # 1단계: 번역 (모델 교체를 줄이려고 전 문항 먼저)
    tpath = out / 'trans_cache.jsonl'
    if not a.assemble_only:
        repair_tail(tpath)
    tcache = load_cache(tpath)
    print(f'원본 {len(picked)}건 · 번역 캐시 {len(tcache)}건', flush=True)
    if not a.assemble_only:
        t0, done = time.time(), 0
        with open(tpath, 'a', encoding='utf-8') as f:
            for i, x in enumerate(picked):
                key = tkey(x, a)
                if key in tcache and not transient(tcache[key]['error']):
                    continue
                try:
                    tr, err = {k: translate_text(a.translator, x[src]) for k, src in FIELDS}, None
                    if a.v2:  # 메모가 검사에 걸린 칸을 이미 한 번 다시 번역했다. 그래도 걸리면 거절
                        err = trans_error(x, tr)
                except Exception as e:  # noqa: BLE001 - 사유만 남기고 다음으로
                    tr, err = None, type(e).__name__
                c = {'key': key, 'out': tr, 'error': err}
                f.write(json.dumps(c, ensure_ascii=False) + '\n')
                f.flush()
                tcache[key] = c
                done += 1
                progress('번역', done, i, len(picked), t0)
        if translate.calls:
            unload(a.translator)  # 작성 모델을 올리기 전에 내린다(한 번에 한 모델)

    # 2단계: 이유·피드백 작성
    wpath = out / 'write_cache.jsonl'
    if not a.assemble_only:
        repair_tail(wpath)
    wcache = load_cache(wpath)
    print(f'작성 캐시 {len(wcache)}건', flush=True)
    if not a.assemble_only:
        t0, done = time.time(), 0
        with open(wpath, 'a', encoding='utf-8') as f:
            for i, x in enumerate(picked):
                key = wkey(x, a)
                old = wcache.get(key)
                t = tcache.get(tkey(x, a))
                if not t or t['error'] or no_answer(x, a):
                    continue
                tr = t['out']
                if old and same_translation(old, tr) and not transient(old['error']):
                    if old['out'] is not None and not check(old['out'], x['label'], a.v2):
                        continue  # 지금 규칙으로 통과하는 캐시는 그대로 쓴다(규칙이 느슨해져 살아난 거절 줄 포함)
                    if old['error']:
                        continue  # 이미 두 번 써서 규칙 위반으로 거절됐고 지금 규칙으로도 그렇다
                user = writer_prompt(x, tr, a.v2)
                o, err = None, None
                for attempt in range(2):
                    prompt = user if attempt == 0 else user + f"\n\n[다시 작성] 직전 결과가 규칙 위반({err})."
                    try:
                        system = H.WRITER_SYSTEM_V2 if a.v2 else H.WRITER_SYSTEM
                        w = json.loads(generate(a.writer, prompt, system=system, fmt='json', seed=a.seed + attempt))
                        o = {**tr, 'reason': str(w.get('reason', '')), 'feedback': str(w.get('feedback', ''))}
                        err = check(o, x['label'], a.v2)
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
        if generate.calls:
            unload(a.writer)

    # 3단계: 조립 (누수 제외 · 꼼수 변형)
    rnd = random.Random(a.seed)
    train, rejected = [], list(label_drops)
    templated: Counter = Counter()
    for x in picked:
        c = wcache.get(wkey(x, a))
        t = tcache.get(tkey(x, a))
        noans = no_answer(x, a) and t is not None
        werr = c and c['error']
        if werr and c['out'] is not None and not check(c['out'], x['label'], a.v2):
            werr = None  # 규칙 위반으로 거절됐지만 지금 규칙으로는 통과한다(번역 일치는 아래 stale_write 가 본다)
        err = (t and t['error']) or (None if noans else (werr or (None if c else 'missing')))
        if not err and a.v2:  # 캐시 번역도 지금 규칙으로 다시 검사하고, 다른 번역으로 쓴 작성 캐시는 쓰지 않는다
            err = (('missing' if t is None else trans_error(x, t['out']))
                   or (None if noans or same_translation(c, t['out']) else 'stale_write'))
        if err:
            rejected.append({'source': x['source'], 'source_id': x['source_id'], 'error': err})
            continue
        if noans:
            o = {**t['out'], 'reason': Q.NO_ANSWER_REASON, 'feedback': Q.NO_ANSWER_FEEDBACK}
        else:
            o = c['out']
        again = check(o, x['label'], a.v2)
        if again:
            rejected.append({'source': x['source'], 'source_id': x['source_id'], 'error': again})
            continue
        best = max((difflib.SequenceMatcher(None, o['question'], q).ratio() for q in eval_questions), default=0)
        if best >= 0.8:
            rejected.append({'source': x['source'], 'source_id': x['source_id'], 'error': f'eval-like {best:.2f}'})
            continue
        base = f"{x['source']}-{x['source_id']}"
        new_rows = [B.record(base, x, o['question'], o['model_answer'], o['student_answer'], x['label'], x['kind'],
                             o['reason'], o['feedback'], x['subject'])]
        # v2: 꼼수 변형의 바탕은 코딩 행만(수용 기준 12를 v2 전체에 적용). 행 선별·캐시와는 무관하다.
        trick_base = x['label'] and (not a.v2 or x['subject'] == CODING_SUBJECT)
        if trick_base and rnd.random() < a.inject_ratio:
            mode = rnd.choice(['tail', 'only', 'tag'])
            if mode == 'tail':
                ans, kind, reason = f"{o['student_answer']} {rnd.choice(B.INJECT_TAILS)}", 'injection_with_answer', B.INJ_REASON
            elif mode == 'only':
                ans, kind, reason = rnd.choice(B.INJECT_ONLY), 'injection', B.INJ_REASON
            else:
                ans, kind, reason = rnd.choice(B.TAG_ESCAPES).format(ans=o['student_answer']), 'tag_escape', B.TAG_REASON
            new_rows.append(B.record(f'{base}-{kind}', x, o['question'], o['model_answer'], ans, False, kind,
                                     reason, B.INJ_FEEDBACK, x['subject']))
        if a.v2:
            for r in new_rows:
                r['qid'] = x['qid']  # 꼼수 행도 바탕 문제의 qid 를 가진다
        train += new_rows
        if noans:
            templated[x['source']] += 1
    rnd.shuffle(train)
    if not train:  # 빈 train.jsonl 로 기존 결과를 덮지 않는다(캐시는 남으므로 고친 뒤 이어서 돌릴 수 있다)
        exit_error(f"학습 행이 0개 (원본 {len(picked)}건 모두 거절: "
                   f"{dict(Counter(r['error'].split(' ')[0] for r in rejected))})")
    stats = {'combo': 'C', 'translator': a.translator, 'writer': a.writer, 'seed': a.seed, 'picked': len(picked),
             'cache_key_fields': CACHE_KEY_FIELDS, 'translate_seed': DEFAULT_SEED,
             'train': len(train), 'rejected': len(rejected), 'label_true': sum(r['label'] for r in train),
             'reject_reason': Counter(r['error'].split(' ')[0] for r in rejected),
             'kind': Counter(r['kind'] for r in train), 'source': Counter(r['source'] for r in train),
             'inject_ratio': a.inject_ratio, 'assemble_only': a.assemble_only,
             'llm_translate_calls': translate.calls, 'llm_write_calls': generate.calls,
             'text_memo_hits': memo.hits if memo else 0}
    if a.v2:
        drops: dict[str, Counter] = defaultdict(Counter)
        for r in rejected:
            drops[r['error'].split(' ')[0] if r['error'].startswith('eval-like') else r['error']][r['source']] += 1
        if templated:
            drops['unknown_template'] = templated  # 버린 행은 아니고 무응답 고정 문장으로 남긴 행
        stats.update({'prompt_hash': {'trans': trans_prompt_hash(), 'write': writer_prompt_hash()},
                      'drops': {k: dict(v) for k, v in sorted(drops.items())},
                      'exclude_review_files': [Path(p).name for p in a.exclude_review]})
        stats.update({'v2': True, 'select_caps': V2_SELECT_CAPS,
                      'picked_source': Counter(x['source'] for x in picked),
                      'picked_questions': len({x['qid'] for x in picked}),
                      'cache_from': str(cache_from) if cache_from else None, 'cache_copied': copied,
                      'review_true_files': [Path(p).name for p in a.review_true],
                      'missing_core_dropped': len(missing_core_ids), 'missing_core_dropped_ids': missing_core_ids})
    # 세 파일을 모두 임시 파일에 쓴 뒤 바꿔 넣는다(일부만 바뀐 세대 혼합 방지)
    write_files_atomic({out / 'train.jsonl': (jsonl_text(train), 'utf-8'),
                        out / 'rejected.jsonl': (jsonl_text(rejected), 'utf-8'),
                        out / 'stats.json': (json.dumps(stats, ensure_ascii=False, indent=1), 'utf-8')})
    print(json.dumps({k: v for k, v in stats.items() if k != 'missing_core_dropped_ids'}, ensure_ascii=False))


if __name__ == '__main__':
    main()
