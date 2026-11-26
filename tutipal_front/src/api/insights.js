import { request } from './client.js'

/**
 * PT-87 개념 분석 (선생님 전용). 계약: docs/PT-87_INSIGHTS_CONTRACT.md
 * ConceptController · InsightsController. 응답에는 질문 원문 · 학생 식별자가 없고 (숙련도 표의 학생 이름만 예외),
 * 화면은 전부 텍스트로만 그린다.
 *
 * @typedef {{ id: number, name: string }} Concept
 * @typedef {{ conceptId: number, name: string, source: 'TEACHER' | 'AI' }} QuizConcept
 * @typedef {{ rank: number, label: string, conceptId: number | null, questionCount: number, studentCount: number }} Topic
 * @typedef {'ok' | 'lowQuality' | 'insufficient' | 'unavailable'} ConfusionsState
 * @typedef {{
 *   state: ConfusionsState, message: string | null, reason: string | null,
 *   questionCount: number, studentCount: number, cached: boolean, topics: Topic[]
 * }} ConfusionsView
 * @typedef {{ conceptId: number, pKnown: number | null, observations: number, lowConfidence: boolean }} MasteryCell
 * @typedef {{ id: number, name: string, quizCount: number, classAvg: number | null, belowHalf: boolean }} MasteryConcept
 * @typedef {{ userId: number, name: string, cells: MasteryCell[] }} MasteryStudent
 * @typedef {{ concepts: MasteryConcept[], students: MasteryStudent[] }} MasteryView
 */

export const INSUFFICIENT_MESSAGE = '데이터가 부족해요. 학생 질문이 더 모이면 다시 분석해 주세요'
export const LOW_QUALITY_MESSAGE = '질문들이 뚜렷하게 묶이지 않아 경향이 약해요. 참고만 해 주세요'
export const UNAVAILABLE_MESSAGE = 'AI 분석을 지금 할 수 없어요. 잠시 후 다시 시도해 주세요'
export const MAX_TOPICS = 3

const STATES = { OK: 'ok', LOW_QUALITY: 'lowQuality', INSUFFICIENT_DATA: 'insufficient' }

const send = (method, body) => ({
  method,
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify(body),
})

const count = (value) => (Number.isFinite(value) ? value : 0)

/** 허용한 필드만 남긴다 (서버가 실수로 다른 값을 보내도 화면에 흘리지 않게). */
function topicOf(topic) {
  return {
    rank: count(topic?.rank),
    label: String(topic?.label ?? ''),
    conceptId: Number.isFinite(topic?.conceptId) ? topic.conceptId : null,
    questionCount: count(topic?.questionCount),
    studentCount: count(topic?.studentCount),
  }
}

/**
 * POST confusions 응답 → 화면 상태. topics 는 rank 순 최대 3개.
 * @returns {ConfusionsView}
 */
export function confusionsView(body) {
  const state = STATES[body?.status]
  // 모르는 상태값은 결과를 믿지 않고 "잠시 후 다시" 로 보여 준다.
  if (!state) return unavailableView()
  const topics = state === 'insufficient' ? [] : (Array.isArray(body?.topics) ? body.topics : [])
    .map(topicOf)
    .sort((a, b) => a.rank - b.rank)
    .slice(0, MAX_TOPICS)
    .map((topic, index) => ({ ...topic, rank: index + 1 }))   // 누락 · 중복 rank 도 1..3 으로
  const message = state === 'insufficient' ? INSUFFICIENT_MESSAGE
    : state === 'lowQuality' ? LOW_QUALITY_MESSAGE : null
  return {
    state,
    message,
    reason: typeof body?.reason === 'string' ? body.reason : null,
    questionCount: count(body?.questionCount),
    studentCount: count(body?.studentCount),
    cached: body?.cached === true,
    topics,
  }
}

/** @returns {ConfusionsView} */
function unavailableView() {
  return { state: 'unavailable', message: UNAVAILABLE_MESSAGE, reason: null, questionCount: 0, studentCount: 0,
    cached: false, topics: [] }
}

/**
 * GET mastery 응답 → 표. 학생 셀을 concepts 순서로 맞추고, 서버에 없는 셀은 빈칸(pKnown null).
 * @returns {MasteryView}
 */
export function masteryView(body) {
  const concepts = (Array.isArray(body?.concepts) ? body.concepts : []).map((c) => ({
    id: c.id,
    name: String(c.name ?? ''),
    quizCount: count(c.quizCount),
    classAvg: Number.isFinite(c.classAvg) ? c.classAvg : null,
    belowHalf: c.belowHalf === true,
  }))
  const students = (Array.isArray(body?.students) ? body.students : []).map((s) => {
    const byConcept = new Map((Array.isArray(s.cells) ? s.cells : []).map((cell) => [cell.conceptId, cell]))
    return {
      userId: s.userId,
      name: String(s.name ?? ''),
      cells: concepts.map((c) => {
        const cell = byConcept.get(c.id)
        return {
          conceptId: c.id,
          pKnown: Number.isFinite(cell?.pKnown) ? cell.pKnown : null,
          observations: count(cell?.observations),
          lowConfidence: cell ? cell.lowConfidence === true : true,
        }
      }),
    }
  })
  return { concepts, students }
}

/** 요청 함수를 주입할 수 있다 (테스트). 만들기만 해서는 아무 요청도 보내지 않는다. */
export function createInsightsApi(call = request) {
  return {
    /** @returns {Promise<Concept[]>} */
    concepts: (sessionId) => call(`/api/sessions/${sessionId}/concepts`),
    /** @returns {Promise<Record<string, QuizConcept[]>>} quizId → 태그 */
    quizConcepts: (sessionId) => call(`/api/sessions/${sessionId}/quiz-concepts`),
    /**
     * 문항 태그 전체 교체 (최대 3개, 각 1~30자). source 생략 = TEACHER, AI = 선생님이 받아들인 AI 추천.
     * @returns {Promise<QuizConcept[]>}
     */
    setQuizConcepts: (quizId, names, source) =>
      call(`/api/quizzes/${quizId}/concepts`, send('PUT', source ? { names, source } : { names })),
    /** @returns {Promise<MasteryView>} */
    mastery: async (sessionId) => masteryView(await call(`/api/sessions/${sessionId}/insights/mastery`)),
    /**
     * "분석하기" 를 눌렀을 때만 부른다 (AI · 한도 사용). 503 은 다시 시도 안내 상태로 바꾸고,
     * 그 밖의 오류(403 · 404 · 429 …)는 그대로 던진다.
     * @returns {Promise<ConfusionsView>}
     */
    analyzeConfusions: async (sessionId) => {
      try {
        return confusionsView(await call(`/api/sessions/${sessionId}/insights/confusions`, { method: 'POST' }))
      } catch (error) {
        if (error?.status === 503) return unavailableView()
        throw error
      }
    },
  }
}

export const insights = createInsightsApi()
