import { site } from '../../lib/krantho'

// Written once at build, from the published pages
export const dynamic = 'force-static'

export async function GET(): Promise<Response> {
  return new Response(await site.sitemapXml(), {
    headers: { 'content-type': 'application/xml; charset=utf-8' },
  })
}
