// 백엔드 DTO 와 1:1 로 맞추는 모양 (JSDoc). 서버 DTO 를 바꾸면 여기도 같이 바꾸기.
// 실행에는 영향이 없고, VS Code 가 자동 완성 · 마우스 올리면 설명을 보여 준다. 쓰는 쪽: /** @type {import('./types').SessionResponse} */

/**
 * com.my.tutipal_back.users.entity.Role — 계정 역할. 선생님/학생은 세션마다 정해짐
 * @typedef {'ADMIN' | 'USER'} Role
 */

/**
 * com.my.tutipal_back.users.dto.UserResponse
 * @typedef {Object} UserResponse
 * @property {number} id
 * @property {string} name
 * @property {SocialProvider | 'dev'} provider
 * @property {Role} role
 */

/**
 * @typedef {'google' | 'naver' | 'kakao'} SocialProvider
 */

/**
 * com.my.tutipal_back.learning.tutor.TopicPrompt (소문자로 보냄)
 * @typedef {'general' | 'coding' | 'english' | 'math'} Topic 개인 챗봇 주제 (단체 방은 과목 이름 문자열)
 */

/**
 * com.my.tutipal_back.learning.tutor.dto.ChatRequest.Message
 * @typedef {Object} ChatMessage
 * @property {'user' | 'assistant'} role
 * @property {string} content
 */

/**
 * com.my.tutipal_back.common.exception.GlobalExceptionHandler 의 응답
 * @typedef {Object} ApiError
 * @property {number} status
 * @property {string} message
 */

/**
 * com.my.tutipal_back.ai.quota.AiFeature — AI 호출 한도를 따로 세는 기능 (PT-97)
 * @typedef {'SCREEN_AUTO' | 'SCREEN_MANUAL' | 'CHAT' | 'QUIZ_GENERATE'} AiFeature
 */

/**
 * GlobalExceptionHandler 의 AI 사용 한도 초과 응답 (PT-97). HTTP 429, Content-Type application/problem+json,
 * Retry-After 헤더(초)도 함께 온다. 기존 ApiError 처럼 message 를 읽어도 되도록 message === detail.
 * 쓰는 쪽은 ApiRequestError.retryAt(Date, 표시용) · retryAfterMs(잠금 · 멈춤 시간, 로컬 시계 기준) · feature 로 받는다 (client.js).
 * @typedef {Object} AiQuotaProblem
 * @property {429} status
 * @property {string} title
 * @property {string} detail 사용자 안내 문구 (시각 대신 "약 N분 뒤", HH:mm 은 화면이 retryAt 으로 만든다)
 * @property {string} message detail 과 같은 값
 * @property {AiFeature} feature
 * @property {string} retryAt 다시 쓸 수 있는 시각, UTC ISO-8601 (예 "2026-11-25T01:00:00Z")
 * @property {number} retryAfterSeconds 1 이상, Retry-After 헤더와 같은 값
 */

/**
 * com.my.tutipal_back.sessions.entity.SessionRole — 세션 안 역할
 * @typedef {'TEACHER' | 'STUDENT'} SessionRole
 */

/**
 * com.my.tutipal_back.sessions.dto.SessionResponse
 * @typedef {Object} SessionResponse
 * @property {number} id
 * @property {string} title
 * @property {string} topic 과목 이름 ("코딩", "한국사", 직접 쓴 이름, 최대 30자)
 * @property {boolean} pinned 내가 즐겨찾기한 방 (홈 목록 맨 위, GET /api/sessions 에서만 의미 있음)
 * @property {string} inviteCode
 * @property {SessionRole} myRole
 * @property {number} difficulty
 * @property {boolean} hideAnswers
 * @property {boolean} preferMaterials
 * @property {number} participantCount
 * @property {boolean} ended
 */

/**
 * com.my.tutipal_back.sessions.dto.ParticipantResponse
 * @typedef {Object} ParticipantResponse
 * @property {number} userId
 * @property {string} name
 * @property {SessionRole} role
 * @property {string} joinedAt
 * @property {boolean} online 지금 세션 화면을 열어 둔 상태 (STOMP 구독 중)
 */

/**
 * com.my.tutipal_back.sessions.dto.UpdateSettingsRequest
 * @typedef {Object} SessionSettings
 * @property {string} topic 과목 이름 (최대 30자)
 * @property {number} difficulty
 * @property {boolean} hideAnswers
 * @property {boolean} preferMaterials
 */

/**
 * ---- 이해도 질문: com.my.tutipal_back.learning.quiz.dto.QuizDtos ----
 * @typedef {'CHOICE' | 'TEXT'} QuizType
 */

/**
 * AUTO 객관식 자동, AI 주관식 AI, TEACHER 선생님. null = 채점 중
 * @typedef {'AUTO' | 'AI' | 'TEACHER'} GradedBy
 */

/**
 * @typedef {Object} QuizDraft
 * @property {QuizType} type
 * @property {string} question
 * @property {string[] | null} choices
 * @property {number | null} answerIndex
 * @property {string | null} modelAnswer
 */

/**
 * @typedef {QuizDraft & Object} CreateQuizRequest
 * @property {number} timeLimitSec
 */

/**
 * @typedef {Object} MyAnswer
 * @property {number | null} choiceIndex
 * @property {string | null} text
 * @property {boolean | null} correct
 * @property {GradedBy | null} gradedBy
 * @property {string | null} feedback
 */

/**
 * @typedef {Object} StudentQuiz
 * @property {number} id
 * @property {number} sessionId
 * @property {QuizType} type
 * @property {string} question
 * @property {string[] | null} choices
 * @property {string} closesAt
 * @property {boolean} open
 * @property {MyAnswer | null} myAnswer
 */

/**
 * @typedef {Object} QuizSummary
 * @property {number} id
 * @property {number} no
 * @property {QuizType} type
 * @property {string} question
 * @property {string[] | null} choices
 * @property {number | null} answerIndex
 * @property {string | null} modelAnswer
 * @property {string} closesAt
 * @property {boolean} open
 * @property {number} answered
 * @property {number} correct
 * @property {number} pending
 * @property {number} studentCount
 */

/**
 * @typedef {Object} AnswerRow
 * @property {number} answerId
 * @property {number} userId
 * @property {string} name
 * @property {number | null} choiceIndex
 * @property {string | null} text
 * @property {boolean | null} correct
 * @property {GradedBy | null} gradedBy
 * @property {string | null} feedback
 */

/**
 * com.my.tutipal_back.common.realtime.SessionEvents.Event — 무엇이 바뀌었는지만, 내용은 REST 로 다시 읽음
 * @typedef {Object} SessionEvent
 * left: 학생이 나갔거나 내보내짐(선생님에게, id=userId) · removed: 내가 내보내짐(그 학생에게, id=sessionId)
 * @property {'quiz' | 'quizClosed' | 'settings' | 'ended' | 'joined' | 'left' | 'removed' | 'presence' | 'quizStats' | 'quizResult' | 'help' | 'helpAccepted' | 'helpDeclined' | 'helpEnded' | 'materials' | 'notice' | 'notices' | 'posts'} type
 * @property {number} id
 */

/**
 * ---- 화면공유: com.my.tutipal_back.livekit.dto.ScreenShareDtos ---- Spring 관문 접속 정보 (세션에 묶인 url, token은 session-bound이며 실제 JWT가 아님)
 * @typedef {Object} JoinInfo
 * @property {string} url
 * @property {string} token
 * @property {string} room
 * @property {string} identity
 */

/**
 * @typedef {'PENDING' | 'ACCEPTED' | 'DECLINED' | 'DONE'} HelpStatus
 */

/**
 * @typedef {Object} HelpResponse
 * @property {number} id
 * @property {number} userId
 * @property {string} name
 * @property {string} message
 * @property {boolean} shareScreen
 * @property {HelpStatus} status
 * @property {string} createdAt
 */

/**
 * ---- 5단계: 자료 · 공지 · 게시판 ---- com.my.tutipal_back.materials.repository.MaterialRepository.MaterialRow
 * @typedef {Object} MaterialRow
 * @property {number} id
 * @property {number} sessionId
 * @property {string} filename
 * @property {number} sizeBytes
 * @property {'PROCESSING' | 'DONE' | 'FAILED'} status
 * @property {number} chunkCount
 * @property {string | null} error
 * @property {string} createdAt
 */

/**
 * com.my.tutipal_back.learning.notice.dto.NoticeDtos.NoticeResponse
 * @typedef {Object} NoticeResponse
 * @property {number} id
 * @property {string} title
 * @property {string} body
 * @property {string} authorName
 * @property {string} createdAt
 */

/**
 * com.my.tutipal_back.learning.qna.dto.QnaDtos
 * @typedef {Object} PostSummary
 * @property {number} id
 * @property {string} title
 * @property {string} authorName
 * @property {number} commentCount
 * @property {string} createdAt
 * @property {boolean} mine
 */

/**
 * @typedef {Object} CommentResponse
 * @property {number} id
 * @property {string} body
 * @property {string} authorName
 * @property {string} createdAt
 * @property {boolean} mine
 */

/**
 * @typedef {Object} PostDetail
 * @property {number} id
 * @property {string} title
 * @property {string} body
 * @property {string} authorName
 * @property {string} createdAt
 * @property {boolean} mine
 * @property {CommentResponse[]} comments
 */

/**
 * ---- 복습: com.my.tutipal_back.learning.review ---- ReviewStore.Item
 * @typedef {Object} ReviewItem
 * @property {number} id
 * @property {string} summary
 * @property {number} seenCount
 * @property {number} seenSeconds
 * @property {string} firstSeen
 * @property {string} lastSeen
 * @property {string | null} suggestedAt
 */

/**
 * ReviewService.Observed
 * @typedef {Object} ReviewObserved
 * @property {boolean} recorded
 * @property {{ id: number; summary: string } | null} suggestion
 */

export {}
