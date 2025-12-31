package com.iptvproxy;

import java.util.ArrayList;
import java.util.List;

public final class ArgsParser {
    private ArgsParser() {
    }

    public static ParseResult parse(String[] args) {
        if (args.length == 0) {
            printUsage("iptv");
            return ParseResult.exit();
        }

        List<String> argv = new ArrayList<>(List.of(args));
        if (argv.contains("-h") || argv.contains("--help")) {
            printUsage("iptv");
            return ParseResult.exit();
        }

        String user = null;
        String passwd = null;
        String mac = null;
        String imei = "";
        String bind = "0.0.0.0:7878";
        String address = "";
        String iface = null;
        String extraPlaylist = null;
        String extraXmltv = null;
        boolean udpProxy = false;
        boolean rtspProxy = false;

        try {
            for (int i = 0; i < argv.size(); i++) {
                String arg = argv.get(i);
                switch (arg) {
                    case "-u", "--user" -> user = nextValue(argv, ++i, arg);
                    case "-p", "--passwd" -> passwd = nextValue(argv, ++i, arg);
                    case "-m", "--mac" -> mac = nextValue(argv, ++i, arg);
                    case "-i", "--imei" -> imei = nextValue(argv, ++i, arg);
                    case "-b", "--bind" -> bind = nextValue(argv, ++i, arg);
                    case "-a", "--address" -> address = nextValue(argv, ++i, arg);
                    case "-I", "--interface" -> iface = nextValue(argv, ++i, arg);
                    case "--extra-playlist" -> extraPlaylist = nextValue(argv, ++i, arg);
                    case "--extra-xmltv" -> extraXmltv = nextValue(argv, ++i, arg);
                    case "--udp-proxy" -> udpProxy = true;
                    case "--rtsp-proxy" -> rtspProxy = true;
                    default -> {
                        if (arg.startsWith("-")) {
                            throw new IllegalArgumentException("Unknown flag: " + arg);
                        }
                    }
                }
            }
        } catch (IllegalArgumentException e) {
            printUsage("iptv");
            return ParseResult.exit();
        }

        if (user == null || passwd == null || mac == null) {
            printUsage("iptv");
            return ParseResult.exit();
        }

        IptvArgs iptvArgs = IptvArgs.fromParsed(user, passwd, mac, imei, bind, address, iface,
            extraPlaylist, extraXmltv, udpProxy, rtspProxy);
        return ParseResult.success(iptvArgs);
    }

    private static String nextValue(List<String> args, int index, String flag) {
        if (index >= args.size()) {
            throw new IllegalArgumentException("Missing value for " + flag);
        }
        return args.get(index);
    }

    private static void printUsage(String cmd) {
        String usage = """
            Usage: %s [OPTIONS] --user <USER> --passwd <PASSWD> --mac <MAC>

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
            """.formatted(cmd);
        System.err.print(usage);
    }

    public record ParseResult(IptvArgs args, boolean shouldExit) {
        public static ParseResult success(IptvArgs args) {
            return new ParseResult(args, false);
        }

        public static ParseResult exit() {
            return new ParseResult(null, true);
        }
    }
}
