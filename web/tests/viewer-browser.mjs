import assert from 'node:assert/strict'
import { createServer } from 'node:http'
import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { resolve, join } from 'node:path'
import { pathToFileURL } from 'node:url'

// Only the SDK boundary is a fixture. The shipped production bundle and shared controls render unchanged.
function initialize(options) {
  const callbacks = new Set()
  const context = { locale: options.locale, theme: options.theme, visible: true, projectFile: 'qa/project.dlproject', view: { id: options.settings ? 'settings' : 'viewer', location: options.settings ? 'settings' : 'dock' } }
  const fixture = window.fixture = { calls: [], version: 1, settings: { showDebugInfo: true, showSynthetic: false }, settingsVersion: 'v1', job: 0, slow: false, release: undefined, context,
    change(value) { Object.assign(context, value); for (const callback of callbacks) callback({ ...context }) } }
  const clazz = { name: 'qa/Demo', superName: 'java/lang/Object', version: 69, access: 1, sourceFile: 'Demo.php', interfaces: [] }
  const method = { name: 'work', descriptor: '()V', access: 1, maxStack: 1, maxLocals: 1, signature: null, exceptions: [],
    tryCatch: [{ start: 'L0', end: 'L1', handler: 'L1', type: 'java/lang/Exception' }], locals: [{ name: 'this', descriptor: 'Lqa/Demo;', index: 0, start: 'L0', end: 'L1' }] }
  const instructions = [
    { index: 0, line: 41, opcode: -1, mnemonic: 'LABEL', operand: 'L0' },
    { index: 0, line: 41, opcode: 21, mnemonic: 'ILOAD', operand: '0' },
    { index: 1, line: 41, opcode: 153, mnemonic: 'IFEQ', operand: 'L1' },
    { index: 2, line: 42, opcode: -1, mnemonic: 'LABEL', operand: 'L1' },
    { index: 2, line: 42, opcode: 177, mnemonic: 'RETURN', operand: '' },
  ]
  window.devline = {
    ready: Promise.resolve(), getContext: () => ({ ...context }), onContextChanged(callback) { callbacks.add(callback); return () => callbacks.delete(callback) },
    async call(name, args = {}) {
      fixture.calls.push({ name, args })
      if (name === 'state') return { projectFile: context.projectFile, files: [...(args.path === 'src/hidden/Late.php' ? [{ path: args.path, kind: 'php', version: '1' }] : []), { path: 'src/Demo.php', kind: 'php', version: String(fixture.version) }, { path: 'lib/Other.class', kind: 'class', version: '1' }], buildFiles: [{ path: 'qa-build/app.jar', kind: 'jar', version: '1' }], settings: { ...fixture.settings }, settingsVersion: fixture.settingsVersion }
      if (name === 'settings') return { settings: { ...fixture.settings }, version: fixture.settingsVersion }
      if (name === 'configure') { assertVersion(args.version); fixture.settings = { ...args.settings }; fixture.settingsVersion = 'v2'; return { settings: fixture.settings, version: fixture.settingsVersion } }
      if (name === 'inspect') { if (fixture.slow) await new Promise(resolve => { fixture.release = resolve }); return { id: 'job-' + ++fixture.job, state: 'preparing' } }
      if (name === 'job') return { id: args.id, state: 'ready' }
      if (name === 'cancel') return { id: args.id, state: 'cancelled' }
      if (name === 'classes') return { source: { path: fixture.calls.findLast(call => call.name === 'inspect').args.path, kind: 'php', origin: fixture.calls.findLast(call => call.name === 'inspect').args.origin, sha256: String(fixture.version).repeat(64), generatedAt: '2026-10-25T10:00:00Z' }, total: 2,
        items: args.offset === 0 ? [{ ...clazz, index: 0, name: 'qa/Hidden', access: 4096, methodCount: 0, fieldCount: 0 }] : [{ ...clazz, index: 1, methodCount: 2, fieldCount: 1 }], nextOffset: args.offset === 0 ? 1 : null, warnings: ['Fixture warning'] }
      if (name === 'classInfo') return { class: clazz, fields: [{ name: 'value', descriptor: 'I', access: 2 }], sourcePath: 'src/Demo.php', totalMethods: 2,
        methods: args.offset === 0 ? [{ ...method, index: 0, instructionCount: 3 }] : [{ ...method, name: 'generated', index: 1, access: 4096, instructionCount: 0 }], nextOffset: args.offset === 0 ? 1 : null }
      if (name === 'method') return { method, instructions: instructions.slice(args.offset, args.offset + 2), total: instructions.length, nextOffset: args.offset + 2 < instructions.length ? args.offset + 2 : null }
      if (name === 'openSource') return { path: 'src/Demo.php', line: args.line }
      if (name.startsWith('host.')) return {}
      throw Error('Unexpected fixture call ' + name)
    },
  }
  function assertVersion(value) { if (value !== fixture.settingsVersion) throw Error('Incorrect settings version') }
}

const output = resolve(process.env.DEVLINE_BYTECODE_UI_EVIDENCE ?? 'devline-bytecode-viewer/build/ui-evidence')
const html = await readFile(resolve('devline-bytecode-viewer/build/extension-web/bytecode.html'), 'utf8')
await mkdir(output, { recursive: true })
const server = createServer((request, response) => {
  const url = new URL(request.url, 'http://127.0.0.1')
  const config = { locale: url.searchParams.get('locale') ?? 'en', theme: url.searchParams.get('theme') ?? 'dark', settings: url.searchParams.has('settings') }
  response.setHeader('Content-Type', 'text/html; charset=utf-8')
  response.end(html.replace('<div id="app"></div>', `<script>(${initialize.toString()})(${JSON.stringify(config)})</script><div id="app"></div>`))
})
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
const url = `http://127.0.0.1:${server.address().port}`
const { chromium } = await import(pathToFileURL(process.env.DEVLINE_PLAYWRIGHT_MODULE ?? 'C:/Users/ozlig/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright/index.mjs').href)
const browser = await chromium.launch({ headless: true })
const errors = [], checks = []
let page
try {
  page = await browser.newPage({ viewport: { width: 1100, height: 660 } })
  page.on('pageerror', error => errors.push(error.message))
  await page.goto(url)
  await page.getByRole('button', { name: 'Inspect bytecode', exact: true }).click()
  const code = page.getByRole('textbox', { name: 'JVM instructions, read only' })
  await code.waitFor()
  await page.waitForFunction(() => document.querySelector('textarea')?.value.includes('RETURN'))
  assert.match(await code.inputValue(), /ILOAD\s+0/)
  assert.match(await code.inputValue(), /IFEQ\s+L1/)
  assert.match(await page.getByRole('combobox', { name: 'Class', exact: true }).innerText(), /qa.Demo/)
  assert.equal(await code.getAttribute('readonly'), '')
  assert.deepEqual(await page.evaluate(() => fixture.calls.filter(call => call.name === 'method').map(call => call.args.offset)), [0, 2, 4])
  checks.push('class, method and instruction pagination including null terminal cursors; hidden synthetic class skipped')
  await page.getByRole('button', { name: 'Open source line', exact: true }).click()
  assert.deepEqual(await page.evaluate(() => fixture.calls.find(call => call.name === 'host.openSource').args), { path: 'src/Demo.php', line: 41 })
  await page.getByRole('button', { name: 'Copy instructions', exact: true }).click()
  assert.match(await page.evaluate(() => fixture.calls.find(call => call.name === 'host.copyText').args.text), /RETURN/)
  await page.getByRole('textbox', { name: 'Find opcode or operand' }).fill('ifeq')
  assert.match(await code.inputValue(), /IFEQ/); assert.doesNotMatch(await code.inputValue(), /RETURN/)
  await page.getByRole('textbox', { name: 'Find opcode or operand' }).fill('')
  checks.push('opcode filtering, full instruction copy via SDK, source resolution and host line navigation')
  await page.mouse.move(500, 400); await page.waitForTimeout(250)
  await page.screenshot({ path: join(output, 'viewer-dark-en.png') })
  await page.getByRole('combobox', { name: 'Bytecode source', exact: true }).click()
  await page.getByRole('option', { name: 'Build output snapshot', exact: true }).click()
  await page.getByRole('button', { name: 'Inspect bytecode', exact: true }).click()
  await page.waitForFunction(() => document.querySelector('textarea')?.value.includes('RETURN'))
  assert.equal(await page.evaluate(() => fixture.calls.findLast(call => call.name === 'inspect').args.origin), 'build')
  await page.getByRole('combobox', { name: 'Bytecode source', exact: true }).click()
  await page.getByRole('option', { name: 'Saved project file', exact: true }).click()
  await page.getByRole('button', { name: 'Inspect bytecode', exact: true }).click()
  await page.waitForFunction(() => document.querySelector('textarea')?.value.includes('RETURN'))
  checks.push('file and build origins use their own inventories and inspect parameters')
  await page.getByRole('button', { name: 'Inspect bytecode', exact: true }).click()
  await page.evaluate(() => fixture.change({ visible: false }))
  const jobCalls = await page.evaluate(() => fixture.calls.filter(call => call.name === 'job').length)
  await page.waitForTimeout(650)
  assert.equal(await page.evaluate(() => fixture.calls.filter(call => call.name === 'job').length), jobCalls)
  await page.evaluate(() => fixture.change({ visible: true }))
  await page.getByRole('button', { name: 'Open source line', exact: true }).waitFor()
  await page.waitForFunction(() => !document.querySelector('[aria-label="Open source line"]')?.disabled)
  checks.push('hidden panel pauses job polling and resumes when shown')
  await page.evaluate(() => { fixture.version++ })
  await page.getByText('Result is out of date. Refresh to inspect again.').waitFor()
  assert.equal(await page.getByRole('button', { name: 'Open source line', exact: true }).isDisabled(), true)
  checks.push('saved source version invalidates results and disables navigation')
  await page.getByRole('button', { name: 'Refresh', exact: true }).click()
  await page.getByText('Result is out of date. Refresh to inspect again.', { exact: true }).waitFor({ state: 'detached' })
  await page.evaluate(() => { fixture.slow = true })
  await page.getByRole('button', { name: 'Inspect bytecode', exact: true }).click()
  await page.waitForFunction(() => typeof fixture.release === 'function')
  await page.getByRole('button', { name: 'Cancel', exact: true }).click()
  await page.evaluate(() => { fixture.slow = false; fixture.release() })
  await page.waitForFunction(() => fixture.calls.some(call => call.name === 'cancel'))
  assert.equal(await page.getByRole('button', { name: 'Open source line', exact: true }).isDisabled(), true)
  checks.push('late inspect response after cancellation is cancelled and cannot publish fresh results')
  await page.evaluate(() => fixture.change({ projectFile: 'qa/other.dlproject' }))
  await code.waitFor({ state: 'detached' })
  checks.push('project change clears old source and instructions')
  await page.evaluate(() => fixture.change({ selectedPath: 'lib/Other.class' }))
  await code.waitFor()
  assert.equal(await page.evaluate(() => fixture.calls.findLast(call => call.name === 'inspect').args.path), 'lib/Other.class')
  checks.push('explicit command selection switches the file and inspects it automatically')
  await page.waitForFunction(() => !document.querySelector('[aria-label="Inspect bytecode"]')?.disabled)
  const inspections = await page.evaluate(() => fixture.calls.filter(call => call.name === 'inspect').length)
  await page.evaluate(() => fixture.change({ selectedPath: 'lib/Other.class', selectionRevision: 1 }))
  await page.waitForFunction(count => fixture.calls.filter(call => call.name === 'inspect').length === count + 1, inspections)
  checks.push('new command revision reinspects the same selected file')
  await page.evaluate(() => fixture.change({ selectedPath: 'src/hidden/Late.php', selectionRevision: 2 }))
  await page.waitForFunction(() => fixture.calls.findLast(call => call.name === 'inspect')?.args.path === 'src/hidden/Late.php')
  assert.equal(await page.evaluate(() => fixture.calls.some(call => call.name === 'state' && call.args.path === 'src/hidden/Late.php')), true)
  checks.push('command path outside capped inventory is requested explicitly and then inspected')
  await page.goto(url + '?locale=ru&theme=light')
  await page.getByRole('button', { name: 'Посмотреть байткод', exact: true }).click()
  await page.getByRole('textbox', { name: 'Инструкции JVM, только чтение' }).waitFor()
  await page.mouse.move(500, 400); await page.waitForTimeout(250)
  await page.screenshot({ path: join(output, 'viewer-light-ru.png') })
  const settings = await browser.newPage({ viewport: { width: 800, height: 400 } })
  settings.on('pageerror', error => errors.push(error.message))
  await settings.goto(url + '?settings')
  await settings.getByRole('switch', { name: 'Show debug information', exact: true }).focus()
  await settings.getByRole('switch', { name: 'Show debug information', exact: true }).press('Space')
  await settings.getByRole('button', { name: 'Save', exact: true }).click()
  await settings.getByText('Saved', { exact: true }).waitFor()
  assert.deepEqual(await settings.evaluate(() => fixture.calls.find(call => call.name === 'configure').args), { settings: { showDebugInfo: false, showSynthetic: false }, version: 'v1' })
  assert.equal(await settings.getByRole('switch', { name: 'Show debug information', exact: true }).isChecked(), false)
  await settings.waitForTimeout(250)
  await settings.screenshot({ path: join(output, 'settings-dark-en.png') })
  await settings.goto(url + '?settings&locale=ru&theme=light')
  await settings.getByRole('switch', { name: 'Показывать синтетические элементы', exact: true }).waitFor()
  await settings.screenshot({ path: join(output, 'settings-light-ru.png') })
  checks.push('settings versioned save, both languages and themes')
  assert.deepEqual(errors, [])
  await writeFile(join(output, 'evidence.json'), JSON.stringify({ fixture: 'SDK boundary fixture; production packaged UI and shared components', checks, errors }, null, 2))
  console.log('BYTECODE_UI_PASS=' + output)
} catch (cause) {
  await page?.screenshot({ path: join(output, 'failure.png') }).catch(() => {})
  await writeFile(join(output, 'failure.json'), JSON.stringify({ checks, errors, cause: String(cause), page: await page?.evaluate(() => ({ body: document.body.innerText, calls: window.fixture?.calls })) }, null, 2))
  throw cause
} finally { await browser.close(); await new Promise(resolve => server.close(resolve)) }
