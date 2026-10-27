<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { UIButton, UIIcon, UIInfoTip, UIInput, UISelect, UITextArea, vUITooltip as vTippy } from '@devline/ui-web/controls'
import { sdk } from './sdk'
import { classText, debugText, instructionText, modifiers, scalarGenerated, synthetic } from './format'
import { useViewer } from './useViewer'

const { t, te, locale } = useI18n()
function fail(cause: unknown) {
  const error = cause as { code?: string; message?: string }
  const message = error.code && te(`errors.${error.code}`) ? t(`errors.${error.code}`) : t('failed')
  void sdk.call('host.notice', { message, details: error.message ?? String(cause), severity: 'error' }).catch(() => {})
}
const viewer = useViewer(fail)
const { state, origin, path, fileOptions, source, classes, classIndex, classInfo, methods, methodIndex, method, warnings, loading, methodLoading, stale, filesFailed, phase } = viewer
const query = ref(''), caretRow = ref(0), codeArea = ref<InstanceType<typeof UITextArea>>()
const showDebug = computed(() => state.value?.settings.showDebugInfo ?? true)
const showSynthetic = computed(() => state.value?.settings.showSynthetic ?? false)
const visibleClasses = computed(() => classes.value.filter(item => showSynthetic.value || !synthetic(item.access)))
const visibleMethods = computed(() => methods.value.filter(item => showSynthetic.value || !synthetic(item.access) || scalarGenerated(item.name)))
const classOptions = computed(() => visibleClasses.value.map(item => ({ value: String(item.index), label: item.name.replaceAll('/', '.') })))
const methodOptions = computed(() => visibleMethods.value.map(item => ({ value: String(item.index), label: item.name + item.descriptor +
  (item.name.startsWith('__$scalarEntry$') ? ' · ' + t('scalarEntry') : item.name.startsWith('__$scalar$') ? ' · ' + t('scalarKernel') : '') })))
const rows = computed(() => {
  const find = query.value.trim().toLocaleLowerCase()
  return (method.value?.instructions ?? []).filter(item => !find || `${item.mnemonic} ${item.operand}`.toLocaleLowerCase().includes(find))
})
const code = computed(() => rows.value.map(item => instructionText(item, showDebug.value)).join('\n'))
const classCode = computed(() => classInfo.value ? classText({ ...classInfo.value.class, fields: classInfo.value.fields, methods: [] }, showSynthetic.value) : '')
const debug = computed(() => method.value ? debugText(method.value) : '')
const selectedLine = computed(() => rows.value[caretRow.value]?.line ?? null)
const sourcePath = computed(() => {
  if (classInfo.value?.sourcePath) return classInfo.value.sourcePath
  if (source.value?.origin === 'file' && /\.(php|kt|java)$/i.test(source.value.path)) return source.value.path
  const filename = classInfo.value?.class.sourceFile
  if (!filename) return undefined
  const candidates = state.value?.files.filter(file => file.path === filename || file.path.endsWith('/' + filename)) ?? []
  return candidates.length === 1 ? candidates[0]!.path : undefined
})
const canOpenSource = computed(() => !stale.value && !loading.value && !methodLoading.value && !!sourcePath.value && selectedLine.value != null && selectedLine.value > 0)
const generated = computed(() => {
  if (!source.value) return ''
  const value = new Date(source.value.generatedAt)
  return Number.isNaN(value.getTime()) ? String(source.value.generatedAt) : value.toLocaleString(locale.value)
})
function caret(event: Event) {
  const target = event.target as HTMLTextAreaElement
  caretRow.value = target.value.slice(0, target.selectionStart).split('\n').length - 1
}
async function openSource() {
  if (!canOpenSource.value) return
  await viewer.openSource(selectedLine.value!)
}
async function copy() {
  if (!code.value) return
  try {
    try {
      if (new TextEncoder().encode(code.value).byteLength > 256 * 1024) throw Error('Clipboard request limit')
      await sdk.call('host.copyText', { text: code.value })
    }
    catch {
      const element = codeArea.value?.$el as HTMLTextAreaElement | undefined
      if (!element) throw Error(t('copyFailed'))
      element.focus(); element.select()
      if (!document.execCommand('copy')) { await sdk.call('host.notice', { message: t('copyManually'), severity: 'info' }); return }
    }
    await sdk.call('host.notice', { message: t('copied'), severity: 'info' })
  } catch (cause) { fail(cause) }
}
async function refresh() { await viewer.refreshState(); await viewer.inspect() }
watch([origin, locale], () => {
  void sdk.call('host.headerHelp', { label: t('title'), text: origin.value === 'build' ? t('buildHelp') : t('fileHelp') }).catch(() => {})
}, { immediate: true })
watch([query, method, showDebug], () => { caretRow.value = 0 })
watch(showSynthetic, () => {
  if (!visibleClasses.value.some(item => String(item.index) === classIndex.value)) void viewer.chooseClass(classOptions.value[0]?.value ?? '')
  else if (!visibleMethods.value.some(item => String(item.index) === methodIndex.value)) void viewer.chooseMethod(methodOptions.value[0]?.value ?? '')
})
</script>

<template>
  <main class="bytecode-viewer" :aria-label="t('title')">
    <header class="bytecode-viewer__toolbar">
      <UISelect v-model="origin" class="bytecode-viewer__origin" :aria-label="t('origin')" :disabled="loading" :options="[{ value: 'file', label: t('savedFile') }, { value: 'build', label: t('buildSnapshot') }]" />
      <UISelect v-model="path" class="bytecode-viewer__file" searchable :aria-label="t('file')" :disabled="loading || !fileOptions.length" :options="fileOptions.map(file => ({ value: file.path, label: file.path }))" />
      <UIButton variant="primary" :disabled="!state?.projectFile || !path || loading" :aria-label="t('inspect')" v-tippy="t('inspect')" @click="viewer.inspect"><UIIcon name="code" /></UIButton>
      <UIButton v-if="loading || methodLoading" :aria-label="t('cancel')" v-tippy="t('cancel')" @click="viewer.cancel"><UIIcon name="x-lg" /></UIButton>
      <UIButton v-else :aria-label="t('refresh')" v-tippy="t('refresh')" @click="refresh"><UIIcon name="arrow-clockwise" /></UIButton>
    </header>
    <div v-if="loading || methodLoading || stale || source" class="bytecode-viewer__summary">
      <span v-if="loading || methodLoading" role="status" aria-live="polite">{{ t(methodLoading ? 'loadingMethod' : phase) }}</span>
      <span v-if="stale" class="bytecode-viewer__stale" role="status">{{ t('stale') }}</span>
      <span v-if="source" class="bytecode-viewer__path is-selectable">{{ source.path }}</span>
      <span v-if="source" class="bytecode-viewer__stamp is-selectable">{{ source.kind }}<template v-if="source.optimizationProfile"> · {{ source.optimizationProfile }}</template> · {{ generated }} · SHA-256 {{ source.sha256.slice(0, 12) }}</span>
    </div>
    <div v-if="!state" class="bytecode-viewer__empty" role="status">{{ t(filesFailed ? 'filesFailed' : 'loading') }}</div>
    <div v-else-if="!state.projectFile" class="bytecode-viewer__empty">{{ t('noProject') }}</div>
    <div v-else-if="!fileOptions.length" class="bytecode-viewer__empty">{{ t(origin === 'build' ? 'noBuild' : 'noFiles') }}</div>
    <template v-else-if="source">
      <div class="bytecode-viewer__selectors">
        <UISelect :model-value="classIndex" searchable :aria-label="t('class')" :disabled="loading || stale || !classOptions.length" :options="classOptions" @update:model-value="viewer.chooseClass(String($event ?? ''))" />
        <UISelect :model-value="methodIndex" searchable :aria-label="t('method')" :disabled="loading || stale || methodLoading || !methodOptions.length" :options="methodOptions" @update:model-value="viewer.chooseMethod(String($event ?? ''))" />
      </div>
      <details v-if="classInfo" class="bytecode-viewer__metadata"><summary>{{ t('classDetails') }} · {{ classInfo.class.version }}</summary><pre>{{ classCode }}</pre></details>
      <div class="bytecode-viewer__code-toolbar">
        <UIInput v-model="query" :aria-label="t('search')" :placeholder="t('search')" :disabled="!method" spellcheck="false" @keydown.esc="query = ''" />
        <span class="bytecode-viewer__count">{{ rows.length }} / {{ method?.instructions.length ?? 0 }}</span>
        <UIInfoTip :label="t('instructionIndex')" :text="t('instructionIndexHelp')" />
        <UIButton variant="quiet" :disabled="!code" :aria-label="t('copy')" v-tippy="t('copy')" @click="copy"><UIIcon name="clipboard" /></UIButton>
        <UIButton variant="quiet" :disabled="!canOpenSource" :aria-label="t('openSource')" v-tippy="canOpenSource ? t('sourceShortcut') : t('sourceUnavailable')" @click="openSource"><UIIcon name="box-arrow-up-right" /><span v-if="selectedLine != null">{{ t('line', { line: selectedLine }) }}</span></UIButton>
      </div>
      <div v-if="method" class="bytecode-viewer__method-info is-selectable">{{ modifiers(method.access) }} {{ method.name }}{{ method.descriptor }} · {{ t('stackLocals', { stack: method.maxStack, locals: method.maxLocals }) }}</div>
      <UITextArea v-if="method && code" ref="codeArea" :model-value="code" readonly class="bytecode-viewer__code" :aria-label="t('instructions')" spellcheck="false" wrap="off" @click="caret" @select="caret" @keyup="caret" @keydown.alt.enter.prevent="openSource" />
      <div v-else class="bytecode-viewer__empty" role="status">{{ t(methodLoading ? 'loadingMethod' : !classOptions.length ? 'noClasses' : method ? query ? 'noMatches' : 'noInstructions' : methods.length ? 'selectMethod' : 'noMethods') }}</div>
      <details v-if="showDebug && debug" class="bytecode-viewer__metadata"><summary>{{ t('debugDetails') }}</summary><pre>{{ debug }}</pre></details>
      <details v-if="warnings.length" class="bytecode-viewer__metadata"><summary>{{ t('warnings', { count: warnings.length }) }}</summary><pre>{{ warnings.join('\n') }}</pre></details>
    </template>
    <div v-else class="bytecode-viewer__empty">{{ t('chooseFile') }}</div>
  </main>
</template>

<style scoped lang="scss" src="./styles/viewer.scss" />
