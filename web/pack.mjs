import { readFile, mkdir, writeFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { resolve, join } from 'node:path'
const source = process.argv[2] ? resolve(process.argv[2]) : fileURLToPath(new URL('../build/panel/', import.meta.url))
const destination = process.argv[3] ? resolve(process.argv[3]) : fileURLToPath(new URL('../build/extension-web/', import.meta.url))
const code = (await readFile(join(source, 'bytecode.js'), 'utf8')).replaceAll('</script', '<\\/script')
const css = (await readFile(join(source, 'bytecode.css'), 'utf8')).replaceAll('</style', '<\\/style')
const html = `<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><style>${css}</style></head><body><div id="app"></div><script>${code}</script></body></html>`
if (Buffer.byteLength(html) > 1024 * 1024) throw Error('Bytecode viewer exceeds the extension webview limit')
await mkdir(destination, { recursive: true })
await writeFile(join(destination, 'bytecode.html'), html)
