import type { Metadata } from 'next'
import { Moved } from '../../components/moved'
import { docHref, site } from '../../lib/krantho'

// The addresses the documentation pages had before they moved under /docs/ (/<file name>/): each sends the reader on,
// so links and bookmarks to them keep working. GitHub Pages cannot redirect, so each is a page of its own.
export const dynamicParams = false

export async function generateStaticParams(): Promise<{ slug: string }[]> {
  return (await site.params('docs')).filter((page) => page.slug !== 'index')
}

interface Props {
  params: Promise<{ slug: string }>
}

export async function generateMetadata({ params }: Props): Promise<Metadata> {
  const href = docHref('docs', (await params).slug)
  return { title: 'Moved', robots: { index: false }, alternates: { canonical: href } }
}

export default async function MovedDoc({ params }: Props): Promise<React.ReactElement> {
  const { slug } = await params
  const doc = await site.loader.getPublished({ collection: 'docs', slug })
  return <Moved basePath={site.basePath} href={docHref('docs', slug)} title={String(doc?.fields.title ?? slug)} />
}
