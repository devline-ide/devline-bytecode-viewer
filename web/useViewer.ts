import { computed, nextTick, onBeforeUnmount, ref, shallowRef, watch } from 'vue'
import { sdk, projectOf } from './sdk'
import { scalarGenerated } from './format'
import type { BytecodeInstruction, BytecodeMethod, ClassPage, ClassesPage, ClassSummary, Job, MethodPage, MethodSummary, Origin, Source, ViewerState } from './contracts'

export function useViewer(fail: (cause: unknown) => void) {
  const context = shallowRef(sdk.getContext()), state = shallowRef<ViewerState>()
  const origin = ref<Origin>('file'), path = ref(''), source = shallowRef<Source>()
  const classes = shallowRef<ClassSummary[]>([]), classIndex = ref(''), classInfo = shallowRef<ClassPage>()
  const methods = shallowRef<MethodSummary[]>([]), methodIndex = ref(''), method = shallowRef<BytecodeMethod>()
  const warnings = shallowRef<string[]>([]), loading = ref(false), methodLoading = ref(false), stale = ref(false), filesFailed = ref(false), phase = ref('loading')
  const fileOptions = computed(() => origin.value === 'file' ? state.value?.files ?? [] : state.value?.buildFiles ?? [])
  let disposed = false, generation = 0, selection = 0, stateRequest = 0, currentJob: Job | undefined, statePending = false
  let inspectedFingerprint = '', inspectedProject = '', inspectedPath = '', inspectedOrigin: Origin = 'file', lastStateError = ''
  let pendingCommandPath = context.value.selectedPath ?? ''
  const fingerprint = () => JSON.stringify([fileOptions.value.find(file => file.path === path.value) ?? null,
    origin.value === 'file' && /\.php$/i.test(path.value) ? state.value?.optimizationProfile ?? 'compatible' : null])
  const active = (epoch: number) => !disposed && generation === epoch
  const pause = () => new Promise<void>(resolve => window.setTimeout(resolve, 400))
  async function waitVisible(epoch: number, chosen?: number) {
    while (active(epoch) && (chosen === undefined || chosen === selection) && !context.value.visible) await pause()
    return active(epoch) && (chosen === undefined || chosen === selection)
  }
  const args = () => ({ expectedProjectFile: inspectedProject, id: currentJob!.id })
  function cancel() {
    generation++; selection++; loading.value = false; methodLoading.value = false
    if (currentJob?.state === 'preparing') void sdk.call('cancel', { expectedProjectFile: inspectedProject, id: currentJob.id }).catch(() => {})
    if (source.value) stale.value = true
  }
  function clearProject() {
    cancel(); source.value = undefined; classes.value = []; classInfo.value = undefined; methods.value = []; method.value = undefined
    classIndex.value = ''; methodIndex.value = ''; warnings.value = []; currentJob = undefined; path.value = ''
  }
  async function refreshState() {
    if (disposed || statePending || !context.value.visible) return
    statePending = true
    const request = ++stateRequest
    try {
      const hint = pendingCommandPath || (origin.value === 'file' ? path.value : context.value.selectedPath)
      const value = await sdk.call<ViewerState>('state', hint && hint.length <= 4096 ? { path: hint } : {})
      if (disposed || request !== stateRequest) return
      if (state.value?.projectFile && state.value.projectFile !== value.projectFile) clearProject()
      state.value = value; lastStateError = ''; filesFailed.value = false
      const selected = context.value.selectedPath ?? context.value.documentPath ?? context.value.selection?.path ?? context.value.document?.path
      if (!path.value || !fileOptions.value.some(file => file.path === path.value)) path.value = fileOptions.value.find(file => file.path === selected)?.path ?? fileOptions.value[0]?.path ?? ''
      if (currentJob && (inspectedProject !== value.projectFile || inspectedOrigin !== origin.value || inspectedPath !== path.value || inspectedFingerprint !== fingerprint())) cancel()
      if (pendingCommandPath && value.files.some(file => file.path === pendingCommandPath)) {
        const target = pendingCommandPath; pendingCommandPath = ''; origin.value = 'file'; path.value = target
        void nextTick().then(() => { if (!disposed && request === stateRequest && path.value === target && origin.value === 'file') void inspect() })
      }
    } catch (cause) {
      const key = String((cause as { code?: string }).code ?? cause)
      filesFailed.value = true
      if (!disposed && request === stateRequest && key !== lastStateError) { lastStateError = key; fail(cause) }
    }
    finally { statePending = false }
  }
  async function inspect() {
    cancel()
    if (!state.value?.projectFile || !path.value || !context.value.visible) return
    const epoch = generation
    inspectedProject = state.value.projectFile; inspectedPath = path.value; inspectedOrigin = origin.value; inspectedFingerprint = fingerprint()
    loading.value = true; phase.value = 'compiling'
    try {
      let job = await sdk.call<Job>('inspect', { expectedProjectFile: inspectedProject, path: inspectedPath, origin: inspectedOrigin })
      if (!active(epoch)) { if (job.state === 'preparing') void sdk.call('cancel', { expectedProjectFile: inspectedProject, id: job.id }).catch(() => {}); return }
      currentJob = job
      while (job.state === 'preparing') {
        await pause()
        if (!active(epoch)) return
        if (!context.value.visible) continue
        job = await sdk.call<Job>('job', args()); currentJob = job
      }
      if (!active(epoch)) return
      if (job.state !== 'ready') throw { code: job.errorCode ?? 'BYTECODE_FAILED', message: job.message }
      phase.value = 'loadingClasses'
      const all: ClassSummary[] = []; let offset: number | undefined = 0, page: ClassesPage | undefined
      while (offset !== undefined) {
        if (!await waitVisible(epoch)) return
        page = await sdk.call<ClassesPage>('classes', { ...args(), offset })
        all.push(...page.items)
        if (page.nextOffset != null && page.nextOffset <= offset) throw Error('Invalid class page cursor')
        offset = page.nextOffset ?? undefined
      }
      if (!active(epoch) || !page) return
      if (inspectedProject !== state.value?.projectFile || inspectedPath !== path.value || inspectedOrigin !== origin.value || inspectedFingerprint !== fingerprint()) throw { code: 'FILE_CONFLICT' }
      source.value = page.source; classes.value = all; warnings.value = page.warnings ?? []; stale.value = false
      classInfo.value = undefined; methods.value = []; method.value = undefined
      classIndex.value = String(all.find(item => state.value?.settings.showSynthetic || !(item.access & 0x1000))?.index ?? '')
      if (classIndex.value !== '') await chooseClass(classIndex.value)
    } catch (cause) { if (active(epoch)) { stale.value = !!source.value; fail(cause) } }
    finally { if (active(epoch)) loading.value = false }
  }
  async function chooseClass(value: string) {
    const chosen = ++selection
    classIndex.value = value; methodIndex.value = ''; method.value = undefined; classInfo.value = undefined; methods.value = []
    if (!currentJob || stale.value || !value) { methodLoading.value = false; return }
    const epoch = generation; methodLoading.value = true
    try {
      let offset: number | undefined = 0, details: ClassPage | undefined; const all: MethodSummary[] = []
      while (offset !== undefined) {
        if (!await waitVisible(epoch, chosen)) return
        const page: ClassPage = await sdk.call<ClassPage>('classInfo', { ...args(), classIndex: Number(value), offset })
        details ??= page; all.push(...page.methods)
        if (page.nextOffset != null && page.nextOffset <= offset) throw Error('Invalid method page cursor')
        offset = page.nextOffset ?? undefined
      }
      if (!active(epoch) || chosen !== selection || !details) return
      classInfo.value = details; methods.value = all
      const first = all.find(item => state.value?.settings.showSynthetic || !(item.access & 0x1000) || scalarGenerated(item.name))
      methodIndex.value = first ? String(first.index) : ''
      if (first) await chooseMethod(String(first.index))
    } catch (cause) { if (active(epoch) && chosen === selection) fail(cause) }
    finally { if (active(epoch) && chosen === selection) methodLoading.value = false }
  }
  async function chooseMethod(value: string) {
    const chosen = ++selection
    methodIndex.value = value; method.value = undefined
    if (!currentJob || stale.value || value === '') { methodLoading.value = false; return }
    const epoch = generation; methodLoading.value = true
    try {
      const instructions: BytecodeInstruction[] = []; let offset: number | undefined = 0, details: MethodPage | undefined
      while (offset !== undefined) {
        if (!await waitVisible(epoch, chosen)) return
        const page: MethodPage = await sdk.call<MethodPage>('method', { ...args(), classIndex: Number(classIndex.value), methodIndex: Number(value), offset })
        details ??= page; instructions.push(...page.instructions)
        if (page.nextOffset != null && page.nextOffset <= offset) throw Error('Invalid instruction page cursor')
        offset = page.nextOffset ?? undefined
      }
      if (active(epoch) && chosen === selection && details) method.value = { ...details.method, instructions }
    } catch (cause) { if (active(epoch) && chosen === selection) { stale.value = true; fail(cause) } }
    finally { if (active(epoch) && chosen === selection) methodLoading.value = false }
  }
  async function openSource(line: number) {
    if (!currentJob || stale.value || loading.value || methodLoading.value) return
    const epoch = generation, chosen = selection
    try {
      const value = await sdk.call<{ path: string; line: number }>('openSource', { ...args(), classIndex: Number(classIndex.value), line })
      if (active(epoch) && chosen === selection && !stale.value) await sdk.call('host.openSource', value)
    } catch (cause) { if (active(epoch) && chosen === selection) fail(cause) }
  }
  watch([origin, path], () => { cancel(); if (path.value && !fileOptions.value.some(file => file.path === path.value)) path.value = fileOptions.value[0]?.path ?? '' })
  const unsubscribe = sdk.onContextChanged(value => {
    const previous = projectOf(context.value), next = projectOf(value)
    if (value.selectedPath && (value.selectedPath !== context.value.selectedPath || value.selectionRevision !== context.value.selectionRevision)) pendingCommandPath = value.selectedPath
    context.value = value
    if (previous !== next && (previous !== undefined || next !== undefined)) { stateRequest++; clearProject(); state.value = undefined }
    if (value.visible) void refreshState()
  })
  const poll = window.setInterval(() => { void refreshState() }, 2500)
  onBeforeUnmount(() => { disposed = true; cancel(); stateRequest++; unsubscribe(); window.clearInterval(poll) })
  void refreshState()
  return { context, state, origin, path, fileOptions, source, classes, classIndex, classInfo, methods, methodIndex, method,
    warnings, loading, methodLoading, stale, filesFailed, phase, refreshState, inspect, cancel, chooseClass, chooseMethod, openSource }
}
