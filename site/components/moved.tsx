'use client'

import Link from 'next/link'
import { useEffect } from 'react'

/**
 * A page at an address the documentation had before it moved under /docs/: it sends the reader to the page's new
 * address, keeping the part of the page the old link named (its #hash). Without JavaScript it links there.
 */
export function Moved({ basePath, href, title }: { basePath: string; href: string; title: string }): React.ReactElement {
  useEffect(() => {
    window.location.replace(`${basePath}${href}${window.location.hash}`)
  }, [basePath, href])
  return (
    <main className="not-found">
      <h1>{title} has moved</h1>
      <p className="lead">
        This page is now at <Link href={href}>{href}</Link>.
      </p>
    </main>
  )
}
