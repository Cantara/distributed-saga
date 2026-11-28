import type { Metadata } from 'next'
import { DocPage, docMetadata } from '../../../components/doc-page'
import { site } from '../../../lib/krantho'

// One page for each file in docs/ but README.md, which is the documentation's home page (/docs/)
export const dynamicParams = false

export async function generateStaticParams(): Promise<{ slug: string }[]> {
  return (await site.params('docs')).filter((page) => page.slug !== 'index')
}

interface Props {
  params: Promise<{ slug: string }>
}

export async function generateMetadata({ params }: Props): Promise<Metadata> {
  return docMetadata('docs', (await params).slug)
}

export default async function Doc({ params }: Props): Promise<React.ReactElement> {
  return DocPage({ collection: 'docs', slug: (await params).slug })
}
