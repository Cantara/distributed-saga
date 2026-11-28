# Documentation site

The site at <https://cantara.github.io/distributed-saga/>, built with [krantho](https://krantho.com) and Next.js to
static files: a landing page, and the documentation under `/docs/`.

| Address | Page | Source |
|---|---|---|
| `/` | The landing page | `content/landing/home.md`, `app/page.tsx` |
| `/docs/` | The documentation's home page | `../docs/README.md` |
| `/docs/<file name>/` | A documentation page | `../docs/<file name>.md` |
| `/docs/modules/<file name>/` | A module page | `../docs/modules/<file name>.md` |

## The documentation

The pages are the markdown files in [docs/](../docs/README.md). The files read the same on GitHub; links between them
become links between pages, and every heading gets the id GitHub gives it, so `using.md#7-recover-unfinished-sagas`
leads to the same place on both. Each file starts with frontmatter that the site reads:

```yaml
---
title: "Topology"
description: "One sentence: the page's summary for search engines and link previews."
group: Overview          # the sidebar group: Overview, Guides or Reference (module pages: Modules)
order: 3                 # the page's place in its group
---
```

A ```mermaid block is drawn as a diagram, on the site as on GitHub, in the reader's theme; a click opens it large.
Mermaid ends a statement at `;`, so a diagram's text must not contain one. Other code blocks are coloured as GitHub
colours them, and get a copy button. A comment `<!-- label: ... -->` right before a block labels it.

## The landing page

`content/landing/home.md` holds it. Its frontmatter is the hero (`eyebrow`, `headline`, `subline` and the two
buttons). Each `##` section is a row below it, in the order of `sections` in `krantho.collections.ts`, and a section
missing from that list is not shown. How a section is laid out follows from what it holds (`app/page.tsx`):

- text and code blocks: the text beside the code;
- `###` headings: the text over the width, then a column per heading;
- a section named in `figureOf`: the text beside its animated figure (`components/figures.tsx`);
- a pair named in `pairOf`: two sections side by side.

The figures are SVG drawn in the theme's colours. Each steps through four states, and they follow the saga-log
protocol: keep them true to [Protocols](../docs/protocols.md) when it changes.

## Layout and themes

`app/globals.css` starts with the tokens, and says which viewports the layout is made for: phones (the sidebar behind
the menu button, the header's links on a row of their own), tablets, laptops (the page's contents beside it from
1344 px) and large screens (the type grows from 1920 px, so 4K and 5K windows show the same page, larger).

The header's switch picks the theme: as the system says, light or dark. The choice is kept in the browser
(`localStorage`) and applied before the page is painted (`components/theme-switch.tsx`).

## Logo and icon

Cantara's mark in the header is traced from Cantara's GitHub avatar into two inks, `public/cantara-mark-paths.svg`.
The header draws each ink with `<use>`, coloured by `--cantara-red` and `--cantara-maroon` in `globals.css`, once for
light and once for dark. `public/cantara-mark.svg` and `public/cantara-logo.svg` (with the taglines) are the same
traces as standalone files, with their palette in their own `<style>`. `app/icon.svg` is the browser tab's icon: a
saga that forks and joins, on Cantara's red.

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
