#!/usr/bin/env bash
# Re-levels the Default pack's UI sounds (Phase 2b.5a, requirement 7) from their sources in
# assets/sounds/ and copies them into app/src/main/assets/themes/default/sounds/.
#
#   Short effects (< 200 ms, and ok.wav at 300 ms, too short for a LUFS reading):
#     peak-normalized to -3 dBFS.
#   Longer effects and cues (system_ng, trophy, opening, gameboot):
#     -16 LUFS integrated, lowered further if needed so the true peak stays <= -1 dBTP.
#
# The balance between roles is set by the pack's audio.soundGains (which can only lower a
# sample), not by leaving files quiet. Run from the repository root; needs ffmpeg. Gains are
# computed from the original levels, so running it twice gives the same files.
#
#   tools/audio/relevel-default-sounds.sh          re-level and copy, then print the table
#   tools/audio/relevel-default-sounds.sh --check  print the table for the pack's files only
set -euo pipefail

SRC=assets/sounds
DST=app/src/main/assets/themes/default/sounds
PEAK_TARGET=-3.0
LUFS_TARGET=-16.0
TP_CEILING=-1.0

# source file -> pack file -> mode (peak | lufs)
MAP=(
  "snd_option.wav|cursor.wav|peak"
  "snd_cancel.wav|back.wav|peak"
  "snd_system_ok.wav|ok.wav|peak"
  "snd_system_ng.wav|system_ng.wav|lufs"
  "snd_trophy.wav|trophy.wav|lufs"
  "01. Opening.wav|01. opening.wav|lufs"
  "gameboot_stereo.wav|gameboot.wav|lufs"
)

peak() { ffmpeg -nostats -hide_banner -i "$1" -af volumedetect -f null - 2>&1 | sed -n 's/.*max_volume: \(-*[0-9.]*\) dB/\1/p'; }
ebur() { ffmpeg -nostats -hide_banner -i "$1" -af ebur128=peak=true -f null - 2>&1; }
lufs() { ebur "$1" | awk '/Integrated loudness:/{f=1} f&&/I:/{print $2; exit}'; }
truepeak() { ebur "$1" | awk '/True peak:/{f=1} f&&/Peak:/{print $2; exit}'; }
duration() { ffprobe -v error -show_entries format=duration -of csv=p=0 "$1"; }

level() { # src dst mode
  local src=$1 dst=$2 mode=$3 gain tmp
  if [[ $mode == peak ]]; then
    gain=$(awk -v t=$PEAK_TARGET -v p="$(peak "$src")" 'BEGIN{printf "%.2f", t-p}')
  else
    gain=$(awk -v t=$LUFS_TARGET -v i="$(lufs "$src")" 'BEGIN{printf "%.2f", t-i}')
  fi
  local codec rate
  codec=$(ffprobe -v error -select_streams a:0 -show_entries stream=codec_name -of csv=p=0 "$src")
  rate=$(ffprobe -v error -select_streams a:0 -show_entries stream=sample_rate -of csv=p=0 "$src")
  tmp=$(mktemp -t relevel).wav
  ffmpeg -nostats -hide_banner -loglevel error -y -i "$src" -af "volume=${gain}dB" -c:a "$codec" -ar "$rate" -map_metadata -1 -fflags +bitexact "$tmp"
  if [[ $mode == lufs ]]; then
    # Keep the true peak under the ceiling: take back whatever it overshoots by.
    local over
    over=$(awk -v c=$TP_CEILING -v p="$(truepeak "$tmp")" 'BEGIN{d=p-c; printf "%.2f", (d>0?d+0.05:0)}')
    if [[ $over != 0.00 ]]; then
      gain=$(awk -v g="$gain" -v o="$over" 'BEGIN{printf "%.2f", g-o}')
      ffmpeg -nostats -hide_banner -loglevel error -y -i "$src" -af "volume=${gain}dB" -c:a "$codec" -ar "$rate" -map_metadata -1 -fflags +bitexact "$tmp"
    fi
  fi
  mv "$tmp" "$dst"
  echo "$gain"
}

table() {
  printf '| %-16s | %8s | %10s | %10s | %10s |\n' File "Length" "Peak dBFS" "True peak" "LUFS (I)"
  printf '|%s|%s|%s|%s|%s|\n' ------------------ ---------- ------------ ------------ ------------
  for entry in "${MAP[@]}"; do
    IFS='|' read -r _ dst _ <<<"$entry"
    local f="$DST/$dst" i
    i=$(lufs "$f")
    [[ $i == -70.0 ]] && i="n/a (short)"
    printf '| %-16s | %6.0f ms | %10s | %10s | %10s |\n' "$dst" "$(awk -v d="$(duration "$f")" 'BEGIN{print d*1000}')" "$(peak "$f")" "$(truepeak "$f")" "$i"
  done
}

if [[ ${1:-} != --check ]]; then
  # A first run keeps the untouched originals, so the gains are always computed from them.
  ORIG=$SRC/original
  mkdir -p "$ORIG"
  for entry in "${MAP[@]}"; do
    IFS='|' read -r src dst mode <<<"$entry"
    [[ -f "$ORIG/$src" ]] || cp "$SRC/$src" "$ORIG/$src"
    gain=$(level "$ORIG/$src" "$SRC/$src" "$mode")
    cp "$SRC/$src" "$DST/$dst"
    echo "$dst: ${gain} dB ($mode)" >&2
  done
fi
table
