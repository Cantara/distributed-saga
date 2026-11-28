'use client'

// The figures of the landing page: a saga's steps and its saga log, told as a story one tick at a time. Each tick is
// one write to the saga log, or one event that writes nothing (the process stops, the process starts again), and a
// caption under the figure says what happened and why. A step's card is drawn from its last entry in the log, as the
// saga-log protocol reads a node's state, so the steps and the log agree at every tick:
//
// - no entry: not started;          - Start: running (dashed);       - End: done (green);
// - Abort: aborted (red);           - CompStart: compensating;       - Comp: compensated (dotted);
// - Start on S, End on E: the saga's own start and end, solid in the text colour.
//
// A figure plays when it comes into view, from its first tick, and stops out of view. The numbered buttons under it
// go to a tick and pause; with reduced motion it does not play by itself. Colours: globals.css, "Figures".

import { useEffect, useRef, useState } from 'react'

type Tone = 'ok' | 'bad' | 'muted'

interface Node {
  id: string
  /** A step's card (its top-left corner), or the circle of S or E (its centre) */
  x: number
  y: number
  w?: number
}

interface Stop {
  /** The entry written at this tick, as `Type node` (`End reserve-stock`); none for an event that writes nothing */
  entry?: string
  caption: string
  /** Words under a card that say more than its state, for this tick */
  labels?: Record<string, [string, Tone]>
  /** A line at the top of the figure, for this tick: the process stopped, or runs again */
  banner?: [string, Tone]
}

interface Story {
  id: string
  title: string
  desc: string
  width: number
  height: number
  nodes: Node[]
  edges: string[]
  logY: number
  stops: Stop[]
}

const H = 40
const TICK = 3400

const typeOf = (entry: string): string => entry.slice(0, entry.indexOf(' '))
const nodeOf = (entry: string): string => entry.slice(entry.indexOf(' ') + 1)

/** A node's state after the entries written so far: the type of its last entry */
function lastType(entries: string[], id: string): string | undefined {
  const last = entries.filter((entry) => nodeOf(entry) === id).at(-1)
  return last && typeOf(last)
}

const cardOf: Record<string, string> = {
  Start: 'fig-bar fig-bar-run',
  End: 'fig-bar fig-bar-on',
  Abort: 'fig-bar fig-bar-bad',
  CompStart: 'fig-bar fig-bar-run',
  Comp: 'fig-bar fig-bar-gone',
}

const wordOf: Record<string, [string, Tone]> = {
  Start: ['running', 'muted'],
  End: ['done', 'ok'],
  Abort: ['aborted', 'bad'],
  CompStart: ['compensating', 'muted'],
  Comp: ['compensated', 'muted'],
}

const toneClass: Record<Tone, string> = { ok: 'fig-answer', bad: 'fig-missing', muted: 'fig-small' }

/** The colour of a saga-log entry: the same as the state it gives its node */
function entryClass(entry: string): string {
  if (entry === 'Start S' || entry === 'End E') return 'fig-entry-saga'
  const byType: Record<string, string> = { End: 'fig-entry-done', Abort: 'fig-entry-bad', Comp: 'fig-entry-gone' }
  return byType[typeOf(entry)] ?? 'fig-entry-run'
}

function Drawing({ story, at }: { story: Story; at: number }): React.ReactElement {
  const stop = story.stops[at]
  const entries = story.stops.slice(0, at + 1).flatMap((s) => (s.entry ? [s.entry] : []))
  const newest = stop?.entry
  const touched = newest ? nodeOf(newest) : undefined
  // The log, its entries as chips that wrap onto a second line. The log's box holds two rows: a story whose entries
  // need a third makes the box taller (its height, 66, and the story's height) or its entries fewer
  let cx = 52
  let row = 0
  const chips = entries.map((entry) => {
    const w = entry.length * 6.9 + 16
    if (cx + w > story.width - 50) {
      cx = 52
      row += 1
    }
    const chip = { entry, x: cx, y: story.logY + 9 + row * 25, w }
    cx += w + 6
    return chip
  })
  return (
    <svg
      className="fig"
      viewBox={`0 0 ${story.width} ${story.height}`}
      role="img"
      aria-labelledby={`${story.id}-title ${story.id}-desc`}
    >
      <title id={`${story.id}-title`}>{story.title}</title>
      <desc id={`${story.id}-desc`}>{story.desc}</desc>
      {stop?.banner && (
        <text className={`fig-banner ${toneClass[stop.banner[1]]}`} x={story.width - 40} y={22} textAnchor="end">
          {stop.banner[0]}
        </text>
      )}
      {story.edges.map((d) => (
        <path key={d} className="fig-arrow" d={d} />
      ))}
      {story.nodes.map((node) => {
        const type = lastType(entries, node.id)
        const fresh = node.id === touched
        if (!node.w) {
          return (
            <g key={node.id}>
              <circle
                className={`fig-card${type ? ' fig-bar fig-bar-saga' : ''}${fresh ? ' fig-fresh' : ''}`}
                cx={node.x}
                cy={node.y}
                r={18}
              />
              <text className="fig-title" x={node.x} y={node.y + 5} textAnchor="middle">
                {node.id}
              </text>
            </g>
          )
        }
        const [word, tone] = stop?.labels?.[node.id] ?? (type ? wordOf[type] : undefined) ?? ['', 'muted']
        return (
          <g key={node.id}>
            <rect
              className={`fig-card${type ? ` ${cardOf[type]}` : ''}${fresh ? ' fig-fresh' : ''}`}
              x={node.x}
              y={node.y}
              width={node.w}
              height={H}
              rx={8}
            />
            <text x={node.x + node.w / 2} y={node.y + 25} textAnchor="middle">
              {node.id}
            </text>
            {word && (
              <text className={toneClass[tone]} x={node.x + node.w / 2} y={node.y + H + 17} textAnchor="middle">
                {word}
              </text>
            )}
          </g>
        )
      })}
      <text className="fig-small" x={40} y={story.logY - 8}>
        saga log
      </text>
      <rect className="fig-card" x={40} y={story.logY} width={story.width - 80} height={66} rx={8} />
      {chips.map((chip, i) => {
        const fresh = i === chips.length - 1 && chip.entry === newest
        return (
          <g key={i}>
            <rect
              className={`fig-entry ${entryClass(chip.entry)}${fresh ? ' fig-entry-new' : ''}`}
              x={chip.x}
              y={chip.y}
              width={chip.w}
              height={20}
              rx={4}
            />
            <text className={fresh ? 'fig-mono fig-mono-new' : 'fig-mono'} x={chip.x + 8} y={chip.y + 14}>
              {chip.entry}
            </text>
          </g>
        )
      })}
    </svg>
  )
}

/** A story: its drawing at the current tick, the buttons for its ticks, and the caption of the tick */
function StoryFigure({ story }: { story: Story }): React.ReactElement {
  const n = story.stops.length
  const [at, setAt] = useState(0)
  const [playing, setPlaying] = useState(true)
  const [visible, setVisible] = useState(false)
  const seen = useRef(false)
  const frame = useRef<HTMLDivElement>(null)

  // Reduced motion: the reader steps through the ticks
  useEffect(() => {
    if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) setPlaying(false)
  }, [])

  // Plays from the first tick when it first comes into view, and only while in view
  useEffect(() => {
    const element = frame.current
    if (!element) return
    const observer = new IntersectionObserver(
      ([entry]) => {
        const inView = Boolean(entry?.isIntersecting)
        if (inView && !seen.current) {
          seen.current = true
          setAt(0)
        }
        setVisible(inView)
      },
      { threshold: 0.35 },
    )
    observer.observe(element)
    return () => observer.disconnect()
  }, [])

  // One tick at a time; the last one holds twice as long before the story starts again
  useEffect(() => {
    if (!playing || !visible) return
    const timer = window.setTimeout(() => setAt((i) => (i + 1) % n), at === n - 1 ? TICK * 2 : TICK)
    return () => window.clearTimeout(timer)
  }, [playing, visible, at, n])

  const go = (i: number): void => {
    setAt(i)
    setPlaying(false)
  }

  return (
    <div className="story" ref={frame}>
      <div className="story-drawing">
        <Drawing story={story} at={at} />
      </div>
      <div className="story-bar">
        <button
          type="button"
          className="story-play"
          aria-label={playing ? 'Pause' : 'Play'}
          title={playing ? 'Pause' : 'Play'}
          onClick={() => setPlaying((p) => !p)}
        >
          <svg viewBox="0 0 16 16" width="14" height="14" fill="currentColor" aria-hidden="true">
            {playing ? <path d="M3 2h3.5v12H3zM9.5 2H13v12H9.5z" /> : <path d="M4 2l10 6-10 6z" />}
          </svg>
        </button>
        <ol className="story-ticks">
          {story.stops.map((stop, i) => (
            <li key={i}>
              <button
                type="button"
                aria-current={i === at ? 'step' : undefined}
                aria-label={`Step ${i + 1} of ${n}`}
                className={stop.entry ? undefined : 'story-event'}
                onClick={() => go(i)}
              >
                {i + 1}
              </button>
            </li>
          ))}
        </ol>
      </div>
      <p className="story-caption" aria-live={playing ? 'off' : 'polite'}>
        <span className="story-count">
          {at + 1}/{n}
        </span>
        {story.stops[at]?.caption}
      </p>
    </div>
  )
}

// ---------------------------------------------------------------------------------------------------------------

const arrow = (x1: number, y1: number, x2: number): string => `M${x1} ${y1}H${x2}m-7 -5l7 5-7 5`

/** S, the steps in a row, and E, with an arrow from each to the next */
function line(ids: string[], y: number, w: number, gap: number): { nodes: Node[]; edges: string[] } {
  const cy = y + H / 2
  const nodes: Node[] = [{ id: 'S', x: 40, y: cy }]
  const edges: string[] = []
  let x = 58 + gap
  edges.push(arrow(58, cy, x - 2))
  for (const id of ids) {
    nodes.push({ id, x, y, w })
    edges.push(arrow(x + w, cy, x + w + gap - 2))
    x += w + gap
  }
  nodes.push({ id: 'E', x: x + 18, y: cy })
  return { nodes, edges }
}

const forkJoin: Story = {
  id: 'fig-graph',
  title: 'A saga runs as a graph',
  desc:
    'The saga starts at S, which forks into reserve-stock and charge-card; they run at the same time, each on a thread of the pool. ship joins them: the walk of the last one to finish runs it. Every step writes Start before its action and End after it, and the saga ends with End on E.',
  width: 640,
  height: 306,
  nodes: [
    { id: 'S', x: 40, y: 106 },
    { id: 'reserve-stock', x: 120, y: 40, w: 132 },
    { id: 'charge-card', x: 120, y: 132, w: 132 },
    { id: 'ship', x: 350, y: 86, w: 132 },
    { id: 'E', x: 576, y: 106 },
  ],
  edges: [
    'M58 100 C86 100 90 60 114 60 m-7 -5 l7 5 -7 5',
    'M58 112 C86 112 90 152 114 152 m-7 -5 l7 5 -7 5',
    'M252 60 C300 60 300 100 344 104 m-7 -5 l7 5 -7 5',
    'M252 152 C300 152 300 112 344 110 m-7 -5 l7 5 -7 5',
    'M482 106 H552 m-7 -5 l7 5 -7 5',
  ],
  logY: 224,
  stops: [
    {
      entry: 'Start S',
      caption:
        'executeSaga writes Start on S, with the saga’s input. From here the saga survives a crash, and the handoff future completes.',
    },
    {
      entry: 'Start reserve-stock',
      caption: 'S forks. A new walk on the thread pool takes reserve-stock: it writes Start, then runs the action.',
    },
    {
      entry: 'Start charge-card',
      caption: 'At the same time, the first walk goes on into charge-card: Start, then its action.',
    },
    {
      entry: 'End reserve-stock',
      caption:
        'reserve-stock’s action returns: End is written, with its output. ship needs charge-card too, so this walk ends here. No thread waits.',
      labels: { ship: ['waits for charge-card', 'muted'] },
    },
    {
      entry: 'End charge-card',
      caption:
        'charge-card returns: End is written. It was the last step ship waited for, so its walk goes on into ship: the join.',
      labels: { ship: ['ready', 'muted'] },
    },
    {
      entry: 'Start ship',
      caption: 'ship starts on that walk, with the outputs of both steps: Start, then its action.',
    },
    { entry: 'End ship', caption: 'ship’s action returns: End is written, with its output.' },
    {
      entry: 'End E',
      caption: 'End on E: the saga is finished. onComplete runs, then the completion future completes.',
    },
  ],
}

const recoveryLine = line(['reserve-stock', 'charge-card'], 50, 150, 80)

const recovery: Story = {
  id: 'fig-rec',
  title: 'Recovery after a crash',
  desc:
    'reserve-stock ends. The process stops after Start on charge-card. After the restart, the application runs the execution again with recovery true: reserve-stock, whose last entry is End, is skipped and its output passed on; charge-card, whose last entry is Start, runs again without a second Start. The saga ends with End on E.',
  width: 640,
  height: 232,
  nodes: recoveryLine.nodes,
  edges: recoveryLine.edges,
  logY: 150,
  stops: [
    { entry: 'Start S', caption: 'executeSaga writes Start on S, with the saga’s input.' },
    {
      entry: 'Start reserve-stock',
      caption: 'reserve-stock starts: Start is written, then its action reserves the stock.',
    },
    {
      entry: 'End reserve-stock',
      caption: 'reserve-stock is done: End is written, with its output, the reservation.',
    },
    {
      entry: 'Start charge-card',
      caption: 'charge-card starts: Start is written, then its action charges the card.',
    },
    {
      caption:
        'The process stops. Was the card charged? The log cannot say: the last entry of charge-card is Start, and there is no End on E.',
      labels: { 'charge-card': ['cut off', 'bad'] },
      banner: ['the process stops', 'bad'],
    },
    {
      caption:
        'After the restart, the application finds the execution without End on E and runs it again with recovery = true. reserve-stock’s last entry is End: it is skipped, and its logged output passed on.',
      labels: { 'reserve-stock': ['skipped', 'ok'], 'charge-card': ['runs again', 'muted'] },
      banner: ['recovery = true', 'muted'],
    },
    {
      entry: 'End charge-card',
      caption:
        'charge-card’s last entry was Start, so it ran again, without a second Start, and End is written. It may have run twice: its action must be idempotent.',
      banner: ['recovery = true', 'muted'],
    },
    {
      entry: 'End E',
      caption: 'End on E: the saga is finished, with the effect of every step in place.',
      banner: ['recovery = true', 'muted'],
    },
  ],
}

const rollbackLine = line(['reserve-stock', 'charge-card', 'ship'], 50, 132, 34)
const neverStarts: Record<string, [string, Tone]> = { ship: ['never starts', 'muted'] }

const rollback: Story = {
  id: 'fig-rb',
  title: 'An abort and its rollback',
  desc:
    'reserve-stock ends. charge-card throws AbortSagaException and Abort is written; ship never starts. The rollback walks back from E: reserve-stock, which ended, is compensated between CompStart and Comp. The saga ends with End on E, rolled back, and the completion future fails with the AbortSagaException.',
  width: 640,
  height: 232,
  nodes: rollbackLine.nodes,
  edges: rollbackLine.edges,
  logY: 150,
  stops: [
    { entry: 'Start S', caption: 'executeSaga writes Start on S, with the saga’s input.' },
    {
      entry: 'Start reserve-stock',
      caption: 'reserve-stock starts: Start is written, then its action reserves the stock.',
    },
    {
      entry: 'End reserve-stock',
      caption: 'reserve-stock is done: End is written, with its output, the reservation.',
    },
    {
      entry: 'Start charge-card',
      caption: 'charge-card starts: Start is written, then its action tries the card.',
    },
    {
      entry: 'Abort charge-card',
      caption:
        'The card is declined: the action throws AbortSagaException, and Abort is written. The saga stops going forward: ship never starts.',
      labels: neverStarts,
    },
    {
      entry: 'CompStart reserve-stock',
      caption:
        'The rollback walks back from E. ship never ran, and charge-card aborted: nothing to undo. reserve-stock ended, so CompStart is written and its compensating action runs.',
      labels: neverStarts,
    },
    {
      entry: 'Comp reserve-stock',
      caption: 'The compensating action releases the reservation, given the output End logged: Comp is written.',
      labels: neverStarts,
    },
    {
      entry: 'End E',
      caption:
        'End on E: the saga is rolled back. onComplete runs, and the completion future fails with the AbortSagaException.',
      labels: neverStarts,
    },
  ],
}

/** The hero: a saga that forks into two steps that run at once, and joins them before the last step */
export function SagaGraphFigure(): React.ReactElement {
  return <StoryFigure story={forkJoin} />
}

/** A crash during a saga, and recovery: the step that ended is skipped, the one cut off runs again */
export function RecoveryFigure(): React.ReactElement {
  return <StoryFigure story={recovery} />
}

/** An abort, and the rollback: the steps that ran and have a compensating action are undone */
export function RollbackFigure(): React.ReactElement {
  return <StoryFigure story={rollback} />
}
