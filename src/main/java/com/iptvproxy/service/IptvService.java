package com.iptvproxy.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.iptvproxy.IptvArgs;
import com.iptvproxy.model.Channel;
import com.iptvproxy.model.Program;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import java.net.InetAddress;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

@Service
public class IptvService {
    private static final Logger logger = LoggerFactory.getLogger(IptvService.class);
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration BASE_URL_TTL = Duration.ofHours(6);
    private static final Duration CHANNELS_TTL = Duration.ofMinutes(10);
    private static final Duration EPG_TTL = Duration.ofMinutes(30);
    private static final int EPG_CONCURRENCY = 12;
    private static final long DAY_MS = 86_400_000L;
    private static final Pattern CHANNEL_PATTERN =
        Pattern.compile("Authentication\\.CTCSetConfig\\('Channel','(.+?)'\\)");

    private final WebClient webClient;
    private final IptvArgs args;
    private final AtomicReference<CacheEntry<String>> baseUrl = new AtomicReference<>();
    private final AtomicReference<CacheEntry<ChannelCache>> channelsCache = new AtomicReference<>();
    private final AtomicReference<CacheEntry<ChannelCache>> epgCache = new AtomicReference<>();
    private final AtomicReference<String> cookieHeader = new AtomicReference<>();

    public IptvService(IptvArgs args) {
        this.args = args;
        this.webClient = buildClient(args);
    }

    public WebClient client() {
        return webClient;
    }

    public Mono<List<Channel>> getChannels(boolean needEpg, String scheme, String host) {
        CacheEntry<ChannelCache> epg = epgCache.get();
        if (needEpg && epg != null && epg.isValid() && epg.value.matches(scheme, host)) {
            return Mono.just(epg.value.channels);
        }
        CacheEntry<ChannelCache> channels = channelsCache.get();
        Mono<List<Channel>> channelsMono;
        if (channels != null && channels.isValid() && channels.value.matches(scheme, host)) {
            channelsMono = Mono.just(channels.value.channels);
        } else {
            channelsMono = getBaseUrl()
                .flatMap(base -> fetchChannels(base, scheme, host)
                    .map(fetched -> {
                        ChannelCache channelCache = new ChannelCache(scheme, host, fetched);
                        channelsCache.set(new CacheEntry<>(channelCache, CHANNELS_TTL));
                        return fetched;
                    }));
        }

        if (!needEpg) {
            return channelsMono;
        }

        return getBaseUrl()
            .flatMap(base -> channelsMono.flatMap(list -> fetchEpg(base, list)
                .map(updated -> {
                    ChannelCache epgChannelCache = new ChannelCache(scheme, host, updated);
                    epgCache.set(new CacheEntry<>(epgChannelCache, EPG_TTL));
                    return updated;
                })));
    }

    public Mono<String> getBaseUrl() {
        CacheEntry<String> cached = baseUrl.get();
        if (cached != null && cached.isValid()) {
            return Mono.just(cached.value);
        }

        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("Action", "Login");
        params.add("return_type", "1");
        params.add("UserID", args.user());

        return webClient.get()
            .uri(uriBuilder -> uriBuilder
                .scheme("http")
                .host("eds.iptv.gd.cn")
                .port(8082)
                .path("/EDS/jsp/AuthenticationURL")
                .queryParams(params)
                .build())
            .retrieve()
            .bodyToMono(AuthResponse.class)
            .map(response -> {
                String epgUrl = response.epgurl();
                java.net.URI uri = java.net.URI.create(epgUrl);
                int port = uri.getPort();
                if (port == -1) {
                    port = "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
                }
                String base = uri.getScheme() + "://" + uri.getHost() + ":" + port;
                logger.debug("Got base_url {}", base);
                baseUrl.set(new CacheEntry<>(base, BASE_URL_TTL));
                return base;
            });
    }

    public Mono<String> fetchExtraXmltv(String url) {
        return webClient.get()
            .uri(url)
            .retrieve()
            .bodyToMono(String.class);
    }

    public Mono<String> fetchExtraPlaylist(String url) {
        return webClient.get()
            .uri(url)
            .retrieve()
            .bodyToMono(String.class)
            .map(body -> body.startsWith("#EXTM3U") ? body.substring("#EXTM3U".length()) : "");
    }

    public Mono<byte[]> fetchIcon(String id) {
        return getBaseUrl()
            .flatMap(base -> webClient.get()
                .uri(base + "/EPG/jsp/iptvsnmv3/en/list/images/channelIcon/" + id + ".png")
                .retrieve()
                .bodyToMono(byte[].class));
    }

    private Mono<List<Channel>> fetchChannels(String baseUrl, String scheme, String host) {
        logger.info("Obtaining channels");
        return getToken(baseUrl)
            .flatMap(token -> authenticate(baseUrl, token))
            .then(fetchChannelList(baseUrl, scheme, host));
    }

    private Mono<String> getToken(String baseUrl) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("response_type", "EncryToken");
        params.add("client_id", "smcphone");
        params.add("userid", args.user());

        return webClient.get()
            .uri(buildUri(baseUrl, "/EPG/oauth/v2/authorize", params))
            .retrieve()
            .bodyToMono(TokenResponse.class)
            .map(TokenResponse::token);
    }

    private Mono<Void> authenticate(String baseUrl, String token) {
        String auth = createAuth(token);

        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("client_id", "smcphone");
        params.add("DeviceType", "deviceType");
        params.add("UserID", args.user());
        params.add("DeviceVersion", "deviceVersion");
        params.add("userdomain", "2");
        params.add("datadomain", "3");
        params.add("accountType", "1");
        params.add("authinfo", auth);
        params.add("grant_type", "EncryToken");

        return webClient.get()
            .uri(buildUri(baseUrl, "/EPG/oauth/v2/token", params))
            .retrieve()
            .bodyToMono(String.class)
            .then();
    }

    private Mono<List<Channel>> fetchChannelList(String baseUrl, String scheme, String host) {
        return webClient.get()
            .uri(baseUrl + "/EPG/jsp/getchannellistHWCTC.jsp")
            .retrieve()
            .bodyToMono(String.class)
            .map(body -> parseChannels(body, scheme, host));
    }

    private List<Channel> parseChannels(String body, String scheme, String host) {
        Matcher matcher = CHANNEL_PATTERN.matcher(body);
        List<Channel> channels = new ArrayList<>();
        while (matcher.find()) {
            String payload = matcher.group(1);
            Map<String, String> values = parseChannelAttributes(payload);
            try {
                long id = Long.parseLong(values.getOrDefault("ChannelID", "-1"));
                String name = values.get("ChannelName");
                String url = values.get("ChannelURL");
                if (id < 0 || name == null || url == null) {
                    continue;
                }
                String rtsp = null;
                String igmp = null;
                for (String part : url.split("\\|")) {
                    if (part.startsWith("rtsp")) {
                        rtsp = part;
                    } else if (part.startsWith("igmp")) {
                        igmp = part;
                    }
                }
                if (rtsp == null) {
                    continue;
                }
                String resolvedRtsp = rtsp;
                if (args.rtspProxy()) {
                    resolvedRtsp = rtsp.replace("rtsp://", scheme + "://" + host + "/rtsp/");
                }
                resolvedRtsp = resolvedRtsp.replace("zoneoffset=0", "zoneoffset=480");
                String resolvedIgmp = null;
                if (igmp != null) {
                    resolvedIgmp = igmp;
                    if (args.udpProxy()) {
                        resolvedIgmp = igmp.replace("igmp://", scheme + "://" + host + "/udp/");
                    }
                }
                channels.add(new Channel(id, name, resolvedRtsp, resolvedIgmp, Collections.emptyList()));
            } catch (NumberFormatException ignored) {
            }
        }
        logger.info("Got {} channel(s)", channels.size());
        return channels;
    }

    private Map<String, String> parseChannelAttributes(String payload) {
        return List.of(payload.split("\","))
            .stream()
            .map(part -> part.split("=\\\""))
            .filter(parts -> parts.length == 2)
            .collect(Collectors.toMap(parts -> parts[0], parts -> parts[1], (a, b) -> a));
    }

    private Mono<List<Channel>> fetchEpg(String baseUrl, List<Channel> channels) {
        long now = System.currentTimeMillis();
        return Flux.fromIterable(channels)
            .flatMap(channel -> fetchEpgForChannel(baseUrl, channel, now), EPG_CONCURRENCY)
            .collectList();
    }

    private Mono<Channel> fetchEpgForChannel(String baseUrl, Channel channel, long now) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("channelId", Long.toString(channel.id()));
        params.add("begin", Long.toString(now - DAY_MS * 2));
        params.add("end", Long.toString(now + DAY_MS * 5));

        return webClient.get()
            .uri(buildUri(baseUrl, "/EPG/jsp/iptvsnmv3/en/play/ajax/_ajax_getPlaybillList.jsp", params))
            .retrieve()
            .bodyToMono(PlaybillList.class)
            .map(list -> {
                List<Program> programs = new ArrayList<>();
                if (list != null && list.playbillLites() != null) {
                    for (Playbill bill : list.playbillLites()) {
                        programs.add(new Program(bill.startTime(), bill.endTime(), bill.name(), bill.name()));
                    }
                }
                return new Channel(channel.id(), channel.name(), channel.rtsp(), channel.igmp(), programs);
            })
            .onErrorReturn(channel);
    }

    private String createAuth(String token) {
        try {
            String md5 = HexFormat.of().withUpperCase().formatHex(
                MessageDigest.getInstance("MD5").digest(args.passwd().getBytes(StandardCharsets.UTF_8)));
            String keyString = md5.substring(0, 24);
            SecretKeySpec key = new SecretKeySpec(keyString.getBytes(StandardCharsets.UTF_8), "DESede");
            Cipher cipher = Cipher.getInstance("DESede/ECB/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, key);
            String payload = "%d$%s$%s$%s$%s$%s$$CTC".formatted(
                new Random().nextInt(10_000_000),
                token,
                args.user(),
                args.imei(),
                args.address(),
                args.mac());
            byte[] encrypted = cipher.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            String auth = HexFormat.of().withUpperCase().formatHex(encrypted);
            logger.debug("Got auth {}", auth);
            return auth;
        } catch (Exception e) {
            throw new IllegalStateException("Encrypt error", e);
        }
    }

    private WebClient buildClient(IptvArgs args) {
        HttpClient httpClient = HttpClient.create()
            .responseTimeout(HTTP_TIMEOUT);

        InetAddress local = args.resolvedAddressOrNull();
        if (local != null) {
            httpClient = httpClient.bindAddress(() -> new java.net.InetSocketAddress(local, 0));
        }

        return WebClient.builder()
            .clientConnector(new ReactorClientHttpConnector(httpClient))
            .filter(cookieFilter())
            .defaultHeader(HttpHeaders.USER_AGENT, "iptv-proxy")
            .build();
    }

    private ExchangeFilterFunction cookieFilter() {
        return (request, next) -> {
            String cookie = cookieHeader.get();
            if (cookie != null && !cookie.isBlank()) {
                request = org.springframework.web.reactive.function.client.ClientRequest.from(request)
                    .header(HttpHeaders.COOKIE, cookie)
                    .build();
            }
            return next.exchange(request)
                .doOnNext(response -> {
                    List<String> cookies = response.headers().header(HttpHeaders.SET_COOKIE);
                    if (!cookies.isEmpty()) {
                        String merged = cookies.stream()
                            .map(value -> value.split(";", 2)[0])
                            .filter(value -> !value.isBlank())
                            .collect(Collectors.joining("; "));
                        if (!merged.isBlank()) {
                            cookieHeader.set(merged);
                        }
                    }
                });
        };
    }

    private java.net.URI buildUri(String baseUrl, String path, MultiValueMap<String, String> params) {
        return UriComponentsBuilder.fromHttpUrl(baseUrl + path)
            .queryParams(params)
            .build(true)
            .toUri();
    }

    private record CacheEntry<T>(T value, Duration ttl, Instant createdAt) {
        CacheEntry(T value, Duration ttl) {
            this(value, ttl, Instant.now());
        }

        boolean isValid() {
            return Instant.now().isBefore(createdAt.plus(ttl));
        }
    }

    private record ChannelCache(String scheme, String host, List<Channel> channels) {
        boolean matches(String scheme, String host) {
            return this.scheme.equals(scheme) && this.host.equals(host);
        }
    }

    private record AuthResponse(@JsonProperty("epgurl") String epgurl) {
    }

    private record TokenResponse(@JsonProperty("EncryToken") String token) {
    }

    private record PlaybillList(@JsonProperty("playbillLites") List<Playbill> playbillLites) {
    }

    private record Playbill(String name, @JsonProperty("startTime") long startTime,
                            @JsonProperty("endTime") long endTime) {
    }
}
