'use client'

import { useEffect } from 'react'

// From krantho's own site (krantho-site/app/components/section-hash.tsx).
// The address bar follows the part of a long page in view: scrolling to the
// landing page's "How it works" makes the URL /#how-it-works, to a docs heading
// /docs/<folder>/<page>/#<heading>, so it can be copied and shared as it stands, and
// opening that URL starts the page there. Above the first part the hash is
// cleared. The URL is replaced, never pushed, so Back leaves the page. Links to
// the current part inside a [data-section-nav] (the docs' "On this page") are
// marked aria-current.

/** How far down the viewport a part's top must be to make it the current one */
const CURRENT_LINE = 0.35

/** The last target whose top has passed the line, or null above the first */
function currentTarget(targets: readonly HTMLElement[]): string | null {
  const line = window.innerHeight * CURRENT_LINE
  let current: string | null = null
  for (const target of targets) {
    if (target.getBoundingClientRect().top <= line) current = target.id
  }
  return current
}

function showHash(id: string | null): void {
  const base = `${window.location.pathname}${window.location.search}`
  const url = id ? `${base}#${id}` : base
  if (url === `${base}${window.location.hash}`) return
  window.history.replaceState(window.history.state, '', url)
}

function markNav(id: string | null): void {
  for (const link of document.querySelectorAll<HTMLAnchorElement>('[data-section-nav] a[href^="#"]')) {
    if (id && link.getAttribute('href') === `#${id}`) link.setAttribute('aria-current', 'location')
    else link.removeAttribute('aria-current')
  }
}

/** Follow the elements `selector` matches — each needs an id — as the page scrolls */
export function SectionHash({ selector }: { selector: string }): null {
  useEffect(() => {
    const targets = Array.from(document.querySelectorAll<HTMLElement>(selector)).filter((el) => el.id)
    if (targets.length === 0) return
    // Only scrolling changes the address: opening /#install keeps it while the
    // browser scrolls there. One check per frame, however often scroll fires.
    let frame = 0
    const follow = (): void => {
      // Leaving the page, a scroll can come before the cleanup below: the
      // address is the next page's by then, and its hash is not ours to set
      if (targets.some((target) => !target.isConnected)) return
      const id = currentTarget(targets)
      showHash(id)
      markNav(id)
    }
    const onScroll = (): void => {
      if (frame) return
      frame = window.requestAnimationFrame(() => {
        frame = 0
        follow()
      })
    }
    markNav(currentTarget(targets))
    window.addEventListener('scroll', onScroll, { passive: true })
    return () => {
      window.removeEventListener('scroll', onScroll)
      if (frame) window.cancelAnimationFrame(frame)
    }
  }, [selector])
  return null
}
