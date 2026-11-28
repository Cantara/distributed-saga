import { join } from 'node:path'
import { defineCollection, defineDocumentArchetype } from '@krantho/schema'
import { z } from 'zod'

// The fields of every page, in each markdown file's frontmatter
const fields = z.object({
  title: z.string(),
  description: z.string(),
  group: z.string().describe('The sidebar group the page is listed under').optional(),
  order: z.number().describe("The page's place in its group").optional(),
})

// One page per markdown file in the repository's docs/ folder, the same files
// GitHub shows. README.md is the home page, as it is the folder's page on
// GitHub, and every other file is served at /<file name>/. The path ends in a
// slash: each page is a folder in the static output.
const docs = defineCollection({
  name: 'docs',
  source: 'filesystem',
  directory: join(process.cwd(), '..', 'docs'),
  indexFile: 'README.md',
  path: '/:slug/',
  archetype: defineDocumentArchetype({ id: 'site/docs', label: 'Docs', fields }),
})

// The module pages in docs/modules, served at /modules/<file name>/. A
// collection reads one folder, so the module pages are a collection of their
// own; links between the two folders (modules/saga-api.md, ../using.md) still
// resolve to the pages.
const modules = defineCollection({
  name: 'modules',
  source: 'filesystem',
  directory: join(process.cwd(), '..', 'docs', 'modules'),
  path: '/modules/:slug/',
  archetype: defineDocumentArchetype({ id: 'site/modules', label: 'Modules', fields }),
})

export const collections = { docs, modules }
