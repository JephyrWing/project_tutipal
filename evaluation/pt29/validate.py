"""Offline fixture checks only: no model, network, database or third-party package."""
import argparse
import collections
import hashlib
import json
from pathlib import Path


def require(condition, message):
    if not condition:
        raise ValueError(message)


def validate(path):
    raw = path.read_bytes()
    data = json.loads(raw.decode('utf-8'))
    require(data['schema_version'] == 1, 'unsupported schema version')
    require(data['model_results'] is None, 'preparation must not contain invented results')
    groups = [('documents', 'doc_id'), ('queries', 'query_id'), ('preprocessing_fixtures', 'id')]
    for group, key in groups:
        ids = [row[key] for row in data[group]]
        require(len(ids) == len(set(ids)), f'duplicate IDs in {group}')
    docs = {doc['doc_id']: doc for doc in data['documents']}
    texts = [doc['text'] for doc in docs.values()]
    require(len(set(texts)) == len(texts), 'duplicate document text')
    covered = set()
    evidence_count = 0
    for doc in docs.values():
        require(0 < len(doc['text']) <= 4000, f'invalid corpus document length: {doc["doc_id"]}')
        require(doc['source']['filename'] and doc['source']['page'] >= 1, 'missing source')
        require(doc['source']['origin'] == 'project_synthetic', 'unexpected provenance')
    questions = [q['text'] for q in data['queries']]
    require(len(set(questions)) == len(questions), 'duplicate query text')
    for q in data['queries']:
        pos, neg = set(q['relevant_doc_ids']), set(q['hard_negative_doc_ids'])
        require(pos | neg <= set(docs), f'unknown reference: {q["query_id"]}')
        require(not pos & neg, f'positive/negative overlap: {q["query_id"]}')
        require(q['answerable'] == bool(pos), f'answerability mismatch: {q["query_id"]}')
        require(bool(q['evidence']) == q['answerable'], f'missing or spurious evidence: {q["query_id"]}')
        require({e['doc_id'] for e in q['evidence']} == pos, f'incomplete positive evidence: {q["query_id"]}')
        require(q['reference_answer'] and 0 < len(q['text']) <= 4000, 'empty/oversize question')
        for e in q['evidence']:
            require(e['quote'] and e['quote'] in docs[e['doc_id']]['text'], f'quote not in source: {q["query_id"]}')
            evidence_count += 1
        covered |= pos
    require(covered == set(docs), 'document without positive evaluation question')
    fixtures = data['preprocessing_fixtures']
    for f in fixtures:
        if 'utf16_units' in f:
            require(len(f['input'].encode('utf-16-le')) // 2 == f['utf16_units'], f'wrong UTF-16 boundary: {f["id"]}')
        for span in f.get('must_preserve', []):
            require(span in f['input'], f'missing preservation target: {f["id"]}')
        if 'required_evidence' in f:
            require(f['input'].endswith('이다.') and f['required_evidence'] in f['input'], 'missing long tail evidence')
    return {'scope': 'offline_dataset_integrity_only', 'dataset_version': data['dataset_version'],
            'sha256': hashlib.sha256(raw).hexdigest(), 'documents': len(docs),
            'queries': len(questions), 'answerable': sum(q['answerable'] for q in data['queries']),
            'unanswerable': sum(not q['answerable'] for q in data['queries']),
            'evidence_references': evidence_count, 'preprocessing_fixtures': len(fixtures),
            'query_kinds': dict(collections.Counter(q['kind'] for q in data['queries'])),
            'model_calls': 0, 'result': 'valid'}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('path', nargs='?', type=Path, default=Path(__file__).with_name('corpus.v1.json'))
    args = parser.parse_args()
    print(json.dumps(validate(args.path), ensure_ascii=False, indent=2))
