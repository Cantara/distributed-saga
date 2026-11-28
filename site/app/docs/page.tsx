import type { Metadata } from 'next'
import { DocPage, docMetadata } from '../../components/doc-page'

// docs/README.md: the documentation's home page
export function generateMetadata(): Promise<Metadata> {
  return docMetadata('docs', 'index')
}

export default function Docs(): Promise<React.ReactElement> {
  return DocPage({ collection: 'docs', slug: 'index' })
}
