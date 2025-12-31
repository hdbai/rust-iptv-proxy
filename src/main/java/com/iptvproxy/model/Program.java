package com.iptvproxy.model;

public class Program {
    private final long start;
    private final long stop;
    private final String title;
    private final String desc;

    public Program(long start, long stop, String title, String desc) {
        this.start = start;
        this.stop = stop;
        this.title = title;
        this.desc = desc;
    }

    public long start() {
        return start;
    }

    public long stop() {
        return stop;
    }

    public String title() {
        return title;
    }

    public String desc() {
        return desc;
    }
}
