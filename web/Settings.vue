<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { UIButton, UIIcon, UIInfoTip, UISwitch, vUITooltip as vTippy } from '@devline/ui-web'
import { sdk } from './sdk'
import type { Settings } from './contracts'

const { t } = useI18n()
const context = ref(sdk.getContext()), ready = ref(false), busy = ref(false), saved = ref(false), version = ref('')
const draft = reactive<Settings>({ showDebugInfo: true, showSynthetic: false }), baseline = ref('')
const dirty = computed(() => ready.value && JSON.stringify(draft) !== baseline.value)
const disabled = computed(() => busy.value || !ready.value || !!context.value.view?.readOnly)
let disposed = false, generation = 0
function accept(value: { settings: Settings; version?: string; settingsVersion?: string }) {
  Object.assign(draft, value.settings); version.value = value.version ?? value.settingsVersion ?? ''
  baseline.value = JSON.stringify(draft); ready.value = true
}
async function perform(save = false) {
  if (busy.value || !context.value.visible || save && (disabled.value || !dirty.value)) return
  const epoch = ++generation; busy.value = true; saved.value = false
  try {
    const value = await sdk.call<{ settings: Settings; version: string }>(save ? 'configure' : 'settings', save ? { settings: { ...draft }, version: version.value } : {})
    if (!disposed && epoch === generation) { accept(value); saved.value = save }
  } catch (cause) {
    if (!disposed && epoch === generation) void sdk.call('host.notice', { message: t('settingsFailed'), details: cause instanceof Error ? cause.message : String(cause), severity: 'error' }).catch(() => {})
  } finally { if (!disposed && epoch === generation) busy.value = false }
}
function discard() { Object.assign(draft, JSON.parse(baseline.value)); saved.value = false }
const unsubscribe = sdk.onContextChanged(value => { context.value = value; if (value.visible && !dirty.value) void perform() })
onBeforeUnmount(() => { disposed = true; generation++; unsubscribe() })
void perform()
</script>

<template>
  <main class="bytecode-settings" :aria-label="t('settingsTitle')">
    <section>
      <h3>{{ t('display') }}</h3>
      <div class="bytecode-settings__fields">
        <div class="bytecode-settings__row"><label for="bytecode-debug">{{ t('showDebugInfo') }}<UIInfoTip :label="t('showDebugInfo')" :text="t('showDebugInfoHelp')" /></label><UISwitch id="bytecode-debug" v-model="draft.showDebugInfo" :disabled="disabled" :aria-label="t('showDebugInfo')" /></div>
        <div class="bytecode-settings__row"><label for="bytecode-synthetic">{{ t('showSynthetic') }}<UIInfoTip :label="t('showSynthetic')" :text="t('showSyntheticHelp')" /></label><UISwitch id="bytecode-synthetic" v-model="draft.showSynthetic" :disabled="disabled" :aria-label="t('showSynthetic')" /></div>
      </div>
    </section>
    <p v-if="context.view?.readOnly" role="status">{{ t('readOnlySettings') }}</p>
    <footer class="bytecode-settings__actions">
      <UIButton variant="primary" :disabled="disabled || !dirty" :busy="busy" @click="perform(true)">{{ t('save') }}</UIButton>
      <UIButton :disabled="busy || !dirty" @click="discard">{{ t('discard') }}</UIButton>
      <UIButton variant="quiet" :disabled="busy || dirty" :aria-label="t('refresh')" v-tippy="t('refresh')" @click="perform()"><UIIcon name="arrow-clockwise" /></UIButton>
      <span v-if="saved && !dirty" role="status">{{ t('saved') }}</span>
    </footer>
  </main>
</template>

<style scoped lang="scss" src="./styles/settings.scss" />
