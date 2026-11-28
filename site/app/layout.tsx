import type { Metadata } from 'next'
import Link from 'next/link'
import { docLinks, site } from '../lib/krantho'
import './globals.css'

const { name, description, baseUrl } = site.config.site
const repository = 'https://github.com/Cantara/distributed-saga'

export const metadata: Metadata = {
  // Absolute URLs (canonical, Open Graph) start at the site's address, path included
  metadataBase: new URL(`${baseUrl.replace(/\/$/, '')}/`),
  title: { default: name, template: `%s — ${name}` },
  description,
}

export default async function RootLayout({
  children,
}: {
  children: React.ReactNode
}): Promise<React.ReactElement> {
  const links = await docLinks()
  const groups = [...new Set(links.map((link) => link.group))]
  return (
    <html lang="en">
      <body>
        <header className="site-header">
          <Link href="/" className="site-name">
            {/* public/cantara-mark.svg carries its own light and dark palette */}
            <img src={`${site.basePath}/cantara-mark.svg`} alt="Cantara" className="site-logo" />
            <span>{name}</span>
          </Link>
          <a href={repository} className="site-repo">
            GitHub
          </a>
        </header>
        <div className="site-body">
          <nav className="sidebar" aria-label="Documentation">
            {groups.map((group) => (
              <section key={group}>
                {group && <h2>{group}</h2>}
                <ul>
                  {links
                    .filter((link) => link.group === group)
                    .map((link) => (
                      <li key={link.href}>
                        <Link href={link.href}>{link.title}</Link>
                      </li>
                    ))}
                </ul>
              </section>
            ))}
          </nav>
          <main>{children}</main>
        </div>
        <footer className="site-footer">
          <span>
            Copyright Cantara © 2026 ·{' '}
            <a href={`${repository}/blob/main/LICENSE`}>Apache License 2.0</a>
          </span>
          <span>
            Built with <a href="https://krantho.com">krantho</a>
          </span>
        </footer>
      </body>
    </html>
  )
}
