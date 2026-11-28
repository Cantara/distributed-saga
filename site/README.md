# Documentation site

The site at <https://cantara.github.io/distributed-saga/>, built with [krantho](https://krantho.com) and
Next.js to static files. Its pages are the markdown files in [docs/](../docs/README.md): `docs/README.md` is the home
page, every other file in `docs/` is served at `/<file name>/`, and the files in `docs/modules/` at
`/modules/<file name>/`. The files read the same on GitHub; links between them become links between pages.

Each file starts with frontmatter that the site reads:

```yaml
---
title: "Topology"
description: "One sentence: the page's summary for search engines and link previews."
group: Overview          # the sidebar group: Overview, Guides or Reference (module pages: Modules)
order: 3                 # the page's place in the sidebar
---
```

A ```mermaid block is drawn as a diagram, on the site as on GitHub. Mermaid ends a statement at `;`, so a
diagram's text must not contain one.

## Logo

`public/cantara-mark.svg` (the arcs and the wordmark, in the header) and `public/cantara-logo.svg` (with the two
taglines) are traced from Cantara's GitHub avatar, one path per ink. The colours are in the `<style>` at the top of
each file: `--cantara-red` and `--cantara-maroon`, once for light backgrounds and once under
`prefers-color-scheme: dark`. Tune the palette there.

## Build

Node 24 and pnpm (the version in `package.json`):

```bash
cd site
pnpm install
pnpm dev                 # http://localhost:3000
pnpm build               # the site, in out/
```

`KRANTHO_SITE_BASE_URL=https://cantara.github.io/distributed-saga pnpm build` builds it as Pages serves it, under
`/distributed-saga`.

## Publish

`.github/workflows/pages.yml` builds the site on a pull request that changes `docs/` or `site/`, and builds and
publishes it to GitHub Pages when they change on `main`. The repository's Pages setting publishes from GitHub
Actions, and only `main` may deploy.
