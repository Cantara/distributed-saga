'use client'

import { usePathname } from 'next/navigation'
import { useEffect, useState } from 'react'

/**
 * The menu button of a narrow screen: shows and hides the sidebar's links
 * (by the `menu-open` class on the body) and hides them again on a new page.
 * On a wide screen the sidebar is always shown and the button is not.
 */
export function MenuButton(): React.ReactElement {
  const [open, setOpen] = useState(false)
  const pathname = usePathname()

  // A new page: the links have done their work
  useEffect(() => {
    setOpen(false)
  }, [pathname])

  useEffect(() => {
    document.body.classList.toggle('menu-open', open)
  }, [open])

  return (
    <button
      type="button"
      className="menu-button"
      aria-controls="site-nav"
      aria-expanded={open}
      aria-label={open ? 'Close the menu' : 'Open the menu'}
      onClick={() => setOpen((value) => !value)}
    >
      <svg viewBox="0 0 24 24" width="24" height="24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" aria-hidden="true">
        {open ? <path d="M6 6l12 12M18 6L6 18" /> : <path d="M4 7h16M4 12h16M4 17h16" />}
      </svg>
    </button>
  )
}
