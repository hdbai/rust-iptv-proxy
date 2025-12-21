#!/bin/sh
set -eu

orig_args="$*"
set -- /usr/local/bin/iptv

if [ -n "${IPTV_USER:-}" ]; then
    set -- "$@" "-u" "$IPTV_USER"
fi
if [ -n "${IPTV_PASSWD:-}" ]; then
    set -- "$@" "-p" "$IPTV_PASSWD"
fi
if [ -n "${IPTV_MAC:-}" ]; then
    set -- "$@" "-m" "$IPTV_MAC"
fi
if [ -n "${IPTV_IMEI:-}" ]; then
    set -- "$@" "-i" "$IPTV_IMEI"
fi
if [ -n "${IPTV_BIND:-}" ]; then
    set -- "$@" "-b" "$IPTV_BIND"
fi
if [ -n "${IPTV_ADDRESS:-}" ]; then
    set -- "$@" "-a" "$IPTV_ADDRESS"
fi
if [ -n "${IPTV_INTERFACE:-}" ]; then
    set -- "$@" "-I" "$IPTV_INTERFACE"
fi
if [ -n "${IPTV_EXTRA_PLAYLIST:-}" ]; then
    set -- "$@" "--extra-playlist" "$IPTV_EXTRA_PLAYLIST"
fi
if [ -n "${IPTV_EXTRA_XMLTV:-}" ]; then
    set -- "$@" "--extra-xmltv" "$IPTV_EXTRA_XMLTV"
fi

case "${IPTV_ENABLE_UDP_PROXY:-}" in
    1|true|TRUE|True)
        set -- "$@" "--udp-proxy"
        ;;
esac

case "${IPTV_ENABLE_RTSP_PROXY:-}" in
    1|true|TRUE|True)
        set -- "$@" "--rtsp-proxy"
        ;;
esac

if [ -n "$orig_args" ]; then
    set -- "$@" $orig_args
fi

redacted=""
skip_next=0
for arg in "$@"; do
    if [ "$skip_next" -eq 1 ]; then
        redacted="$redacted [REDACTED]"
        skip_next=0
        continue
    fi
    case "$arg" in
        -p|--passwd)
            redacted="$redacted $arg"
            skip_next=1
            ;;
        *)
            redacted="$redacted $arg"
            ;;
    esac
done

redacted=${redacted# }
echo "Starting: $redacted"

exec "$@"
