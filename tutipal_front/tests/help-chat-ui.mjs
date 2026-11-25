// Actual React rendering with an injected synthetic transport, never the team server.
// Run: node tests/help-chat-ui.mjs (local Edge), or HELP_CHAT_BROWSER=chromium with installed Chromium.
import { createServer } from 'vite'
import react from '@vitejs/plugin-react'
import { chromium, expect } from '@playwright/test'
import { fileURLToPath } from 'node:url'
const root=fileURLToPath(new URL('../',import.meta.url))
const start=new Date().toISOString()
const server=await createServer({root,configFile:false,plugins:[react()],server:{host:'127.0.0.1',port:0}})
let browser,passed=0
try {
  await server.listen()
  const channel=process.env.HELP_CHAT_BROWSER??'msedge'
  browser=await chromium.launch({headless:true,...(channel==='chromium'?{}:{channel})})
  async function scenario(name,check) {
    const page=await browser.newPage({viewport:{width:780,height:900}})
    const errors=[];page.on('pageerror',error=>errors.push(error.message))
    // Permit only this fixture server, never an API or unrelated host.
    const origin=`http://127.0.0.1:${server.httpServer.address().port}`
    await page.route('**/*',route=>new URL(route.request().url()).origin===origin&&!route.request().url().includes('/api/')?route.continue():route.abort())
    try {
      await page.goto(`${origin}/tests/fixtures/help-chat.html`)
      await page.getByRole('button',{name:'검사용 대화 불러오기'}).click()
      await expect(page.getByText('연결됨',{exact:true})).toBeVisible()
      await check(page)
      expect(errors).toEqual([]);passed++;console.log(`PASS ${name}`)
    } finally {await page.close()}
  }
  await scenario('safe plain text without screen-sharing prerequisites',async page=>{
    await page.getByRole('textbox',{name:'보낼 메시지'}).fill('<img src=x onerror=alert(1)>')
    await page.getByRole('button',{name:'보내기',exact:true}).click()
    await expect(page.getByRole('log')).toContainText('<img src=x onerror=alert(1)>')
    await expect(page.getByRole('log').locator('img')).toHaveCount(0)
  })
  await scenario('failed retry reuses client ID and replaces failed row',async page=>{
    await page.evaluate(()=>window.chatFixture.fail(true))
    await page.getByRole('textbox').fill('국어 질문');await page.getByRole('button',{name:'보내기',exact:true}).click()
    await expect(page.getByText('전송 실패',{exact:true})).toBeVisible()
    await page.evaluate(()=>window.chatFixture.fail(false));await page.getByRole('button',{name:'다시 보내기'}).click()
    await expect(page.getByText('전송 실패',{exact:true})).toHaveCount(0)
    await expect(page.locator('.help-chat-bubble')).toHaveCount(1)
    const requests=await page.evaluate(()=>window.chatFixture.requests)
    expect(requests[0].clientMessageId).toBe(requests[1].clientMessageId)
  })
  await scenario('closed help ignores a delayed send and removes composer',async page=>{
    await page.evaluate(()=>window.chatFixture.delay());await page.getByRole('textbox').fill('늦은 메시지')
    await page.getByRole('button',{name:'보내기',exact:true}).click()
    await page.evaluate(()=>{window.chatFixture.close();window.chatFixture.release()})
    await expect(page.getByRole('textbox')).toHaveCount(0)
    await expect(page.getByRole('log')).toHaveCount(0)
    expect(await page.evaluate(()=>window.chatFixture.snapshot().items)).toEqual([])
  })
  await scenario('help switch clears unsent draft and previous transcript',async page=>{
    await page.getByRole('textbox').fill('이전 도움의 비공개 초안')
    await page.evaluate(()=>window.chatFixture.switchHelp())
    await expect(page.getByRole('textbox')).toHaveValue('')
    await expect(page.getByRole('log')).not.toContainText('이전 도움')
  })
  await scenario('IME Enter does not submit; normal Enter does',async page=>{
    const input=page.getByRole('textbox');await input.fill('한글 조합')
    await input.dispatchEvent('keydown',{key:'Enter',code:'Enter',isComposing:true,bubbles:true})
    expect(await page.evaluate(()=>window.chatFixture.requests.length)).toBe(0)
    await input.press('Enter');await expect(page.locator('.help-chat-bubble')).toHaveText('한글 조합')
  })
  await scenario('length limit uses Unicode code points; whitespace is not sent',async page=>{
    const input=page.getByRole('textbox');const send=page.getByRole('button',{name:'보내기',exact:true})
    await input.fill(' \n ');await expect(send).toBeDisabled()
    await input.fill('😀'.repeat(121));await expect(send).toBeDisabled();await expect(input).toHaveAttribute('aria-invalid','true')
    await input.fill('😀'.repeat(120));await expect(send).toBeEnabled()
  })
  await scenario('320px narrow layout keeps input and actions in bounds',async page=>{
    await page.setViewportSize({width:320,height:900})
    await page.getByRole('textbox').fill('좁은 화면에서도 질문')
    expect(await page.evaluate(()=>document.documentElement.scrollWidth<=document.documentElement.clientWidth)).toBe(true)
    await expect(page.getByRole('button',{name:'보내기',exact:true})).toBeInViewport()
  })
  console.log(JSON.stringify({scope:'synthetic React component only',collected:7,ran:passed,passed,failed:0,skipped:0,startUtc:start,endUtc:new Date().toISOString()}))
} finally {await browser?.close();await server.close()}
