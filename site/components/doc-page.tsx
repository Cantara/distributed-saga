import type { Metadata } from 'next'
import Link from 'next/link'
import { notFound } from 'next/navigation'
import { getCMSContent, resolveSeoContent, SeoJsonLd } from '@krantho/ui-react/server'
import { prepareHtml } from '../lib/html'
import { type DocCollection, docHref, docLinks, site } from '../lib/krantho'
import { CodeCopy } from './code-copy'
import { FigureZoom } from './figure-zoom'
import { Mermaid } from './mermaid'
import { SectionHash } from './section-hash'

/** A docs page's title, description and canonical address */
export async function docMetadata(collection: DocCollection, slug: string): Promise<Metadata> {
  const seo = await resolveSeoContent(collection, slug)
  const href = docHref(collection, slug)
  return {
    title: seo.title,
    description: seo.description,
    alternates: { canonical: href },
    openGraph: { title: seo.title, description: seo.description, url: href },
  }
}

/**
 * A docs page: its markdown body with the headings GitHub would give it, the contents of the page beside it, and the
 * pages before and after it. Each file of docs/ starts with its own `# Title`, as GitHub shows it.
 */
export async function DocPage({
  collection,
  slug,
}: {
  collection: DocCollection
  slug: string
}): Promise<React.ReactElement> {
  const doc = await site.loader.getPublished({ collection, slug })
  if (!doc) notFound()
  const links = await docLinks()
  const at = links.findIndex((link) => link.collection === collection && link.slug === slug)
  const previous = at > 0 ? links[at - 1] : undefined
  const next = at >= 0 ? links[at + 1] : undefined
  const { html, toc, hasFigures } = await prepareHtml((await getCMSContent(collection, slug)).html)
  return (
    <div className="doc-layout">
      <article className="doc">
        <SeoJsonLd collection={collection} slug={slug} />
        <div className="cms-markdown prose" dangerouslySetInnerHTML={{ __html: html }} />
        {hasFigures && <Mermaid />}
        <CodeCopy />
        <FigureZoom />
        <SectionHash selector=".doc .prose :is(h2, h3)[id]" />
        <nav className="pager" aria-label="Pages">
          {previous && (
            <Link href={previous.href} rel="prev">
              ← {previous.title}
            </Link>
          )}
          {next && (
            <Link href={next.href} rel="next">
              {next.title} →
            </Link>
          )}
        </nav>
      </article>
      {toc.length > 1 && (
        <nav className="toc" aria-label="On this page" data-section-nav>
          <h2>On this page</h2>
          <ul>
            {toc.map((entry) => (
              <li key={entry.id} className={`toc-${entry.level}`}>
                <a href={`#${entry.id}`}>{entry.text}</a>
              </li>
            ))}
          </ul>
        </nav>
      )}
    </div>
  )
}
