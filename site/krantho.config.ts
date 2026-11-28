import { defineKranthoConfig } from '@krantho/config'

/**
 * The documentation site. Its pages are the markdown files in ../docs and
 * ../docs/modules, the same files GitHub shows in the repository. The Pages
 * workflow sets KRANTHO_SITE_BASE_URL to the address Pages serves the site at
 * (https://cantara.github.io/distributed-saga/); its path is the path every
 * page is served under.
 */
export default defineKranthoConfig({
  site: {
    domain: 'localhost',
    name: 'Distributed Saga',
    description:
      'Java libraries that run sagas: a graph of actions, each with a compensating action, recorded in a durable saga log so a failed saga can be rolled back and an interrupted one continued.',
    language: 'en',
    baseUrl: 'http://localhost:3000',
  },
  // The folder whose git history dates each page
  paths: { contentDir: '../docs' },
  // The markdown files are the content: no database, every page written at build
  persistence: { provider: 'filesystem' },
  cache: { pages: 'none' },
  // The docs hold Java annotations such as @throws: no @field substitution
  content: { atNotation: false },
  // The structured data each page carries
  seo: { identity: 'WebSite', collections: { docs: ['TechArticle'], modules: ['TechArticle'] } },
})
