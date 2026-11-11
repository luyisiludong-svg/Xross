package dev.xross.launcher.state

/** One level of a category's drill-down stack. */
data class Frame(val key: String, val title: String, val index: Int = 0)

/**
 * Pure navigation state: which category is focused and, per category, the stack of opened
 * lists with their cursor positions (so switching categories keeps your place).
 */
data class NavState(
    val categoryIndex: Int,
    val stacks: Map<String, List<Frame>> = emptyMap(),
) {
    fun categoryId(categories: List<XmbCategory>): String = categories[categoryIndex].id

    fun stack(categoryId: String, rootTitle: String): List<Frame> =
        stacks[categoryId] ?: listOf(Frame(Categories.rootKey(categoryId), rootTitle))

    fun withStack(categoryId: String, stack: List<Frame>) =
        copy(stacks = stacks + (categoryId to stack))

    fun moveCategory(delta: Int, count: Int) =
        copy(categoryIndex = (categoryIndex + delta).coerceIn(0, count - 1))

    fun moveCursor(categoryId: String, rootTitle: String, delta: Int, listSize: Int): NavState {
        if (listSize == 0) return this
        val stack = stack(categoryId, rootTitle)
        val top = stack.last()
        val next = (top.index.coerceIn(0, listSize - 1) + delta).coerceIn(0, listSize - 1)
        return withStack(categoryId, stack.dropLast(1) + top.copy(index = next))
    }

    fun push(categoryId: String, rootTitle: String, frame: Frame) =
        withStack(categoryId, stack(categoryId, rootTitle) + frame)

    /** Returns null when already at the category root. */
    fun pop(categoryId: String, rootTitle: String): NavState? {
        val stack = stack(categoryId, rootTitle)
        return if (stack.size <= 1) null else withStack(categoryId, stack.dropLast(1))
    }

    fun jumpTo(categoryIndex: Int, categoryId: String, rootTitle: String, frame: Frame?): NavState {
        val root = listOf(Frame(Categories.rootKey(categoryId), rootTitle))
        return copy(categoryIndex = categoryIndex)
            .withStack(categoryId, if (frame == null) root else root + frame)
    }
}
