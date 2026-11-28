// The figures of the landing page: SVG drawn in the theme's colours (globals.css, "Figures"). Each steps through four
// stops, four seconds each: the groups s0 to s3 are shown one at a time. With reduced motion, the second stop shows.
//
// One colour per meaning, in the steps and in the saga log alike: a step's card has the colour of its last entry in
// the log, as the saga-log protocol reads a node's state. Start (running) is dashed, End (done) green, Abort red,
// Comp (compensated) dotted; S and E, the saga's own start and end, solid in the text colour. A crash writes nothing:
// the step it cut off keeps the state of its Start.

type State = 'idle' | 'running' | 'done' | 'waiting' | 'aborted' | 'undone' | 'crashed' | 'saga'

/** The word under a step, and its class, for each state */
const stateText: Record<State, [string, string]> = {
  idle: ['', ''],
  running: ['running', 'fig-small'],
  done: ['done', 'fig-answer'],
  waiting: ['waits for 1 more', 'fig-small'],
  aborted: ['AbortSagaException', 'fig-missing'],
  undone: ['compensated', 'fig-small'],
  crashed: ['cut off', 'fig-missing'],
  saga: ['', ''],
}

const cardClass: Partial<Record<State, string>> = {
  running: 'fig-bar fig-bar-run',
  done: 'fig-bar fig-bar-on',
  aborted: 'fig-bar fig-bar-bad',
  crashed: 'fig-bar fig-bar-run',
  undone: 'fig-bar fig-bar-gone',
  saga: 'fig-bar fig-bar-saga',
}

/** The colour of a saga-log entry, by its type: the same as the state it gives its node */
function entryClass(entry: string): string {
  if (entry === 'Start S' || entry === 'End E') return 'fig-entry-saga'
  const type = entry.split(' ')[0]
  if (type === 'End') return 'fig-entry-done'
  if (type === 'Abort') return 'fig-entry-bad'
  if (type === 'Comp') return 'fig-entry-gone'
  return 'fig-entry-run'
}

interface Step {
  id: string
  x: number
  y: number
}

const W = 132
const H = 40

/** A step: its card, its name, and the word under it at each stop */
function StepCard({ step, states }: { step: Step; states: State[] }): React.ReactElement {
  return (
    <g>
      <rect className="fig-card" x={step.x} y={step.y} width={W} height={H} rx={8} />
      {states.map((state, stop) =>
        cardClass[state] ? (
          <rect key={stop} className={`${cardClass[state]} s${stop}`} x={step.x} y={step.y} width={W} height={H} rx={8} />
        ) : null,
      )}
      <text x={step.x + W / 2} y={step.y + 25} textAnchor="middle">
        {step.id}
      </text>
      {states.map((state, stop) =>
        stateText[state][0] ? (
          <text key={stop} className={`${stateText[state][1]} s${stop}`} x={step.x + W / 2} y={step.y + H + 16} textAnchor="middle">
            {stateText[state][0]}
          </text>
        ) : null,
      )}
    </g>
  )
}

/** The start or end node of a saga */
function Terminal({ id, x, y, states }: { id: string; x: number; y: number; states: State[] }): React.ReactElement {
  return (
    <g>
      <circle className="fig-card" cx={x} cy={y} r={18} />
      {states.map((state, stop) =>
        cardClass[state] ? <circle key={stop} className={`${cardClass[state]} s${stop}`} cx={x} cy={y} r={18} /> : null,
      )}
      <text x={x} y={y + 5} textAnchor="middle" className="fig-title">
        {id}
      </text>
    </g>
  )
}

/** The saga log at each stop: its entries in order, as chips that wrap onto a second line */
function Log({ y, stops, label = 'saga log' }: { y: number; stops: string[][]; label?: string }): React.ReactElement {
  const width = 560
  return (
    <g>
      <text className="fig-small" x={40} y={y - 8}>
        {label}
      </text>
      <rect className="fig-card" x={40} y={y} width={width} height={64} rx={8} />
      {stops.map((entries, stop) => {
        let cx = 52
        let row = 0
        return (
          <g key={stop} className={`s${stop}`}>
            {entries.map((entry, i) => {
              const w = entry.length * 6.7 + 14
              if (cx + w > 40 + width - 10) {
                cx = 52
                row += 1
              }
              const x = cx
              cx += w + 6
              const fresh = i >= (stops[stop - 1]?.length ?? 0) && stop > 0
              return (
                <g key={i}>
                  <rect className={`fig-entry ${entryClass(entry)}${fresh ? ' fig-entry-new' : ''}`} x={x} y={y + 9 + row * 24} width={w} height={20} rx={4} />
                  <text className={fresh ? 'fig-mono fig-mono-new' : 'fig-mono'} x={x + 7} y={y + 23 + row * 24}>
                    {entry}
                  </text>
                </g>
              )
            })}
          </g>
        )
      })}
    </g>
  )
}

/** What is happening at each stop, a line under the figure */
function Told({ y, lines }: { y: number; lines: string[] }): React.ReactElement {
  return (
    <>
      {lines.map((line, stop) => (
        <text key={stop} className={`fig-told s${stop}`} x={40} y={y}>
          {line}
        </text>
      ))}
    </>
  )
}

const arrow = (d: string): React.ReactElement => <path className="fig-arrow" d={d} />

/**
 * The hero: a saga that forks into two steps that run at once and joins them before the last step. The saga log
 * fills in as the steps run.
 */
export function SagaGraphFigure(): React.ReactElement {
  const reserve = { id: 'reserve-stock', x: 120, y: 40 }
  const charge = { id: 'charge-card', x: 120, y: 132 }
  const ship = { id: 'ship', x: 350, y: 86 }
  return (
    <svg className="fig" viewBox="0 0 640 330" role="img" aria-labelledby="fig-graph-title fig-graph-desc">
      <title id="fig-graph-title">A saga runs as a graph</title>
      <desc id="fig-graph-desc">
        The saga forks at S into reserve-stock and charge-card, which run at the same time. The step ship joins them:
        it starts when the last of the two is done. Each step writes Start before it runs and End after, and the saga
        ends with End on E.
      </desc>
      {arrow('M58 100 C86 100 90 60 114 60 m-7 -5 l7 5 -7 5')}
      {arrow('M58 112 C86 112 90 152 114 152 m-7 -5 l7 5 -7 5')}
      {arrow('M252 60 C300 60 300 100 344 104 m-7 -5 l7 5 -7 5')}
      {arrow('M252 152 C300 152 300 112 344 110 m-7 -5 l7 5 -7 5')}
      {arrow('M482 106 H552 m-7 -5 l7 5 -7 5')}
      <Terminal id="S" x={40} y={106} states={['saga', 'saga', 'saga', 'saga']} />
      <StepCard step={reserve} states={['running', 'done', 'done', 'done']} />
      <StepCard step={charge} states={['running', 'running', 'done', 'done']} />
      <StepCard step={ship} states={['idle', 'waiting', 'running', 'done']} />
      <Terminal id="E" x={576} y={106} states={['idle', 'idle', 'idle', 'saga']} />
      <Log
        y={224}
        stops={[
          ['Start S', 'Start reserve-stock', 'Start charge-card'],
          ['Start S', 'Start reserve-stock', 'Start charge-card', 'End reserve-stock'],
          ['Start S', 'Start reserve-stock', 'Start charge-card', 'End reserve-stock', 'End charge-card', 'Start ship'],
          ['Start S', 'Start reserve-stock', 'Start charge-card', 'End reserve-stock', 'End charge-card', 'Start ship', 'End ship', 'End E'],
        ]}
      />
      <Told
        y={316}
        lines={[
          'The saga forks: both branches run at once, each on a pool thread.',
          'reserve-stock is done. ship waits for charge-card; no thread waits.',
          'charge-card is done, and its walk starts ship: the join.',
          'ship is done. End on E: the saga is finished.',
        ]}
      />
    </svg>
  )
}

const line = (ids: string[], y = 60): Step[] => ids.map((id, i) => ({ id, x: 40 + i * 194, y }))

/** A crash during a saga, and recovery: the step that ended is skipped, the one cut off runs again */
export function RecoveryFigure(): React.ReactElement {
  const [reserve, charge, ship] = line(['reserve-stock', 'charge-card', 'ship'])
  return (
    <svg className="fig" viewBox="0 0 640 300" role="img" aria-labelledby="fig-rec-title fig-rec-desc">
      <title id="fig-rec-title">Recovery after a crash</title>
      <desc id="fig-rec-desc">
        reserve-stock ends. The process stops after Start on charge-card. After the restart, recovery reads the log:
        reserve-stock ended, so it is skipped and its output is passed on; charge-card has Start but no End, so it
        runs again. Then ship runs and the saga ends.
      </desc>
      <text className="fig-request" x={40} y={34}>
        executeSaga(id, input, recovery)
      </text>
      {arrow('M174 80h14m-6 -6l6 6-6 6')}
      {arrow('M368 80h14m-6 -6l6 6-6 6')}
      <StepCard step={reserve!} states={['done', 'done', 'done', 'done']} />
      <StepCard step={charge!} states={['idle', 'crashed', 'running', 'done']} />
      <StepCard step={ship!} states={['idle', 'idle', 'idle', 'done']} />
      <Log
        y={160}
        stops={[
          ['Start S', 'Start reserve-stock', 'End reserve-stock'],
          ['Start S', 'Start reserve-stock', 'End reserve-stock', 'Start charge-card'],
          ['Start S', 'Start reserve-stock', 'End reserve-stock', 'Start charge-card'],
          ['Start S', 'Start reserve-stock', 'End reserve-stock', 'Start charge-card', 'End charge-card', 'Start ship', 'End ship', 'End E'],
        ]}
      />
      <Told
        y={262}
        lines={[
          'reserve-stock is done, and End is in the log.',
          'The process stops after Start on charge-card.',
          'Restart: reserve-stock ended, so it is skipped; charge-card runs again.',
          'charge-card and ship end. End on E: the saga is finished.',
        ]}
      />
    </svg>
  )
}

/** An abort, and the rollback: the steps that ran are compensated, newest first */
export function RollbackFigure(): React.ReactElement {
  const [reserve, charge, ship] = line(['reserve-stock', 'charge-card', 'ship'])
  return (
    <svg className="fig" viewBox="0 0 640 300" role="img" aria-labelledby="fig-rb-title fig-rb-desc">
      <title id="fig-rb-title">An abort and its rollback</title>
      <desc id="fig-rb-desc">
        reserve-stock ends. charge-card throws AbortSagaException, and Abort is written. The saga walks back: the
        compensating action of reserve-stock runs between CompStart and Comp. The saga ends with End on E, rolled back.
      </desc>
      <text className="fig-request" x={40} y={34}>
        throw new AbortSagaException(&quot;card declined&quot;)
      </text>
      {arrow('M174 80h14m-6 -6l6 6-6 6')}
      {arrow('M368 80h14m-6 -6l6 6-6 6')}
      <StepCard step={reserve!} states={['done', 'done', 'undone', 'undone']} />
      <StepCard step={charge!} states={['running', 'aborted', 'aborted', 'aborted']} />
      <StepCard step={ship!} states={['idle', 'idle', 'idle', 'idle']} />
      <Log
        y={160}
        stops={[
          ['Start S', 'Start reserve-stock', 'End reserve-stock', 'Start charge-card'],
          ['Start S', 'Start reserve-stock', 'End reserve-stock', 'Start charge-card', 'Abort charge-card'],
          ['Start S', 'Start reserve-stock', 'End reserve-stock', 'Start charge-card', 'Abort charge-card', 'CompStart reserve-stock', 'Comp reserve-stock'],
          ['Start S', 'Start reserve-stock', 'End reserve-stock', 'Start charge-card', 'Abort charge-card', 'CompStart reserve-stock', 'Comp reserve-stock', 'End E'],
        ]}
      />
      <Told
        y={262}
        lines={[
          'reserve-stock is done; charge-card runs.',
          'charge-card throws AbortSagaException: Abort is written.',
          'The saga walks back: reserve-stock is compensated.',
          'End on E: rolled back, and the caller gets the AbortSagaException.',
        ]}
      />
    </svg>
  )
}
