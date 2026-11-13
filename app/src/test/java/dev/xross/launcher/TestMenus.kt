package dev.xross.launcher

import dev.xross.launcher.state.MenuTree
import dev.xross.launcher.state.XmbNode

/** Settings ▸ Theme and its folders as one list (the folder rows included), top level first. */
fun MenuTree.themeRows(): List<XmbNode> = MenuTree.THEME_LISTS.flatMap(::list)
