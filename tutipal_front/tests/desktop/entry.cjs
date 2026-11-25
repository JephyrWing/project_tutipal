const { app, Menu } = require('electron')
// Test process only. Keep real native Menu objects and product callbacks.
app.disableHardwareAcceleration()
const build = Menu.buildFromTemplate.bind(Menu)
Menu.buildFromTemplate = template => {
  const menu = build(template)
  if (template.some(item => item.label === '캐릭터 위젯 보이기/숨기기')) global.pt30TrayMenu = menu
  return menu
}
// This existing guarded entry isolates profile data and blocks OS protocol registration.
require('../e2e/entry.cjs')
