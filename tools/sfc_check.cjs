/* 纯内存校验 .vue / .js 是否可编译（沙箱下 npm run build 会因清理 dist 被拦，用这个替代）
   运行：node tools/sfc_check.cjs <文件...>
   不传参数时校验最近改动的常见集合 */
const path = require('path')
const fs = require('fs')

const ROOT = path.join(__dirname, '..', 'frontend')
const modPath = path.join(ROOT, 'node_modules', '@vue', 'compiler-sfc')
let sfc
try {
  sfc = require(modPath)
} catch (e) {
  console.log('找不到 @vue/compiler-sfc：' + modPath)
  process.exit(2)
}

const files = process.argv.slice(2).length
  ? process.argv.slice(2)
  : [
      'src/layout/AppLayout.vue',
      'src/components/NotificationDetailDialog.vue',
      'src/utils/notification.js',
      'src/api/index.js'
    ]

let bad = 0
for (const rel of files) {
  const abs = path.resolve(ROOT, rel)
  if (!fs.existsSync(abs)) { console.log('MISSING | ' + rel); bad++; continue }
  const src = fs.readFileSync(abs, 'utf8')
  if (abs.endsWith('.vue')) {
    const { descriptor, errors } = sfc.parse(src, { filename: abs })
    if (errors.length) {
      console.log('FAIL parse | ' + rel + ' | ' + errors[0].message)
      bad++
      continue
    }
    try {
      const script = sfc.compileScript(descriptor, { id: rel })
      if (descriptor.template) {
        const tpl = sfc.compileTemplate({
          source: descriptor.template.content,
          filename: abs,
          id: rel,
          compilerOptions: { bindingMetadata: script.bindings }
        })
        if (tpl.errors.length) throw new Error(String(tpl.errors[0]))
      }
      console.log('OK | ' + rel + ' | script ' + (script.content.length) + 'B, template ' +
        (descriptor.template ? descriptor.template.content.length : 0) + 'B')
    } catch (e) {
      console.log('FAIL compile | ' + rel + ' | ' + e.message)
      bad++
    }
  } else {
    // .js：用 esbuild（vite 自带）按 ESM 解析，能识别 import/export 与顶层 await
    try {
      const esbuild = require(path.join(ROOT, 'node_modules', 'esbuild'))
      esbuild.transformSync(src, { loader: 'js', format: 'esm' })
      console.log('OK | ' + rel + ' | ' + src.length + 'B')
    } catch (e) {
      console.log('FAIL js | ' + rel + ' | ' + (e.errors ? e.errors.map(x => x.text).join('; ') : e.message))
      bad++
    }
  }
}
console.log(bad ? '\n编译失败 ' + bad + ' 个文件' : '\n全部编译通过')
process.exit(bad ? 1 : 0)
