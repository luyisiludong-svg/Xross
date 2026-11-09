package dev.wave.launcher.input

/** Launcher-level intents produced by every input source (gamepad, keyboard, remote, touch). */
enum class NavAction {
    UP,
    DOWN,
    LEFT,
    RIGHT,
    CONFIRM,
    BACK,
    OPTIONS,
    PREV_CATEGORY,
    NEXT_CATEGORY,
    START;

    val isDirectional: Boolean
        get() = this == UP || this == DOWN || this == LEFT || this == RIGHT

    fun opposite(): NavAction? = when (this) {
        UP -> DOWN
        DOWN -> UP
        LEFT -> RIGHT
        RIGHT -> LEFT
        else -> null
    }
}

/** Which face button confirms. SOUTH = Xbox/PlayStation convention, EAST = Nintendo convention. */
enum class ConfirmLayout { AUTO, SOUTH, EAST }
