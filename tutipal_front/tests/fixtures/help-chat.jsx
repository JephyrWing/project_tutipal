// Synthetic component fixture only. No authentication, HTTP API, storage or sockets.
import React, { useState } from 'react'
import { createRoot } from 'react-dom/client'
import HelpChatPanel from '../../src/components/HelpChatPanel'
import { createHelpChatClient } from '../../src/utils/helpChatClient'
import '../../src/styles/global.css'

const saved = []
let sequence = 0, fail = false, gate = null, release = null
const requests = []
function makeClient(helpId) {
  return createHelpChatClient({helpId,userId:'1',maxLength:120,transport:{
    async load({afterSequence}) {
      const items=saved.filter(m=>m.helpId===helpId&&BigInt(m.sequence)>BigInt(afterSequence))
      return {items,nextCursor:items.at(-1)?.sequence??afterSequence,hasMore:false}
    },
    async send(request) {
      requests.push(request)
      if (gate) await gate
      if (fail) throw Error('synthetic server detail must stay hidden')
      const existing=saved.find(m=>m.helpId===helpId&&m.clientMessageId===request.clientMessageId)
      if(existing)return existing
      const item={id:String(++sequence),sequence:String(sequence),helpId,senderId:'1',clientMessageId:request.clientMessageId,text:request.text}
      saved.push(item);return item
    },
  }})
}
const first=makeClient('7')
function Fixture() {
  const [client,setClient]=useState(first)
  window.chatFixture={
    fail(value){fail=value},
    delay(){gate=new Promise(resolve=>{release=resolve})},
    release(){release?.();gate=null},
    requests,
    close(){client.close()},
    refresh(){return client.refresh()},
    switchHelp(){client.close();const next=makeClient('8');setClient(next);void next.refresh()},
    snapshot(){return client.getSnapshot()},
  }
  return <main style={{maxWidth:480,margin:'24px auto',padding:16}}>
    <p>합성 컴포넌트 검사 · 서버 미연결 · 입력 한도 120자는 검사 설정</p>
    <button className="btn" onClick={()=>void client.refresh()}>검사용 대화 불러오기</button>
    <div className="card" style={{padding:18,marginTop:16}}><HelpChatPanel client={client} partnerName="선생님 예시" /></div>
  </main>
}
createRoot(document.getElementById('root')).render(<React.StrictMode><Fixture /></React.StrictMode>)
