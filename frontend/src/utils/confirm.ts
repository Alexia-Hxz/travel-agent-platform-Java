import { ElMessageBox } from 'element-plus'

/**
 * Ask before a destructive action, and say what else it takes with it.
 *
 * Every delete in this app is permanent — there is no undo endpoint and several of
 * them cascade (deleting a trip removes its places, itinerary, files and adjustment
 * drafts; deleting a saved place removes the itinerary items booked for it). The
 * backend cannot report the cascade count, so the caller passes the consequence it
 * already knows about.
 *
 * Returns false when the user dismisses or cancels, so callers simply `return`.
 */
export async function confirmDestructive(
  title: string,
  consequence: string,
  confirmText = '删除',
): Promise<boolean> {
  try {
    await ElMessageBox.confirm(consequence, title, {
      confirmButtonText: confirmText,
      cancelButtonText: '取消',
      type: 'warning',
      // Element Plus shows the confirm button on the right; the destructive action
      // should not be the one the user's cursor is already resting on.
      autofocus: false,
      closeOnClickModal: false,
    })
    return true
  } catch {
    // Dismissing the dialog rejects; that means "do not delete".
    return false
  }
}
