import type { Metadata, Viewport } from 'next'
import Link from 'next/link'
import { GitHubLink, Logo, repository } from '../components/brand'
import { MenuButton } from '../components/menu-button'
import { ThemeSwitch, themeScript } from '../components/theme-switch'
import { site } from '../lib/krantho'
import './globals.css'

const { name, description, baseUrl } = site.config.site

export const metadata: Metadata = {
  // Absolute URLs (canonical, Open Graph) start at the site's address, path included
  metadataBase: new URL(`${baseUrl.replace(/\/$/, '')}/`),
  title: { default: name, template: `%s — ${name}` },
  description,
}

// A phone lays the page out at its own width; its browser bars take the page's background, light or dark
export const viewport: Viewport = {
  width: 'device-width',
  initialScale: 1,
  themeColor: [
    { media: '(prefers-color-scheme: light)', color: '#fbfbf9' },
    { media: '(prefers-color-scheme: dark)', color: '#121315' },
  ],
}

export default function RootLayout({ children }: { children: React.ReactNode }): React.ReactElement {
  return (
    // The theme script sets data-theme before React hydrates, so the attribute differs from the server's HTML
    <html lang="en" suppressHydrationWarning>
      <head>
        <script dangerouslySetInnerHTML={{ __html: themeScript }} />
      </head>
      <body>
        <a className="skip" href="#content">
          Skip to the content
        </a>
        <header className="site-header">
          <div className="site-header-inner">
            <Link href="/" className="site-name" aria-label={`${name}, home`}>
              <Logo />
            </Link>
            <nav className="header-nav" aria-label="Site">
              <Link href="/docs/">Documentation</Link>
              <Link href="/docs/glossary/">Glossary</Link>
            </nav>
            <div className="header-actions">
              <ThemeSwitch />
              <GitHubLink />
              <MenuButton />
            </div>
          </div>
        </header>
        <div id="content" tabIndex={-1}>
          {children}
        </div>
        <footer className="site-footer">
          <div className="site-footer-inner">
            <span>
              Copyright Cantara © 2026 · <a href={`${repository}/blob/main/LICENSE`}>APL2</a>
            </span>
            <span>
              Built with <a href="https://krantho.com">krantho</a>
            </span>
          </div>
        </footer>
      </body>
    </html>
  )
}
