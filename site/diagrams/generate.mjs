// Draws the figures of the documentation as SVG files in docs/images/, in one style:
//
// - the sequence diagrams of docs/sequence-flows.md, from their source in sequences/*.mmd (Mermaid's sequence syntax:
//   participant, ->>, -->>, --x, Note over, par/and, loop, alt/else, end, autonumber);
// - the topology figures (modules, one instance, the shape of a saga, the cluster), laid out here.
//
// Each file carries its own palette, light and dark (prefers-color-scheme), so GitHub shows it in the reader's
// system theme. The site puts the drawing into the page without that palette and colours its classes by the theme
// the reader picked (site/app/globals.css, "A figure of docs/images").
//
// Run: node site/diagrams/generate.mjs (from the repository root or from site/)

import { readFileSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const out = join(here, '..', '..', 'docs', 'images')

// ---------------------------------------------------------------------------------------------------------------
// Style

const palette = {
  light: {
    bg: '#fbfbf9', box: '#f2f2ef', line: '#8f9297', text: '#1d1f22', muted: '#55595f', accent: '#b3262c',
    accentBg: '#fbe9ea', note: '#fff7e0', noteLine: '#d9c17a', ok: '#1f7a4d', danger: '#b3262c',
  },
  dark: {
    bg: '#121315', box: '#202226', line: '#7b7f86', text: '#e9e9e6', muted: '#a8aaad', accent: '#f06a70',
    accentBg: '#3a1c1e', note: '#2b2617', noteLine: '#6b5c2c', ok: '#5fc995', danger: '#ff8a8f',
  },
}

const rules = (c) => `
.bg{fill:${c.bg}}
.box{fill:${c.box};stroke:${c.line};stroke-width:1.2}
.lib{fill:${c.accentBg};stroke:${c.accent};stroke-width:1.5}
.frame{fill:none;stroke:${c.line};stroke-width:1.2;stroke-dasharray:6 4}
.frame-lib{fill:none;stroke:${c.accent};stroke-width:1.4;stroke-dasharray:6 4}
.group{fill:none;stroke:${c.line};stroke-width:1.1}
.tab{fill:${c.box};stroke:${c.line};stroke-width:1.1}
.divider{stroke:${c.line};stroke-width:1;stroke-dasharray:4 4}
.lifeline{stroke:${c.line};stroke-width:1;stroke-dasharray:3 4}
.edge{fill:none;stroke:${c.line};stroke-width:1.5}
.dash{stroke-dasharray:5 4}
.fail{stroke:${c.danger}}
.head{fill:${c.line}}
.head-fail{fill:none;stroke:${c.danger};stroke-width:1.8}
.note{fill:${c.note};stroke:${c.noteLine};stroke-width:1}
.num{fill:${c.text}}
.num-text{fill:${c.bg};font-size:10.5px;font-weight:700}
text{fill:${c.text};font-family:ui-sans-serif,system-ui,-apple-system,'Segoe UI',Roboto,sans-serif;font-size:13px}
.title{font-size:13.5px;font-weight:650}
.small{fill:${c.muted};font-size:11.5px}
.label{font-size:12.5px}
.mono{font-family:ui-monospace,'SF Mono',Menlo,Consolas,monospace;font-size:12.5px}
.accent{fill:${c.accent}}
.ok{fill:${c.ok}}`

const style = `<style>${rules(palette.light)}
@media (prefers-color-scheme: dark){${rules(palette.dark)}
}
</style>`

const esc = (s) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')

/** The width of a line of text, estimated: the drawings use the system's sans-serif font */
const widthOf = (s, size = 13, mono = false) => s.length * size * (mono ? 0.62 : 0.57)

function svg(name, title, desc, width, height, body) {
  const file = join(out, `${name}.svg`)
  const content = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${Math.ceil(width)} ${Math.ceil(height)}" width="${Math.ceil(width)}" height="${Math.ceil(height)}" role="img" aria-labelledby="${name}-title ${name}-desc">
<title id="${name}-title">${esc(title)}</title>
<desc id="${name}-desc">${esc(desc)}</desc>
${style}
<rect class="bg" width="100%" height="100%" rx="8"/>
${body.join('\n')}
</svg>
`
  writeFileSync(file, content)
  console.log(`docs/images/${name}.svg`)
}

const text = (x, y, s, cls = '', anchor = 'middle') =>
  `<text x="${r(x)}" y="${r(y)}"${cls ? ` class="${cls}"` : ''}${anchor === 'start' ? '' : ` text-anchor="${anchor}"`}>${esc(s)}</text>`
const r = (n) => Math.round(n * 10) / 10

/** An arrowhead at (x, y) pointing in the direction (dx, dy) */
function head(x, y, dx, dy) {
  const len = Math.hypot(dx, dy) || 1
  const ux = dx / len
  const uy = dy / len
  const p = (a, b) => `${r(x - ux * 9 + a * -uy * 4.5)},${r(y - uy * 9 + b * ux * 4.5)}`
  return `<path class="head" d="M${r(x)},${r(y)} L${p(1, 1)} L${p(-1, -1)} Z"/>`
}

/** A box with a title and lines under it */
function box(x, y, w, h, lines, cls = 'box', mono = true) {
  const [first, ...rest] = lines
  const total = 17 + rest.length * 15
  let ty = y + h / 2 - total / 2 + 13
  const parts = [`<rect class="${cls}" x="${r(x)}" y="${r(y)}" width="${r(w)}" height="${r(h)}" rx="7"/>`]
  parts.push(text(x + w / 2, ty, first, mono ? 'mono title' : 'title'))
  for (const line of rest) {
    ty += 15
    parts.push(text(x + w / 2, ty, line, 'small'))
  }
  return parts.join('\n')
}

/** A database or other store: a cylinder */
function store(x, y, w, h, lines) {
  const ry = 7
  const parts = [
    `<path class="box" d="M${x},${y + ry} a${w / 2},${ry} 0 0 1 ${w},0 v${h - 2 * ry} a${w / 2},${ry} 0 0 1 ${-w},0 Z"/>`,
    `<path class="box" d="M${x},${y + ry} a${w / 2},${ry} 0 0 0 ${w},0"/>`,
  ]
  const [first, ...rest] = lines
  let ty = y + ry + (h - ry) / 2 - (rest.length * 15) / 2 + 4
  parts.push(text(x + w / 2, ty, first, 'title'))
  for (const line of rest) {
    ty += 15
    parts.push(text(x + w / 2, ty, line, 'small'))
  }
  return parts.join('\n')
}

/** A line through points, with an arrowhead at its end unless `arrow` is false */
function edge(points, { cls = '', arrow = true, label, lx, ly, anchor = 'middle' } = {}) {
  const d = points.map(([x, y], i) => `${i ? 'L' : 'M'}${r(x)},${r(y)}`).join(' ')
  const parts = [`<path class="edge${cls ? ` ${cls}` : ''}" d="${d}"/>`]
  if (arrow) {
    const [x1, y1] = points.at(-2)
    const [x2, y2] = points.at(-1)
    parts.push(head(x2, y2, x2 - x1, y2 - y1))
  }
  if (label) {
    for (const [i, line] of label.split('\n').entries()) parts.push(text(lx, ly + i * 15, line, 'label', anchor))
  }
  return parts.join('\n')
}

/** A dashed frame with its name in its top-left corner */
function frame(x, y, w, h, name, cls = 'frame', right = false) {
  return [
    `<rect class="${cls}" x="${r(x)}" y="${r(y)}" width="${r(w)}" height="${r(h)}" rx="10"/>`,
    right
      ? text(x + w - 14, y + 20, name, 'title', 'end')
      : text(x + 14, y + 20, name, cls === 'frame-lib' ? 'title accent' : 'title', 'start'),
  ].join('\n')
}

// ---------------------------------------------------------------------------------------------------------------
// Sequence diagrams

function parseSequence(source) {
  const participants = []
  const items = []
  let numbered = false
  const index = (id) => {
    const at = participants.findIndex((p) => p.id === id)
    if (at < 0) throw new Error(`unknown participant ${id}`)
    return at
  }
  for (const raw of source.split('\n')) {
    const line = raw.trim()
    if (!line || line === 'sequenceDiagram') continue
    let m
    if (line === 'autonumber') numbered = true
    else if ((m = /^participant (\S+)(?: as (.+))?$/.exec(line))) participants.push({ id: m[1], label: m[2] ?? m[1] })
    else if ((m = /^Note over ([^:]+): (.+)$/.exec(line))) {
      const ids = m[1].split(',').map((s) => index(s.trim()))
      items.push({ kind: 'note', from: Math.min(...ids), to: Math.max(...ids), lines: m[2].split('<br/>') })
    } else if ((m = /^(\S+?)(->>|-->>|--x)(\S+?): (.+)$/.exec(line)))
      items.push({ kind: 'message', from: index(m[1]), to: index(m[3]), arrow: m[2], label: m[4] })
    else if ((m = /^(par|loop|alt)(?: (.+))?$/.exec(line))) items.push({ kind: 'open', type: m[1], label: m[2] ?? '' })
    else if ((m = /^(and|else)(?: (.+))?$/.exec(line))) items.push({ kind: 'divide', label: m[2] ?? '' })
    else if (line === 'end') items.push({ kind: 'close' })
    else throw new Error(`not understood: ${line}`)
  }
  return { participants, items, numbered }
}

function sequence(name, title, desc) {
  const { participants, items, numbered } = parseSequence(readFileSync(join(here, 'sequences', `${name}.mmd`), 'utf8'))
  const n = participants.length
  const boxW = participants.map((p) => Math.max(116, widthOf(p.label, 13.5) + 28))
  // The gaps between the lifelines: wide enough for the boxes, then for every message's label
  const gaps = Array.from({ length: n - 1 }, (_, i) => Math.max(150, (boxW[i] + boxW[i + 1]) / 2 + 26))
  const span = (a, b) => gaps.slice(Math.min(a, b), Math.max(a, b)).reduce((s, g) => s + g, 0)
  for (const item of items) {
    if (item.kind !== 'message' || item.from === item.to) continue
    const need = widthOf(item.label, 12.5) + (numbered ? 44 : 28)
    const have = span(item.from, item.to)
    if (have < need) {
      const lo = Math.min(item.from, item.to)
      const hi = Math.max(item.from, item.to)
      for (let g = lo; g < hi; g++) gaps[g] += (need - have) / (hi - lo)
    }
  }
  // A note over two lifelines must fit between them
  for (const item of items) {
    if (item.kind !== 'note' || item.from === item.to) continue
    const need = Math.max(...item.lines.map((l) => widthOf(l, 12.5))) + 24 - 40
    const have = span(item.from, item.to)
    if (have < need) for (let g = item.from; g < item.to; g++) gaps[g] += (need - have) / (item.to - item.from)
  }
  const xs = [0]
  for (const g of gaps) xs.push(xs.at(-1) + g)

  // Vertical layout, and the horizontal extent of every frame and note
  const top = 20
  const boxH = 42
  let y = top + boxH + 22
  const drawn = []
  const stack = []
  let minX = -boxW[0] / 2
  let maxX = xs.at(-1) + boxW[n - 1] / 2
  let number = 0
  const touch = (a, b) => {
    const lo = Math.min(a, b)
    const hi = Math.max(a, b)
    for (const f of stack) {
      f.lo = Math.min(f.lo, lo)
      f.hi = Math.max(f.hi, hi)
    }
  }
  for (const item of items) {
    if (item.kind === 'message') {
      number += 1
      drawn.push({ ...item, y: y + 18, number })
      touch(item.from, item.to)
      y += 38
    } else if (item.kind === 'note') {
      const w = Math.max(...item.lines.map((l) => widthOf(l, 12.5))) + 24
      const x1 = item.from === item.to ? xs[item.from] - w / 2 : xs[item.from] - 20
      const x2 = item.from === item.to ? xs[item.from] + w / 2 : Math.max(xs[item.to] + 20, x1 + w)
      const h = item.lines.length * 16 + 12
      drawn.push({ ...item, x1, x2, y, h })
      for (const f of stack) {
        f.nx1 = Math.min(f.nx1, x1)
        f.nx2 = Math.max(f.nx2, x2)
      }
      minX = Math.min(minX, x1)
      maxX = Math.max(maxX, x2)
      touch(item.from, item.to)
      y += h + 12
    } else if (item.kind === 'open') {
      y += 4
      const f = { ...item, y, lo: Infinity, hi: -Infinity, nx1: Infinity, nx2: -Infinity, dividers: [], depth: stack.length, parent: stack.at(-1) }
      stack.push(f)
      drawn.push(f)
      y += 30
    } else if (item.kind === 'divide') {
      const f = stack.at(-1)
      f.dividers.push({ y: y + 2, label: item.label })
      y += 28
    } else if (item.kind === 'close') {
      const f = stack.pop()
      f.bottom = y + 2
      y += 14
    }
  }
  const bottom = y + 6

  // The frames, around the lifelines their content touches, inset by their depth; a frame holds the frames in it
  const frames = drawn.filter((d) => d.kind === 'open').sort((a, b) => b.depth - a.depth)
  for (const f of frames) {
    const pad = 34 - f.depth * 10
    f.x1 = Math.min(xs[f.lo] - pad - 28, f.nx1 - 12)
    f.x2 = Math.max(xs[f.hi] + pad + 28, f.nx2 + 12)
    const labelW = widthOf(`${f.type} ${f.label}`, 12.5) + 70
    f.x2 = Math.max(f.x2, f.x1 + labelW)
    for (const child of frames.filter((c) => c.parent === f)) {
      f.x1 = Math.min(f.x1, child.x1 - 12)
      f.x2 = Math.max(f.x2, child.x2 + 12)
    }
    minX = Math.min(minX, f.x1)
    maxX = Math.max(maxX, f.x2)
  }

  const shift = 20 - minX
  const X = (x) => x + shift
  const width = maxX - minX + 40
  const height = bottom + 16
  const body = []

  // Frames first, behind everything
  for (const f of drawn.filter((d) => d.kind === 'open')) {
    const tabW = widthOf(f.type, 12.5) + 20
    body.push(`<rect class="group" x="${r(X(f.x1))}" y="${r(f.y)}" width="${r(f.x2 - f.x1)}" height="${r(f.bottom - f.y)}" rx="6"/>`)
    body.push(`<path class="tab" d="M${r(X(f.x1))},${r(f.y + 6)} a6,6 0 0 1 6,-6 h${r(tabW - 6)} v14 l-8,8 h${r(-tabW + 8)} Z"/>`)
    body.push(text(X(f.x1) + 9, f.y + 16, f.type, 'title', 'start'))
    if (f.label) body.push(text(X(f.x1) + tabW + 10, f.y + 16, `[${f.label}]`, 'label', 'start'))
    for (const d of f.dividers) {
      body.push(`<line class="divider" x1="${r(X(f.x1))}" y1="${r(d.y)}" x2="${r(X(f.x2))}" y2="${r(d.y)}"/>`)
      if (d.label) body.push(text(X(f.x1) + 10, d.y + 17, `[${d.label}]`, 'label', 'start'))
    }
  }
  // Lifelines and participants
  participants.forEach((p, i) => {
    body.push(`<line class="lifeline" x1="${r(X(xs[i]))}" y1="${top + boxH}" x2="${r(X(xs[i]))}" y2="${r(bottom)}"/>`)
    body.push(box(X(xs[i]) - boxW[i] / 2, top, boxW[i], boxH, [p.label], 'box', false))
  })
  // Messages and notes
  for (const d of drawn) {
    if (d.kind === 'message') {
      const x1 = X(xs[d.from])
      const x2 = X(xs[d.to])
      const dir = Math.sign(x2 - x1)
      const dashed = d.arrow !== '->>'
      const failed = d.arrow === '--x'
      const start = numbered ? x1 + dir * 10 : x1
      const end = x2 - dir * (failed ? 2 : 1)
      body.push(`<line class="edge${dashed ? ' dash' : ''}${failed ? ' fail' : ''}" x1="${r(start)}" y1="${d.y}" x2="${r(end)}" y2="${d.y}"/>`)
      if (failed) {
        const cx = x2 - dir * 9
        body.push(`<path class="head-fail" d="M${r(cx - 5)},${d.y - 5} l10,10 M${r(cx + 5)},${d.y - 5} l-10,10"/>`)
      } else body.push(head(end, d.y, dir, 0))
      body.push(text((x1 + x2) / 2 + (numbered ? dir * 5 : 0), d.y - 7, d.label, `label${failed ? ' accent' : ''}`))
      if (numbered) {
        body.push(`<circle class="num" cx="${r(x1)}" cy="${d.y}" r="9"/>`)
        body.push(text(x1, d.y + 3.8, String(d.number), 'num-text'))
      }
    } else if (d.kind === 'note') {
      body.push(`<rect class="note" x="${r(X(d.x1))}" y="${r(d.y)}" width="${r(d.x2 - d.x1)}" height="${d.h}" rx="5"/>`)
      d.lines.forEach((line, i) => body.push(text((X(d.x1) + X(d.x2)) / 2, d.y + 20 + i * 16, line, 'label')))
    }
  }
  svg(name, title, desc, width, height, body)
}

// ---------------------------------------------------------------------------------------------------------------
// Topology

function modules() {
  const w = 180
  const h = 62
  const top = 50
  const low = 220
  const at = { ser: 30, exec: 290, file: 530, pg: 750, api: 30, fs: 290, logapi: 640 }
  const c = (k) => at[k] + w / 2
  const body = [
    text(30, 32, 'Each arrow points to a module the other one needs at run time.', 'small', 'start'),
    box(at.ser, top, w, h, ['saga-serialization', 'sagas to and from JSON', 'org.json']),
    box(at.exec, top, w, h, ['saga-execution', 'runs sagas', 'no third-party dependencies'], 'lib'),
    box(at.file, top, w, h, ['sagalog-file', 'the file log', 'no third-party dependencies']),
    box(at.pg, top, w, h, ['sagalog-postgres', 'the PostgreSQL log', 'HikariCP, JDBC driver, SLF4J']),
    box(at.api, low, w, h, ['saga-api', 'Saga, SagaNode, the builder', 'no dependencies']),
    box(at.fs, low, w, h, ['future-selector', 'futures, the thread pool', 'no dependencies']),
    box(at.logapi, low, w, h, ['sagalog-api', 'the SPI, the pool, memory log', 'no dependencies']),
    edge([[c('ser'), top + h], [c('api'), low]]),
    edge([[c('exec') - 40, top + h], [c('api') + 50, low]]),
    edge([[c('exec'), top + h], [c('fs'), low]]),
    edge([[c('exec') + 40, top + h], [c('logapi') - 50, low]]),
    edge([[c('file'), top + h], [c('logapi') - 10, low]]),
    edge([[c('pg'), top + h], [c('logapi') + 40, low]]),
    text(30, low + h + 36, 'An application depends on saga-execution and one saga-log provider; saga-serialization when it keeps sagas as JSON.', 'small', 'start'),
  ]
  svg(
    'modules',
    'The modules and what they depend on',
    'saga-execution depends on saga-api, future-selector and sagalog-api. saga-serialization depends on saga-api. sagalog-file and sagalog-postgres depend on sagalog-api. saga-api, future-selector and sagalog-api depend on nothing.',
    960,
    low + h + 56,
    body,
  )
}

function instance() {
  const body = [
    box(330, 24, 300, 46, ['Application code'], 'box', false),
    frame(20, 112, 920, 250, "Distributed Saga, in the application's process", 'frame-lib'),
    box(60, 150, 260, 58, ['SagaExecution', 'one per saga and log'], 'lib'),
    box(60, 278, 260, 58, ['SelectableThreadPoolExecutor', 'created, sized and shut down by the app'], 'lib'),
    box(640, 150, 260, 58, ['SagaLogPool', 'one per provider'], 'lib'),
    box(580, 278, 150, 52, ['SagaLog', 'one owner at a time'], 'lib'),
    box(760, 278, 150, 52, ['SagaLog', '…'], 'lib'),
    box(60, 410, 260, 52, ['Adapters', 'action, compensating action'], 'box'),
    store(60, 500, 260, 64, ['Databases, queues, services', 'what the steps change']),
    store(610, 410, 270, 64, ['Saga-log storage', 'PostgreSQL, files, memory']),
    edge([[400, 70], [400, 92], [190, 92], [190, 150]], { label: 'executeSaga', lx: 290, ly: 86 }),
    edge([[560, 70], [560, 92], [770, 92], [770, 150]], { label: 'tryAcquire, releaseOwnership', lx: 665, ly: 86 }),
    edge([[190, 208], [190, 278]], { label: 'walks', lx: 200, ly: 248, anchor: 'start' }),
    edge([[190, 336], [190, 410]], { label: 'executeAction', lx: 200, ly: 386, anchor: 'start' }),
    edge([[190, 462], [190, 500]]),
    edge([[320, 190], [460, 190], [460, 304], [580, 304]], { label: 'write, read', lx: 470, ly: 250, anchor: 'start' }),
    edge([[720, 208], [680, 278]]),
    edge([[820, 208], [835, 278]]),
    edge([[655, 330], [690, 410]]),
    edge([[835, 330], [800, 410]]),
  ]
  svg(
    'instance',
    'Inside one application instance',
    "The application calls executeSaga on a SagaExecution and takes and releases saga logs from the SagaLogPool. The execution runs its walks on the application's SelectableThreadPoolExecutor, whose threads call the adapters, which change databases, queues and services. The execution writes and reads its SagaLog, which the pool keeps in the saga-log storage.",
    960,
    588,
    body,
  )
}

function shape() {
  const nodeY = 96
  const circle = (cx, id) =>
    `<circle class="lib" cx="${cx}" cy="${nodeY}" r="22"/>\n${text(cx, nodeY + 5, id, 'mono title')}`
  const body = [
    circle(56, 'S'),
    box(170, 26, 170, 46, ['reserve-stock'], 'box'),
    box(170, 120, 170, 46, ['charge-card'], 'box'),
    box(470, 73, 150, 46, ['ship'], 'box'),
    circle(744, 'E'),
    edge([[78, 90], [120, 49], [170, 49]]),
    edge([[78, 102], [120, 143], [170, 143]]),
    edge([[340, 49], [400, 49], [470, 88]]),
    edge([[340, 143], [400, 143], [470, 104]]),
    edge([[620, 96], [722, 96]]),
    text(150, 196, 'fork: a new walk for each extra branch', 'small'),
    text(545, 196, 'join: starts when the last branch is done, on its walk', 'small'),
  ]
  svg(
    'saga-shape',
    'The shape of a saga',
    'S forks into reserve-stock and charge-card, which run at the same time. ship joins them: it starts when the last of the two is done. Then E.',
    800,
    216,
    body,
  )
}

function cluster() {
  const body = [
    frame(20, 20, 300, 116, 'Instance 01'),
    box(60, 62, 220, 50, ['SagaLogPool', 'cluster.instance-id 01']),
    frame(640, 20, 300, 116, 'Instance 02'),
    box(680, 62, 220, 50, ['SagaLogPool', 'cluster.instance-id 02']),
    frame(20, 236, 920, 196, 'PostgreSQL: the schema cluster.owner', 'frame', true),
    store(50, 290, 260, 100, ['SAGALOG_orders_01_log-0 …', 'the logs of instance 01']),
    store(350, 290, 260, 100, ['Locks', 'lock key and owner token', 'of every log']),
    store(650, 290, 260, 100, ['SAGALOG_orders_02_log-0 …', 'the logs of instance 02']),
    edge([[130, 112], [130, 290]], { label: 'owns, writes', lx: 138, ly: 220, anchor: 'start' }),
    edge([[850, 112], [850, 290]], { label: 'owns, writes', lx: 842, ly: 220, anchor: 'end' }),
    edge([[700, 112], [700, 176], [250, 176], [250, 290]], {
      cls: 'dash',
      label: 'recovers them when instance 01 is gone',
      lx: 475,
      ly: 168,
    }),
    edge([[230, 112], [430, 290]], { arrow: false }),
    edge([[780, 112], [530, 290]], { arrow: false }),
    text(480, 418, 'An advisory lock per log keeps one writer per log, cluster-wide.', 'small'),
  ]
  svg(
    'cluster',
    'Several instances share their saga logs',
    'Instances 01 and 02 each own and write their own logs in one PostgreSQL schema. Both take an advisory lock per log and keep an owner token in Locks. When instance 01 is gone, instance 02 can open its logs and recover them.',
    960,
    452,
    body,
  )
}


function polyglot() {
  const mid = 120
  const circle = (cx, id) => `<circle class="lib" cx="${cx}" cy="${mid}" r="22"/>\n${text(cx, mid + 5, id, 'mono title')}`
  const rows = [['rdbms', 30], ['graph', 97], ['objectstore', 164]]
  const body = [circle(56, 'S'), circle(744, 'E'), box(470, mid - 23, 150, 46, ['pubsub'], 'box')]
  for (const [id, y] of rows) {
    body.push(box(170, y, 170, 46, [id], 'box'))
    body.push(edge([[78, mid], [120, y + 23], [170, y + 23]]))
    body.push(edge([[340, y + 23], [400, y + 23], [470, mid + (y + 23 - mid) / 8]]))
  }
  body.push(edge([[620, mid], [722, mid]]))
  svg(
    'polyglot',
    'The saga of the sample',
    'S forks into rdbms, graph and objectstore, which run at the same time. pubsub joins them: it starts when the last of the three is done. Then E.',
    800,
    240,
    body,
  )
}

// ---------------------------------------------------------------------------------------------------------------

modules()
polyglot()
instance()
shape()
cluster()
sequence('success', 'A saga that succeeds', 'The caller starts the saga S, a, E; a walk on the pool writes Start on S, runs a between Start and End, writes End on E, calls onComplete and completes the completion future.')
sequence('fork-join', 'A fork and a join', 'Walk 1 starts the saga and submits a new walk for a; it runs b itself. The walk that finishes last runs the join j; the other ends.')
sequence('abort', 'An abort and its rollback', 'b throws AbortSagaException; Abort is written; once no walk is left, the rollback compensates a between CompStart and Comp, writes End on E, and the completion future fails with the AbortSagaException.')
sequence('failure', 'A failure that is not an abort', 'b throws another exception; the walk fails and the traversal stops; onComplete runs and the completion future fails; End on E is not written, so recovery runs b again.')
sequence('recovery', 'Recovery after a crash', 'The application reads the unfinished execution and runs it again with recovery true: a, which ended, is skipped and its output passed on; b runs again; End on E is written.')
sequence('take-log', 'Taking and releasing a saga log', 'tryAcquire takes the next available log; the first time, the PostgreSQL session takes its lock key, its advisory lock and a new owner token, and creates its table; releaseOwnership puts the log back in the queue.')
sequence('lost-connection', 'A PostgreSQL log loses its connection', 'The session evicts the connection, takes the advisory lock again on a new one, and checks the owner token: still its own, it writes again; changed, it refuses with SagaLogBusyException.')
sequence('cross-instance', "Recovering another instance's logs", "Instance 02 lists the cluster's logs and takes each log of instance 01: if 01 still holds it, SagaLogBusyException; else it recovers the log, removes it and releases it.")
