import { AI_RETENTION_OPTIONS, aiRetentionLabel } from '../utils/aiRetention'

/**
 * PT-13 수업 AI 대화 보관 기간 선택 (수업 종료 후 0~90일). value 는 select 문자열 ('' = 아직 안 고름).
 * 방 만들기에서는 기본값 없이 반드시 고르게 한다.
 */
export default function AiRetentionSelect({ value, onChange, disabled, required }) {
  const current = value === null || value === undefined ? '' : String(value)
  const known = current === '' || AI_RETENTION_OPTIONS.map(String).includes(current)
  return (
    <select
      value={current}
      onChange={(e) => onChange(e.target.value)}
      disabled={disabled}
      required={required}
      aria-label="AI 대화 보관 기간"
    >
      <option value="" disabled>보관 기간을 골라 주세요</option>
      {AI_RETENTION_OPTIONS.map((days) => (
        <option key={days} value={String(days)}>{aiRetentionLabel(days)}</option>
      ))}
      {/* 목록에 없는 값(예: 예전 수업 · 다른 화면에서 정한 값)도 그대로 보이게 */}
      {!known && <option value={current}>{aiRetentionLabel(Number(current))}</option>}
    </select>
  )
}
