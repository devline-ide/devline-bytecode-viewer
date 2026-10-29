/** Install and build only the dependencies declared by the extension itself. */
import fs from 'node:fs'
import path from 'node:path'
import { spawnSync } from 'node:child_process'
const tools = ['vite', '@vitejs/plugin-vue', 'vue-tsc', 'typescript', 'sass']

function npm(arguments_, cwd, capture = false) {
  const executable = process.platform === 'win32' ? 'cmd' : 'npm'
  const args = process.platform === 'win32' ? ['/d', '/c', 'npm', ...arguments_] : arguments_
  const result = spawnSync(executable, args, { cwd, encoding: 'utf8', stdio: capture ? 'pipe' : 'inherit' })
  if (result.error) throw result.error
  if (!capture && result.status !== 0) throw new Error(`npm ${arguments_[0]} failed (${result.status})`)
  return result
}

function prepareExtension(directory) {
  const manifest = JSON.parse(fs.readFileSync(path.join(directory, 'package.json'), 'utf8'))
  if (!manifest.devDependencies?.['@devline/extension-devkit']) throw new Error('The extension has no declared web devkit dependency')
  if (!manifest.dependencies?.['@devline/ui-web'] || tools.some(name => !manifest.devDependencies?.[name])) {
    throw new Error('The extension must declare its UI package and frontend tools. Update to the pinned extension revision, or use that checkout\'s matching SDK.')
  }
  npm(['ci', '--ignore-scripts', '--no-audit', '--no-fund'], directory)
}

const [mode, value] = process.argv.slice(2)
if (!value || !['extension', 'panel'].includes(mode)) throw new Error('Usage: frontend.mjs extension|panel <directory>')
const directory = fs.realpathSync(value)
if (mode === 'extension') prepareExtension(directory)
else {
  // The documented bootstrap (or Gradle panelInstall task) prepares dependencies.
  // This entry point also works without configuring unrelated JVM IDE projects.
  for (const arguments_ of [
    [path.join(directory, 'node_modules/vue-tsc/bin/vue-tsc.js'), '--noEmit', '-p', path.join(directory, 'web/tsconfig.json')],
    [path.join(directory, 'node_modules/vite/bin/vite.js'), 'build', '--config', path.join(directory, 'web/vite.config.ts'), '--outDir', path.join(directory, 'build/panel')],
  ]) {
    const result = spawnSync(process.execPath, arguments_, { cwd: directory, stdio: 'inherit' })
    if (result.error) throw result.error
    if (result.status !== 0) throw new Error(`Extension panel verification/build failed (${result.status})`)
  }
}
