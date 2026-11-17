# Changelog

## 0.2.0.2-beta — 2026-11-17

- **Surface styles work.** Flat glass, Y2K glossy bezel, OLED true black and E-Ink high contrast
  now change the Options panel, the Information sheet, toasts, the mini player and the button-hint
  bars. Before, only a few rarely seen elements used the chosen style, so the styles looked the same.

## 0.2.0.1-beta — 2026-11-17

First beta.

- **Theme packs:** browser preview for background shaders and packs; the classic look ships as the
  built-in Default (XMB) pack, Phosphor as "Xross"; MECHA shader; Phosphor icons and synthesized UI
  sounds.
- **ScreenScraper sign-in moved to Settings ▸ Accounts.** Enter your own screenscraper.fr user name
  and password to use your own daily quota; saving checks the login and shows today's quota, and a
  blank user name signs out. Settings ▸ Library ▸ Metadata & artwork links to it.
- **ScreenScraper works without setup:** release builds carry Xross's developer credentials, masked
  in the APK.
- **Release builds** are signed from `.env` and refuse to build without ScreenScraper developer
  credentials.
