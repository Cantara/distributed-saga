import type { MetadataRoute } from 'next'
import { site } from '../lib/krantho'

// manifest.webmanifest: the name, colours and icons a phone or a browser uses when the site is added to a home screen
// or installed. The icons are drawn from Cantara's mark (site/icons/, see site/README.md).
export const dynamic = 'force-static'

export default function manifest(): MetadataRoute.Manifest {
  const base = site.basePath
  return {
    name: site.config.site.name,
    short_name: 'Saga',
    description: site.config.site.description,
    start_url: `${base}/`,
    scope: `${base}/`,
    display: 'browser',
    background_color: '#fbfbf9',
    theme_color: '#c12a30',
    icons: [
      { src: `${base}/icons/icon-192.png`, sizes: '192x192', type: 'image/png', purpose: 'any' },
      { src: `${base}/icons/icon-512.png`, sizes: '512x512', type: 'image/png', purpose: 'any' },
      { src: `${base}/icons/icon-maskable-512.png`, sizes: '512x512', type: 'image/png', purpose: 'maskable' },
    ],
  }
}
