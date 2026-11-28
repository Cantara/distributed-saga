import type { Metadata } from 'next'
import { Moved } from '../../../components/moved'
import { docHref, site } from '../../../lib/krantho'

// The addresses the module pages had before they moved under /docs/ (/modules/<file name>/): each sends the reader on
export const dynamicParams = false

export async function generateStaticParams(): Promise<{ slug: string }[]> {
  return site.params('modules')
}

interface Props {
  params: Promise<{ slug: string }>
}

export async function generateMetadata({ params }: Props): Promise<Metadata> {
  const href = docHref('modules', (await params).slug)
  return { title: 'Moved', robots: { index: false }, alternates: { canonical: href } }
}

export default async function MovedModule({ params }: Props): Promise<React.ReactElement> {
  const { slug } = await params
  const doc = await site.loader.getPublished({ collection: 'modules', slug })
  return <Moved basePath={site.basePath} href={docHref('modules', slug)} title={String(doc?.fields.title ?? slug)} />
}
