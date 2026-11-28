import type { Metadata } from 'next'
import Link from 'next/link'
import { notFound } from 'next/navigation'
import { getCMSContent, resolveSeoContent, SeoJsonLd } from '@krantho/ui-react/server'
import { collections } from '../krantho.collections'
import { CodeCopy } from '../components/code-copy'
import { RecoveryFigure, RollbackFigure, SagaGraphFigure } from '../components/figures'
import { SectionHash } from '../components/section-hash'
import { highlightCode } from '../lib/html'
import { site } from '../lib/krantho'

export async function generateMetadata(): Promise<Metadata> {
  const seo = await resolveSeoContent('landing', 'home')
  return { title: { absolute: seo.title }, description: seo.description, alternates: { canonical: '/' } }
}

const idOf = (name: string): string => name.toLowerCase().replace(/[^a-z0-9]+/g, '-')

/** A section's HTML cut in two: its text, and its code blocks (as `highlightCode` makes them) */
function codeOf(html: string): { text: string; code: string } {
  const blocks = html.match(/<div class="code-block[^"]*">[\s\S]*?<\/pre><\/div>/g) ?? []
  let text = html
  for (const block of blocks) text = text.replace(block, '')
  return { text, code: blocks.join('') }
}

/** A section's HTML cut at its `###` headings: the text before the first, and a column for each */
function columnsOf(html: string): { intro: string; columns: { title: string; html: string }[] } {
  const parts = html.split(/(?=<h3[\s>])/)
  const intro = parts[0]?.startsWith('<h3') ? '' : (parts.shift() ?? '')
  const columns = parts.map((part) => {
    const [, title = '', body = ''] = /^<h3[^>]*>([\s\S]*?)<\/h3>([\s\S]*)$/.exec(part) ?? []
    return { title, html: body }
  })
  return { intro, columns }
}

/** The figure a section shows beside its text */
const figureOf: Record<string, () => React.ReactElement> = {
  'Every step is logged before and after it runs': () => <RecoveryFigure />,
  'Undo with compensating actions': () => <RollbackFigure />,
}

/** Sections shown side by side in one row, half and half: the first names the second */
const pairOf: Record<string, string> = {
  'The problem': 'How a saga solves it',
}

/** A section's heading and its text */
function Part({ name, html, children }: { name: string; html: string; children?: React.ReactNode }): React.ReactElement {
  return (
    <div className="row-part">
      <h2 id={idOf(name)}>{name}</h2>
      {html.trim() && <div className="prose" dangerouslySetInnerHTML={{ __html: html }} />}
      {children}
    </div>
  )
}

/**
 * The landing page: the hero from the fields of landing/home, then the sections of the section contract
 * (`collections.landing.sections`), in that order, each a row of one of three kinds (globals.css, "The landing
 * page"):
 *
 * - 40/60: a section's heading and text, beside its figure or its code;
 * - 50/50: two sections of a pair, side by side;
 * - thirds: a section whose `###` headings are columns, under its heading and text.
 *
 * A section the document lacks is left out. Alternate wide rows put the figure or code on the left, so the page
 * reads as a zigzag on a wide screen.
 */
export default async function Home(): Promise<React.ReactElement> {
  const doc = await site.loader.getPublished({ collection: 'landing', slug: 'home' })
  if (!doc) notFound()
  const f = doc.fields as Record<string, string>
  const { sections: raw } = await getCMSContent('landing', 'home')
  // The code blocks coloured as on the documentation pages
  const sections = Object.fromEntries(
    await Promise.all(Object.entries(raw).map(async ([name, html]) => [name, await highlightCode(html)] as const)),
  )
  // A second section of a pair is shown in the row of the first, when the document has the first
  const shownWithFirst = new Set(
    Object.entries(pairOf)
      .filter(([first]) => sections[first])
      .map(([, second]) => second),
  )
  let wide = 0
  const row = (name: string): React.ReactElement => {
    const html = sections[name] ?? ''
    const second = pairOf[name]
    if (second && sections[second]) {
      return (
        <section key={name} className="row row-halves">
          <Part name={name} html={html} />
          <Part name={second} html={sections[second] ?? ''} />
        </section>
      )
    }
    const { intro, columns } = columnsOf(html)
    if (columns.length > 0) {
      return (
        <section key={name} className="row row-thirds">
          <Part name={name} html={intro} />
          {columns.map((column) => (
            <div key={column.title} className="row-column">
              <h3 dangerouslySetInnerHTML={{ __html: column.title }} />
              <div className="prose" dangerouslySetInnerHTML={{ __html: column.html }} />
            </div>
          ))}
        </section>
      )
    }
    const { text, code } = codeOf(html)
    const figure = figureOf[name]
    const flipped = wide++ % 2 === 1
    return (
      <section key={name} className={`row row-wide${flipped ? ' row-flipped' : ''}`}>
        <Part name={name} html={text} />
        <div className="row-side">
          {figure ? figure() : <div className="prose" dangerouslySetInnerHTML={{ __html: code }} />}
        </div>
      </section>
    )
  }
  return (
    <main className="landing">
      <SeoJsonLd collection="landing" slug="home" />
      <section className="row row-wide hero">
        <div className="row-part">
          <p className="eyebrow">{f.eyebrow}</p>
          <h1>{f.headline}</h1>
          <p className="subline">{f.subline}</p>
          <p className="actions">
            <Link className="button primary" href={f['primary-href'] ?? '/docs/'}>
              {f['primary-label']}
            </Link>
            <Link className="button" href={f['secondary-href'] ?? '/docs/'}>
              {f['secondary-label']}
            </Link>
          </p>
        </div>
        <div className="row-side">
          <SagaGraphFigure />
        </div>
      </section>
      {(collections.landing.sections ?? [])
        .filter((name) => sections[name] && !shownWithFirst.has(name))
        .map(row)}
      <CodeCopy />
      <SectionHash selector="main h2[id]" />
    </main>
  )
}
