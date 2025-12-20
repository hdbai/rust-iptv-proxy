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

# Ensure required credentials are present so the service can start
missing=""
[ -z "${IPTV_USER:-}" ] && missing="${missing} IPTV_USER"
[ -z "${IPTV_PASSWD:-}" ] && missing="${missing} IPTV_PASSWD"
[ -z "${IPTV_MAC:-}" ] && missing="${missing} IPTV_MAC"

if [ -n "$missing" ]; then
  echo "Missing required environment variables:${missing}" >&2
  echo "Please set IPTV_USER, IPTV_PASSWD, and IPTV_MAC when running the container." >&2
  exit 1
fi

# Start with any arguments passed directly to the container
set -- "$@"

# Track a redacted view of the arguments we will pass to the binary for easier debugging
pretty_args=""
append_pretty_arg() {
  pretty_args="${pretty_args} $1"
}
append_pretty_kv() {
  pretty_args="${pretty_args} $1 $2"
}

if [ -n "${IPTV_USER:-}" ]; then
  set -- "$@" -u "$IPTV_USER"
  append_pretty_kv -u "$IPTV_USER"
fi

if [ -n "${IPTV_PASSWD:-}" ]; then
  set -- "$@" -p "$IPTV_PASSWD"
  append_pretty_kv -p "********"
fi

if [ -n "${IPTV_MAC:-}" ]; then
  set -- "$@" -m "$IPTV_MAC"
  append_pretty_kv -m "$IPTV_MAC"
fi

if [ -n "${IPTV_IMEI:-}" ]; then
  set -- "$@" -i "$IPTV_IMEI"
  append_pretty_kv -i "$IPTV_IMEI"
fi

if [ -n "${IPTV_BIND:-}" ]; then
  set -- "$@" -b "$IPTV_BIND"
  append_pretty_kv -b "$IPTV_BIND"
fi

if [ -n "${IPTV_ADDRESS:-}" ]; then
  set -- "$@" -a "$IPTV_ADDRESS"
  append_pretty_kv -a "$IPTV_ADDRESS"
fi

if [ -n "${IPTV_INTERFACE:-}" ]; then
  set -- "$@" -I "$IPTV_INTERFACE"
  append_pretty_kv -I "$IPTV_INTERFACE"
fi

if [ -n "${IPTV_EXTRA_PLAYLIST:-}" ]; then
  set -- "$@" --extra-playlist "$IPTV_EXTRA_PLAYLIST"
  append_pretty_kv --extra-playlist "$IPTV_EXTRA_PLAYLIST"
fi

if [ -n "${IPTV_EXTRA_XMLTV:-}" ]; then
  set -- "$@" --extra-xmltv "$IPTV_EXTRA_XMLTV"
  append_pretty_kv --extra-xmltv "$IPTV_EXTRA_XMLTV"
fi

if is_true "${IPTV_ENABLE_UDP_PROXY:-}"; then
  set -- "$@" --udp-proxy
  append_pretty_arg --udp-proxy
fi

if is_true "${IPTV_ENABLE_RTSP_PROXY:-}"; then
  set -- "$@" --rtsp-proxy
  append_pretty_arg --rtsp-proxy
fi

if [ -n "$pretty_args" ]; then
  echo "Starting iptv with:$pretty_args"
else
  echo "Starting iptv with no extra CLI arguments"
fi

exec /usr/local/bin/iptv "$@"
