'use client'

import { useEffect } from 'react'

/**
 * Draws the page's ```mermaid blocks as diagrams in the browser, as GitHub
 * does. The markdown keeps them as code, so the page reads without script;
 * mermaid is loaded only on a page that has a diagram.
 */
export function Mermaid(): null {
  useEffect(() => {
    const blocks = Array.from(document.querySelectorAll<HTMLElement>('pre > code.language-mermaid'))
    if (blocks.length === 0) return
    let cancelled = false
    void (async () => {
      const { default: mermaid } = await import('mermaid')
      if (cancelled) return
      const dark = window.matchMedia('(prefers-color-scheme: dark)').matches
      mermaid.initialize({ startOnLoad: false, theme: dark ? 'dark' : 'default', securityLevel: 'strict' })
      const nodes = blocks.map((code) => {
        const figure = document.createElement('div')
        figure.className = 'mermaid'
        figure.textContent = code.textContent ?? ''
        code.parentElement?.replaceWith(figure)
        return figure
      })
      await mermaid.run({ nodes })
    })()
    return () => {
      cancelled = true
    }
  }, [])
  return null
}
