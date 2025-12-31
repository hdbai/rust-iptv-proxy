package com.iptvproxy.web;

import com.iptvproxy.IptvArgs;
import com.iptvproxy.model.Channel;
import com.iptvproxy.service.IptvService;
import com.iptvproxy.service.ProxyService;
import com.iptvproxy.service.XmltvFormatter;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping
public class IptvController {
    private static final Logger logger = LoggerFactory.getLogger(IptvController.class);

    private final IptvArgs args;
    private final IptvService iptvService;
    private final XmltvFormatter xmltvFormatter;
    private final ProxyService proxyService;
    private final AtomicReference<String> oldPlaylist = new AtomicReference<>();
    private final AtomicReference<String> oldXmltv = new AtomicReference<>();

    public IptvController(IptvArgs args, IptvService iptvService, XmltvFormatter xmltvFormatter,
                          ProxyService proxyService) {
        this.args = args;
        this.iptvService = iptvService;
        this.xmltvFormatter = xmltvFormatter;
        this.proxyService = proxyService;
    }

    @GetMapping(value = "/xmltv", produces = MediaType.TEXT_XML_VALUE)
    public Mono<ResponseEntity<String>> xmltv(ServerHttpRequest request) {
        logger.debug("Get EPG");
        String scheme = request.getURI().getScheme();
        String host = host(request);
        Mono<String> extra = args.extraXmltv() == null
            ? Mono.just("")
            : iptvService.fetchExtraXmltv(args.extraXmltv()).onErrorReturn("");

        return iptvService.getChannels(true, scheme, host)
            .zipWith(extra)
            .map(tuple -> xmltvFormatter.buildXmltv(tuple.getT1(), tuple.getT2()))
            .map(xml -> {
                oldXmltv.set(xml);
                return ResponseEntity.ok().contentType(MediaType.TEXT_XML).body(xml);
            })
            .onErrorResume(error -> {
                String fallback = oldXmltv.get();
                if (fallback != null) {
                    return Mono.just(ResponseEntity.ok().contentType(MediaType.TEXT_XML).body(fallback));
                }
                return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error getting channels: " + error.getMessage()));
            });
    }

    @GetMapping(value = "/logo/{id}.png", produces = MediaType.IMAGE_PNG_VALUE)
    public Mono<ResponseEntity<byte[]>> logo(@PathVariable("id") String id) {
        logger.debug("Get logo");
        return iptvService.fetchIcon(id)
            .map(bytes -> ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(bytes))
            .onErrorResume(error -> Mono.just(ResponseEntity.status(HttpStatus.NOT_FOUND)
                .contentType(MediaType.TEXT_PLAIN)
                .body(("Error getting channels: " + error.getMessage()).getBytes())));
    }

    @GetMapping(value = "/playlist", produces = "application/vnd.apple.mpegurl")
    public Mono<ResponseEntity<String>> playlist(ServerHttpRequest request) {
        logger.debug("Get playlist");
        String scheme = request.getURI().getScheme();
        String host = host(request);

        Mono<String> extra = args.extraPlaylist() == null
            ? Mono.just("")
            : iptvService.fetchExtraPlaylist(args.extraPlaylist()).onErrorReturn("");

        return iptvService.getChannels(false, scheme, host)
            .zipWith(extra)
            .map(tuple -> buildPlaylist(tuple.getT1(), tuple.getT2(), scheme, host))
            .map(playlist -> {
                oldPlaylist.set(playlist);
                return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/vnd.apple.mpegurl"))
                    .body(playlist);
            })
            .onErrorResume(error -> {
                String fallback = oldPlaylist.get();
                if (fallback != null) {
                    return Mono.just(ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType("application/vnd.apple.mpegurl"))
                        .body(fallback));
                }
                return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error getting channels: " + error.getMessage()));
            });
    }

    @GetMapping(value = "/rtsp/{tail:.*}", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<Flux<DataBuffer>> rtsp(@PathVariable("tail") String tail, ServerHttpRequest request) {
        String query = request.getURI().getQuery();
        String url = "rtsp://" + tail + (query == null || query.isBlank() ? "" : "?" + query);
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .header("Cache-Control", "no-store")
            .body(proxyService.rtsp(url));
    }

    @GetMapping(value = "/udp/{addr}", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<Flux<DataBuffer>> udp(@PathVariable("addr") String addr) {
        InetSocketAddress socketAddress;
        try {
            String[] parts = addr.split(":", 2);
            socketAddress = new InetSocketAddress(parts[0], Integer.parseInt(parts[1]));
        } catch (Exception e) {
            String message = "Error: " + e.getMessage();
            DataBuffer buffer = new DefaultDataBufferFactory().wrap(message.getBytes());
            return ResponseEntity.badRequest()
                .contentType(MediaType.TEXT_PLAIN)
                .body(Flux.just(buffer));
        }
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .header("Cache-Control", "no-store")
            .body(proxyService.udp(socketAddress));
    }

    private String buildPlaylist(List<Channel> channels, String extra, String scheme, String host) {
        StringBuilder sb = new StringBuilder("#EXTM3U\n");
        for (Channel channel : channels) {
            String group;
            if (channel.name().contains("超清")) {
                group = "超清频道";
            } else if (channel.name().contains("高清")) {
                group = "高清频道";
            } else {
                group = "普通频道";
            }
            String catchUp = " catchup=\"append\" catchup-source=\"?playseek=${(b)yyyyMMddHHmmss}-${(e)yyyyMMddHHmmss}\" ";
            sb.append(String.format(
                "#EXTINF:-1 tvg-id=\"%d\" tvg-name=\"%s\" tvg-chno=\"%d\"%stvg-logo=\"%s://%s/logo/%d.png\" group-title=\"%s\",%s%n",
                channel.id(),
                channel.name(),
                channel.id(),
                catchUp,
                scheme,
                host,
                channel.id(),
                group,
                channel.name()));
            String stream = args.udpProxy()
                ? (channel.igmp() == null ? channel.rtsp() : channel.igmp())
                : channel.rtsp();
            sb.append(stream).append('\n');
        }
        if (extra != null && !extra.isBlank()) {
            sb.append(extra);
        }
        return sb.toString();
    }

    private String host(ServerHttpRequest request) {
        var host = request.getHeaders().getHost();
        if (host == null) {
            return request.getURI().getAuthority();
        }
        return host.getPort() == -1 ? host.getHostString() : host.getHostString() + ":" + host.getPort();
    }
}
