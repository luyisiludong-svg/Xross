#!/usr/bin/env bash
# Library suite (41 checks): platform auto-detection, .cue/.bin pairing, title cleaning,
# favorites, per-game emulator override, search, game tiles and Details paging, category
# navigation, rescan (added and deleted files) and background fingerprinting.
#
#   tools/device-tests/test-library.sh
#
# WARNING: clears Xross's app data and replaces /sdcard/ROMs with test files (see lib/harness.sh).
set -u
. "$(dirname "$0")/lib/harness.sh"
suite_begin library 41

setup_base_env
start_app

section "Library and navigation"
expect "All Games" "platforms are grouped in All Games"
expect "3 systems" "All Games counts the detected platforms"
expect_not "Recently Played" "no Recently Played before anything is launched"
key KEYCODE_DPAD_CENTER
expect "Super Nintendo" "auto-detected /sdcard/ROMs and listed platforms"
expect "Game Boy Advance" "second platform listed"
key KEYCODE_DPAD_CENTER
expect "Chrono Trigger" "entering a platform lists its games (titles cleaned)"
expect "Super Metroid" "all games in the platform are listed"
key KEYCODE_BACK
press DPAD_DOWN 2 KEYCODE_DPAD_CENTER
expect "Vagrant Story" "cue sheet listed"
expect "CUE" "the .bin track is hidden behind its .cue (focused entry is the .cue)"
key KEYCODE_BACK KEYCODE_BACK

section "Favorites"
# Game Options menu rows: Play, Details, Achievements (systems with RetroAchievements), Add to
# favorites, Emulator, (Played), Metadata, Information.
all_games
key KEYCODE_DPAD_CENTER
expect "Options" "Options hint is shown for a game"
key KEYCODE_BUTTON_Y
expect "Add to favorites" "Options button opens the game's menu"
expect "Details" "Options menu offers Details"
expect "Achievements" "Options menu offers Achievements (SNES has RetroAchievements)"
expect "Metadata" "Options menu offers Metadata"
key KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN KEYCODE_DPAD_CENTER
# Unlinked it asks to link; linked (an account can outlive pm clear), the test ROM is unknown.
expect_any "Link your RetroAchievements account" "doesn't recognize this file" "Achievements screen opens"
key KEYCODE_BACK
expect_not "check the game's page on retroachievements.org" "Back closes the Achievements screen"
expect_not "Link your RetroAchievements account" "Back closes the Achievements screen (unlinked text gone)"
key KEYCODE_BUTTON_Y
key KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN KEYCODE_DPAD_CENTER
pause 0.6
key KEYCODE_BUTTON_Y
expect "Remove from favorites" "menu toggles favorite"

section "Per-game emulator"
key KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN KEYCODE_DPAD_CENTER
expect "Platform default" "Emulator submenu lists the platform default"
key KEYCODE_DPAD_DOWN KEYCODE_DPAD_CENTER
pause 0.6
key KEYCODE_BUTTON_Y
expect "this game only" "per-game emulator override is saved and shown"
key KEYCODE_BACK      # close the menu
key KEYCODE_BACK      # platform list → All Games
key KEYCODE_BACK      # All Games → Games root
top
expect "Favorites" "Favorites collection appears in Games"

section "Search"
key KEYCODE_DPAD_CENTER
expect "Type to search your library" "Search opens from the top of Games"
adb shell input keyevent KEYCODE_M KEYCODE_E KEYCODE_T
pause 1
expect "Super Metroid" "typing finds games by title prefix"
expect "1 game" "result count is shown"
key KEYCODE_BUTTON_R1
expect "Keyboard" "R1 moves focus to the results"
key KEYCODE_BACK KEYCODE_BACK
expect "Find a game by name" "Back returns to the keys, then closes search"

section "Game tiles and Details paging"
all_games 1 # Favorites sits above All Games now
key KEYCODE_DPAD_CENTER
expect "Chrono Trigger" "Super Nintendo lists its games"
expect "Never played" "game rows show playtime instead of the file size"
key KEYCODE_DPAD_RIGHT
expect "Game 1 of 3" "Right opens Details with its position in the list"
key KEYCODE_BUTTON_R1
expect "Game 2 of 3" "R1 pages to the next game"
key KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT
expect "Game 3 of 3" "Right pages too and stops at the last game"
key KEYCODE_BUTTON_L1
expect "Game 2 of 3" "L1 pages back"
key KEYCODE_BACK
expect_not "Game 2 of 3" "Back closes Details"
key KEYCODE_DPAD_RIGHT
expect "Game 2 of 3" "the list cursor followed the paging"
key KEYCODE_BACK KEYCODE_BACK KEYCODE_BACK

section "Categories"
home
key KEYCODE_BUTTON_R1
expect_not "All Games" "R1 moves to the Apps category"
press BUTTON_L1 5
expect "Library" "L1 moves to Settings"
press DPAD_DOWN 4
expect "Sound" "Settings lists the Sound section"
top

section "Rescan"
# Library rows: All files access, Add ROM folder…, Base ROMs folder…, Create system folders, Rescan library, …
category 0
key KEYCODE_DPAD_CENTER
expect "Base ROMs folder" "Library shows the base ROMs folder"
adb shell 'echo x > "/sdcard/ROMs/gba/Metroid Fusion (USA).gba"'
press DPAD_DOWN 4 KEYCODE_DPAD_CENTER
pause 1.2
expect "+1" "rescan picks up a new file"
adb shell 'rm "/sdcard/ROMs/gba/Metroid Fusion (USA).gba"'
key KEYCODE_DPAD_CENTER
pause 1.2
expect "−1" "rescan drops a deleted file"
key KEYCODE_DPAD_DOWN
pause 3
expect "identified by content" "background fingerprinting reports identified games"

suite_end
