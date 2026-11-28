'use client'

import { useEffect } from 'react'

/**
 * The commands of a console session: each line that starts with `$ `, without it, and the lines a command continues on
 * (after a `\` at the end of a line). The answers are left out, so what is copied can be pasted into a shell.
 */
function commandsOf(session: string): string {
  const commands: string[] = []
  let continued = false
  for (const line of session.split('\n')) {
    if (line.startsWith('$ ')) commands.push(line.slice(2))
    else if (continued) commands.push(line)
    else continue
    continued = line.trimEnd().endsWith('\\')
  }
  return commands.join('\n')
}

/**
 * Gives every code block of the page a button that copies its text; of a console session, its commands only. Added in
 * the browser, where copying works; without JavaScript the blocks are shown without it. The button says what happened
 * for two seconds, and a screen reader hears it.
 */
export function CodeCopy(): null {
  useEffect(() => {
    const added: HTMLButtonElement[] = []
    for (const block of document.querySelectorAll<HTMLElement>('.code-block')) {
      if (block.querySelector('.code-copy')) continue
      const button = document.createElement('button')
      button.type = 'button'
      button.className = 'code-copy'
      button.textContent = 'Copy'
      button.setAttribute('aria-live', 'polite')
      button.addEventListener('click', () => {
        const code = block.querySelector('pre')?.innerText ?? ''
        const isConsole = block.querySelector('.code-lang')?.textContent === 'Console'
        const say = (text: string): void => {
          button.textContent = text
          setTimeout(() => {
            button.textContent = 'Copy'
          }, 2000)
        }
        void navigator.clipboard.writeText(isConsole ? commandsOf(code) : code).then(
          () => say('Copied'),
          () => say('Not copied'),
        )
      })
      ;(block.querySelector('.code-head') ?? block).append(button)
      added.push(button)
    }
    return () => {
      for (const button of added) button.remove()
    }
  }, [])
  return null
}
