export interface BytecodeInstruction { index: number; line: number | null; opcode: number; mnemonic: string; operand: string }
export interface BytecodeLocal { name: string; descriptor: string; index: number; start: string; end: string; signature?: string | null }
export interface BytecodeTryCatch { start: string; end: string; handler: string; type: string | null }
export interface BytecodeMethod {
  name: string; descriptor: string; access: number; maxStack: number; maxLocals: number
  instructions: BytecodeInstruction[]; tryCatch: BytecodeTryCatch[]; locals: BytecodeLocal[]
  signature?: string | null; exceptions: string[]
}
export interface BytecodeField { name: string; descriptor: string; access: number; value?: string | null }
export interface BytecodeClass {
  name: string; superName: string | null; version: number; access: number; sourceFile: string | null
  fields: BytecodeField[]; methods: BytecodeMethod[]; interfaces: string[]
}
export type Origin = 'file' | 'build'
export interface BytecodeFile { path: string; kind: string; version?: string; sha256?: string; modifiedAt?: number }
export interface Settings { showDebugInfo: boolean; showSynthetic: boolean }
export interface SettingsState { settings: Settings; settingsVersion: string }
export interface ViewerState extends SettingsState { projectFile?: string; files: BytecodeFile[]; buildFiles: BytecodeFile[]; optimizationProfile?: string }
export interface Inspection {
  source: { path: string; kind: string; origin: Origin; sha256: string; generatedAt: string | number; optimizationProfile?: string }
  classes: BytecodeClass[]; warnings: string[]
}
export type Source = Inspection['source']
export interface Job { id: string; state: 'preparing' | 'ready' | 'failed' | 'cancelled'; errorCode?: string; message?: string }
export interface ClassSummary extends Omit<BytecodeClass, 'methods' | 'fields' | 'interfaces'> { index: number; methodCount: number; fieldCount: number }
export interface MethodSummary extends Pick<BytecodeMethod, 'name' | 'descriptor' | 'access' | 'maxStack' | 'maxLocals'> { index: number; instructionCount: number }
export interface ClassesPage { source: Source; total: number; items: ClassSummary[]; nextOffset?: number; warnings?: string[] }
export interface ClassPage { class: Omit<BytecodeClass, 'fields' | 'methods'>; fields: BytecodeField[]; methods: MethodSummary[]; totalMethods: number; nextOffset?: number; sourcePath?: string }
export interface MethodPage { method: Omit<BytecodeMethod, 'instructions'>; instructions: BytecodeInstruction[]; total: number; nextOffset?: number }
