#!/usr/bin/env bash
# Simple extractor test for incestflix.com watch pages
# Usage:
#   ./test_incestflix_extraction.sh "https://www.incestflix.com/watch/<slug>"
# Prints the poster and video src found in <video id="incflix-player"> and common fallbacks.

set -euo pipefail

if [[ $# -lt 1 ]]; then
  echo "Usage: $0 <watch_url>" >&2
  exit 1
fi

URL="$1"
USER_AGENT="Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36"

fetch() {
  curl -fsSL -A "$USER_AGENT" "$1"
}

normalize_url() {
  local u="$1"
  if [[ -z "$u" ]]; then echo ""; return; fi
  if [[ "$u" =~ ^// ]]; then
    echo "https:${u}"
  elif [[ "$u" =~ ^https?:// ]]; then
    echo "$u"
  else
    # If relative, join with base domain
    local base
    base="https://www.incestflix.com"
    if [[ "$u" =~ ^/ ]]; then
      echo "${base}${u}"
    else
      echo "$u"
    fi
  fi
}

HTML="$(fetch "$URL")"

# 1) Priority: dedicated player
VIDEO_BLOCK="$(printf '%s' "$HTML" | tr '\n' ' ' | grep -oP '<video[^>]*id=["\']incflix-player["\'][^>]*>.*?</video>' || true)"
POSTER=""
SRC=""
if [[ -n "$VIDEO_BLOCK" ]]; then
  POSTER_RAW="$(printf '%s' "$VIDEO_BLOCK" | grep -oP 'poster=\"[^\"]+\"|poster=\'[^\']+\'' | head -n1 | sed -E 's/^poster=["\'\'\"](.*)["\'\'\"]$/\1/')"
  SRC_RAW="$(printf '%s' "$VIDEO_BLOCK" | grep -oP '<source[^>]*src=["\']([^"\']+)["\']' | head -n1 | sed -E 's/.*src=["\'\'\"]([^"\'\"]+).*/\1/')"
  POSTER="$(normalize_url "$POSTER_RAW")"
  SRC="$(normalize_url "$SRC_RAW")"
fi

# 2) Fallbacks if empty
if [[ -z "$SRC" ]]; then
  SRC="$(printf '%s' "$HTML" | tr '\n' ' ' | grep -oP '<video[^>]*src=["\']([^"\']+)["\']' | head -n1 | sed -E 's/.*src=["\'\'\"]([^"\'\"]+).*/\1/')"
  SRC="$(normalize_url "$SRC")"
fi

if [[ -z "$SRC" ]]; then
  SRC="$(printf '%s' "$HTML" | grep -oP 'https?://[^ "\']+\.(m3u8|mp4)' | head -n1)"
fi

if [[ -z "$POSTER" ]]; then
  POSTER="$(printf '%s' "$HTML" | grep -oP 'property=["\']og:image["\'][^>]*content=["\']([^"\']+)["\']' | sed -E 's/.*content=["\'\'\"]([^"\'\"]+).*/\1/' | head -n1)"
  POSTER="$(normalize_url "$POSTER")"
fi

printf 'Watch URL : %s\n' "$URL"
printf 'Poster    : %s\n' "${POSTER:-}"
printf 'Video src : %s\n' "${SRC:-}"

if [[ -z "$SRC" ]]; then
  echo "[WARN] No video source found" >&2
  exit 2
fi

exit 0
