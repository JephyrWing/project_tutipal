import {
  AI_RETENTION_MAX_DAYS,
  AI_RETENTION_QUICK_PICKS,
  aiRetentionError,
  aiRetentionLabel,
  parseAiRetentionDays,
} from '../utils/aiRetention'

/**
 * PT-13 수업 AI 대화 보관 기간 입력 (수업 종료 후 0~90일, 선생님이 정한 만큼). 숫자 칸에 직접 쓰거나 빠른 선택
 * 버튼을 누른다. value 는 입력 문자열 ('' = 아직 안 정함, 기본값 없음).
 */
export default function AiRetentionInput({ value, onChange, disabled, required }) {
  const text = value === null || value === undefined ? '' : String(value)
  const days = parseAiRetentionDays(text)
  const error = aiRetentionError(text)
  return (
    <div className="ai-retention-input">
      <div className="row-gap">
        <input
          type="number"
          inputMode="numeric"
          min={0}
          max={AI_RETENTION_MAX_DAYS}
          step={1}
          value={text}
          onChange={(e) => onChange(e.target.value)}
          disabled={disabled}
          required={required}
          placeholder="0~90"
          aria-label="AI 대화 보관 기간 (일)"
          aria-invalid={!!error}
        />
        <span className="muted">일</span>
        {AI_RETENTION_QUICK_PICKS.map((pick) => (
          <button
            key={pick}
            type="button"
            className="btn btn-sm"
            aria-pressed={days === pick}
            disabled={disabled}
            onClick={() => onChange(String(pick))}
          >
            {pick === 0 ? '종료 직후' : `${pick}일`}
          </button>
        ))}
      </div>
      {error ? (
        <span className="error-text small" role="alert">{error}</span>
      ) : (
        days !== null && <span className="muted small">{aiRetentionLabel(days)}</span>
      )}
    </div>
  )
}
