#!/bin/sh
set -e

is_true() {
  case "${1:-}" in
    1|true|TRUE|yes|YES|on|ON|y|Y)
      return 0
      ;;
    *)
      return 1
      ;;
  esac
}

# Start with any arguments passed directly to the container
set -- "$@"

if [ -n "${IPTV_USER:-}" ]; then
  set -- -u "$IPTV_USER" "$@"
fi

if [ -n "${IPTV_PASSWD:-}" ]; then
  set -- -p "$IPTV_PASSWD" "$@"
fi

if [ -n "${IPTV_MAC:-}" ]; then
  set -- -m "$IPTV_MAC" "$@"
fi

if [ -n "${IPTV_IMEI:-}" ]; then
  set -- -i "$IPTV_IMEI" "$@"
fi

if [ -n "${IPTV_BIND:-}" ]; then
  set -- -b "$IPTV_BIND" "$@"
fi

if [ -n "${IPTV_ADDRESS:-}" ]; then
  set -- -a "$IPTV_ADDRESS" "$@"
fi

if [ -n "${IPTV_INTERFACE:-}" ]; then
  set -- -I "$IPTV_INTERFACE" "$@"
fi

if [ -n "${IPTV_EXTRA_PLAYLIST:-}" ]; then
  set -- --extra-playlist "$IPTV_EXTRA_PLAYLIST" "$@"
fi

if [ -n "${IPTV_EXTRA_XMLTV:-}" ]; then
  set -- --extra-xmltv "$IPTV_EXTRA_XMLTV" "$@"
fi

if is_true "${IPTV_ENABLE_UDP_PROXY:-}"; then
  set -- --udp-proxy "$@"
fi

if is_true "${IPTV_ENABLE_RTSP_PROXY:-}"; then
  set -- --rtsp-proxy "$@"
fi

exec /usr/local/bin/iptv "$@"
