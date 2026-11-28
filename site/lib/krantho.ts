import { createStaticSite } from '@krantho/static'
import { initServerLoader } from '@krantho/ui-react/server'
import config from '../krantho.config'
import { collections } from '../krantho.collections'

// Pages read the markdown in ../docs through the site's loader. A link to another file of docs/
// (using.md#7-recover-unfinished-sagas, modules/saga-api.md) becomes that page's address, under the path the site is
// served under, so the files read the same on GitHub and on the site.
export const site = createStaticSite({ config, collections })
initServerLoader(site.loader, site.rendering)

/** The collections that hold documentation pages, in the order the sidebar shows them */
export const docCollections = ['docs', 'modules'] as const
export type DocCollection = (typeof docCollections)[number]

/** A docs page as the sidebar and the pager list it */
export interface DocLink {
  collection: DocCollection
  slug: string
  title: string
  href: string
  group: string
}

/** The address of a docs page: docs/README.md is the documentation's home page */
export function docHref(collection: DocCollection, slug: string): string {
  if (collection === 'modules') return `/docs/modules/${slug}/`
  return slug === 'index' ? '/docs/' : `/docs/${slug}/`
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
      // The documentation's home page, titled for the browser tab, is the introduction
      title: collection === 'docs' && page.slug === 'index' ? 'Introduction' : String(page.fields.title),
      href: docHref(collection, page.slug),
      group: typeof page.fields.group === 'string' ? page.fields.group : fallbackGroup,
    }))
}

/**
 * Every documentation page in reading order: the introduction, first in the first group, then the groups of docs/ in
 * the order of their first page, then the module pages
 */
export async function docLinks(): Promise<DocLink[]> {
  const docs = await linksOf('docs', 'Overview')
  const modules = await linksOf('modules', 'Modules')
  const rest = [...docs.filter((link) => link.slug !== 'index'), ...modules]
  const home = docs
    .filter((link) => link.slug === 'index')
    .map((link) => ({ ...link, group: rest[0]?.group ?? link.group }))
  const groups: string[] = []
  for (const link of rest) {
    if (!groups.includes(link.group)) groups.push(link.group)
  }
  return [...home, ...groups.flatMap((group) => rest.filter((link) => link.group === group))]
}
