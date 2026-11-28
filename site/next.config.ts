import { resolveConfig } from '@krantho/config'
import { basePathOf } from '@krantho/static/base-path'
import { prepareImages } from '@krantho/static/images'
import type { NextConfig } from 'next'
import { PHASE_DEVELOPMENT_SERVER, PHASE_PRODUCTION_BUILD } from 'next/constants'
import kranthoConfig from './krantho.config'

// `next build` writes the site to out/ as static files. They are served under
// the path of site.baseUrl: /<repository> on a GitHub Pages project site, and
// none on a custom domain. Each page is a folder (about/index.html), which
// every static host serves at /about/.
export default async function config(phase: string): Promise<NextConfig> {
  // The images in public/ get AVIF and WebP copies at widths from phones to 2×
  // desktop screens, in public/_krantho/images; pages show them through
  // <picture>. Only new and changed images are encoded again.
  if (phase === PHASE_PRODUCTION_BUILD || phase === PHASE_DEVELOPMENT_SERVER) {
    await prepareImages({ config: kranthoConfig })
  }
  return {
    output: 'export',
    basePath: basePathOf(resolveConfig(kranthoConfig, process.env).site.baseUrl),
    trailingSlash: true,
    images: { unoptimized: true },
    // Loaded by Node at build, not bundled: they load optional packages on demand
    serverExternalPackages: ['@krantho/static', '@krantho/server'],
  }
}
