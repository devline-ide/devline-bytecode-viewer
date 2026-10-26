import type { DevLineWebviewContext, DevLineWebviewSdk } from '@devline/extension-devkit'
export interface Context extends DevLineWebviewContext {
  project?: { projectFile?: string }; selection?: { path?: string }; documentPath?: string
}
type SDK = DevLineWebviewSdk<Context>

declare global { interface Window { devline: SDK } }
export const sdk = window.devline
export const projectOf = (context: Context) => context.projectFile ?? context.project?.projectFile
export function appearance(context: Context) {
  document.documentElement.dataset.theme = context.theme
  for (const [key, value] of Object.entries(context.colors ?? {})) if (key.startsWith('--')) document.documentElement.style.setProperty(key, value)
  for (const [key, value] of Object.entries(context.effects ?? {})) if (key.startsWith('data-ui-')) document.documentElement.setAttribute(key, value)
}
