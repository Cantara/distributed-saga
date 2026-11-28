'use client'

import { useEffect } from 'react'

/** Whether the page is dark now: the reader's choice, or else the system's */
function isDark(): boolean {
  const chosen = document.documentElement.dataset.theme
  if (chosen === 'light' || chosen === 'dark') return chosen === 'dark'
  return window.matchMedia('(prefers-color-scheme: dark)').matches
}

/**
 * Draws the page's figures: every `<pre class="mermaid">` becomes the diagram its source describes, as GitHub draws it.
 * Mermaid is loaded only on a page that has a figure. The figures are drawn again in the other theme when the reader
 * picks another theme or the system switches between light and dark.
 */
export function Mermaid(): null {
  useEffect(() => {
    const blocks = Array.from(document.querySelectorAll<HTMLElement>('pre.mermaid'))
    for (const block of blocks) block.dataset.source ??= block.textContent ?? ''
    const system = window.matchMedia('(prefers-color-scheme: dark)')
    let cancelled = false

    const draw = async (): Promise<void> => {
      // Mermaid takes only `select` of d3, and d3 says it has no side effects, so the bundler leaves out the module
      // that gives a selection its transition(), which Mermaid calls when the pointer leaves a box of a flowchart.
      // Loaded here, it adds it to the one d3-selection Mermaid uses.
      const [{ default: mermaid }] = await Promise.all([import('mermaid'), import('d3-transition')])
      if (cancelled) return
      // A timeline (Gantt chart) takes the width of the text column; Mermaid would measure the hidden element it
      // draws in, and draw it narrow
      const width = Math.max(320, ...blocks.map((block) => block.clientWidth - 34))
      mermaid.initialize({
        startOnLoad: false,
        theme: isDark() ? 'dark' : 'default',
        securityLevel: 'strict',
        gantt: { useWidth: width },
      })
      for (const block of blocks) {
        block.removeAttribute('data-processed')
        block.textContent = block.dataset.source ?? ''
      }
      await mermaid.run({ nodes: blocks })
    }

    // One drawing at a time: a change of theme during a drawing waits for it to end
    let drawing = Promise.resolve()
    const redraw = (): void => {
      drawing = drawing.then(draw, draw)
    }
    redraw()
    system.addEventListener('change', redraw)
    window.addEventListener('themechange', redraw)
    return () => {
      cancelled = true
      system.removeEventListener('change', redraw)
      window.removeEventListener('themechange', redraw)
    }
  }, [])
  return null
}
