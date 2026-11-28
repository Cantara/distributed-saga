import type { Metadata } from 'next'

// The card a link to the site shows where it is shared (Slack, LinkedIn, X, Teams): public/og.png, 1200 x 630,
// rendered from icons/og.html. A page's own openGraph replaces the layout's, so every page builds its tags here.
const card = {
  url: '/og.png',
  width: 1200,
  height: 630,
  alt: 'Distributed Saga: operations across many systems that finish, or undo, even after a crash',
}

/** The Open Graph and X tags of a page: its title, description and address, and the site's card */
export function shareTags(
  title: string,
  description: string,
  url: string,
  type: 'website' | 'article' = 'article',
): Pick<Metadata, 'openGraph' | 'twitter'> {
  return {
    openGraph: { type, siteName: 'Distributed Saga', locale: 'en', title, description, url, images: [card] },
    twitter: { card: 'summary_large_image', title, description, images: [card] },
  }
}
