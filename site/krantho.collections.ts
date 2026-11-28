import { join } from 'node:path'
import { defineCollection, defineDocumentArchetype } from '@krantho/schema'
import { z } from 'zod'

// The fields of every documentation page, in each markdown file's frontmatter
const fields = z.object({
  title: z.string(),
  description: z.string(),
  group: z.string().describe('The sidebar group the page is listed under').optional(),
  order: z.number().describe("The page's place in its group").optional(),
})

// The documentation is the markdown in the repository's docs/ folder, the same files GitHub shows. A collection reads
// one folder, so docs/ and docs/modules/ are a collection each. docs/README.md is the documentation's home page
// (/docs/), every other file of docs/ is served at /docs/<file name>/, and those of docs/modules/ at
// /docs/modules/<file name>/. Each path ends in a slash: every page is a folder in the static output. Links between
// the two folders (modules/saga-api.md, ../using.md) resolve to the pages.
const docs = defineCollection({
  name: 'docs',
  source: 'filesystem',
  directory: join(process.cwd(), '..', 'docs'),
  indexFile: 'README.md',
  path: '/docs/:slug/',
  archetype: defineDocumentArchetype({ id: 'site/docs', label: 'Docs', fields }),
})

const modules = defineCollection({
  name: 'modules',
  source: 'filesystem',
  directory: join(process.cwd(), '..', 'docs', 'modules'),
  path: '/docs/modules/:slug/',
  archetype: defineDocumentArchetype({ id: 'site/modules', label: 'Modules', fields }),
})

// The landing page, one document (home), rendered at /: its fields are the hero, and the `##` sections of its body
// the rows below it, in the order of the section contract. The `###` headings of a section are its columns.
const landing = defineCollection({
  name: 'landing',
  source: 'filesystem',
  directory: join(process.cwd(), 'content', 'landing'),
  path: '/',
  sections: [
    'The problem',
    'How a saga solves it',
    'Steps in a graph, run in parallel',
    'Every step is logged before and after it runs',
    'Undo with compensating actions',
    'Choose where the saga log lives',
    'A library inside your application',
    'Add it to your project',
    'Run a saga',
  ],
  archetype: defineDocumentArchetype({
    id: 'site/landing',
    label: 'Landing',
    fields: z.object({
      title: z.string(),
      description: z.string(),
      eyebrow: z.string(),
      headline: z.string(),
      subline: z.string(),
      'primary-label': z.string(),
      'primary-href': z.string(),
      'secondary-label': z.string(),
      'secondary-href': z.string(),
    }),
  }),
})

export const collections = { docs, modules, landing }
