import type { Metadata } from 'next'
import Link from 'next/link'
import { notFound } from 'next/navigation'
import { getCMSContent, resolveSeoContent, SeoJsonLd } from '@krantho/ui-react/server'
import { withHeadingIds } from '../lib/heading-ids'
import { type DocCollection, docHref, docLinks, site } from '../lib/krantho'
import { Mermaid } from './mermaid'

/** A page's title, description and canonical address */
export async function docMetadata(collection: DocCollection, slug: string): Promise<Metadata> {
  const seo = await resolveSeoContent(collection, slug)
  const home = collection === 'docs' && slug === 'index'
  return {
    title: home ? { absolute: seo.title } : seo.title,
    description: seo.description,
    alternates: { canonical: docHref(collection, slug) },
    openGraph: { title: seo.title, description: seo.description, url: docHref(collection, slug) },
  }
}

/** A page: its markdown body, its diagrams, and the pages before and after it */
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
  // The files in docs/ are also read on GitHub, so each starts with its own
  // `# Title`; the page then shows that, and not the title from the frontmatter
  const ownHeading = /^\s*# /.test(doc.body)
  // Headings get GitHub's ids, so links to a section work as they do on GitHub
  const html = withHeadingIds((await getCMSContent(collection, slug)).html)
  return (
    <article className="doc">
      <SeoJsonLd collection={collection} slug={slug} />
      {!ownHeading && <h1>{String(doc.fields.title)}</h1>}
      {!ownHeading && <p className="lead">{String(doc.fields.description)}</p>}
      <div className="cms-markdown prose" dangerouslySetInnerHTML={{ __html: html }} />
      <Mermaid />
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
  )
}
