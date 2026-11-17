# Synthesized UI sounds

Made from scratch by `tools/audio/synth_ui_sounds.py` (numpy only, no samples), so they belong to
the project. They are not used by the app yet: the Default pack still plays the sounds in
`app/src/main/assets/themes/default/sounds/`. To regenerate them:

    python3 tools/audio/synth_ui_sounds.py assets/sounds/synth

File names match the SoundRole file names, except `opening.wav`, which plays as `01. opening.wav`.
