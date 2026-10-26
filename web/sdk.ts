export interface Context {
  locale: string; theme: string; visible: boolean; colors?: Record<string, string>; effects?: Record<string, string>
  projectFile?: string; project?: { projectFile?: string }; selectedPath?: string; selectionRevision?: number; documentPath?: string
  selection?: { path?: string }; document?: { path: string }
  view?: { id: string; location: 'dock' | 'sidebar' | 'settings'; readOnly?: boolean; settingsScope?: 'user' | 'workspace' }
}
interface SDK {
  ready: Promise<unknown>
  getContext(): Context
  onContextChanged(callback: (context: Context) => void): () => void
  call<T>(method: string, params?: Record<string, unknown>): Promise<T>
}
declare global { interface Window { devline: SDK } }
export const sdk = window.devline
export const projectOf = (context: Context) => context.projectFile ?? context.project?.projectFile
export function appearance(context: Context) {
  document.documentElement.dataset.theme = context.theme
  for (const [key, value] of Object.entries(context.colors ?? {})) if (key.startsWith('--')) document.documentElement.style.setProperty(key, value)
  for (const [key, value] of Object.entries(context.effects ?? {})) if (key.startsWith('data-ui-')) document.documentElement.setAttribute(key, value)
}
