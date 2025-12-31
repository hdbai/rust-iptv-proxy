package com.iptvproxy.service;

import com.iptvproxy.IptvArgs;
import com.iptvproxy.NetworkUtils;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import java.util.concurrent.atomic.AtomicInteger;
import reactor.core.scheduler.Schedulers;

@Service
public class ProxyService {
    private static final Logger logger = LoggerFactory.getLogger(ProxyService.class);
    private static final int RTP_SEQUENCE_WINDOW = 3000;
    private static final int UDP_BUFFER_SIZE = 65535;

    private final DataBufferFactory bufferFactory = new DefaultDataBufferFactory();
    private final IptvArgs args;

    public ProxyService(IptvArgs args) {
        this.args = args;
    }

    public Flux<DataBuffer> rtsp(String url) {
        return Flux.<DataBuffer>create(sink -> {
            Thread worker = new Thread(() -> streamRtsp(url, sink), "rtsp-proxy");
            worker.setDaemon(true);
            worker.start();
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public Flux<DataBuffer> udp(InetSocketAddress address) {
        return Flux.<DataBuffer>create(sink -> {
            Thread worker = new Thread(() -> streamUdp(address, sink), "udp-proxy");
            worker.setDaemon(true);
            worker.start();
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private void streamUdp(InetSocketAddress address, FluxSink<DataBuffer> sink) {
        NetworkInterface networkInterface = null;
        if (args.iface() != null && !args.iface().isBlank()) {
            networkInterface = NetworkUtils.resolveInterface(args.iface());
        }
        try (MulticastSocket socket = new MulticastSocket(address.getPort())) {
            socket.setReuseAddress(true);
            NetworkInterface joinInterface = networkInterface;
            if (joinInterface == null) {
                joinInterface = NetworkInterface.getByInetAddress(InetAddress.getLocalHost());
            }
            if (joinInterface != null) {
                socket.setNetworkInterface(joinInterface);
                socket.joinGroup(address, joinInterface);
            } else {
                socket.joinGroup(address.getAddress());
            }
            logger.info("Udp proxy joined {}", address);
            byte[] buffer = new byte[UDP_BUFFER_SIZE];
            int seq = 0;
            while (!sink.isCancelled()) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                RtpPayload payload = parseRtpPayload(packet.getData(), packet.getLength(), seq);
                if (payload != null) {
                    seq = payload.seq;
                    sink.next(bufferFactory.wrap(payload.payload));
                }
            }
        } catch (Exception e) {
            sink.error(e);
        } finally {
            logger.info("Udp proxy left {}", address);
        }
    }

    private void streamRtsp(String url, FluxSink<DataBuffer> sink) {
        try {
            RtspSession session = new RtspSession(url, args.iface());
            session.start();
            while (!sink.isCancelled()) {
                byte[] payload = session.readRtpPayload();
                if (payload != null) {
                    sink.next(bufferFactory.wrap(payload));
                }
            }
        } catch (Exception e) {
            sink.error(e);
        }
    }

    private static RtpPayload parseRtpPayload(byte[] data, int length, int lastSeq) {
        if (length < 12) {
            return null;
        }
        int vpxcc = data[0] & 0xFF;
        int cc = vpxcc & 0x0F;
        boolean hasExtension = (vpxcc & 0x10) != 0;
        int headerLen = 12 + cc * 4;
        if (hasExtension) {
            if (length < headerLen + 4) {
                return null;
            }
            int extLen = ((data[headerLen + 2] & 0xFF) << 8) | (data[headerLen + 3] & 0xFF);
            headerLen += 4 + (extLen * 4);
        }
        if (length < headerLen) {
            return null;
        }
        int seq = ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);
        if (!validSeq(lastSeq, seq)) {
            return null;
        }
        byte[] payload = new byte[length - headerLen];
        System.arraycopy(data, headerLen, payload, 0, payload.length);
        return new RtpPayload(seq, payload);
    }

    private static boolean validSeq(int last, int next) {
        if (last == 0) {
            return true;
        }
        int valid = (last + RTP_SEQUENCE_WINDOW) & 0xFFFF;
        if (valid > last) {
            return next > last && next <= valid;
        }
        return next > last || next <= valid;
    }

    private static final class RtspSession {
        private final URI uri;
        private final String iface;
        private final AtomicInteger cseq = new AtomicInteger(1);
        private Socket socket;
        private InputStream input;
        private OutputStream output;
        private String session;
        private final List<String> controlUrls = new ArrayList<>();
        private String aggregateControl;
        private int sessionSeq;

        private RtspSession(String url, String iface) {
            this.uri = URI.create(url);
            this.iface = iface;
        }

        private void start() throws IOException {
            InetAddress local = iface == null || iface.isBlank() ? null : NetworkUtils.resolveAddress(iface);
            socket = new Socket();
            if (local != null) {
                socket.bind(new InetSocketAddress(local, 0));
            }
            int port = uri.getPort() == -1 ? 554 : uri.getPort();
            socket.connect(new InetSocketAddress(uri.getHost(), port), (int) Duration.ofSeconds(5).toMillis());
            socket.setSoTimeout((int) Duration.ofSeconds(15).toMillis());
            input = new BufferedInputStream(socket.getInputStream());
            output = socket.getOutputStream();
            sendRequest("OPTIONS", uri.toString(), null);
            readResponse();
            sendRequest("DESCRIBE", uri.toString(), "Accept: application/sdp\r\n");
            RtspResponse describe = readResponse();
            parseSdp(describe.body, describe.headers.getOrDefault("content-base", uri.toString()));
            setupTracks();
            String playUrl = aggregateControl == null ? uri.toString() : aggregateControl;
            String sessionHeader = session == null ? "" : "Session: " + session + "\r\n";
            sendRequest("PLAY", playUrl, sessionHeader + "Range: npt=0.000-\r\n");
            readResponse();
        }

        private byte[] readRtpPayload() throws IOException {
            int b = input.read();
            if (b == -1) {
                return null;
            }
            if (b == '$') {
                int channel = input.read();
                int len1 = input.read();
                int len2 = input.read();
                int length = (len1 << 8) | len2;
                byte[] payload = input.readNBytes(length);
                if (channel % 2 != 0) {
                    return null;
                }
                RtpPayload parsed = parseRtpPayload(payload, payload.length, sessionSeq);
                if (parsed == null) {
                    return null;
                }
                sessionSeq = parsed.seq;
                return parsed.payload;
            }
            if (b == 'R') {
                readResponseLineStartingWith((byte) b);
                return null;
            }
            return null;
        }

        private void setupTracks() throws IOException {
            int channel = 0;
            for (String control : controlUrls) {
                String transport = "Transport: RTP/AVP/TCP;unicast;interleaved=%d-%d\r\n".formatted(channel, channel + 1);
                String sessionHeader = session == null ? "" : "Session: " + session + "\r\n";
                sendRequest("SETUP", control, transport + sessionHeader);
                RtspResponse response = readResponse();
                if (session == null) {
                    session = response.headers.get("session");
                    if (session != null && session.contains(";")) {
                        session = session.substring(0, session.indexOf(';'));
                    }
                }
                channel += 2;
            }
        }

        private void parseSdp(String sdp, String contentBase) {
            String base = contentBase;
            if (!base.endsWith("/")) {
                base += "/";
            }
            for (String line : sdp.split("\n")) {
                line = line.trim();
                if (line.startsWith("a=control:")) {
                    String control = line.substring("a=control:".length());
                    if ("*".equals(control)) {
                        aggregateControl = uri.toString();
                        continue;
                    }
                    if (control.startsWith("rtsp://")) {
                        controlUrls.add(control);
                    } else {
                        controlUrls.add(base + control);
                    }
                }
            }
        }

        private void sendRequest(String method, String url, String extraHeaders) throws IOException {
            StringBuilder sb = new StringBuilder();
            sb.append(method).append(" ").append(url).append(" RTSP/1.0\r\n");
            sb.append("CSeq: ").append(cseq.getAndIncrement()).append("\r\n");
            sb.append("User-Agent: iptv-proxy\r\n");
            if (extraHeaders != null) {
                sb.append(extraHeaders);
            }
            sb.append("\r\n");
            output.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            output.flush();
        }

        private RtspResponse readResponse() throws IOException {
            String statusLine = readLine();
            if (statusLine == null) {
                throw new IOException("RTSP connection closed");
            }
            return readResponseWithStatus(statusLine);
        }

        private void readResponseLineStartingWith(byte firstByte) throws IOException {
            String statusLine = readLine(firstByte);
            if (statusLine != null) {
                readResponseWithStatus(statusLine);
            }
        }

        private String readLine() throws IOException {
            return readLine(null);
        }

        private String readLine(Byte firstByte) throws IOException {
            StringBuilder sb = new StringBuilder();
            if (firstByte != null) {
                sb.append((char) (byte) firstByte);
            }
            int ch;
            while ((ch = input.read()) != -1) {
                if (ch == '\n') {
                    break;
                }
                if (ch != '\r') {
                    sb.append((char) ch);
                }
            }
            if (ch == -1 && sb.isEmpty()) {
                return null;
            }
            return sb.toString();
        }

        private RtspResponse readResponseWithStatus(String statusLine) throws IOException {
            RtspResponse response = new RtspResponse();
            response.statusLine = statusLine;
            String line;
            while ((line = readLine()) != null && !line.isBlank()) {
                int idx = line.indexOf(':');
                if (idx > 0) {
                    response.headers.put(line.substring(0, idx).trim().toLowerCase(), line.substring(idx + 1).trim());
                }
            }
            String contentLength = response.headers.get("content-length");
            if (contentLength != null) {
                int len = Integer.parseInt(contentLength);
                byte[] body = input.readNBytes(len);
                response.body = new String(body, StandardCharsets.UTF_8);
            }
            return response;
        }
    }

    private static final class RtspResponse {
        private final java.util.Map<String, String> headers = new java.util.HashMap<>();
        private String body = "";
        private String statusLine = "";
    }

    private static final class RtpPayload {
        private final int seq;
        private final byte[] payload;

        private RtpPayload(int seq, byte[] payload) {
            this.seq = seq;
            this.payload = payload;
        }
    }
}
