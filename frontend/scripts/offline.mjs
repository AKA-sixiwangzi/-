import { readFile, writeFile, mkdir } from 'node:fs/promises'
import { resolve, dirname } from 'node:path'
import { parseAst } from 'rollup/parseAst'
const root = resolve('dist')
let html = await readFile(resolve(root, 'index.html'), 'utf8')
async function asset(url) {
  const path = resolve(root, url.replace(/^\.\//, ''))
  if (!path.startsWith(root + '/')) throw new Error('Unexpected asset path')
  return readFile(path, 'utf8')
}
for (const match of [...html.matchAll(/<script\b[^>]*src="([^"]+)"[^>]*><\/script>/g)]) {
  const js = await asset(match[1])
  if (
    parseAst(js).body.some(
      (n) =>
        n.type === 'ImportDeclaration' ||
        n.type === 'ExportAllDeclaration' ||
        (n.type === 'ExportNamedDeclaration' && n.source),
    )
  )
    throw new Error('Offline build must have no additional JS chunks')
  html = html.replace(
    match[0],
    () => '<script type="module">' + js.replace(/<\/script/gi, '<\\/script') + '</script>',
  )
}
for (const match of [...html.matchAll(/<link\b[^>]*rel="stylesheet"[^>]*href="([^"]+)"[^>]*>/g)]) {
  const css = await asset(match[1])
  html = html.replace(match[0], () => '<style>' + css + '</style>')
}
html = html.replace(/<link\b[^>]*rel="modulepreload"[^>]*>/g, '')
await mkdir('preview', { recursive: true })
await writeFile('preview/旭日学堂-离线演示.html', html)
await mkdir('../demo', { recursive: true })
await writeFile('../demo/旭日学堂-离线演示.html', html)
console.log('离线演示已生成：preview/旭日学堂-离线演示.html（双击打开，无需Node.js或数据库）')
