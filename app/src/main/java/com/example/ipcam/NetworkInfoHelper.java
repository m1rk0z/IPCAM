package com.example.ipcam;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;

import java.util.ArrayList;

public class NetworkInfoHelper {
    
    public static class IpAddressInfo {
        public final String interfaceName;
        public final String ipAddress;

        public IpAddressInfo(String interfaceName, String ipAddress) {
            this.interfaceName = interfaceName;
            this.ipAddress = ipAddress;
        }

        @Override
        public String toString() {
            return interfaceName + " (" + ipAddress + ")";
        }
    }
    
    /**
     * Finds and returns all available active non-loopback IPv4 interfaces on the device.
     */
    public static List<IpAddressInfo> getAvailableIpAddresses() {
        List<IpAddressInfo> list = new ArrayList<>();
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface intf : interfaces) {
                if (intf.isLoopback() || !intf.isUp()) continue;
                
                String name = intf.getName().toLowerCase();
                String displayName = intf.getDisplayName().toLowerCase();
                String userFriendlyName = "Network";
                
                // Identify interface type based on standard prefixes
                if (name.contains("wlan") || displayName.contains("wireless") || displayName.contains("wi-fi")) {
                    userFriendlyName = "Wi-Fi";
                } else if (name.contains("ap") || name.contains("softap") || name.contains("wigig")) {
                    userFriendlyName = "Hotspot";
                } else if (name.contains("eth")) {
                    userFriendlyName = "Ethernet";
                } else if (name.contains("rndis")) {
                    userFriendlyName = "USB Tethering";
                } else if (name.contains("rmnet") || name.contains("p2p")) {
                    userFriendlyName = "Cellular/P2P";
                } else {
                    userFriendlyName = intf.getDisplayName();
                }

                for (InetAddress addr : Collections.list(intf.getInetAddresses())) {
                    if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                        list.add(new IpAddressInfo(userFriendlyName, addr.getHostAddress()));
                    }
                }
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        if (list.isEmpty()) {
            list.add(new IpAddressInfo("Loopback", "127.0.0.1"));
        }
        return list;
    }
    
    /**
     * Attempts to find the local IPv4 address.
     * Prioritizes wireless (wlan) and access point (ap/softap) interfaces.
     */
    public static String getLocalIpAddress() {
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            
            // Phase 1: Try to find wlan or ap interfaces first
            for (NetworkInterface intf : interfaces) {
                if (intf.isLoopback() || !intf.isUp()) continue;
                String name = intf.getName().toLowerCase();
                if (name.contains("wlan") || name.contains("ap") || name.contains("softap") || name.contains("p2p")) {
                    for (InetAddress addr : Collections.list(intf.getInetAddresses())) {
                        if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                            return addr.getHostAddress();
                        }
                    }
                }
            }
            
            // Phase 2: Fallback to any other active interface (e.g. ethernet, rndis)
            for (NetworkInterface intf : interfaces) {
                if (intf.isLoopback() || !intf.isUp()) continue;
                for (InetAddress addr : Collections.list(intf.getInetAddresses())) {
                    if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return "127.0.0.1";
    }
}
