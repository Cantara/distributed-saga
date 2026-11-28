import type { Metadata } from 'next'
import { DocPage, docMetadata } from '../../../components/doc-page'
import { site } from '../../../lib/krantho'

// One page for each file in docs/modules; any other address is not found
export const dynamicParams = false

export async function generateStaticParams(): Promise<{ slug: string }[]> {
  return site.params('modules')
}

interface Props {
  params: Promise<{ slug: string }>
}

export async function generateMetadata({ params }: Props): Promise<Metadata> {
  return docMetadata('modules', (await params).slug)
}

export default async function Module({ params }: Props): Promise<React.ReactElement> {
  return DocPage({ collection: 'modules', slug: (await params).slug })
}
