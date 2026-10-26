import type { BytecodeClass, BytecodeInstruction, BytecodeMethod } from './contracts'

export const synthetic = (access: number) => !!(access & 0x1000)
export const scalarGenerated = (name: string) => name.startsWith('__$scalar$') || name.startsWith('__$scalarEntry$')
export const methodKey = (method: BytecodeMethod) => method.name + method.descriptor
export function modifiers(access: number, kind: 'class' | 'method' | 'field' = 'method') {
  const flags: [number, string][] = [[1, 'public'], [2, 'private'], [4, 'protected'], [8, 'static'], [16, 'final'],
    [32, kind === 'method' ? 'synchronized' : ''], [64, kind === 'field' ? 'volatile' : kind === 'method' ? 'bridge' : ''],
    [128, kind === 'field' ? 'transient' : kind === 'method' ? 'varargs' : ''], [256, 'native'], [512, 'interface'],
    [1024, 'abstract'], [2048, 'strictfp'], [4096, 'synthetic'], [8192, 'annotation'], [16384, 'enum'], [32768, 'mandated']]
  return flags.filter(([flag, text]) => text && (access & flag)).map(([, text]) => text).join(' ')
}
export function classText(value: BytecodeClass, showSynthetic: boolean) {
  return [`${modifiers(value.access, 'class')} ${value.name}`, value.superName ? `extends ${value.superName}` : '',
    value.interfaces.length ? `implements ${value.interfaces.join(', ')}` : '', `class version ${value.version}`,
    ...value.fields.filter(field => showSynthetic || !synthetic(field.access)).map(field =>
      `${modifiers(field.access, 'field')} ${field.name}: ${field.descriptor}${field.value != null ? ` = ${field.value}` : ''}`)].filter(Boolean).join('\n')
}
export function instructionText(item: BytecodeInstruction, debug: boolean) {
  if (item.opcode < 0) return `      ${item.operand}:`
  return `${String(item.index).padStart(5)}  ${debug ? `${item.line == null ? '' : item.line}`.padStart(5) + '  ' : ''}${item.mnemonic.padEnd(17)} ${item.operand}`.trimEnd()
}
export function debugText(method: BytecodeMethod) {
  return [method.signature ? `signature ${method.signature}` : '', method.exceptions.length ? `throws ${method.exceptions.join(', ')}` : '',
    ...method.tryCatch.map(item => `try ${item.start}..${item.end} -> ${item.handler} ${item.type ?? 'finally'}`),
    ...method.locals.map(item => `local ${item.index} ${item.name}: ${item.descriptor} ${item.start}..${item.end}${item.signature ? ` ${item.signature}` : ''}`)].filter(Boolean).join('\n')
}
