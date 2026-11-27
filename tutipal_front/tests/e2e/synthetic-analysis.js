import path from 'node:path'
import { fileURLToPath } from 'node:url'

const screenFile = fileURLToPath(new URL('../../electron/screen.cjs', import.meta.url))

// Only OS inputs/pixels are synthetic. Execute the unchanged production controller
// so preview tokens, single-use start permission and settings invalidation remain real.
export async function installSyntheticAnalysis(app) {
  await app.application.evaluate(({ app, BrowserWindow, ipcMain }, file) => {
    const fs = process.getBuiltinModule('node:fs')
    const vm = process.getBuiltinModule('node:vm')
    const realRequire = process.getBuiltinModule('node:module').createRequire(file)
    const context = { module: { exports: {} }, Buffer, require(name) {
      if (name === 'electron') return { app, BrowserWindow, desktopCapturer: {
        getSources: async () => [{ id: 'window:synthetic', name: '합성 학습 창' }],
      } }
      if (name === './selectedWindowCapture.cjs') return {
        captureWindow: async () => 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a5xkAAAAASUVORK5CYII=', cancelCapture: () => {},
      }
      return realRequire(name)
    } }
    vm.runInNewContext(fs.readFileSync(file, 'utf8'), context, { filename: file })
    const controller = context.module.exports
    const notify = () => {
      for (const win of BrowserWindow.getAllWindows()) {
        if (new URL(win.webContents.getURL()).pathname === '/widget') win.webContents.send('screen-settings-changed')
      }
    }
    const handlers = {
      'screen-sources': () => controller.listSources(),
      'screen-settings-get': () => controller.loadSettings(),
      'screen-settings-set': (_event, settings) => { const saved = controller.saveSettings(settings); notify(); return saved },
      'screen-preview': async (_event, id) => { const pending = controller.previewSource(id); notify(); return await pending },
      'screen-preview-cancel': () => controller.cancelPreview(),
      'screen-capture-cancel': () => controller.cancelAnalysisCapture(),
    }
    for (const [channel, handler] of Object.entries(handlers)) {
      ipcMain.removeHandler(channel); ipcMain.handle(channel, handler)
    }
  }, path.resolve(screenFile))
}

export async function startSyntheticAnalysis(app) {
  return app.page.evaluate(async () => {
    const preview = await window.desktop.previewScreenSource('window:synthetic')
    return window.desktop.setScreenSettings({ enabled: true, sourceId: preview.sourceId,
      sourceName: '합성 학습 창', previewToken: preview.previewToken })
  })
}
