package com.iptvproxy;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Objects;

public final class IptvArgs {
    private final String user;
    private final String passwd;
    private final String mac;
    private final String imei;
    private final String bind;
    private final String address;
    private final String iface;
    private final String extraPlaylist;
    private final String extraXmltv;
    private final boolean udpProxy;
    private final boolean rtspProxy;

    private IptvArgs(
        String user,
        String passwd,
        String mac,
        String imei,
        String bind,
        String address,
        String iface,
        String extraPlaylist,
        String extraXmltv,
        boolean udpProxy,
        boolean rtspProxy
    ) {
        this.user = user;
        this.passwd = passwd;
        this.mac = mac;
        this.imei = imei;
        this.bind = bind;
        this.address = address;
        this.iface = iface;
        this.extraPlaylist = extraPlaylist;
        this.extraXmltv = extraXmltv;
        this.udpProxy = udpProxy;
        this.rtspProxy = rtspProxy;
    }

    public static IptvArgs fromParsed(
        String user,
        String passwd,
        String mac,
        String imei,
        String bind,
        String address,
        String iface,
        String extraPlaylist,
        String extraXmltv,
        boolean udpProxy,
        boolean rtspProxy
    ) {
        return new IptvArgs(
            Objects.requireNonNull(user, "user"),
            Objects.requireNonNull(passwd, "passwd"),
            Objects.requireNonNull(mac, "mac"),
            imei == null ? "" : imei,
            bind == null ? "0.0.0.0:7878" : bind,
            address == null ? "" : address,
            iface,
            extraPlaylist,
            extraXmltv,
            udpProxy,
            rtspProxy
        );
    }

    public String user() {
        return user;
    }

    public String passwd() {
        return passwd;
    }

    public String mac() {
        return mac;
    }

    public String imei() {
        return imei;
    }

    public String bind() {
        return bind;
    }

    public String address() {
        return address;
    }

    public String iface() {
        return iface;
    }

    public String extraPlaylist() {
        return extraPlaylist;
    }

    public String extraXmltv() {
        return extraXmltv;
    }

    public boolean udpProxy() {
        return udpProxy;
    }

    public boolean rtspProxy() {
        return rtspProxy;
    }

    public String bindAddress() {
        return bindSocket().getHostString();
    }

    public int bindPort() {
        return bindSocket().getPort();
    }

    public InetSocketAddress bindSocket() {
        String[] parts = bind.split(":", 2);
        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid bind address: " + bind);
        }
        int port = Integer.parseInt(parts[1]);
        return new InetSocketAddress(parts[0], port);
    }

    public InetAddress resolvedAddressOrNull() {
        if (iface == null || iface.isBlank()) {
            return null;
        }
        return NetworkUtils.resolveAddress(iface);
    }
}
