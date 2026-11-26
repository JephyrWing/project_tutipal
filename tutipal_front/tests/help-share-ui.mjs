// Synthetic renderer integration only: no real API, user pixels or display capture.
import { createServer } from 'vite'
import react from '@vitejs/plugin-react'
import { _electron as electron } from 'playwright'
import { expect } from '@playwright/test'
import electronPath from 'electron'
import { mkdtemp, writeFile, rm } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import os from 'node:os'
import path from 'node:path'
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const temp = await mkdtemp(path.join(os.tmpdir(), 'tutipal-share-ui-'))
const mock = `
export const RoomEvent=Object.fromEntries(['TrackSubscribed','TrackUnsubscribed','SignalReconnecting','Reconnecting','Reconnected','Disconnected','ParticipantConnected','ParticipantDisconnected'].map(k=>[k,k]));
window.testRooms=[];window.testCapture=[];
export class Room {
 constructor(){this.handlers=new Map();this.state='disconnected';this.remoteParticipants=new Map(location.search.includes('noTeacher')?[]:[['teacher',{identity:'teacher',trackPublications:new Map()}]]);this.localParticipant={trackPublications:new Map(),setScreenShareEnabled:async on=>{
  window.testCapture.push(on);if(!on)return;const media=new EventTarget();media.readyState='live';media.getSettings=()=>({width:800,height:600});const track={mediaStreamTrack:media,stop(){media.readyState='ended'},attach(){},detach(){}};this.localParticipant.trackPublications.set('screen',{track});return{track};}};window.testRooms.push(this);}
 on(e,fn){if(!this.handlers.has(e))this.handlers.set(e,new Set());this.handlers.get(e).add(fn);return this}
 off(e,fn){this.handlers.get(e)?.delete(fn);return this}
 emit(e,...args){this.handlers.get(e)?.forEach(fn=>fn(...args))}
 async connect(){this.state='connected'} async disconnect(){this.state='disconnected';this.emit('Disconnected')}
}
`
const entry = `
import React from 'react';import {createRoot} from 'react-dom/client';import {StudentHelp} from '/src/pages/LivePanels.jsx';
const thumbnail='data:image/svg+xml,'+encodeURIComponent('<svg xmlns="http://www.w3.org/2000/svg" width="300" height="200"><rect width="300" height="200" fill="cornflowerblue"/></svg>');
window.sources=[{id:'window:123:0',name:'Synthetic A',thumbnail,supported:true},{id:'window:456:0',name:'Synthetic B',thumbnail,supported:true}];
window.prepared=[];window.sizes=[];
window.desktop={helpShareSources:async()=>window.sources,setShareSource(){},showOverlay(){},sendOverlayDraw(){},
 prepareHelpShare:async id=>{window.prepared.push(id);if(window.mainGate)await window.mainGate;if(!window.sources.some(s=>s.id===id))throw Error('gone');return{id};},
 setHelpShareVideoSize:(...args)=>window.sizes.push(args),onHelpShareEnded:cb=>{window.fireGone=cb;return()=>{window.fireGone=()=>{}};}};
createRoot(document.getElementById('root')).render(React.createElement(React.StrictMode,null,React.createElement(StudentHelp,{sessionId:7,reloadKey:0})));
`
const server = await createServer({ root, configFile: false,
  plugins: [react(), { name: 'share-test-fixture', enforce: 'pre',
    resolveId(id) { if (id.endsWith('/HelpChat')) return '\0share-chat'; if (id === 'livekit-client') return '\0share-livekit'; if (id === '/share-entry.js') return '\0share-entry' },
    load(id) { if (id === '\0share-chat') return 'export default function HelpChat(){return null}'; if (id === '\0share-livekit') return mock; if (id === '\0share-entry') return entry },
    configureServer(s) { s.middlewares.use(async (req, res, next) => {
      if (!req.url.startsWith('/share-test.html')) return next()
      try { res.setHeader('Content-Type', 'text/html'); res.end(await s.transformIndexHtml('/share-test.html', '<div id="root"></div><script type="module" src="/share-entry.js"></script>')) }
      catch (error) { next(error) }
    }) },
  }], server: { host: '127.0.0.1', port: 0 },
  optimizeDeps: { noDiscovery: true, include: ['react', 'react-dom/client', 'react/jsx-dev-runtime', '@stomp/stompjs'] },
})
let app, checks = 0
try {
  await server.listen()
  const port = server.httpServer.address().port
  const main = path.join(temp, 'main.cjs')
  await writeFile(main, `const {app,BrowserWindow}=require('electron');app.setPath('userData',${JSON.stringify(path.join(temp, 'profile'))});app.whenReady().then(()=>new BrowserWindow({show:false,webPreferences:{sandbox:true,contextIsolation:true,nodeIntegration:false}}).loadURL('about:blank'));`)
  const env = { ...process.env }; delete env.ELECTRON_RUN_AS_NODE
  app = await electron.launch({ executablePath: electronPath, args: [main], env, timeout: 20000 })
  const page = await app.firstWindow({ timeout: 20000 }), errors = []
  page.on('pageerror', error => errors.push(error.message))
  await page.route(url => url.pathname.startsWith('/api/'), route => {
    const pathname = new URL(route.request().url()).pathname
    const body = pathname.endsWith('/csrf') ? { headerName: 'X-XSRF-TOKEN', token: 'synthetic' }
      : pathname.endsWith('/help') ? [{ id: 17, name: 'Synthetic Student', status: 'ACCEPTED', shareScreen: true }]
      : { url: '/livekit/synthetic', token: 'session-bound' }
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })
  })
  await page.goto(`http://127.0.0.1:${port}/share-test.html`)
  const picker = page.getByRole('combobox'), consent = page.getByRole('checkbox')
  const start = page.getByRole('button', { name: '화면 공유 시작', exact: true })
  await expect(picker).toBeEnabled(); await expect(start).toBeDisabled(); await expect(consent).toBeDisabled()
  expect(await page.evaluate(() => window.testCapture)).toEqual([]); checks++
  await picker.selectOption('window:123:0')
  await expect(page.getByAltText('공유 전 미리보기: Synthetic A')).toBeVisible()
  await consent.check(); await expect(start).toBeEnabled()
  await picker.selectOption('window:456:0')
  await expect(consent).not.toBeChecked(); await expect(start).toBeDisabled(); checks++
  await consent.check(); await start.click()
  await expect(page.getByText('공유 중 · Synthetic B', { exact: true })).toBeVisible()
  expect(await page.evaluate(() => window.prepared)).toEqual(['window:456:0'])
  await expect.poll(() => page.evaluate(() => window.sizes.length)).toBeGreaterThan(0)
  expect(await page.evaluate(() => window.sizes.at(-1)[0])).toBe('window:456:0'); checks++
  await page.evaluate(() => { window.sources = window.sources.filter(s => s.id !== 'window:456:0'); window.fireGone('공유하던 창이 닫혔어요') })
  await expect(picker).toBeEnabled(); await expect(start).toBeDisabled(); await expect(consent).not.toBeChecked()
  await expect(page.getByRole('button', { name: '화면 공유 중지', exact: true })).toHaveCount(0); checks++
  await picker.selectOption('window:123:0'); await consent.check()
  const before = await page.evaluate(() => window.testCapture.filter(Boolean).length)
  await page.evaluate(() => { window.savedSources = window.sources; window.sources = [] })
  await start.click()
  await expect(page.getByText('선택한 창을 준비하지 못했어요. 창을 열고 다시 선택해 주세요', { exact: true })).toBeVisible()
  expect(await page.evaluate(() => window.testCapture.filter(Boolean).length)).toBe(before); checks++
  await page.evaluate(() => { window.sources = window.savedSources })
  await page.getByRole('button', { name: '목록 새로고침' }).click()
  await picker.selectOption('window:123:0'); await consent.check(); await start.click()
  await expect(page.getByRole('button', { name: '화면 공유 중지', exact: true })).toBeVisible()
  const captureCount = await page.evaluate(() => window.testCapture.filter(Boolean).length)
  await page.evaluate(() => { const r = window.testRooms.at(-1); r.state = 'reconnecting'; r.emit('Reconnecting'); r.state = 'connected'; r.emit('Reconnected') })
  await expect(picker).toBeEnabled(); await expect(picker).toHaveValue(''); await expect(consent).not.toBeChecked(); await expect(start).toBeDisabled()
  expect(await page.evaluate(() => window.testCapture.filter(Boolean).length)).toBe(captureCount); checks++
  // Last teacher leaves while capture is live: stop pixels, reset consent and never republish on return.
  await picker.selectOption('window:123:0'); await consent.check(); await start.click()
  await expect(page.getByRole('button', { name: '화면 공유 중지', exact: true })).toBeVisible()
  const peerCaptureCount = await page.evaluate(() => window.testCapture.filter(Boolean).length)
  await page.evaluate(() => { const r=window.testRooms.at(-1); const p=r.remoteParticipants.get('teacher'); r.remoteParticipants.delete('teacher'); r.emit('ParticipantDisconnected',p) })
  await expect(page.getByText('선생님 화면 연결을 기다리는 중', { exact: true })).toBeVisible()
  await expect(picker).toHaveValue(''); await expect(consent).not.toBeChecked(); await expect(start).toBeDisabled()
  await expect(page.getByRole('button', { name: '화면 공유 중지', exact: true })).toHaveCount(0)
  expect(await page.evaluate(() => window.testRooms.at(-1).localParticipant.trackPublications.get('screen').track.mediaStreamTrack.readyState)).toBe('ended')
  await expect.poll(() => page.evaluate(() => window.testCapture.at(-1))).toBe(false); checks++
  await page.evaluate(() => { const r=window.testRooms.at(-1),p={identity:'teacher',trackPublications:new Map()};r.remoteParticipants.set(p.identity,p);r.emit('ParticipantConnected',p) })
  await expect(page.getByText('선생님과 연결됨', { exact: true })).toBeVisible()
  await expect(start).toBeDisabled(); await expect(consent).not.toBeChecked(); await expect(picker).toHaveValue('')
  expect(await page.evaluate(() => window.testCapture.filter(Boolean).length)).toBe(peerCaptureCount); checks++
  // Departure while Main is still selecting also cancels a late publish, including a quick teacher return.
  await picker.selectOption('window:123:0'); await consent.check()
  await page.evaluate(() => { window.mainGate=new Promise(resolve=>{window.releaseMain=resolve}) })
  await start.click(); await expect(page.getByRole('button', {name:'공유 준비 중…',exact:true})).toBeDisabled()
  await page.evaluate(() => {const r=window.testRooms.at(-1),p=r.remoteParticipants.get('teacher');r.remoteParticipants.delete(p.identity);r.emit('ParticipantDisconnected',p);r.remoteParticipants.set(p.identity,p);r.emit('ParticipantConnected',p);window.releaseMain();window.mainGate=null})
  await expect(picker).toBeEnabled(); await expect(picker).toHaveValue(''); await expect(start).toBeDisabled()
  expect(await page.evaluate(() => window.testCapture.filter(Boolean).length)).toBe(peerCaptureCount); checks++
  await picker.selectOption('window:123:0'); await consent.check()
  await page.evaluate(() => { window.mainGate = new Promise(resolve => { window.releaseMain = resolve }) })
  await start.click(); await expect(page.getByRole('button', { name: '공유 준비 중…', exact: true })).toBeDisabled()
  await page.getByRole('button', { name: '끝내기', exact: true }).click()
  await expect(page.getByRole('button', { name: '✋ 도움 요청', exact: true })).toBeVisible()
  await page.evaluate(() => window.releaseMain())
  expect(await page.evaluate(() => window.testCapture.filter(Boolean).length)).toBe(peerCaptureCount); checks++
  await page.goto(`http://127.0.0.1:${port}/share-test.html?noTeacher`)
  await expect(page.getByText('선생님 화면 연결을 기다리는 중', {exact:true})).toBeVisible()
  await picker.selectOption('window:123:0')
  await expect(consent).toBeDisabled(); await expect(start).toBeDisabled()
  expect(await page.evaluate(() => window.testCapture)).toEqual([]); checks++
  expect(errors).toEqual([])
  console.log(`Synthetic Electron share UI: scenarios=${checks} passed=${checks} failed=0 rendererErrors=${errors.length}`)
} finally {
  await app?.close(); await server.close()
  if (path.dirname(temp) !== os.tmpdir() || !path.basename(temp).startsWith('tutipal-share-ui-')) throw new Error('Unexpected temporary profile path')
  await rm(temp, { recursive: true, force: true })
}
