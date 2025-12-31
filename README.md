### Usage
```
Usage: iptv [OPTIONS] --user <USER> --passwd <PASSWD> --mac <MAC>

Options:
  -u, --user <USER>                      Login username
  -p, --passwd <PASSWD>                  Login password
  -m, --mac <MAC>                        MAC address
  -i, --imei <IMEI>                      IMEI [default: ]
  -b, --bind <BIND>                      Bind address:port [default: 0.0.0.0:7878]
  -a, --address <ADDRESS>                IP address/interface name [default: ]
  -I, --interface <INTERFACE>            Interface to request
      --extra-playlist <EXTRA_PLAYLIST>  Url to extra m3u
      --extra-xmltv <EXTRA_XMLTV>        Url to extra xmltv
      --udp-proxy                        Use UDP proxy
      --rtsp-proxy                       Use rtsp proxy
  -h, --help                             Print help
```

### Endpoints

- `/playlist`: m3u8 list
- `/xmltv`: EGP

### Docker environment variables

When running the Docker image, you can pass configuration through environment variables instead of CLI flags. The runtime entrypoint turns the variables into the corresponding CLI flags before starting the binary (for example, `IPTV_USER=foo IPTV_MAC=aa:bb:cc` becomes `iptv -u foo -m aa:bb:cc`). The entrypoint prints the redacted command-line arguments it computed to the container logs so you can confirm your environment variables were picked up by `docker run -e ...`. Only set the values you need:

- `IPTV_USER` / `IPTV_PASSWD` / `IPTV_MAC`: credentials for the upstream service (required by the binary; the container will exit with an error if they are missing)
- `IPTV_IMEI`: optional IMEI value
- `IPTV_BIND`: bind address (defaults to `0.0.0.0:7878` in the container)
- `IPTV_ADDRESS`: source IP/interface name for requests
- `IPTV_INTERFACE`: interface for the proxy to use
- `IPTV_EXTRA_PLAYLIST` / `IPTV_EXTRA_XMLTV`: URLs for additional playlists or XMLTV data
- `IPTV_ENABLE_UDP_PROXY` and `IPTV_ENABLE_RTSP_PROXY`: set to `true`/`1` to enable the corresponding proxy

Example:

```sh
docker run --rm -p 7878:7878 \
  -e IPTV_USER=myuser \
  -e IPTV_PASSWD=mypassword \
  -e IPTV_MAC=00:11:22:33:44:55 \
  -e IPTV_BIND=0.0.0.0:7878 \
  ghcr.io/your-org/iptv:latest

# Check the translated flags (password is redacted) to ensure your environment values were applied
docker logs <container_name>
```

### Example init.d

```sh
#!/bin/sh /etc/rc.common

START=99
STOP=99

MAC=
USER=
PASSWD=
INTERFACE=pppoe-iptv
BIND=0.0.0.0:7878

start() {
        ( /usr/bin/java -jar /usr/local/bin/iptv-proxy.jar -u $USER -p $PASSWD -m $MAC -b $BIND -I $INTERFACE --udp-proxy --rtsp-proxy 2>&1 & echo $! >&3 ) 3>/var/run/iptv.pid | logger -t "iptv-proxy" &
}

stop() {
        if [ -f /var/run/iptv.pid ]; then
                kill -9 $(cat /var/run/iptv.pid) 2>/dev/null
                rm -f /var/run/iptv.pid
        fi
}
```

### Build
```bash
mvn -DskipTests package
java -jar target/iptv-proxy-0.1.0.jar --help
```
