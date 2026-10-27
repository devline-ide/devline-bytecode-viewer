import { createApp } from 'vue'
import { createI18n } from 'vue-i18n'
import { installUI, setUILocale, UI_LOCALES } from '@devline/ui-web/controls'
import '@devline/ui-web/controls.css'
import './styles/base.scss'
import Viewer from './Viewer.vue'
import Settings from './Settings.vue'
import { appearance, sdk } from './sdk'
import en from './locales/en.json'
import ru from './locales/ru.json'

void sdk.ready.then(() => {
  const context = sdk.getContext()
  const i18n = createI18n({ legacy: false, locale: context.locale.startsWith('ru') ? 'ru' : 'en', fallbackLocale: 'en', messages: { en, ru } })
  appearance(context)
  const app = createApp(context.view?.location === 'settings' ? Settings : Viewer)
  installUI(app, { locale: UI_LOCALES[i18n.global.locale.value] }); app.use(i18n)
  const unsubscribe = sdk.onContextChanged(value => {
    appearance(value); i18n.global.locale.value = value.locale.startsWith('ru') ? 'ru' : 'en'; setUILocale(app, UI_LOCALES[i18n.global.locale.value])
  })
  app.mount('#app')
  window.addEventListener('pagehide', () => { unsubscribe(); app.unmount() }, { once: true })
})
