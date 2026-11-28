import GithubSlugger from 'github-slugger'
import { createHighlighter, type Highlighter } from 'shiki'

/** One entry of a page's contents: a `##` or `###` heading */
export interface TocEntry {
  id: string
  text: string
  level: 2 | 3
}

const decode = (text: string): string =>
  text
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
    .replace(/&amp;/g, '&')

const escape = (text: string): string =>
  text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')

/** The languages of the code blocks in the documentation, and the name each is labelled with */
const languages: Record<string, string> = {
  java: 'Java',
  xml: 'XML',
  sh: 'Shell',
  shell: 'Shell',
  console: 'Console',
  json: 'JSON',
  properties: 'Properties',
  sql: 'SQL',
  yaml: 'YAML',
}

// Shiki's name of a language, where it differs
const grammarOf: Record<string, string> = { console: 'shellsession', shell: 'sh' }

// One highlighter for the whole build: loading the grammars and the themes is the slow part
let highlighter: Promise<Highlighter> | undefined
function getHighlighter(): Promise<Highlighter> {
  highlighter ??= createHighlighter({
    themes: ['github-light', 'github-dark'],
    langs: [...new Set(Object.keys(languages).map((lang) => grammarOf[lang] ?? lang))],
  })
  return highlighter
}

/**
 * A code block coloured as GitHub colours it, in both themes: each token carries its light and its dark colour as CSS
 * variables (`--shiki-light`, `--shiki-dark`), and the stylesheet picks one by the page's theme. A language that is
 * not known, and a block without one, is shown as plain text, and wraps its lines. The block is labelled with its
 * language, or with the text of a comment `<!-- label: ... -->` right before it, and the page adds a copy button.
 */
async function highlight(lang: string, escaped: string, title?: string): Promise<string> {
  const code = decode(escaped).replace(/\n$/, '')
  const label = languages[lang]
  const pre = label
    ? (await getHighlighter()).codeToHtml(code, {
        lang: grammarOf[lang] ?? lang,
        themes: { light: 'github-light', dark: 'github-dark' },
        defaultColor: false,
      })
    : `<pre class="shiki"><code>${escape(code)}</code></pre>`
  const kind = !label || lang === 'console' ? ' code-wrap' : ''
  return `<div class="code-block${kind}"><div class="code-head"><span class="code-lang">${title ? escape(decode(title)) : (label ?? 'Text')}</span></div>${pre}</div>`
}

/** Replaces each match of a pattern by what an async function makes of it */
async function replaceAsync(
  text: string,
  pattern: RegExp,
  replace: (...groups: string[]) => Promise<string>,
): Promise<string> {
  const parts: Promise<string>[] = []
  let last = 0
  for (const match of text.matchAll(pattern)) {
    parts.push(Promise.resolve(text.slice(last, match.index)), replace(...match))
    last = (match.index ?? 0) + match[0].length
  }
  parts.push(Promise.resolve(text.slice(last)))
  return (await Promise.all(parts)).join('')
}

/**
 * The HTML of a page as krantho renders its markdown, made ready to show:
 *
 * - every heading gets the id GitHub gives it (github-slugger, the algorithm GitHub uses), so a link such as
 *   `using.md#7-recover-unfinished-sagas` leads to the same place on the site as on GitHub;
 * - a ```mermaid block becomes a `<pre class="mermaid">` with its source, which the page draws in the browser;
 * - every other code block is coloured by its language when the site is built (`highlight`);
 * - the `##` and `###` headings are listed as the page's contents.
 */
export async function prepareHtml(source: string): Promise<{ html: string; toc: TocEntry[]; hasFigures: boolean }> {
  const slugger = new GithubSlugger()
  const toc: TocEntry[] = []
  let html = source.replace(/<h([1-6])>([\s\S]*?)<\/h\1>/g, (_, level: string, inner: string) => {
    const text = decode(inner.replace(/<[^>]+>/g, ''))
    const id = slugger.slug(text)
    if (level === '2' || level === '3') toc.push({ id, text, level: Number(level) as 2 | 3 })
    return `<h${level} id="${id}"><a class="anchor" href="#${id}" aria-hidden="true" tabindex="-1">#</a>${inner}</h${level}>`
  })
  let hasFigures = false
  html = html.replace(/<pre><code class="language-mermaid">([\s\S]*?)<\/code><\/pre>/g, (_, code: string) => {
    hasFigures = true
    return `<pre class="mermaid">${code}</pre>`
  })
  html = await highlightCode(html)
  return { html, toc, hasFigures }
}

/** The code blocks of a piece of HTML, coloured by `highlight`; for the documentation pages and the landing page */
export async function highlightCode(html: string): Promise<string> {
  return replaceAsync(
    html,
    /(?:<!--\s*label:\s*([^<>]*?)\s*-->\s*)?<pre><code(?: class="language-([\w-]+)")?>([\s\S]*?)<\/code><\/pre>/g,
    (_, title = '', lang = '', code = '') => highlight(lang, code, title || undefined),
  )
}
