#!/usr/bin/env bash
set -euo pipefail

package=com.locket.app
case "${1:-}" in
  reset) adb shell dumpsys gfxinfo "$package" reset >/dev/null ;;
  sample)
    # Keep frame timing only, without a hierarchy dump, logcat or app records.
    adb shell dumpsys gfxinfo "$package" framestats | awk '
      /^(Total frames rendered|Janky frames|[0-9]+th percentile)/ { print }
      /---PROFILEDATA---/ { print; profile = !profile; next }
      profile { print }
    '
    adb shell dumpsys meminfo "$package" | awk '/TOTAL PSS:|TOTAL RSS:|Native Heap|Dalvik Heap/ { print }'
    ;;
  *) echo "Usage: $0 reset|sample" >&2; exit 2 ;;
esac
