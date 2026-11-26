# RAG 평가 코퍼스 출처

PT-86 RAG 평가 코퍼스의 문서별 출처와 라이선스다. 문서는 두 종류다.

- **팀 작성**: 이 저장소 팀이 평가용으로 새로 쓴 강의 노트다. 다른 자료에서 옮겨 온 문장은 없다. 본문에 나오는 "실습 측정값"은 실제 측정 기록이 아니라 수업 자료처럼 보이도록 넣은 예시 수치다. 그래도 수업 자료로 그럴듯한 범위 안에 있다.
- **위키백과 각색**: 한국어 위키백과 글을 아래 판(revision)에서 가져와 줄였다. 위키백과 글은 [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/deed.ko)으로 배포되므로, 이 다섯 파일(각색본)도 같은 CC BY-SA 4.0으로 배포한다. 저작자는 해당 글의 위키백과 기여자들이며, 기여자 목록은 각 판의 "역사" 페이지에서 볼 수 있다.

## 위키백과 각색 문서

가져온 방법: MediaWiki API `action=query&prop=extracts|revisions&explaintext=1`(TextExtracts, 일반 텍스트), 2026-11-25. 스크립트는 `src/ragEval/tools/fetch_wiki.py`(가져오기), `src/ragEval/tools/adapt_wiki.py`(각색)다. TextExtracts는 최신 판만 내주므로, 아래 판 번호로 고정한 사본은 커밋된 Markdown 파일이다.

모든 각색 문서에 공통으로 적용한 변경은 세 가지다. 첫째, `== 절 ==` 제목을 Markdown `##` 제목으로 바꿨다. 둘째, 빈 줄을 정리했다. 셋째, 맨 위에 출처 안내 한 줄을 넣었다. 문서별 추가 변경은 표에 적었다.

| 파일 | 원문 제목 | 판(oldid) | URL | 문서별 추가 변경 |
|---|---|---|---|---|
| `session-a/a1-hash-table-wiki.md` | 해시 테이블 | 42851599 | https://ko.wikipedia.org/w/index.php?title=%ED%95%B4%EC%8B%9C_%ED%85%8C%EC%9D%B4%EB%B8%94&oldid=42851599 | **생략한 절**: 역사, 문자열 해싱, 해시 함수 선택, 분리 체이닝을 위한 다른 자료 구조, 캐싱 및 참조 국부성(2곳), 병합 해싱, 홉스카치 해싱, 로빈 후드 해싱, 일괄 재해싱의 대안, 선형 해싱, 전치 테이블, 구현, 참조, 같이 보기, 외부 링크. **수식 복원**: 일반 텍스트로 뽑으면서 분수선과 띄어쓰기가 사라진 수식 5개를 평문 수식으로 되살렸다(`n / m`, `x mod m`, `⌊m((xA) mod 1)⌋`, `1 + α/2 + Θ(1/m)`, `e^(−α) + α + Θ(1/m)`). |
| `session-a/a2-deadlock-wiki.md` | 교착 상태 | 42739990 | https://ko.wikipedia.org/w/index.php?title=%EA%B5%90%EC%B0%A9_%EC%83%81%ED%83%9C&oldid=42739990 | **생략한 절**: 같이 보기, 각주, 외부 링크. |
| `session-a/a5-page-replacement-wiki.md` | 페이지 교체 알고리즘 | 42835065 | https://ko.wikipedia.org/w/index.php?title=%ED%8E%98%EC%9D%B4%EC%A7%80_%EA%B5%90%EC%B2%B4_%EC%95%8C%EA%B3%A0%EB%A6%AC%EC%A6%98&oldid=42835065 | **생략한 절**: 역사, 참조 및 수정된 페이지 감지, 사전 청소, (h,k)-페이징 문제, 마킹 알고리즘, 보수적 알고리즘, 클럭의 변형, LRU의 변형, 무작위 (Random), 최장 거리 우선 (LDF) 페이지 교체 알고리즘, 구현 세부 사항, 같이 보기, 각주. **삭제**: 수식이 깨진 문장 2개("…kk−h+1-경쟁적이다.")와, 빠진 파이썬 예제 코드를 설명하던 세 단락을 지웠다. **형식 변경**: NRU 클래스 목록(`3. 참조됨, 수정됨` …)을 Markdown 목록(`- 클래스 3: …`)으로 바꿨다. |
| `session-b/b2-normalization-wiki.md` | 데이터베이스 정규화 | 41633061 | https://ko.wikipedia.org/w/index.php?title=%EB%8D%B0%EC%9D%B4%ED%84%B0%EB%B2%A0%EC%9D%B4%EC%8A%A4_%EC%A0%95%EA%B7%9C%ED%99%94&oldid=41633061 | **생략한 절**: 인용 자료, 같이 보기, 외부 링크. **오타 수정**: 원문의 "hh관계 스키마"를 "관계 스키마"로 고쳤다. |
| `session-b/b3-dns-wiki.md` | 도메인 네임 시스템 | 42233154 | https://ko.wikipedia.org/w/index.php?title=%EB%8F%84%EB%A9%94%EC%9D%B8_%EB%84%A4%EC%9E%84_%EC%8B%9C%EC%8A%A4%ED%85%9C&oldid=42233154 | **생략한 절**: 주요 DNS 목록, 같이 보기, 각주, 외부 링크. |

## 팀 작성 문서

| 파일 | 형식 | 원본 |
|---|---|---|
| `session-a/a3-sorting-searching-notes.txt` | TXT, 번호 제목(`제n장`, `n.n`) | 이 파일 |
| `session-a/a4-os-process-sync-notes.pdf` | PDF 7쪽 | `src/ragEval/tools/pdf-src/a4-os-process-sync-notes.txt` → `make_corpus_pdfs.py` |
| `session-b/b1-db-index-notes.md` | Markdown | 이 파일 |
| `session-b/b4-transaction-concurrency-notes.pdf` | PDF 6쪽 | `src/ragEval/tools/pdf-src/b4-transaction-concurrency-notes.txt` → `make_corpus_pdfs.py` |
| `session-b/b5-network-tcp-ip-notes.txt` | TXT, 번호 제목 | 이 파일 |

PDF는 reportlab 5.1.1로 만들었다. 글꼴은 Windows에 들어 있는 맑은 고딕(`malgun.ttf`, `malgunbd.ttf`)의 일부 글자만 PDF에 넣었다. 이것은 글꼴 파일에 적힌 임베딩 권한(OS/2 fsType)이 허용하는 범위다. reportlab은 이 권한이 없는 글꼴을 넣으려 하면 오류를 낸다. 글꼴 파일 자체는 저장소에 넣지 않았다. `invariant=1`로 만들어서 다시 생성해도 바이트까지 같은 파일이 나온다.
