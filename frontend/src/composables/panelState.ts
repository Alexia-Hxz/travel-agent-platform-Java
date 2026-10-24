import { ref } from 'vue'

/**
 * Shared fold/unfold state for every collapsible panel on the page.
 *
 * The panels live in five different components (the trip page, the planning agent, the
 * itinerary editor, the files list and the overview), so a single "全部收起 / 全部展开"
 * button cannot reach them through props. They all watch the one command below instead.
 * A nonce makes the command fire even when the same action is pressed twice in a row.
 */
export type PanelAction = 'open' | 'close'

export const panelCommand = ref<{ action: PanelAction; nonce: number } | null>(null)

let commandNonce = 0

export function setAllPanels(action: PanelAction): void {
  commandNonce += 1
  panelCommand.value = { action, nonce: commandNonce }
}

const STORAGE_PREFIX = 'travel-agent:panel:'

/**
 * What the user chose last time for this panel, or null when they never touched it.
 *
 * A fresh result opens itself without writing here, so "auto-open what was just produced"
 * and "remember that I folded this" do not overwrite each other.
 */
export function readPanelPreference(key: string): boolean | null {
  try {
    const stored = window.localStorage.getItem(STORAGE_PREFIX + key)
    if (stored === 'open') return true
    if (stored === 'closed') return false
    return null
  } catch {
    // Private mode or a blocked storage backend: the panel simply falls back to its default.
    return null
  }
}

export function writePanelPreference(key: string, open: boolean): void {
  try {
    window.localStorage.setItem(STORAGE_PREFIX + key, open ? 'open' : 'closed')
  } catch {
    // Not being able to remember the choice must never break the page.
  }
}

export function forgetPanelPreferences(): void {
  try {
    for (const key of Object.keys(window.localStorage)) {
      if (key.startsWith(STORAGE_PREFIX)) window.localStorage.removeItem(key)
    }
  } catch {
    // Nothing to do: the preferences are only a convenience.
  }
}

/**
 * Where a list stops being readable at a glance on the trip page.
 *
 * Every panel uses `defaultOpenForCount` so the rule lives in ONE place: a short block --
 * including an empty one, whose hint is worth showing -- stays open, and a long one starts
 * folded with its size in the header. Six rows is roughly what fits without pushing the
 * next feature off the screen.
 */
export const PANEL_LONG_LIST = 6

export function defaultOpenForCount(count: number): boolean {
  return count <= PANEL_LONG_LIST
}
