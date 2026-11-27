import { useEffect, useRef } from 'react'
import './screenAnalysisDialog.css'

/** Displays the current result only. Opening this dialog never requests analysis. */
export default function ScreenAnalysisDialog({ open, summary, onClose, children }) {
  const dialog = useRef(null)
  useEffect(() => {
    const node = dialog.current
    if (open && !node.open) node.showModal()
    if (!open && node.open) node.close()
  }, [open])

  return (
    <dialog ref={dialog} className="screen-analysis-dialog" aria-labelledby="screen-analysis-title" onClose={onClose}>
      {open && <>
      <header>
        <h2 id="screen-analysis-title">화면 분석 상세</h2>
        <button type="button" className="btn btn-sm" autoFocus onClick={onClose} aria-label="상세 닫기">닫기</button>
      </header>
      <h3>현재 화면 요약</h3>
      <p className="screen-analysis-summary">{summary}</p>
      <p className="screen-analysis-notice">현재 분석은 요약만 제공해요. 별도의 상세 설명은 아직 제공되지 않아요.</p>
      {children}
      </>}
    </dialog>
  )
}
