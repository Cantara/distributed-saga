import { createStaticSite } from '@krantho/static'
import { initServerLoader } from '@krantho/ui-react/server'
import config from '../krantho.config'
import { collections } from '../krantho.collections'

// Pages read the markdown in ../docs through the site's loader. A link to
// another file of docs/ (using.md#recovery, modules/saga-api.md) becomes that
// page's address, under the path the site is served under, so the files read
// the same on GitHub and on the site.
export const site = createStaticSite({ config, collections })
initServerLoader(site.loader, site.rendering)

/** The collections with pages */
export type DocCollection = 'docs' | 'modules'

/** A page as the sidebar and the pager list it */
export interface DocLink {
  collection: DocCollection
  slug: string
  title: string
  href: string
  group: string
}

/** The address of a page: docs/README.md is the home page */
export function docHref(collection: DocCollection, slug: string): string {
  if (collection === 'modules') return `/modules/${slug}/`
  return slug === 'index' ? '/' : `/${slug}/`
}

const orderOf = (fields: Record<string, unknown>): number =>
  typeof fields.order === 'number' ? fields.order : Number.MAX_SAFE_INTEGER

async function linksOf(collection: DocCollection, fallbackGroup: string): Promise<DocLink[]> {
  const pages = await site.loader.listPublishedSummaries(collection)
  return [...pages]
    .sort(
      (a, b) =>
        orderOf(a.fields) - orderOf(b.fields) ||
        String(a.fields.title).localeCompare(String(b.fields.title)),
    )
    .map((page) => ({
      collection,
      slug: page.slug,
      // The home page, titled for the browser tab, is the introduction
      title: collection === 'docs' && page.slug === 'index' ? 'Introduction' : String(page.fields.title),
      href: docHref(collection, page.slug),
      group: typeof page.fields.group === 'string' ? page.fields.group : fallbackGroup,
    }))
}

/**
 * Every page in reading order: the home page, then the groups of docs/ in the
 * order of their first page, then the module pages
 */
export async function docLinks(): Promise<DocLink[]> {
  const docs = await linksOf('docs', '')
  const modules = await linksOf('modules', 'Modules')
  const home = docs.filter((link) => link.slug === 'index')
  const rest = [...docs.filter((link) => link.slug !== 'index'), ...modules]
  const groups: string[] = []
  for (const link of rest) {
    if (!groups.includes(link.group)) groups.push(link.group)
  }
  return [...home, ...groups.flatMap((group) => rest.filter((link) => link.group === group))]
}
