import { useCallback, useEffect, useRef, useState } from 'react'
import { selectedHelpSource } from '../utils/helpShareSelection'
import './HelpSharePicker.css'

export default function HelpSharePicker({ desktop, disabled, selected, onSelect, onError }) {
  const [sources, setSources] = useState([])
  const [loading, setLoading] = useState(false)
  const version = useRef(0)
  const callbacks = useRef({ onSelect, onError })
  callbacks.current = { onSelect, onError }
  const refresh = useCallback(async () => {
    const attempt = ++version.current
    callbacks.current.onSelect(null)
    setLoading(true)
    try {
      const list = await desktop.helpShareSources()
      if (attempt === version.current) setSources(list)
    } catch {
      if (attempt === version.current) {
        setSources([])
        callbacks.current.onError('공유할 창을 읽지 못했어요. 목록을 다시 불러와 주세요')
      }
    } finally { if (attempt === version.current) setLoading(false) }
  }, [desktop])
  useEffect(() => { void refresh(); return () => { version.current++ } }, [refresh])
  return <div className="help-share-picker">
    <div className="row-between"><strong>공유할 창 선택</strong>
      <button type="button" className="btn btn-sm" disabled={disabled || loading} onClick={() => void refresh()}>목록 새로고침</button></div>
    <label className="field small-label"><span>선생님에게 보여 줄 창이나 화면</span>
      <select className="input" value={selected?.id ?? ''} disabled={disabled || loading}
        onChange={event => onSelect(selectedHelpSource(sources, event.target.value))}>
        <option value="">{loading ? '목록을 불러오는 중…' : '공유할 대상을 골라 주세요'}</option>
        {sources.map(source => <option key={source.id} value={source.id} disabled={!source.supported}>
          {source.name}{source.supported ? '' : ' (이 환경에서 공유 불가)'}</option>)}
      </select>
    </label>
    {selected ? <figure className="help-share-preview"><img src={selected.thumbnail} alt={`공유 전 미리보기: ${selected.name}`} />
      <figcaption>{selected.name}</figcaption></figure> : <p className="muted small">대상을 고르면 미리보기가 나타나요. 아직 공유되지 않아요.</p>}
    <p className="muted small">선택한 대상만 공유해요. 창을 닫거나 최소화하면 공유가 중지돼요.</p>
  </div>
}
