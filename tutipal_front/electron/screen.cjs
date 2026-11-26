const { createHash, randomUUID } = require('node:crypto')
const { readFileSync, writeFileSync } = require('node:fs')
const path = require('node:path')
const { app, BrowserWindow, desktopCapturer } = require('electron')
const { captureWindow, cancelCapture } = require('./selectedWindowCapture.cjs')

const OFF = { enabled: false, sourceId: '', sourceName: '' }
const file = () => path.join(app.getPath('userData'), 'screen.json')
let lastHash = '', revision = 0, captureRevision = 0, runningId = '', preview = null, previewPending = false

function loadSettings() {
  try {
    const s = JSON.parse(readFileSync(file(), 'utf8'))
    return { enabled: s.enabled === true && runningId === s.sourceId, sourceId: String(s.sourceId ?? ''), sourceName: String(s.sourceName ?? '') }
  } catch { return { ...OFF } }
}

function saveSettings(input) {
  const s = input ?? {}
  const next = { enabled: s.enabled === true,
    sourceId: typeof s.sourceId === 'string' ? s.sourceId.slice(0, 200) : '',
    sourceName: typeof s.sourceName === 'string' ? s.sourceName.slice(0, 200) : '' }
  if (next.enabled && (!preview || preview.id !== next.sourceId || preview.token !== s.previewToken)) {
    throw new Error('선택한 창을 먼저 미리보기로 확인해 주세요.')
  }
  if (!next.sourceId.startsWith('window:')) next.enabled = false
  writeFileSync(file(), JSON.stringify(next))
  revision++; cancelCapture(); lastHash = ''
  runningId = next.enabled ? next.sourceId : ''
  preview = null; previewPending = false
  return loadSettings()
}

async function listSources() {
  const own = new Set(BrowserWindow.getAllWindows().map(w => w.getMediaSourceId().split(':')[1]))
  const sources = await desktopCapturer.getSources({ types: ['window'], thumbnailSize: { width: 0, height: 0 }, fetchWindowIcons: false })
  return sources.filter(s => s.id.startsWith('window:') && !own.has(s.id.split(':')[1]) && s.name.trim())
    .map(s => ({ id: s.id, name: s.name }))
}

async function previewSource(sourceId) {
  // Selecting a replacement stops the previous analysis before taking the explicit preview.
  saveSettings({ ...loadSettings(), enabled: false })
  const ownRevision = revision
  previewPending = true
  try {
    if (!(await listSources()).some(s => s.id === sourceId)) throw new Error('창을 다시 선택해 주세요.')
    if (revision !== ownRevision) throw new Error('미리보기가 취소됐어요.')
    const image = await captureWindow(sourceId, 'preview')
    const exists = (await listSources()).some(s => s.id === sourceId)
    if (revision !== ownRevision || !exists) throw new Error('미리보기가 취소됐어요.')
    const token = randomUUID()
    preview = { id: sourceId, token }
    return { sourceId, image, previewToken: token }
  } finally { if (revision === ownRevision) previewPending = false }
}

function cancelPreview() {
  if (preview || previewPending) { preview = null; previewPending = false; revision++; cancelCapture('preview') }
}

function cancelAnalysisCapture() {
  captureRevision++; cancelCapture('analysis')
}

async function capture() {
  const settings = loadSettings(), ownRevision = revision, ownCapture = captureRevision
  const current = () => revision === ownRevision && captureRevision === ownCapture
  if (!settings.enabled) return { status: 'off' }
  if (!(await listSources()).some(s => s.id === settings.sourceId)) return { status: 'gone' }
  if (!current()) return { status: 'off' }
  let image
  try { image = await captureWindow(settings.sourceId) }
  catch (error) {
    if (!current()) return { status: 'off' }
    if (!(await listSources()).some(s => s.id === settings.sourceId)) return { status: 'gone' }
    throw error
  }
  if (!current() || !loadSettings().enabled) return { status: 'off' }
  const exists = (await listSources()).some(s => s.id === settings.sourceId)
  if (!current() || !loadSettings().enabled) return { status: 'off' }
  if (!exists) return { status: 'gone' }
  const jpeg = Buffer.from(image.split(',')[1], 'base64')
  const hash = createHash('sha1').update(jpeg).digest('hex')
  const changed = hash !== lastHash
  lastHash = hash
  return { status: 'ok', image: jpeg.toString('base64'), changed, sourceId: settings.sourceId }
}

async function targetStatus(expectedSourceId) {
  const settings = loadSettings()
  if (!settings.enabled) return { status: 'off' }
  if (expectedSourceId && settings.sourceId !== expectedSourceId) return { status: 'changed' }
  const sources = await listSources()
  const latest = loadSettings()
  if (!latest.enabled) return { status: 'off' }
  if (latest.sourceId !== settings.sourceId) return { status: 'changed' }
  return { status: sources.some(s => s.id === settings.sourceId) ? 'ok' : 'gone' }
}
module.exports = { loadSettings, saveSettings, listSources, previewSource, cancelPreview, cancelAnalysisCapture, capture, targetStatus }
