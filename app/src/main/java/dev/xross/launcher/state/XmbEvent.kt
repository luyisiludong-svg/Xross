package dev.xross.launcher.state

import dev.xross.launcher.input.NavAction

/**
 * Everything the user does on the menu screen, as one type: `XmbScreen` and the boot overlay hand
 * each one to [XmbViewModel.onEvent], which passes it to whoever owns it.
 *
 * The cases are grouped by owner. [Nav] and [Menu] are the view model's (the menu itself: the
 * category and item columns, Options, Information, Details, remapping, the mini player); every
 * other group belongs to one controller, which handles it in its own `onEvent`. A new panel adds a
 * case to its owner's group and a branch in that owner's `onEvent`, not a parameter on `XmbScreen`
 * or a lambda in `MainActivity`.
 *
 * What the screen reports about itself rather than asks for (its backdrop covering the screen, a UI
 * filter that failed, where the crosshair landed) is not an event: those go to the Activity as
 * `ScreenSignal`s (`ui/`), since the view model has no say in them.
 */
sealed interface XmbEvent {

    /** A D-pad, gamepad, key or swipe action: the same path as hardware input. */
    data class Nav(val action: NavAction) : XmbEvent

    /** The menu itself; handled by [XmbViewModel]. */
    sealed interface Menu : XmbEvent {
        data class CategoryTap(val index: Int) : Menu
        data class ItemTap(val index: Int) : Menu
        data class ItemLongPress(val index: Int) : Menu
        data object CancelRemap : Menu
        data class OptionTap(val index: Int) : Menu
        data object CloseOptions : Menu
        data class InfoRowTap(val index: Int) : Menu
        data object CloseInfo : Menu
        data object PlayDetails : Menu
        data object DetailsAchievements : Menu
        data object CloseDetails : Menu
        data object MiniPlayerTap : Menu
        data object MiniPlayerLongPress : Menu
    }

    /** The search keyboard and its results, and the name keyboard; handled by [SearchController]. */
    sealed interface Typing : XmbEvent {
        data class SearchKeyTap(val row: Int, val column: Int) : Typing
        data class SearchResultTap(val index: Int) : Typing
        data class NameKeyTap(val row: Int, val column: Int) : Typing
    }

    /** Wave settings and Profile customization; handled by [EditorController]. */
    sealed interface Editor : XmbEvent {
        data class WaveRowTap(val index: Int) : Editor
        data class WaveSlider(val index: Int, val fraction: Float) : Editor
        data object CloseWave : Editor
        data class AvatarRowTap(val index: Int) : Editor
        data class AvatarSlider(val index: Int, val fraction: Float) : Editor
        data object CloseAvatar : Editor
    }

    /** The Metadata & Media editor and its media picker; handled by [MetadataEditorController]. */
    sealed interface MetadataEditor : XmbEvent {
        data class RowTap(val index: Int) : MetadataEditor
        data class PickerTap(val index: Int) : MetadataEditor
        data object ClosePicker : MetadataEditor
        data object Close : MetadataEditor
    }

    /** The Layout panel; handled by [ThemeController]. */
    sealed interface Layout : XmbEvent {
        data class RowTap(val index: Int) : Layout
        data class Slider(val index: Int, val fraction: Float) : Layout
        data object Close : Layout
    }

    /**
     * The RetroAchievements screens: the avatar and its HUD, the profile card, the social stack and
     * its panel, the hub and the achievements grid; handled by [RaController].
     */
    sealed interface Ra : XmbEvent {
        data object AvatarTap : Ra
        data object CloseAvatarHud : Ra
        data class ProfileCardButtonTap(val index: Int) : Ra
        data object CloseProfileCard : Ra
        data object SocialTap : Ra
        data object CloseSocialPanel : Ra
        data class HubPillTap(val index: Int) : Ra
        data class HubRowTap(val index: Int) : Ra
        data object CloseHub : Ra
        data class AchievementTap(val index: Int) : Ra
        /** The achievements grid laid out [columns] badges to a row (for D-pad moves). */
        data class AchievementsColumns(val columns: Int) : Ra
        data object CloseAchievements : Ra
    }

    /** The cold-start intro; handled by [TransitionController]. */
    sealed interface Boot : XmbEvent {
        data object Skip : Boot
        data object VideoEnded : Boot
    }
}
