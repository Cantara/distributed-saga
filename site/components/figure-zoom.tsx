'use client'

import { useEffect } from 'react'

/** The figures of the page: those of docs/ put into the page, and those Mermaid draws */
const FIGURES = '.prose .diagram, .prose pre.mermaid'

/** The name of a figure: the title of its drawing, else the heading of the section it is in */
function nameOf(figure: HTMLElement): string {
  const title = figure.querySelector('svg > title')?.textContent?.trim()
  if (title) return title
  let at: Element | null = figure
  while (at) {
    let before = at.previousElementSibling
    while (before) {
      if (/^H[1-4]$/.test(before.tagName)) return before.textContent?.replace(/^#/, '').trim() ?? 'Figure'
      before = before.previousElementSibling
    }
    at = at.parentElement
  }
  return 'Figure'
}

/**
 * Lets the reader see every figure of the page large: a figure opens in a dialog over the page, as wide as the window,
 * with a click, or with Enter or Space when it has the focus. The dialog shows a copy of the drawing as it is then (in
 * the page's theme), and closes with Escape, its button, or a click beside the drawing. A figure is told to a screen
 * reader as a button that opens it. Without JavaScript the figures are shown as they are.
 */
/**
 * The copy of a drawing with ids of its own: each id gets `-zoom`, and so does every reference to it (an ARIA label,
 * `url(#…)`, `href="#…"`, and the selectors of a `<style>` in the drawing, as Mermaid's), so the page has no id twice
 * and the copy is labelled by its own title.
 */
function withOwnIds(svg: SVGSVGElement): SVGSVGElement {
  const ids = [svg, ...svg.querySelectorAll('[id]')].map((element) => element.id).filter(Boolean)
  if (ids.length === 0) return svg
  const escaped = ids.map((id) => id.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'))
  const reference = new RegExp(`#(${escaped.join('|')})(?![\\w-])`, 'g')
  const own = (text: string): string => text.replace(reference, '#$1-zoom')
  for (const element of [svg, ...svg.querySelectorAll('*')]) {
    if (element.id) element.id = `${element.id}-zoom`
    for (const attribute of [...element.attributes]) {
      if (attribute.name === 'aria-labelledby' || attribute.name === 'aria-describedby') {
        attribute.value = attribute.value
          .split(/\s+/)
          .map((id) => (ids.includes(id) ? `${id}-zoom` : id))
          .join(' ')
      } else if (attribute.name !== 'id' && attribute.value.includes('#')) {
        attribute.value = own(attribute.value)
      }
    }
    if (element.localName === 'style' && element.textContent) element.textContent = own(element.textContent)
  }
  return svg
}

export function FigureZoom(): null {
  useEffect(() => {
    const dialog = document.createElement('dialog')
    dialog.className = 'figure-dialog'
    dialog.setAttribute('aria-labelledby', 'figure-dialog-title')
    dialog.innerHTML =
      '<div class="figure-dialog-head"><h2 id="figure-dialog-title"></h2>' +
      '<button type="button" class="figure-dialog-close">Close</button></div><div class="figure-dialog-body"></div>'
    document.body.append(dialog)
    const heading = dialog.querySelector('h2') as HTMLHeadingElement
    const body = dialog.querySelector('.figure-dialog-body') as HTMLDivElement
    let opener: HTMLElement | null = null

    const open = (figure: HTMLElement): void => {
      const drawing = figure.querySelector('svg')
      if (!drawing) return
      const copy = withOwnIds(drawing.cloneNode(true) as SVGSVGElement)
      copy.removeAttribute('height')
      copy.setAttribute('width', '100%')
      copy.style.maxWidth = 'none'
      heading.textContent = nameOf(figure)
      // A figure of docs/ is coloured by the classes of `.diagram`; Mermaid's drawing carries its own style
      if (figure.classList.contains('diagram')) {
        const frame = document.createElement('div')
        frame.className = 'diagram'
        frame.append(copy)
        body.replaceChildren(frame)
      } else {
        body.replaceChildren(copy)
      }
      opener = figure
      dialog.showModal()
    }

    const figureOf = (target: EventTarget | null): HTMLElement | null =>
      target instanceof Element ? target.closest<HTMLElement>(FIGURES) : null
    const onClick = (event: MouseEvent): void => {
      const figure = figureOf(event.target)
      if (figure && figure.querySelector('svg')) open(figure)
    }
    const onKey = (event: KeyboardEvent): void => {
      if (event.key !== 'Enter' && event.key !== ' ') return
      const figure = figureOf(event.target)
      if (figure && event.target === figure) {
        event.preventDefault()
        open(figure)
      }
    }
    const onDialogClick = (event: MouseEvent): void => {
      if (event.target === dialog || (event.target as Element).closest('.figure-dialog-close')) dialog.close()
    }
    const onClose = (): void => {
      body.replaceChildren()
      opener?.focus()
    }

    for (const figure of document.querySelectorAll<HTMLElement>(FIGURES)) {
      figure.tabIndex = 0
      figure.setAttribute('role', 'button')
      figure.setAttribute('aria-label', `Enlarge the figure: ${nameOf(figure)}`)
    }
    document.addEventListener('click', onClick)
    document.addEventListener('keydown', onKey)
    dialog.addEventListener('click', onDialogClick)
    dialog.addEventListener('close', onClose)
    return () => {
      document.removeEventListener('click', onClick)
      document.removeEventListener('keydown', onKey)
      dialog.remove()
    }
  }, [])
  return null
}
