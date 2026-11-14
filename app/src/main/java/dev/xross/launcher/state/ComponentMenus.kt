package dev.xross.launcher.state

import dev.xross.launcher.theme.pack.ResolvedComponents
import dev.xross.launcher.theme.pack.StatusSide
import dev.xross.launcher.theme.pack.StatusSlot
import dev.xross.launcher.theme.pack.label

/** One edit of a status-bar cluster from Settings ▸ Theme ▸ Components (2b.7a). */
enum class SlotEdit(val label: String) {
    /** One place toward the start of the cluster. */
    EARLIER("Move left"),

    /** One place toward the end (never past the avatar). */
    LATER("Move right"),

    /** To the end of the other cluster (before the avatar on the right). */
    OTHER_SIDE("Move to the other side"),
    REMOVE("Remove"),

    /** Into this cluster, at its end (before the avatar); taken out of the other cluster if it is there. */
    ADD("Add"),
}

/**
 * The status-bar slot editors of Settings ▸ Theme ▸ Components (2b.7a): the Options list of
 * Status bar left / right, and the edits they run. Pure, so MenuTree and the view model (which
 * reopens the list after each edit) build the same menu.
 */
object ComponentMenus {
    /** The slots of [side] in [c]. */
    fun slots(c: ResolvedComponents, side: StatusSide): List<StatusSlot> = if (side == StatusSide.LEFT) c.left else c.right

    /** "Clock · Battery · Avatar", or "Empty". */
    fun summary(slots: List<StatusSlot>): String = if (slots.isEmpty()) "Empty" else slots.joinToString(" · ") { it.label }

    /**
     * [c]'s clusters after [edit] of [slot] in [side]: each slot once, `AVATAR` only last on the
     * right (it can be removed or added back, never moved). Null when the edit changes nothing.
     */
    fun edit(c: ResolvedComponents, side: StatusSide, slot: StatusSlot, edit: SlotEdit): Pair<List<StatusSlot>, List<StatusSlot>>? {
        val left = c.left.toMutableList()
        val right = c.right.filter { it != StatusSlot.AVATAR }.toMutableList()
        var avatar = c.avatar
        val mine = if (side == StatusSide.LEFT) left else right
        val other = if (side == StatusSide.LEFT) right else left
        if (slot == StatusSlot.AVATAR) {
            when (edit) {
                SlotEdit.REMOVE -> if (!avatar) return null else avatar = false
                SlotEdit.ADD -> if (avatar || side == StatusSide.LEFT) return null else avatar = true
                else -> return null
            }
        } else {
            when (edit) {
                SlotEdit.EARLIER, SlotEdit.LATER -> {
                    val i = mine.indexOf(slot)
                    val j = if (edit == SlotEdit.EARLIER) i - 1 else i + 1
                    if (i < 0 || j !in mine.indices) return null
                    mine[i] = mine[j].also { mine[j] = slot }
                }
                SlotEdit.OTHER_SIDE -> {
                    if (!mine.remove(slot)) return null
                    other += slot
                }
                SlotEdit.REMOVE -> if (!mine.remove(slot)) return null
                SlotEdit.ADD -> {
                    if (slot in mine) return null
                    other.remove(slot)
                    mine += slot
                }
            }
        }
        val out = left.toList() to (if (avatar) right + StatusSlot.AVATAR else right.toList())
        return out.takeIf { it != (c.left to c.right) }
    }

    /**
     * The Options list of Status bar [side]: the theme pack's slots ([pack], selected while the
     * user hasn't set this side, [userSet]), each slot of [c] with its moves, then Add. [hasLogo]:
     * the pack has a logo for `LOGO` to draw.
     */
    fun options(side: StatusSide, c: ResolvedComponents, pack: ResolvedComponents, userSet: Boolean, hasLogo: Boolean): List<MenuOption> = buildList {
        add(MenuOption("Theme pack", detail = summary(slots(pack, side)), command = Command.SetStatusSlots(side, null), selected = !userSet))
        val mine = slots(c, side)
        val movable = mine.filter { it != StatusSlot.AVATAR }
        for (slot in mine) {
            if (slot == StatusSlot.AVATAR) {
                add(
                    MenuOption(
                        slot.label, detail = "Always last",
                        submenu = listOf(MenuOption(SlotEdit.REMOVE.label, detail = "The User Card stays in Settings ▸ Accounts", command = Command.EditStatusSlot(side, slot, SlotEdit.REMOVE))),
                    ),
                )
                continue
            }
            val i = movable.indexOf(slot)
            val edits = buildList {
                if (i > 0) add(SlotEdit.EARLIER)
                if (i < movable.lastIndex) add(SlotEdit.LATER)
                add(SlotEdit.OTHER_SIDE)
                add(SlotEdit.REMOVE)
            }
            add(
                MenuOption(
                    slot.label,
                    detail = "${i + 1} of ${movable.size}",
                    submenu = edits.map { e ->
                        val label = if (e == SlotEdit.OTHER_SIDE) (if (side == StatusSide.LEFT) "Move to the right" else "Move to the left") else e.label
                        MenuOption(label, command = Command.EditStatusSlot(side, slot, e))
                    },
                ),
            )
        }
        val addable = StatusSlot.entries.filter { s ->
            s !in mine && (s != StatusSlot.AVATAR || side == StatusSide.RIGHT)
        }
        if (addable.isNotEmpty()) {
            add(
                MenuOption(
                    SlotEdit.ADD.label,
                    submenu = addable.map { s ->
                        val other = slots(c, if (side == StatusSide.LEFT) StatusSide.RIGHT else StatusSide.LEFT)
                        val detail = when {
                            s == StatusSlot.LOGO && !hasLogo -> "This pack has no logo"
                            s == StatusSlot.AVATAR -> "Always last"
                            s in other -> if (side == StatusSide.LEFT) "Moves from the right" else "Moves from the left"
                            else -> null
                        }
                        MenuOption(s.label, detail = detail, command = Command.EditStatusSlot(side, s, SlotEdit.ADD))
                    },
                ),
            )
        }
    }

    /**
     * The menu reopened after an edit of [slot]: on its row (else the Theme pack row), and after a
     * move left or right with its moves open, so it can be moved again at once.
     */
    fun reopen(title: String, options: List<MenuOption>, slot: StatusSlot, last: SlotEdit): OptionsMenuState {
        val at = options.indexOfFirst { it.label == slot.label && it.submenu.isNotEmpty() && it.command == null && it.label != SlotEdit.ADD.label }
        val root = OptionsMenuState.Level(title, options, at.coerceAtLeast(0))
        if (at < 0 || (last != SlotEdit.EARLIER && last != SlotEdit.LATER)) return OptionsMenuState(title, listOf(root))
        val sub = options[at].submenu
        val again = sub.indexOfFirst { (it.command as? Command.EditStatusSlot)?.edit == last }.takeIf { it >= 0 }
            ?: sub.indexOfFirst { (it.command as? Command.EditStatusSlot)?.edit in setOf(SlotEdit.EARLIER, SlotEdit.LATER) }.coerceAtLeast(0)
        return OptionsMenuState(title, listOf(root, OptionsMenuState.Level(options[at].label, sub, again)))
    }
}
