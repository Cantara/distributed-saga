import type { Metadata } from 'next'
import { DocPage, docMetadata } from '../components/doc-page'

// docs/README.md
export function generateMetadata(): Promise<Metadata> {
  return docMetadata('docs', 'index')
}

export default function Home(): Promise<React.ReactElement> {
  return DocPage({ collection: 'docs', slug: 'index' })
}
