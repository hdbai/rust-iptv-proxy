package com.iptvproxy;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Enumeration;

public final class NetworkUtils {
    private NetworkUtils() {
    }

    public static InetAddress resolveAddress(String nameOrIp) {
        try {
            NetworkInterface nif = NetworkInterface.getByName(nameOrIp);
            if (nif != null) {
                InetAddress addr = firstIpv4(nif);
                if (addr != null) {
                    return addr;
                }
            }
        } catch (SocketException ignored) {
        }

        try {
            return InetAddress.getByName(nameOrIp);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid interface/address: " + nameOrIp, e);
        }
    }

    public static NetworkInterface resolveInterface(String nameOrIp) {
        try {
            NetworkInterface nif = NetworkInterface.getByName(nameOrIp);
            if (nif != null) {
                return nif;
            }
        } catch (SocketException ignored) {
        }

        try {
            InetAddress address = InetAddress.getByName(nameOrIp);
            return NetworkInterface.getByInetAddress(address);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid interface/address: " + nameOrIp, e);
        }
    }

    private static InetAddress firstIpv4(NetworkInterface nif) {
        Enumeration<InetAddress> addresses = nif.getInetAddresses();
        while (addresses.hasMoreElements()) {
            InetAddress address = addresses.nextElement();
            if (address.getHostAddress().contains(".")) {
                return address;
            }
        }
        return null;
    }
}
