package com.iptvproxy.model;

import java.util.ArrayList;
import java.util.List;

public class Channel {
    private final long id;
    private final String name;
    private final String rtsp;
    private final String igmp;
    private final List<Program> epg;

    public Channel(long id, String name, String rtsp, String igmp, List<Program> epg) {
        this.id = id;
        this.name = name;
        this.rtsp = rtsp;
        this.igmp = igmp;
        this.epg = epg == null ? new ArrayList<>() : new ArrayList<>(epg);
    }

    public long id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String rtsp() {
        return rtsp;
    }

    public String igmp() {
        return igmp;
    }

    public List<Program> epg() {
        return epg;
    }
}
