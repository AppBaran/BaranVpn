package ir.baran.vpn;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.core.content.ContextCompat;

final class VpnConnectionController {
    /** 0=masque 1=wg 2=gool 3=psiphon 4=tor */
    private static final String[] PROTOCOLS = {"masque", "wg", "gool", "psiphon", "tor"};
    private static final String[] SCANS = {"balanced", "turbo", "thorough", "stealth", "ironclad"};
    private static final String[] IP_MODES = {"v4", "v6", "both"};
    private static final String[] OBFUSCATION = {"firewall", "gfw", "balanced", "aggressive", "off"};
    private static final String[] LOG_LEVELS = {"info", "warn", "error", "debug", "trace"};
    private static final String[] ROUTING = {"bypass-local", "full", "split-include", "split-exclude"};

    static Intent startIntent(Context context, SharedPreferences preferences) {
        Intent intent = new Intent(context, AetherVpnService.class)
                .setAction(AetherVpnService.ACTION_START)
                .putExtra("connectionMode", preferences.getString("mode", "vpn"))
                .putExtra("protocol", value(PROTOCOLS, preferences.getInt("protocol", ConnectionDefaults.PROTOCOL_INDEX), ConnectionDefaults.PROTOCOL))
                .putExtra("scan", value(SCANS, preferences.getInt("scan", ConnectionDefaults.SCAN_INDEX), ConnectionDefaults.SCAN))
                .putExtra("transport", preferences.getInt("transport", 0) == 1 ? "h2" : "h3")
                .putExtra("ipMode", value(IP_MODES, preferences.getInt("ip", 0), "v4"))
                .putExtra("obfuscation", value(OBFUSCATION, preferences.getInt("obfuscation", 0), "firewall"))
                .putExtra("logLevel", value(LOG_LEVELS, preferences.getInt("log", 0), "info"))
                .putExtra("routing", value(ROUTING, preferences.getInt("routing", 0), "bypass-local"))
                .putExtra("socks", preferences.getString("socks", "127.0.0.1:1819"))
                .putExtra("peer", preferences.getString("peer", ""))
                .putExtra("mtu", parseMtu(preferences.getString("mtu", "1500")))
                .putExtra("splitApps", preferences.getString("splitApps", ""))
                .putExtra("dnsLeak", preferences.getBoolean("dnsLeak", true))
                .putExtra("killSwitch", preferences.getBoolean("killSwitch", false))
                .putExtra("quickReconnect", preferences.getBoolean("quickReconnect", true))
                .putExtra("lanSharing", preferences.getBoolean("lanSharing", false));

        if ("psiphon".equals(intent.getStringExtra("protocol"))) {
            String regionCode = preferences.getString("psiphon_region_code", "");
            if (regionCode == null) regionCode = "";
            regionCode = regionCode.trim().toUpperCase();
            intent.putExtra("region", regionCode);
            Log.i("VpnController", "Psiphon EgressRegion extra=" +
                    (regionCode.isEmpty() ? "AUTO" : regionCode));
            String upstream = preferences.getString("psiphon_upstream", "WARP");
            if (upstream == null) upstream = "WARP";
            boolean direct = upstream.equalsIgnoreCase("Direct")
                    || upstream.equalsIgnoreCase("مستقیم")
                    || upstream.equalsIgnoreCase("direct");
            intent.putExtra("direct_connection", direct);
            if (!direct) {
                intent.putExtra("upstream_protocol",
                        upstream.equalsIgnoreCase("WireGuard") ? "wg" : "gool");
            } else {
                intent.putExtra("upstream_protocol", "");
            }
            Log.i("VpnController", "Psiphon upstream=" + upstream + " direct=" + direct);
        }

        // ---- Tor module (same pattern as Psiphon) ----
        // Keep protocol="tor" so the service runs runTorConnection:
        //   1) optional Aether carrier on 127.0.0.1:1818 (WARP/WG)
        //   2) Tor layer SOCKS on 127.0.0.1:1819 (--tor-only + AETHER_UPSTREAM)
        if ("tor".equals(intent.getStringExtra("protocol"))) {
            String upstream = preferences.getString("tor_upstream", "WARP");
            if (upstream == null) upstream = "WARP";
            upstream = upstream.trim();
            intent.putExtra("enableTor", true);
            intent.putExtra("ui_protocol", "tor");
            intent.putExtra("tor_upstream_label", upstream);
            // do NOT rewrite protocol away from "tor"

            if (upstream.equalsIgnoreCase("Direct")
                    || upstream.equalsIgnoreCase("مستقیم")
                    || upstream.equalsIgnoreCase("direct")) {
                intent.putExtra("tor_mode", "only");
                intent.putExtra("direct_connection", true);
                intent.putExtra("upstream_protocol", "");
                Log.i("VpnController", "Tor DIRECT (--tor-only)");
            } else if (upstream.equalsIgnoreCase("WireGuard")) {
                intent.putExtra("tor_mode", "through");
                intent.putExtra("direct_connection", false);
                intent.putExtra("upstream_protocol", "wg");
                Log.i("VpnController", "Tor via WireGuard upstream :1818");
            } else {
                intent.putExtra("tor_mode", "through");
                intent.putExtra("direct_connection", false);
                intent.putExtra("upstream_protocol", "gool");
                Log.i("VpnController", "Tor via WARP/gool upstream :1818");
            }
        }

        return intent;
    }

    static void connect(Context context, SharedPreferences preferences) {
        ContextCompat.startForegroundService(context, startIntent(context, preferences));
    }

    static void disconnect(Context context) {
        context.startService(new Intent(context, AetherVpnService.class).setAction(AetherVpnService.ACTION_STOP));
    }

    static boolean canDisconnect(String state) {
        return "starting".equals(state) || "smart-testing".equals(state) || "scanning".equals(state)
                || "securing".equals(state) || "connected".equals(state) || "reconnecting".equals(state)
                || "disconnecting".equals(state) || "blocked".equals(state)
                || "proxy-starting".equals(state) || "proxy-connected".equals(state);
    }

    static boolean needsVpn(SharedPreferences preferences) {
        String mode = preferences.getString("mode", "vpn");
        if (!"manual".equals(mode)) {
            return true;
        }
        String protocol = value(
                PROTOCOLS,
                preferences.getInt("protocol", ConnectionDefaults.PROTOCOL_INDEX),
                ConnectionDefaults.PROTOCOL
        );
        return "psiphon".equals(protocol) || "tor".equals(protocol);
    }

    private static String value(String[] values, int index, String fallback) {
        return index >= 0 && index < values.length ? values[index] : fallback;
    }

    private static int parseMtu(String value) {
        try { return Math.max(1280, Math.min(9000, Integer.parseInt(value))); }
        catch (Exception ignored) { return 1500; }
    }

    private VpnConnectionController() { }
}
