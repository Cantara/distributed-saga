'use client'

import { useEffect, useState } from 'react'

type Theme = 'system' | 'light' | 'dark'
const next: Record<Theme, Theme> = { system: 'light', light: 'dark', dark: 'system' }

/**
 * Applies the stored theme before the body is painted: one page is served to every reader, so the choice is the
 * browser's, and anything later than this would show the other theme first. Rendered in the root layout's head.
 */
export const themeScript =
  "(function(){try{var t=localStorage.getItem('theme');if(t==='light'||t==='dark')document.documentElement.dataset.theme=t}catch(e){}})()"

/** The theme of the page: as the system says, light or dark. Each press moves to the next. */
export function ThemeSwitch(): React.ReactElement {
  const [theme, setTheme] = useState<Theme>('system')

  useEffect(() => {
    const stored = document.documentElement.dataset.theme
    if (stored === 'light' || stored === 'dark') setTheme(stored)
  }, [])

  const choose = (value: Theme): void => {
    setTheme(value)
    try {
      if (value === 'system') localStorage.removeItem('theme')
      else localStorage.setItem('theme', value)
    } catch {
      // A private window may refuse storage; the theme still holds on this page
    }
    if (value === 'system') delete document.documentElement.dataset.theme
    else document.documentElement.dataset.theme = value
    window.dispatchEvent(new Event('themechange'))
  }

  return (
    <button
      type="button"
      className="icon-button"
      aria-label={`Theme: ${theme}`}
      title={`Theme: ${theme}`}
      onClick={() => choose(next[theme])}
    >
      <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" aria-hidden="true">
        {theme === 'light' && (
          <>
            <circle cx="12" cy="12" r="4" />
            <path d="M12 2v2M12 20v2M2 12h2M20 12h2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4" />
          </>
        )}
        {theme === 'dark' && <path d="M20 14.5A8 8 0 0 1 9.5 4a8 8 0 1 0 10.5 10.5Z" />}
        {theme === 'system' && (
          <>
            <circle cx="12" cy="12" r="8" />
            <path d="M12 4a8 8 0 0 1 0 16Z" fill="currentColor" />
          </>
        )}
      </svg>
    </button>
  )
}
