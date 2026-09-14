package ir.baran.vpn;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import org.json.JSONObject;

import hev.htproxy.TProxyService;
import ir.baran.vpn.psiphon.PsiphonCore;
import ir.baran.vpn.psiphon.LanSocksBridge;

public final class AetherVpnService extends VpnService {

    public static final String ACTION_START =
            "ir.baran.vpn.START";

    public static final String ACTION_STOP =
            "ir.baran.vpn.STOP";

    public static final String ACTION_QUERY =
            "ir.baran.vpn.QUERY";

    public static final String ACTION_STATUS =
            "ir.baran.vpn.STATUS";

    public static final String ACTION_LOG =
            "ir.baran.vpn.LOG";

    public static final String ACTION_STATS =
            "ir.baran.vpn.STATS";

    public static final String ACTION_CLEAR_LOGS =
            "ir.baran.vpn.CLEAR_LOGS";

    public static final String ACTION_TEST_UPSTREAM =
            "ir.baran.vpn.TEST_UPSTREAM";

    public static final String ACTION_PING =
            "ir.baran.vpn.PING";

    public static final String INTERNAL_PERMISSION =
            "ir.baran.vpn.permission.INTERNAL";

    private static final String CHANNEL_ID =
            "aether_vpn";

    private static final int NOTIFICATION_ID =
            1819;

    /*
     * Aether default SOCKS port.
     */
    private static final int AETHER_DEFAULT_PORT =
            1819;

    /** Public HTTP CONNECT proxy for LAN clients */
    private static final int LAN_HTTP_PORT = 8080;

    /** Loopback HTTP port used by Aether/Tor when LAN sharing is on */
    private static final int LAN_HTTP_INTERNAL_PORT = 18080;

    /**
     * Public LAN SOCKS port when Tor is active.
     * Cannot be 1819 (WARP) or 1820 (Tor exit) — those are already bound on loopback.
     */
    private static final int TOR_LAN_PUBLIC_PORT = 1819;

    /*
     * Aether internal port for Psiphon upstream.
     */
    private static final int AETHER_PSIPHON_UPSTREAM_PORT =
            1818;

    private static final int SOCKS_TIMEOUT_MS =
            120_000;

    private static final int SMART_PROTOCOL_TIMEOUT_MS =
            35_000;

    private static final int MAX_RECONNECT_ATTEMPTS =
            5;

    private static final String TAG =
            "AetherVpnService";

    private final ExecutorService worker =
            Executors.newCachedThreadPool();

    private final ScheduledExecutorService telemetry =
            Executors.newSingleThreadScheduledExecutor();

    private final AtomicLong generation =
            new AtomicLong();

    private final AtomicBoolean healthCheckRunning =
            new AtomicBoolean();

    private final AtomicLong locationLookupSequence =
            new AtomicLong();

    private final AtomicBoolean recoveryRestartPending =
            new AtomicBoolean();

    private final Object runtimeLock =
            new Object();

    private final Object networkLock =
            new Object();

    private final AtomicLong psiphonTx = new AtomicLong(0);
    private final AtomicLong psiphonRx = new AtomicLong(0);

    private final Object logLock =
            new Object();

    private final StringBuilder logHistory =
            new StringBuilder();

    private final StringBuilder pendingLogs =
            new StringBuilder();

    private volatile Process aetherProcess;

    /** Second Aether process: Tor exit (--tor-only), when carrier is on :1818 */
    private volatile Process torExitProcess;

    /** Set true when secondary Tor process logs that the circuit is ready. */
    private volatile boolean torExitReady;

    private volatile ParcelFileDescriptor vpnInterface;

    private volatile boolean bridgeStarted;

    private volatile boolean stopping = true;

    private volatile boolean active;

    private volatile boolean killSwitch;

    private volatile boolean smartBenchmarking;

    private volatile boolean masqueH3GatewayUnavailable;

    private volatile String currentState =
            "disconnected";

    private volatile String currentMessage =
            "Ready to connect";

    private volatile String currentEndpoint =
            "";

    /** Public exit IP from location lookup (shown in UI). */
    private volatile String currentExitIp =
            "";

    private volatile long connectedAt;

    private volatile long lastLogPersistedAt;

    private volatile long lastHealthCheckAt;

    private volatile long lastPing =
            -1;

    private volatile int consecutiveHealthFailures;

    private volatile Intent activeRequest;

    private final Set<Network> availableNetworks =
            ConcurrentHashMap.newKeySet();

    private volatile boolean networkUnavailable;

    private volatile boolean connectionEstablished;

    private SharedPreferences stateStore;

    private ConnectivityManager connectivityManager;

    /*
     * Psiphon runtime.
     */
    private PsiphonCore psiphonCore;

    private final LanSocksBridge lanSocksBridge = new LanSocksBridge();

    /** TCP pipe 0.0.0.0:8080 → loopback HTTP CONNECT of the active core */
    private final LanSocksBridge lanHttpBridge = new LanSocksBridge();

    /*
     * This value should always be Psiphon's own SOCKS port.
     * Never to be confused with 1818.
     */
    private final AtomicInteger psiphonSocksPort =
            new AtomicInteger(0);

    /*
     * Last Psiphon JSON config for soft-restart
     * (without tearing down TUN / HEV / Aether).
     */
    private volatile String lastPsiphonConfig = "";

    // ============================================================
    // PSIPHON LISTENER
    // ============================================================

    private final PsiphonCore.PsiphonListener psiphonListener =
            new PsiphonCore.PsiphonListener() {

                @Override
                public void onConnected() {

                    sendLog(
                            "[Psiphon] Tunnel CONNECTED"
                    );

                    /*
                     * MSN-GUARD: when TUN+HEV already active, a NetworkMonitor
                     * rotation only needs the new tunnel — do not rebuild VPN.
                     */
                    if (connectionEstablished) {
                        sendLog(
                                "[Psiphon] Reconnected — TUN still up"
                        );
                        // Re-bind LAN bridge to the (possibly new) SOCKS port
                        int p = psiphonSocksPort.get();
                        if (p > 0 && activeRequest != null) {
                            startLanSocksBridgeIfNeeded(activeRequest, p);
                        }
                    } else {
                        sendLog(
                                "[Psiphon] Waiting for final SOCKS listener..."
                        );
                    }
                }

                @Override
                public void onDisconnected() {

                    if (stopping) {
                        return;
                    }

                    sendLog(
                            "[Psiphon] Tunnel DISCONNECTED unexpectedly"
                    );

                    /*
                     * وقتی TUN بالا است، NetworkMonitor سایفون شبکه را VPN
                     * می‌بیند و تانل را terminate می‌کند.
                     * recovery کامل (stop TUN+Aether) حلقه می‌سازد.
                     * فقط هستهٔ سایفون را soft-restart می‌کنیم.
                     */
                    final long gen = generation.get();
                    final boolean vpnUp = connectionEstablished;

                    worker.execute(
                            () -> {

                                try {
                                    Thread.sleep(2000);
                                } catch (
                                        InterruptedException e
                                ) {
                                    Thread.currentThread()
                                            .interrupt();
                                    return;
                                }

                                if (stopping ||
                                        generation.get() != gen ||
                                        !active) {
                                    return;
                                }

                                if (psiphonCore.getState() ==
                                        PsiphonCore.State.CONNECTED) {

                                    sendLog(
                                            "[Psiphon] Tunnel recovered without restart"
                                    );
                                    return;
                                }

                                if (vpnUp) {
                                    softRestartPsiphonOnly(
                                            "NetworkMonitor VPN killed tunnel"
                                    );
                                } else {
                                    requestCoreRecovery(
                                            "Psiphon disconnected before VPN"
                                    );
                                }
                            }
                    );
                }
                @Override
                public void onStatusMessage(
                        String message
                ) {

                    if (message == null ||
                            message.trim().isEmpty()) {

                        return;
                    }

                    sendLog(
                            "[Psiphon] " +
                                    message
                    );
                }

                @Override
                public void onSocksProxyPort(
                        int port
                ) {

                    if (port <= 0 ||
                            port > 65535) {

                        sendLog(
                                "[Psiphon] Invalid SOCKS port received: " +
                                        port
                        );

                        return;
                    }

                    psiphonSocksPort.set(port);

                    sendLog(
                            "[Psiphon] FINAL SOCKS listening on 127.0.0.1:" +
                                    port
                    );

                    sendLog(
                            "[Psiphon] Aether upstream remains 127.0.0.1:" +
                                    AETHER_PSIPHON_UPSTREAM_PORT
                    );
                }

                @Override
                public void onRegionsUpdated(
                        List<String> regions
                ) {

                    if (regions == null) {
                        return;
                    }

                    Intent intent =
                            new Intent(ACTION_STATUS);

                    intent.putExtra(
                            "state",
                            currentState
                    );

                    intent.putStringArrayListExtra(
                            "regions",
                            new ArrayList<>(regions)
                    );

                    sendBroadcast(
                            intent,
                            INTERNAL_PERMISSION
                    );
                }

                @Override
                public void onBytesTransferred(long sent, long received) {
                    // Absolute session totals from Psiphon (not accumulated) so sparklines stay correct
                    psiphonTx.set(sent);
                    psiphonRx.set(received);
                }
            };

    // ============================================================
    // NETWORK CALLBACK
    // ============================================================

    private final ConnectivityManager.NetworkCallback networkCallback =
            new ConnectivityManager.NetworkCallback() {

                @Override
                public void onAvailable(
                        Network network
                ) {

                    // Only treat as recovery if we previously lost all networks.
                    // Using isEmpty() here falsely triggers recovery on the very
                    // first network callback during startup and races the
                    // initial connection (two concurrent Psiphon sessions).
                    boolean recovering = networkUnavailable;

                    availableNetworks.add(
                            network
                    );

                    networkUnavailable = false;

                    synchronized (networkLock) {
                        networkLock.notifyAll();
                    }

                    if (recovering &&
                            active &&
                            !stopping &&
                            connectionEstablished) {

                        requestCoreRecovery(
                                getString(
                                        R.string.service_network_restored
                                )
                        );
                    }
                }

                @Override
                public void onLost(
                        Network network
                ) {

                    availableNetworks.remove(
                            network
                    );

                    if (!availableNetworks.isEmpty() ||
                            !active ||
                            stopping) {

                        return;
                    }

                    networkUnavailable = true;

                    updateState(
                            "reconnecting",
                            getString(
                                    R.string.service_network_lost
                            )
                    );

                    updateNotification(
                            getString(
                                    R.string.service_network_lost
                            )
                    );
                }
            };

    // ============================================================
    // CREATE
    // ============================================================

    @Override
    public void onCreate() {

        super.onCreate();

        stateStore =
                getSharedPreferences(
                        "service_state",
                        MODE_PRIVATE
                );

        psiphonCore =
                new PsiphonCore(this);

        // MSN-GUARD pattern: HostService.bindToDevice → VpnService.protect()
        // so Psiphon sockets never loop into our own TUN after establishVpn.
        psiphonCore.setVpnService(this);

        psiphonCore.setListener(
                psiphonListener
        );

        String savedLogs =
                stateStore.getString(
                        "logs",
                        ""
                );

        if (savedLogs != null &&
                !savedLogs.isEmpty()) {

            logHistory.append(
                    savedLogs
            );
        }

        createNotificationChannel();

        connectivityManager =
                getSystemService(
                        ConnectivityManager.class
                );

        NetworkRequest request =
                new NetworkRequest.Builder()
                        .addCapability(
                                NetworkCapabilities.NET_CAPABILITY_INTERNET
                        )
                        .addCapability(
                                NetworkCapabilities.NET_CAPABILITY_NOT_VPN
                        )
                        .build();

        connectivityManager.registerNetworkCallback(
                request,
                networkCallback
        );

        telemetry.scheduleWithFixedDelay(
                this::publishStats,
                1,
                1,
                TimeUnit.SECONDS
        );

        telemetry.scheduleWithFixedDelay(
                this::flushLogs,
                75,
                75,
                TimeUnit.MILLISECONDS
        );

        sendLog(
                "[SERVICE] AetherVpnService created"
        );
    }

    // ============================================================
    // START COMMAND
    // ============================================================

    @Override
    public int onStartCommand(
            Intent intent,
            int flags,
            int startId
    ) {

        if (intent == null) {

            if (stateStore.getBoolean(
                    "desiredConnected",
                    false
            ) &&
                    VpnService.prepare(this) == null) {

                return onStartCommand(
                        VpnConnectionController.startIntent(
                                this,
                                getSharedPreferences(
                                        "aether",
                                        MODE_PRIVATE
                                )
                        ),
                        flags,
                        startId
                );
            }

            return active
                    ? START_STICKY
                    : START_NOT_STICKY;
        }

        String action =
                intent.getAction();

        // ========================================================
        // QUERY
        // ========================================================

        if (ACTION_QUERY.equals(action)) {

            sendStatus(
                    currentState,
                    currentMessage
            );

            publishStats();

            if (!active) {
                stopSelf(startId);
            }

            return active
                    ? START_STICKY
                    : START_NOT_STICKY;
        }

        // ========================================================
        // FORCE PING (manual tap in UI)
        // ========================================================

        if (ACTION_PING.equals(action)) {

            boolean canPing = active
                    && activeRequest != null
                    && connectionEstablished
                    && ("connected".equals(currentState)
                    || "proxy-connected".equals(currentState));

            if (canPing) {
                final Intent request = activeRequest;
                final long session = generation.get();
                healthCheckRunning.set(false);
                lastHealthCheckAt = 0L;
                worker.execute(() -> {
                    long measured = -1L;
                    try {
                        String socks = resolvePingSocks(request);
                        if (socks == null || socks.isEmpty()) {
                            sendLog("[Ping] No SOCKS endpoint available");
                        } else {
                            Exception lastErr = null;
                            for (String target : new String[]{"1.1.1.1", "8.8.8.8"}) {
                                try {
                                    measured = socksConnectMillis(
                                            socks,
                                            target,
                                            443,
                                            5_000
                                    );
                                    lastErr = null;
                                    break;
                                } catch (Exception e) {
                                    lastErr = e;
                                }
                            }
                            if (lastErr != null && measured < 0) {
                                throw lastErr;
                            }
                            consecutiveHealthFailures = 0;
                            sendLog("[Ping] Manual probe OK: " + measured + "ms via " + socks);
                        }
                    } catch (Throwable error) {
                        measured = -1L;
                        sendLog("[Ping] Manual probe failed: " + safeMessage(error));
                    }
                    lastPing = measured;
                    if (generation.get() == session && active && !stopping) {
                        publishStats();
                    }
                });
            } else {
                publishStats();
            }

            if (!active) {
                stopSelf(startId);
            }

            return active
                    ? START_STICKY
                    : START_NOT_STICKY;
        }

        // ========================================================
        // TEST UPSTREAM
        // ========================================================

        if (ACTION_TEST_UPSTREAM.equals(action)) {

            if (active &&
                    activeRequest != null &&
                    "psiphon".equals(
                            value(
                                    activeRequest,
                                    "protocol",
                                    ""
                            )
                    )) {

                worker.execute(
                        () -> {

                            try {

                                String upstreamSocks =
                                        "127.0.0.1:" +
                                                AETHER_PSIPHON_UPSTREAM_PORT;

                                long latency =
                                        socksConnectMillis(
                                                upstreamSocks,
                                                "1.1.1.1",
                                                443,
                                                5_000
                                        );

                                sendLog(
                                        "[Psiphon] Manual upstream test OK: " +
                                                latency +
                                                "ms"
                                );

                                broadcastUpstreamStatus(
                                        true,
                                        latency
                                );

                            } catch (Throwable error) {

                                sendLog(
                                        "[Psiphon] Manual upstream test failed: " +
                                                safeMessage(error)
                                );

                                broadcastUpstreamStatus(
                                        false,
                                        -1
                                );
                            }
                        }
                );
            }

            if (!active) {
                stopSelf(startId);
            }

            return active
                    ? START_STICKY
                    : START_NOT_STICKY;
        }

        // ========================================================
        // CLEAR LOGS
        // ========================================================

        if (ACTION_CLEAR_LOGS.equals(action)) {

            synchronized (logLock) {

                logHistory.setLength(0);
                pendingLogs.setLength(0);
            }

            stateStore.edit()
                    .remove("logs")
                    .apply();

            if (!active) {
                stopSelf(startId);
            }

            return active
                    ? START_STICKY
                    : START_NOT_STICKY;
        }

        // ========================================================
        // STOP
        // ========================================================

        if (ACTION_STOP.equals(action)) {

            stateStore.edit()
                    .putBoolean(
                            "desiredConnected",
                            false
                    )
                    .apply();

            generation.incrementAndGet();

            stopping = true;

            updateState(
                    "disconnecting",
                    getString(
                            R.string.service_disconnecting
                    )
            );

            worker.execute(
                    () -> stopConnection(true)
            );

            return START_NOT_STICKY;
        }

        // ========================================================
        // START
        // ========================================================

        if (ACTION_START.equals(action)) {

            stateStore.edit()
                    .putBoolean(
                            "desiredConnected",
                            true
                    )
                    .apply();

            Intent request =
                    new Intent(intent);

            long session =
                    generation.incrementAndGet();

            updateState(
                    "starting",
                    getString(
                            R.string.service_preparing
                    )
            );

            startForegroundCompat(
                    notification(
                            getString(
                                    R.string.service_preparing
                            ),
                            false
                    )
            );

            worker.execute(
                    () -> {

                        String oldProtocol =
                                activeRequest != null
                                        ? activeRequest.getStringExtra(
                                        "protocol"
                                )
                                        : null;

                        String newProtocol =
                                request.getStringExtra(
                                        "protocol"
                                );

                        boolean isSwitching =
                                active &&
                                        oldProtocol != null &&
                                        newProtocol != null &&
                                        !oldProtocol.equals(
                                                newProtocol
                                        );

                        if (isSwitching) {

                            sendLog(
                                    "[VPN_SWITCH] " +
                                            oldProtocol.toUpperCase(
                                                    Locale.US
                                            ) +
                                            " -> " +
                                            newProtocol.toUpperCase(
                                                    Locale.US
                                            )
                            );

                            stopping = true;

                            synchronized (runtimeLock) {

                                stopRuntime();
                            }

                            active = false;

                            connectionEstablished = false;

                            activeRequest = null;

                            currentEndpoint = "";
                            currentExitIp = "";

                            recoveryRestartPending.set(
                                    false
                            );

                            psiphonSocksPort.set(
                                    0
                            );

                        } else {

                            synchronized (runtimeLock) {

                                stopConnection(false);
                            }
                        }

                        if (generation.get() != session) {

                            sendLog(
                                    "[VPN] Session cancelled before startup"
                            );

                            return;
                        }

                        activeRequest =
                                request;

                        stopping = false;

                        active = true;

                        killSwitch =
                                request.getBooleanExtra(
                                        "killSwitch",
                                        false
                                );

                        sendLog(
                                "[VPN] Starting protocol: " +
                                        request.getStringExtra(
                                                "protocol"
                                        )
                        );

                        runConnection(
                                request,
                                session
                        );
                    }
            );

            return START_STICKY;
        }

        return active
                ? START_STICKY
                : START_NOT_STICKY;
    }

    // ============================================================
    // MAIN CONNECTION
    // ============================================================

    private void runConnection(
            Intent request,
            long session
    ) {

        try {

            currentEndpoint =
                    reliableEndpoint(
                            request
                    );

            String connectionMode =
                    value(
                            request,
                            "connectionMode",
                            "vpn"
                    );

            String protocol =
                    value(
                            request,
                            "protocol",
                            "masque"
                    );

            // ====================================================
            // SMART
            // ====================================================

            if ("smart".equals(connectionMode)) {

                updateState(
                        "smart-testing",
                        getString(
                                R.string.service_smart_testing
                        )
                );

                protocol =
                        chooseSmartProtocol(
                                request,
                                session
                        );

                request.putExtra(
                        "protocol",
                        protocol
                );

                stateStore.edit()
                        .putString(
                                "smartProtocol",
                                protocol
                        )
                        .apply();

                sendLog(
                        "[Smart] Selected " +
                                protocolLabel(protocol)
                );

                currentEndpoint =
                        reliableEndpoint(
                                request
                        );
            }

            updateState(
                    "starting",
                    getString(
                            R.string.service_launching
                    )
            );

            // ====================================================
            // PSIPHON
            // ====================================================

            if ("psiphon".equals(
                    protocol
            )) {

                runPsiphonConnection(
                        request,
                        session
                );

                return;
            }

            if ("tor".equals(
                    protocol
            )) {

                runTorConnection(
                        request,
                        session
                );

                return;
            }

            // ====================================================
            // AETHER
            // ====================================================

            updateState(
                    "scanning",
                    getString(
                            R.string.service_scanning
                    )
            );

            boolean socksReady =
                    startAetherWithMasqueFallback(
                            request,
                            SOCKS_TIMEOUT_MS
                    );

            if (!socksReady) {

                throw new IllegalStateException(
                        aetherExitMessage(
                                "Aether did not open its SOCKS5 listener"
                        )
                );
            }

            // --tor (through): port 1819 = WARP/carrier, port 1820 = Tor exit
            // Route the VPN through 1820 so the public IP is a Tor exit, not WARP.
            if (request.getBooleanExtra("enableTor", false)
                    && !"only".equals(value(request, "tor_mode", "through"))) {
                String torSocks = "127.0.0.1:1820";
                sendLog("[Tor] Waiting for Tor SOCKS on " + torSocks
                        + " (1819 is carrier-only)");
                boolean torReady = waitForSocks(torSocks, Math.max(SOCKS_TIMEOUT_MS, 120_000));
                if (!torReady) {
                    throw new IllegalStateException(
                            aetherExitMessage(
                                    "Tor SOCKS on 1820 did not open — carrier may be up but Tor is not"
                            )
                    );
                }
                request.putExtra("socks", torSocks);
                sendLog("[Tor] Using Tor exit proxy " + torSocks + " for TUN/HEV");
            }

            if (stopping ||
                    generation.get() != session) {

                return;
            }

            connectionEstablished =
                    true;

            // ====================================================
            // MANUAL
            // ====================================================

            if ("manual".equals(
                    connectionMode
            )) {

                connectedAt =
                        System.currentTimeMillis();

                updateState(
                        "connected",
                        getString(
                                R.string.service_proxy_ready
                        )
                );

                updateNotification(
                        getString(
                                R.string.service_proxy_connected
                        )
                );

            } else {

                // =================================================
                // TUN
                // =================================================

                establishVpn(
                        request
                );

                if (stopping ||
                        generation.get() != session) {

                    return;
                }

                connectedAt =
                        System.currentTimeMillis();

                updateState(
                        "connected",
                        getString(
                                R.string.service_protected
                        )
                );

                String notificationText;

                if ("smart".equals(
                        connectionMode
                )) {

                    notificationText =
                            getString(
                                    R.string.service_smart_protected
                            );

                } else {

                    notificationText =
                            getString(
                                    R.string.service_aethon_protected
                            );
                }

                updateNotification(
                        notificationText
                );
            }

            scheduleLocationLookup(
                    request,
                    session
            );

            monitorAether(
                    request,
                    session
            );

        } catch (Exception error) {

            if (generation.get() != session) {

                sendLog(
                        "[VPN] Session " +
                                session +
                                " cancelled."
                );

                return;
            }

            if (stopping) {

                sendLog(
                        "[VPN] Connection stopped intentionally."
                );

                return;
            }

            Log.e(
                    TAG,
                    "Connection failed",
                    error
            );

            sendLog(
                    "[VPN] ERROR: " +
                            safeMessage(error)
            );

            stopping = true;

            try {

                stopRuntime();

            } catch (Throwable cleanupError) {

                Log.w(
                        TAG,
                        "Runtime cleanup failed",
                        cleanupError
                );
            }

            connectionEstablished =
                    false;

            active =
                    false;

            locationLookupSequence.incrementAndGet();

            psiphonSocksPort.set(
                    0
            );

            currentEndpoint =
                    "";

            if (killSwitch) {

                updateState(
                        "blocked",
                        getString(
                                R.string.service_blocked
                        )
                );

                updateNotification(
                        getString(
                                R.string.service_blocked_notification
                        )
                );

                stateStore.edit()
                        .putBoolean(
                                "desiredConnected",
                                true
                        )
                        .apply();

                stopping =
                        false;

                return;
            }

            stateStore.edit()
                    .putBoolean(
                            "desiredConnected",
                            false
                    )
                    .apply();

            updateState(
                    "error",
                    getString(
                            R.string.status_error
                    )
            );

            stopForeground(
                    STOP_FOREGROUND_REMOVE
            );

            stopSelf();
        }
    }


    // ============================================================
    // TOR CONNECTION — Aether v2 official modes (single process):
    //   WARP:  aether --gool --tor   → SOCKS :1819 (WARP) + :1820 (Tor exit)
    //   WG:    aether --wg --tor     → same ports
    //   Direct: aether --tor-only    → SOCKS :1819 (Tor only)
    // See https://github.com/CluvexStudio/Aether Docs/DOCS.en.md#tor
    // ============================================================

    private void runTorConnection(
            Intent request,
            long session
    ) throws Exception {

        boolean direct =
                request.getBooleanExtra("direct_connection", false)
                        || "only".equals(value(request, "tor_mode", "through"));

        String upstreamProtocol =
                request.getStringExtra("upstream_protocol");
        if (upstreamProtocol == null) upstreamProtocol = "";
        upstreamProtocol = upstreamProtocol.trim();

        torExitReady = false;
        // Kill any previous core / bridges so :1818-:1820 are free
        try { stopAetherOnly(); } catch (Throwable ignored) {}
        try { lanSocksBridge.stop(); } catch (Throwable ignored) {}
        try { lanHttpBridge.stop(); } catch (Throwable ignored) {}
        try { Thread.sleep(400); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        sendLog("[Tor] =======================================");
        sendLog("[Tor] Starting Tor connection (Aether single-process)");
        if (direct) {
            sendLog("[Tor] Mode = DIRECT (--tor-only)");
        } else if ("wg".equalsIgnoreCase(upstreamProtocol)) {
            sendLog("[Tor] Mode = --wg --tor  (WireGuard carries Tor → exit :1820)");
        } else {
            sendLog("[Tor] Mode = --gool --tor  (WARP-in-WARP carries Tor → exit :1820)");
            if (!"gool".equalsIgnoreCase(upstreamProtocol)) {
                upstreamProtocol = "gool";
            }
        }

        // Keep user LAN preference for bridge AFTER Tor is up.
        // Aether process itself must ALWAYS bind 127.0.0.1 — never 0.0.0.0 —
        // otherwise Tor bootstraps via socks5://0.0.0.0:1819 and SOCKS fails
        // with "Address already in use".
        final boolean lanSharing = request.getBooleanExtra("lanSharing", false);

        // Free previous LAN bridges that may still hold :1819 / :8080
        try {
            if (lanSocksBridge != null) lanSocksBridge.stop();
        } catch (Throwable ignored) {}
        try {
            lanHttpBridge.stop();
        } catch (Throwable ignored) {}

        Intent torIntent = new Intent(request);
        torIntent.putExtra("enableTor", true);
        torIntent.putExtra("ui_protocol", "tor");
        torIntent.putExtra("connectionMode", value(request, "connectionMode", "vpn"));
        // Tor rides INSIDE the tunnel — no external --upstream
        torIntent.removeExtra("aether_upstream");
        torIntent.putExtra("aether_upstream", "");
        // Core listens only on loopback; LAN is published later via bridge
        torIntent.putExtra("enable_http_proxy", false);
        torIntent.putExtra("lanSharing", false);
        torIntent.putExtra("aether_secondary", false);

        // Through (WARP/WG+Tor):
        //   WARP SOCKS 127.0.0.1:1818  (internal carrier only)
        //   Tor exit   127.0.0.1:1820
        //   LAN public 0.0.0.0:1819 → 1820  (same address users already use)
        // Direct (--tor-only): single SOCKS on :1819; LAN still uses bridge on a free port.
        final int carrierPort = direct ? AETHER_DEFAULT_PORT : AETHER_PSIPHON_UPSTREAM_PORT; // 1819 or 1818

        if (direct) {
            torIntent.putExtra("tor_mode", "only");
            torIntent.putExtra("protocol", "masque");
            torIntent.putExtra("upstream_protocol", "");
            torIntent.putExtra("socks", "127.0.0.1:" + carrierPort);
            torIntent.putExtra("socksPort", carrierPort);
            updateState("starting", getString(R.string.tor_status_connecting_exit));
        } else {
            torIntent.putExtra("tor_mode", "through");
            String carrier = "wg".equalsIgnoreCase(upstreamProtocol) ? "wg" : "gool";
            torIntent.putExtra("protocol", carrier);
            torIntent.putExtra("upstream_protocol", carrier);
            torIntent.putExtra("socks", "127.0.0.1:" + carrierPort);
            torIntent.putExtra("socksPort", carrierPort);
            updateState(
                    "proxy-starting",
                    getString(R.string.tor_status_upstream_starting, protocolLabel(carrier))
            );
        }

        sendLog("[Tor] cmdline will be built as "
                + (direct ? "--tor-only" : ("--" + value(torIntent, "protocol", "gool") + " --tor")));

        startAether(torIntent);

        if (direct) {
            int listen = torIntent.getIntExtra("socksPort", AETHER_DEFAULT_PORT);
            String torSocks = "127.0.0.1:" + listen;
            long torWait = Math.max(SOCKS_TIMEOUT_MS, 180_000L);
            if (!waitForSocksProcess(torSocks, torWait, false)) {
                stopAetherOnly();
                throw new IllegalStateException(
                        aetherExitMessage("Tor SOCKS did not open on " + torSocks)
                );
            }
            // optional readiness
            long readyDeadline = System.currentTimeMillis() + 90_000L;
            while (System.currentTimeMillis() < readyDeadline && !stopping) {
                if (torExitReady) break;
                Process tp = aetherProcess;
                if (tp != null && !tp.isAlive()) break;
                try { Thread.sleep(300); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if (torExitReady) {
                sendLog("[Tor] Confirmed: tor is ready");
            } else {
                sendLog("[Tor] Warning: SOCKS up but 'tor is ready' not seen yet — continuing");
            }
            request.putExtra("socks", torSocks);
            request.putExtra("socksPort", listen);
            if (lanSharing) {
                startTorLanBridgeIfNeeded(request, listen);
            }
        } else {
            // 1) Wait for carrier SOCKS (stable bind, not mid-reconnect)
            String carrierSocks = "127.0.0.1:" + carrierPort;
            long carrierWait = Math.max(SOCKS_TIMEOUT_MS, 120_000L);
            if (!waitForSocksProcess(carrierSocks, carrierWait, false)) {
                stopAetherOnly();
                throw new IllegalStateException(
                        aetherExitMessage("Carrier SOCKS did not open on " + carrierSocks)
                );
            }
            // Give gool a moment after first accept before traffic test
            try { Thread.sleep(800); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            boolean carrierOk = false;
            String lastErr = "";
            for (int attempt = 1; attempt <= 4; attempt++) {
                try {
                    long latency = socksConnectMillis(carrierSocks, "1.1.1.1", 443, 12_000);
                    sendLog("[Tor] Carrier SOCKS test OK: " + latency + "ms (attempt " + attempt + ")");
                    carrierOk = true;
                    break;
                } catch (Throwable error) {
                    lastErr = safeMessage(error);
                    sendLog("[Tor] Carrier test attempt " + attempt + " failed: " + lastErr);
                    try { Thread.sleep(1500); } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            if (!carrierOk) {
                stopAetherOnly();
                throw new IllegalStateException(
                        "Carrier SOCKS opened but cannot pass traffic: " + lastErr
                );
            }
            updateState(
                    "proxy-connected",
                    getString(R.string.tor_status_upstream_ready,
                            protocolLabel(value(torIntent, "protocol", "gool")))
            );
            sendLog("[Tor] Carrier ready on " + carrierSocks + " — waiting for Tor exit");

            // 2) Tor exit: try :1820 first (docs default), then carrierPort+1
            updateState("starting", getString(R.string.tor_status_connecting_exit));
            long torWait = Math.max(SOCKS_TIMEOUT_MS, 180_000L);
            String torSocks = null;
            int[] torCandidates = new int[] { 1820 };
            long deadline = System.currentTimeMillis() + torWait;
            while (System.currentTimeMillis() < deadline && !stopping) {
                Process p = aetherProcess;
                if (p != null && !p.isAlive()) {
                    break;
                }
                for (int port : torCandidates) {
                    if (port == carrierPort) continue;
                    String candidate = "127.0.0.1:" + port;
                    try (java.net.Socket socket = new java.net.Socket()) {
                        socket.connect(new java.net.InetSocketAddress("127.0.0.1", port), 600);
                        torSocks = candidate;
                        break;
                    } catch (Exception ignored) {
                    }
                }
                if (torSocks != null) break;
                try { Thread.sleep(500); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if (torSocks == null) {
                stopAetherOnly();
                throw new IllegalStateException(
                        aetherExitMessage("Tor SOCKS did not open (tried 1820/" + (carrierPort + 1) + "/1819)")
                );
            }
            long readyDeadline = System.currentTimeMillis() + 90_000L;
            while (System.currentTimeMillis() < readyDeadline && !stopping) {
                if (torExitReady) break;
                Process tp = aetherProcess;
                if (tp != null && !tp.isAlive()) break;
                try { Thread.sleep(300); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if (torExitReady) {
                sendLog("[Tor] Confirmed: tor is ready");
            } else {
                sendLog("[Tor] Warning: SOCKS up but 'tor is ready' not seen yet — continuing");
            }
            sendLog("[Tor] Tor SOCKS ready: " + torSocks);

            try {
                long latency = socksConnectMillis(torSocks, "check.torproject.org", 443, 20_000);
                sendLog("[Tor] Exit connectivity OK: " + latency + "ms");
            } catch (Throwable error) {
                sendLog("[Tor] Exit test warning: " + safeMessage(error));
            }

            // TUN must use Tor exit, not WARP carrier
            int torPort = HostPort.parse(torSocks).port;
            request.putExtra("socks", torSocks);
            request.putExtra("socksPort", torPort);
            if (lanSharing) {
                startTorLanBridgeIfNeeded(request, torPort);
            }
        }

        if (stopping || generation.get() != session) {
            return;
        }

        connectionEstablished = true;

        String connectionMode = value(request, "connectionMode", "vpn");
        if ("manual".equals(connectionMode)) {
            connectedAt = System.currentTimeMillis();
            updateState("connected", getString(R.string.service_proxy_ready));
            updateNotification(getString(R.string.service_proxy_connected));
        } else {
            establishVpn(request);
            if (stopping || generation.get() != session) {
                return;
            }
            connectedAt = System.currentTimeMillis();
            updateState("connected", getString(R.string.service_protected));
            updateNotification(getString(R.string.service_protected));
        }

        scheduleLocationLookup(request, session);
        sendLog("[Tor] =======================================");
        sendLog("[Tor] CONNECTED (traffic via Tor SOCKS " + socksAddress(request) + ")");
        if (lanSharing) {
            int torPort = request.getIntExtra("socksPort", 1820);
            if (torPort <= 0) torPort = 1820;
            startTorLanBridgeIfNeeded(request, torPort);
            int pub = request.getIntExtra("lan_socks_port", TOR_LAN_PUBLIC_PORT);
            String lanIp = resolveLanIpv4();
            sendLog("[Tor] LAN sharing ON — SOCKS5 "
                    + (lanIp != null ? lanIp : "<phone-wifi-ip>")
                    + ":" + pub + " → Tor exit :" + torPort);
        }
    }

    // ============================================================
    // PSIPHON CONNECTION
    // ============================================================

    private void runPsiphonConnection(
            Intent request,
            long session
    ) throws Exception {

        boolean directConnection =
                request.getBooleanExtra(
                        "direct_connection",
                        true
                );

        String upstreamProtocol =
                request.getStringExtra(
                        "upstream_protocol"
                );

        int upstreamPort =
                0;

        // ========================================================
        // RESET OLD PSIPHON PORT
        // ========================================================

        psiphonSocksPort.set(
                0
        );

        sendLog(
                "[Psiphon] ======================================="
        );

        sendLog(
                "[Psiphon] Starting Psiphon connection"
        );

        sendLog(
                "[Psiphon] Mode = " +
                        (
                                directConnection
                                        ? "DIRECT"
                                        : "AETHER -> PSIPHON"
                        )
        );

        // ========================================================
        // AETHER UPSTREAM
        // ========================================================

        if (!directConnection &&
                upstreamProtocol != null &&
                !upstreamProtocol.trim().isEmpty()) {

            upstreamPort =
                    AETHER_PSIPHON_UPSTREAM_PORT;

            updateState(
                    "proxy-starting",
                    "Launching upstream: " +
                            protocolLabel(
                                    upstreamProtocol
                            )
            );

            Intent proxyIntent =
                    new Intent(request);

            proxyIntent.putExtra(
                    "protocol",
                    upstreamProtocol
            );

            proxyIntent.putExtra(
                    "connectionMode",
                    "manual"
            );

            proxyIntent.putExtra(
                    "socksPort",
                    upstreamPort
            );

            /*
             * CRITICAL:
             * This internal Aether upstream MUST only listen on 127.0.0.1:1818.
             * We explicitly set "socks" to override any user-defined default.
             */
            proxyIntent.putExtra(
                    "socks",
                    "127.0.0.1:" + upstreamPort
            );

            proxyIntent.putExtra(
                    "lanSharing",
                    false
            );
            // Upstream carrier does not need public HTTP
            proxyIntent.putExtra("enable_http_proxy", false);

            /*
             * IMPORTANT:
             * This Aether is only an upstream proxy.
             * We haven't built the VPN yet.
             */

            sendLog(
                    "[Psiphon] Starting Aether upstream..."
            );

            sendLog(
                    "[Psiphon] Aether protocol = " +
                            protocolLabel(
                                    upstreamProtocol
                            )
            );

            sendLog(
                    "[Psiphon] Aether SOCKS = 127.0.0.1:" +
                            upstreamPort
            );

            startAether(
                    proxyIntent
            );

            String upstreamSocks =
                    socksAddress(
                            proxyIntent
                    );

            if (!waitForSocks(
                    upstreamSocks,
                    SOCKS_TIMEOUT_MS
            )) {

                stopAetherOnly();

                throw new IllegalStateException(
                        "Aether upstream SOCKS did not start: " +
                                upstreamSocks
                );
            }

            sendLog(
                    "[Psiphon] Aether SOCKS listener is reachable"
            );

            // ====================================================
            // TEST AETHER SOCKS
            // ====================================================

            try {

                long latency =
                        socksConnectMillis(
                                upstreamSocks,
                                "1.1.1.1",
                                443,
                                10_000
                        );

                sendLog(
                        "[Psiphon] Aether upstream SOCKS test OK: " +
                                latency +
                                "ms"
                );

            } catch (Throwable error) {

                stopAetherOnly();

                throw new IllegalStateException(
                        "Aether upstream SOCKS test failed: " +
                                safeMessage(error),
                        error
                );
            }

            updateState(
                    "proxy-connected",
                    "Upstream proxy connected"
            );

            sendLog(
                    "[Psiphon] ---------------------------------------"
            );

            sendLog(
                    "[Psiphon] AETHER UPSTREAM READY"
            );

            sendLog(
                    "[Psiphon] Aether SOCKS = " +
                            upstreamSocks
            );

            sendLog(
                    "[Psiphon] ---------------------------------------"
            );
        } else {

            sendLog(
                    "[Psiphon] No Aether upstream. Psiphon will connect DIRECT."
            );
        }

        // ========================================================
        // PSIPHON CONFIG
        // ========================================================

        updateState(
                "starting",
                "Connecting to Psiphon..."
        );

        String regionCode =
                value(
                        request,
                        "region",
                        ""
                );

        JSONObject config =
                new JSONObject();

        config.put(
                "ClientPlatform",
                "Android"
        );

        if (!regionCode.isEmpty()) {

            String egress = regionCode.toUpperCase(Locale.US);
            config.put("EgressRegion", egress);
            sendLog("[Psiphon] EgressRegion = " + egress + " (strict filter)");
        } else {
            sendLog("[Psiphon] EgressRegion = AUTO (no country filter)");
        }

        /*
         * Known project config.
         */
        config.put(
                "SponsorId",
                "1BC527D3D09985CF"
        );

        config.put(
                "PropagationChannelId",
                "92AACC5BABE0944C"
        );

        File dataDir =
                new File(
                        getFilesDir(),
                        "psiphon_core_data"
                );

        if (!dataDir.exists() &&
                !dataDir.mkdirs()) {

            sendLog(
                    "[Psiphon] Warning: could not create data directory"
            );
        }

        config.put(
                "DataRootDirectory",
                dataDir.getAbsolutePath()
        );

        config.put(
                "DisableLocalHTTPProxy",
                false
        );

        config.put(
                "DisableLocalSOCKSProxy",
                false
        );

        /*
         * Psiphon SOCKS is loopback-only (many AARs ignore 0.0.0.0 bind).
         * LAN sharing: core on 127.0.0.1:1825 + LanSocksBridge on 0.0.0.0:1819.
         */
        boolean lanSharing = request.getBooleanExtra("lanSharing", false);
        int psiphonInternalSocks = lanSharing ? 1825 : 1819;

        config.put("LocalSOCKSProxyPort", psiphonInternalSocks);
        config.put("LocalHTTPProxyPort", lanSharing ? 1826 : 1820);
        config.put("LocalSOCKSProxyListenInterface", "127.0.0.1");
        config.put("LocalHTTPProxyListenInterface", "127.0.0.1");

        if (lanSharing) {
            sendLog(
                    "[Psiphon] LAN sharing ON — core SOCKS 127.0.0.1:"
                            + psiphonInternalSocks
                            + ", public bridge 0.0.0.0:1819"
            );
        } else {
            sendLog("[Psiphon] LAN sharing OFF — SOCKS only 127.0.0.1:1819");
        }

        config.put(
                "UseIndistinguishableTLS",
                true
        );

        config.put(
                "EstablishTunnelTimeoutSeconds",
                0
        );

        config.put(
                "EmitBytesTransferred",
                true
        );

        config.put(
                "EmitDiagnosticNotices",
                true
        );

        // ========================================================
        // AETHER -> PSIPHON
        // ========================================================

        if (upstreamPort > 0) {

            String upstreamUrl =
                    "socks5://127.0.0.1:" +
                            upstreamPort;

            config.put(
                    "UpstreamProxyURL",
                    upstreamUrl
            );

            /*
             * MSN-GUARD: when Psiphon dials through SOCKS5 upstream (Aether),
             * only TCP-based tunnel protocols work. UDP/QUIC/WebRTC cannot
             * cross a SOCKS5 CONNECT hop and waste the establish budget.
             */
            org.json.JSONArray tcpOnly =
                    new org.json.JSONArray();
            tcpOnly.put("OSSH");
            tcpOnly.put("TLS-OSSH");
            tcpOnly.put("SSH");
            tcpOnly.put("UNFRONTED-MEEK-OSSH");
            tcpOnly.put("UNFRONTED-MEEK-HTTPS-OSSH");
            tcpOnly.put("UNFRONTED-MEEK-SESSION-TICKET-OSSH");
            tcpOnly.put("FRONTED-MEEK-OSSH");
            tcpOnly.put("FRONTED-MEEK-HTTP-OSSH");
            tcpOnly.put("SHADOWSOCKS-OSSH");
            config.put("LimitTunnelProtocols", tcpOnly);
            config.put("InitialLimitTunnelProtocols", tcpOnly);
            config.put("InitialLimitTunnelProtocolsCandidateCount", 10);

            sendLog(
                    "[Psiphon] UpstreamProxyURL = " +
                            upstreamUrl
            );
            sendLog(
                    "[Psiphon] LimitTunnelProtocols = TCP-only (SOCKS upstream)"
            );

        } else {

            sendLog(
                    "[Psiphon] UpstreamProxyURL = DIRECT"
            );
        }

        long versionCode =
                1;

        try {

            if (Build.VERSION.SDK_INT >=
                    Build.VERSION_CODES.P) {

                versionCode =
                        getPackageManager()
                                .getPackageInfo(
                                        getPackageName(),
                                        0
                                )
                                .getLongVersionCode();

            } else {

                versionCode =
                        getPackageManager()
                                .getPackageInfo(
                                        getPackageName(),
                                        0
                                )
                                .versionCode;
            }

        } catch (Exception ignored) {
        }

        config.put(
                "ClientVersion",
                String.valueOf(
                        versionCode
                )
        );

        sendLog(
                "[Psiphon] Region = " +
                        (
                                regionCode.isEmpty()
                                        ? "AUTO"
                                        : regionCode.toUpperCase(
                                        Locale.US
                                )
                        )
        );

        /*
         * ========================================================
         * PACKET TUNNEL (same as sourceandroidir/Vp)
         *
         * TUN is created FIRST. Psiphon core owns packet routing via
         * PacketTunnelTunFileDescriptor + setVpnMode(true).
         * No HEV/tun2socks on this path — that is why standalone Vp
         * routes all apps, while HEV path only worked for in-app tests.
         * ========================================================
         */
        sendLog(
                "[Psiphon] Establishing PacketTunnel TUN (vpnMode=true, no HEV)"
        );

        establishPacketTunnelOnly(request);

        if (vpnInterface == null) {
            throw new IllegalStateException(
                    "PacketTunnel TUN fd missing after establish"
            );
        }

        int tunFd = vpnInterface.getFd();
        config.put(
                "PacketTunnelTunFileDescriptor",
                tunFd
        );

        sendLog(
                "[Psiphon] PACKET_TUNNEL_FD_CONFIGURED=" + tunFd
        );

        sendLog(
                "[Psiphon] Starting tunnel core (vpnMode=true)..."
        );

        // ========================================================
        // START PSIPHON — vpnMode true (Vp architecture)
        // ========================================================

        lastPsiphonConfig = config.toString();

        psiphonCore.start(
                lastPsiphonConfig,
                true
        );

        // ========================================================
        // WAIT FOR PSIPHON
        // ========================================================

        boolean vpnEstablished =
                false;

        long startTime =
                System.currentTimeMillis();

        while (!stopping &&
                generation.get() == session) {

            Thread.sleep(
                    500
            );

            PsiphonCore.State state =
                    psiphonCore.getState();

            int socksPort =
                    psiphonSocksPort.get();

            /*
             * Debug state
             */
            if (state ==
                    PsiphonCore.State.CONNECTING) {

                updateState(
                        "starting",
                        "Psiphon connecting..."
                );
            }

            if (state ==
                    PsiphonCore.State.IDLE) {

                sendLog(
                        "[Psiphon] Core returned to IDLE before VPN establishment"
                );

                break;
            }

            // ====================================================
            // PSIPHON CONNECTED
            // ====================================================

            if (state ==
                    PsiphonCore.State.CONNECTED &&
                    socksPort > 0 &&
                    !vpnEstablished) {

                String psiphonSocks =
                        "127.0.0.1:" +
                                socksPort;

                sendLog(
                        "[Psiphon] ======================================="
                );

                sendLog(
                        "[Psiphon] PSIPHON TUNNEL CONNECTED"
                );

                sendLog(
                        "[Psiphon] Final Psiphon SOCKS = " +
                                psiphonSocks
                );

                if (upstreamPort > 0) {

                    sendLog(
                            "[Psiphon] Aether upstream = 127.0.0.1:" +
                                    upstreamPort
                    );

                } else {

                    sendLog(
                            "[Psiphon] Aether upstream = DIRECT"
                    );
                }

                sendLog(
                        "[Psiphon] ======================================="
                );

                // =================================================
                // TEST LOCAL PSIPHON SOCKS
                // =================================================

                if (!waitForLocalSocket(
                        "127.0.0.1",
                        socksPort,
                        10_000
                )) {

                    throw new IllegalStateException(
                            "Psiphon SOCKS listener is not reachable: " +
                                    psiphonSocks
                    );
                }

                sendLog(
                        "[Psiphon] Final SOCKS TCP listener OK"
                );

                // =================================================
                // TEST SOCKS5 CONNECT
                // =================================================

                try {

                    long testMs =
                            socksConnectMillis(
                                    psiphonSocks,
                                    "1.1.1.1",
                                    443,
                                    10_000
                            );

                    sendLog(
                            "[Psiphon] FINAL SOCKS CONNECT OK: " +
                                    testMs +
                                    "ms"
                    );

                } catch (Throwable error) {

                    throw new IllegalStateException(
                            "Psiphon final SOCKS CONNECT failed: " +
                                    safeMessage(error),
                            error
                    );
                }

                // =================================================
                // TEST HTTPS THROUGH PSIPHON
                // =================================================

                try {

                    String ipResponse =
                            socksHttpGet(
                                    psiphonSocks,
                                    "api.ipify.org",
                                    "/?format=json"
                            );

                    sendLog(
                            "[Psiphon] FINAL SOCKS PUBLIC IP = " +
                                    ipResponse
                    );

                } catch (Throwable error) {

                    throw new IllegalStateException(
                            "Psiphon SOCKS works but HTTPS through Psiphon failed: " +
                                    safeMessage(error),
                            error
                    );
                }

                // =================================================
                // CREATE FINAL VPN INTENT
                // =================================================

                Intent vpnIntent =
                        new Intent(request);

                /*
                 * CRITICAL:
                 *
                 * اینجا نباید 1818 قرار بگیرد.
                 *
                 * باید پورت واقعی Psiphon قرار بگیرد.
                 */

                vpnIntent.putExtra(
                        "socks",
                        psiphonSocks
                );

                vpnIntent.putExtra(
                        "socksPort",
                        socksPort
                );

                /*
                 * Packet tunnel: TUN already up and owned by Psiphon core.
                 * Do NOT start HEV — that was the whole-app routing bug.
                 */
                sendLog(
                        "[Psiphon] PacketTunnel active — skipping HEV bridge"
                );

                // Keep vpnIntent socks port for status/UI only
                // establishVpn/HEV intentionally not called.

                if (stopping ||
                        generation.get() != session) {

                    return;
                }

                /*
                 * از اینجا به بعد activeRequest باید
                 * همان Intent باشد که SOCKS سایفون را دارد.
                 */
                activeRequest =
                        vpnIntent;

                vpnEstablished =
                        true;

                connectionEstablished =
                        true;

                connectedAt =
                        System.currentTimeMillis();

                // Public SOCKS :1819 + HTTP :8080 for LAN clients
                startLanPublishersIfNeeded(vpnIntent, socksPort);

                updateState(
                        "connected",
                        getString(
                                R.string.service_protected
                        )
                );

                updateNotification(
                        getString(
                                R.string.service_aethon_protected
                        )
                );

                scheduleLocationLookup(
                        vpnIntent,
                        session
                );

                sendLog(
                        "[Psiphon] ======================================="
                );

                sendLog(
                        "[Psiphon] PROTECTED CONNECTION ACTIVE"
                );

                sendLog(
                        "[Psiphon] Android TUN -> Psiphon PacketTunnel"
                                + (upstreamPort > 0
                                ? " -> Aether -> Internet"
                                : " -> Internet")
                );

                if (upstreamPort > 0) {

                    sendLog(
                            "[Psiphon] Psiphon -> Aether -> Internet"
                    );

                } else {

                    sendLog(
                            "[Psiphon] Psiphon -> Internet"
                    );
                }

                sendLog(
                        "[Psiphon] ======================================="
                );

                sendLog(
                        "[VPN_SWITCH] Switch to PSIPHON completed successfully."
                );
            }

            // ====================================================
            // TIMEOUT
            // ====================================================

            if (!vpnEstablished &&
                    System.currentTimeMillis() -
                            startTime >
                            120_000L) {

                sendLog(
                        "[Psiphon] Connection timeout after 120 seconds"
                );

                break;
            }
        }



        if (!vpnEstablished &&
                !stopping &&
                generation.get() == session) {

            throw new IllegalStateException(
                    "Psiphon failed to establish tunnel"
            );
        }

        // ========================================================
        // KEEP ALIVE
        // ========================================================

        /*
         * بعد از بالا آمدن TUN، NetworkMonitor ممکن است تانل را قطع کند.
         * onDisconnected در صورت vpnUp فقط soft-restart می‌زند.
         * اینجا هم اگر تانل برنگشت، یک soft-restart دیگر می‌زنیم.
         */
        while (!stopping &&
                generation.get() == session) {

            Thread.sleep(1000);

            PsiphonCore.State state =
                    psiphonCore.getState();

            if (state == PsiphonCore.State.CONNECTED ||
                    state == PsiphonCore.State.CONNECTING) {
                continue;
            }

            sendLog(
                    "[Psiphon] Tunnel state=" +
                            state +
                            " (vpnEstablished=" +
                            vpnEstablished +
                            ")"
            );

            if (stopping ||
                    generation.get() != session) {
                return;
            }

            if (!vpnEstablished) {
                throw new IllegalStateException(
                        "Psiphon tunnel disconnected before VPN was ready"
                );
            }

            sendLog(
                    "[Psiphon] Waiting for soft recovery after tunnel death..."
            );

            long waitUntil =
                    System.currentTimeMillis() + 20_000L;

            while (!stopping &&
                    generation.get() == session &&
                    System.currentTimeMillis() < waitUntil) {

                Thread.sleep(500);

                if (psiphonCore.getState() ==
                        PsiphonCore.State.CONNECTED) {

                    sendLog(
                            "[Psiphon] Tunnel came back while waiting"
                    );
                    break;
                }
            }

            if (generation.get() != session ||
                    stopping) {
                return;
            }

            if (psiphonCore.getState() ==
                    PsiphonCore.State.CONNECTED) {
                continue;
            }

            softRestartPsiphonOnly(
                    "Keep-alive: still not CONNECTED after wait"
            );

            long wait2 =
                    System.currentTimeMillis() + 45_000L;

            while (!stopping &&
                    generation.get() == session &&
                    System.currentTimeMillis() < wait2) {

                Thread.sleep(500);

                if (psiphonCore.getState() ==
                        PsiphonCore.State.CONNECTED) {

                    sendLog(
                            "[Psiphon] Tunnel OK after soft-restart"
                    );
                    break;
                }
            }

            if (generation.get() != session ||
                    stopping) {
                return;
            }

            if (psiphonCore.getState() !=
                    PsiphonCore.State.CONNECTED) {

                throw new IllegalStateException(
                        "Psiphon tunnel died after VPN and soft-restart failed"
                );
            }
        }
    }

    /**
     * Best-effort IPv4 of the active Wi-Fi / Ethernet interface (not VPN, not loopback).
     * Used only for user-facing LAN sharing hints in the log.
     */
    private String resolveLanIpv4() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> ifaces =
                    java.net.NetworkInterface.getNetworkInterfaces();
            if (ifaces == null) {
                return null;
            }
            while (ifaces.hasMoreElements()) {
                java.net.NetworkInterface nif = ifaces.nextElement();
                if (nif == null || !nif.isUp() || nif.isLoopback() || nif.isVirtual()) {
                    continue;
                }
                String name = nif.getName() != null ? nif.getName().toLowerCase() : "";
                if (name.startsWith("tun") || name.startsWith("ppp") ||
                        name.startsWith("rmnet") || name.contains("vpn")) {
                    continue;
                }
                java.util.Enumeration<java.net.InetAddress> addrs = nif.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress a = addrs.nextElement();
                    if (a instanceof java.net.Inet4Address && !a.isLoopbackAddress()) {
                        String ip = a.getHostAddress();
                        if (ip != null && (ip.startsWith("192.168.") ||
                                ip.startsWith("10.") ||
                                ip.startsWith("172."))) {
                            return ip;
                        }
                    }
                }
            }
            ifaces = java.net.NetworkInterface.getNetworkInterfaces();
            while (ifaces != null && ifaces.hasMoreElements()) {
                java.net.NetworkInterface nif = ifaces.nextElement();
                if (nif == null || !nif.isUp() || nif.isLoopback()) {
                    continue;
                }
                java.util.Enumeration<java.net.InetAddress> addrs = nif.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress a = addrs.nextElement();
                    if (a instanceof java.net.Inet4Address && !a.isLoopbackAddress()) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }


    /**
     * Tor LAN sharing: WARP holds :1819 and Tor exit holds :1820 on loopback.
     * Publish Tor exit to the LAN on :1080 so other devices use phone_ip:1080 → 127.0.0.1:1820.
     */
    private void startTorLanBridgeIfNeeded(Intent request, int torSocksPort) {
        if (request == null || !request.getBooleanExtra("lanSharing", false)) {
            sendLog("[LAN][Tor] bridge not started (lanSharing off)");
            return;
        }
        if (torSocksPort <= 0) {
            sendLog("[LAN][Tor] bridge skipped: invalid Tor SOCKS port");
            return;
        }
        // Already listening — keep 1819; avoid stop/rebind race (EADDRINUSE)
        if (lanSocksBridge.isRunning()
                && lanSocksBridge.getPublicPort() == TOR_LAN_PUBLIC_PORT) {
            sendLog("[LAN][Tor] bridge already UP on 0.0.0.0:" + TOR_LAN_PUBLIC_PORT
                    + " → 127.0.0.1:" + torSocksPort);
            request.putExtra("lan_socks_port", TOR_LAN_PUBLIC_PORT);
            if (activeRequest != null) {
                activeRequest.putExtra("lan_socks_port", TOR_LAN_PUBLIC_PORT);
            }
            return;
        }
        try {
            lanSocksBridge.stop();
        } catch (Throwable ignored) {
        }
        try {
            Thread.sleep(250);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        request.putExtra("lanSharing", true);
        if (activeRequest != null) {
            activeRequest.putExtra("lanSharing", true);
            activeRequest.putExtra("socksPort", torSocksPort);
            activeRequest.putExtra("socks", "127.0.0.1:" + torSocksPort);
        }

        int publicPort = TOR_LAN_PUBLIC_PORT;
        boolean ok = lanSocksBridge.start(publicPort, torSocksPort);
        if (!ok) {
            // Fallback if 1819 still held (e.g. direct tor-only on 1819)
            publicPort = 1080;
            sendLog("[LAN][Tor] port 1819 busy — trying " + publicPort);
            ok = lanSocksBridge.start(publicPort, torSocksPort);
        }
        if (ok) {
            String lanIp = resolveLanIpv4();
            sendLog("[LAN][Tor] SOCKS bridge UP (Tor exit shared on LAN)");
            sendLog("[LAN][Tor]   • this phone : 127.0.0.1:" + publicPort
                    + " → 127.0.0.1:" + torSocksPort);
            if (lanIp != null) {
                sendLog("[LAN][Tor]   • other devices (SOCKS5): " + lanIp + ":" + publicPort);
            } else {
                sendLog("[LAN][Tor]   • other devices (SOCKS5): <phone-wifi-ip>:" + publicPort);
            }
            sendLog("[LAN][Tor]   • upstream Tor exit : 127.0.0.1:" + torSocksPort);
            // Persist for UI / guide
            request.putExtra("lan_socks_port", publicPort);
            if (activeRequest != null) {
                activeRequest.putExtra("lan_socks_port", publicPort);
            }
        } else {
            sendLog("[LAN][Tor] SOCKS bridge FAILED (tried 1819 and 1080)");
        }
    }

    /**
     * When LAN sharing is enabled, Psiphon keeps SOCKS on 127.0.0.1 only.
     * This starts a public bridge on 0.0.0.0:1819 → 127.0.0.1:{socksPort}
     * so v2ray / other devices on Wi-Fi can use phone_ip:1819.
     */
    private void startLanSocksBridgeIfNeeded(Intent request, int socksPort) {
        try {
            lanSocksBridge.stop();
        } catch (Throwable ignored) {
        }

        if (request == null || !request.getBooleanExtra("lanSharing", false)) {
            sendLog("[LAN] bridge not started (lanSharing off)");
            return;
        }
        if (socksPort <= 0) {
            sendLog("[Psiphon] LAN bridge skipped: invalid SOCKS port");
            return;
        }

        // Public port always 1819 for clients; core may be on 1825
        final int publicPort = 1819;
        boolean ok = lanSocksBridge.start(publicPort, socksPort);
        if (ok) {
            String lanIp = resolveLanIpv4();
            sendLog("[LAN] SOCKS bridge UP");
            sendLog("[LAN]   • this phone : 127.0.0.1:" + publicPort);
            if (lanIp != null) {
                sendLog("[LAN]   • LAN / v2ray : " + lanIp + ":" + publicPort);
            } else {
                sendLog("[LAN]   • LAN / v2ray : <phone-wifi-ip>:" + publicPort);
            }
            sendLog("[LAN]   • upstream core : 127.0.0.1:" + socksPort);
        } else {
            sendLog("[LAN] SOCKS bridge FAILED to bind 0.0.0.0:" + publicPort);
        }
    }

    /**
     * Public HTTP CONNECT on 0.0.0.0:8080 → 127.0.0.1:{httpInternalPort}
     * Works for MASQUE / WG / WARP / Psiphon / Tor when LAN sharing is on.
     */
    private void startLanHttpBridgeIfNeeded(Intent request, int httpInternalPort) {
        try {
            lanHttpBridge.stop();
        } catch (Throwable ignored) {
        }

        if (request == null || !request.getBooleanExtra("lanSharing", false)) {
            sendLog("[LAN] HTTP bridge not started (lanSharing off)");
            return;
        }
        if (httpInternalPort <= 0) {
            sendLog("[LAN] HTTP bridge skipped: invalid port");
            return;
        }

        boolean ok = lanHttpBridge.start(LAN_HTTP_PORT, httpInternalPort);
        if (ok) {
            String lanIp = resolveLanIpv4();
            sendLog("[LAN] HTTP CONNECT bridge UP");
            sendLog("[LAN]   • this phone : 127.0.0.1:" + LAN_HTTP_PORT);
            if (lanIp != null) {
                sendLog("[LAN]   • LAN HTTP : " + lanIp + ":" + LAN_HTTP_PORT);
            } else {
                sendLog("[LAN]   • LAN HTTP : <phone-wifi-ip>:" + LAN_HTTP_PORT);
            }
            sendLog("[LAN]   • upstream core : 127.0.0.1:" + httpInternalPort);
        } else {
            sendLog("[LAN] HTTP bridge FAILED to bind 0.0.0.0:" + LAN_HTTP_PORT);
        }
    }

    /** Start both SOCKS(:1819) and HTTP(:8080) LAN publishers when enabled. */
    private void startLanPublishersIfNeeded(Intent request, int socksPort) {
        // Tor: keep single bridge 0.0.0.0:1819 → Tor exit; skip HTTP (no Aether HTTP on :18080)
        String proto = value(request, "protocol", "");
        String ui = value(request, "ui_protocol", "");
        if ("tor".equals(proto) || "tor".equals(ui)) {
            startTorLanBridgeIfNeeded(request, socksPort > 0 ? socksPort : 1820);
            return;
        }
        startLanSocksBridgeIfNeeded(request, socksPort);
        int httpInternal = LAN_HTTP_INTERNAL_PORT;
        if ("psiphon".equals(proto)) {
            httpInternal = request.getBooleanExtra("lanSharing", false) ? 1826 : 1820;
        }
        startLanHttpBridgeIfNeeded(request, httpInternal);
    }

    // ============================================================
    // PACKET TUNNEL ONLY (Psiphon vpnMode=true — same as Vp project)
    // TUN for whole-device routing; Psiphon core reads the fd.
    // No HEV / TProxy on this path.
    // ============================================================

    private void establishPacketTunnelOnly(
            Intent request
    ) throws Exception {

        // Stop HEV if a previous session left it running
        if (bridgeStarted) {
            try {
                TProxyService.TProxyStopService();
            } catch (Throwable error) {
                Log.w(TAG, "Could not stop previous HEV", error);
            }
            bridgeStarted = false;
        }

        if (vpnInterface != null) {
            try {
                vpnInterface.close();
            } catch (Exception ignored) {
            }
            vpnInterface = null;
        }

        sendLog("[VPN] Creating PacketTunnel TUN (no HEV)");

        Builder builder = new Builder();
        builder.setSession("Baran Psiphon");
        builder.setMtu(1500);

        // Same address scheme as typical Psiphon packet tunnel samples
        builder.addAddress("10.0.0.2", 32);
        builder.addRoute("0.0.0.0", 0);
        builder.addDnsServer("8.8.8.8");
        builder.addDnsServer("1.1.1.1");

        try {
            builder.addAddress("fd00:0:0:0:0:0:0:2", 128);
            builder.addRoute("::", 0);
        } catch (Throwable ignored) {
            // IPv6 optional
        }

        /*
         * LAN sharing: keep private ranges on the physical interface so that
         * other devices can reach LocalSOCKS on 0.0.0.0:1819 and so the
         * phone can talk to LAN peers without hair-pinning into TUN.
         * (Only when lanSharing or routing=bypass-local.)
         */
        boolean lanSharing = request.getBooleanExtra("lanSharing", false);
        String routing = value(request, "routing", "bypass-local");
        if (lanSharing || "bypass-local".equals(routing)) {
            try {
                // Exclude RFC1918 from VPN so LAN stays direct
                // (Android ignores addRoute exclusions; we use explicit
                // smaller routes only if full route is not used — here we
                // rely on disallowed app + protect for core, and for LAN
                // clients inbound to phone IP is still delivered to wlan.)
                sendLog("[VPN] LAN-aware routing (lanSharing=" + lanSharing + ")");
            } catch (Throwable ignored) {
            }
        }

        // CRITICAL: never route our own package into TUN (loop).
        // Also required so Aether + Psiphon sockets can leave via protect().
        try {
            builder.addDisallowedApplication(getPackageName());
            sendLog("[VPN] addDisallowedApplication(self) OK");
        } catch (Exception e) {
            sendLog("[VPN] addDisallowedApplication failed: " + e.getMessage());
        }

        // Optional split-apps from request / prefs if helper exists
        try {
            applySplitApps(builder, request);
        } catch (Throwable t) {
            Log.w(TAG, "applySplitApps skipped", t);
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            try {
                builder.setMetered(false);
            } catch (Throwable ignored) {
            }
        }

        ParcelFileDescriptor pfd = builder.establish();
        if (pfd == null) {
            if (VpnService.prepare(this) != null) {
                throw new IllegalStateException(
                        "VPN permission revoked or not granted"
                );
            }
            throw new IllegalStateException(
                    "VpnService.Builder.establish() returned null"
            );
        }

        vpnInterface = pfd;
        sendLog(
                "[VPN] PacketTunnel TUN established fd=" +
                        pfd.getFd()
        );
    }

    // ============================================================
    // ANDROID VPN + HEV
    // ============================================================

    private void establishVpn(
            Intent request
    ) throws Exception {

        // ========================================================
        // STOP OLD HEV
        // ========================================================

        if (bridgeStarted) {

            try {

                TProxyService.TProxyStopService();

            } catch (Throwable error) {

                Log.w(
                        TAG,
                        "Could not stop previous HEV",
                        error
                );
            }

            bridgeStarted =
                    false;
        }

        // ========================================================
        // CLOSE OLD TUN
        // ========================================================

        if (vpnInterface != null) {

            try {

                vpnInterface.close();

            } catch (Exception ignored) {
            }

            vpnInterface =
                    null;
        }

        HostPort socks =
                HostPort.parse(
                        socksAddress(
                                request
                        )
                );

        sendLog(
                "[VPN] Creating Android TUN"
        );

        sendLog(
                "[VPN] HEV SOCKS target = " +
                        socks.host +
                        ":" +
                        socks.port
        );

        // ========================================================
        // BUILDER
        // ========================================================

        Builder builder =
                new Builder()
                        .setSession(
                                getString(
                                        R.string.app_name
                                )
                        )
                        .setMtu(
                                request.getIntExtra(
                                        "mtu",
                                        1500
                                )
                        )
                        .setBlocking(false)
                        .addAddress(
                                "198.18.0.1",
                                30
                        )
                        .addAddress(
                                "fc00::1",
                                126
                        );

        String routing =
                value(
                        request,
                        "routing",
                        "full"
                );

        // ========================================================
        // ROUTING
        // ========================================================

        if ("bypass-local".equals(
                routing
        )) {

            sendLog(
                    "[VPN] Routing = BYPASS LOCAL"
            );

            addPublicRoutes(
                    builder
            );

        } else {

            sendLog(
                    "[VPN] Routing = FULL"
            );

            builder.addRoute(
                    "0.0.0.0",
                    0
            );

            builder.addRoute(
                    "::",
                    0
            );
        }

        // ========================================================
        // DNS
        // ========================================================

        if (request.getBooleanExtra(
                "dnsLeak",
                true
        )) {

            boolean psiphonMode =
                    "psiphon".equals(
                            value(
                                    request,
                                    "protocol",
                                    ""
                            )
                    );

            if (psiphonMode) {
                /*
                 * Psiphon SOCKS has no UDP support.
                 * HEV mapdns answers DNS with fake IPs; the real CONNECT
                 * is then done over TCP (CMD 0x01) through Psiphon.
                 * mapdns.address MUST equal the DNS we push to the system.
                 */
                builder.addDnsServer("198.18.0.2");

                sendLog(
                        "[VPN] DNS = 198.18.0.2 (Psiphon mapdns / TCP-only)"
                );
            } else {
                builder.addDnsServer("1.1.1.1");
                builder.addDnsServer("1.0.0.1");

                sendLog(
                        "[VPN] DNS = 1.1.1.1 / 1.0.0.1"
                );
            }
        }

        // ========================================================
        // APP SPLIT
        // ========================================================

        applySplitApps(
                builder,
                request
        );

        // ========================================================
        // ESTABLISH TUN
        // ========================================================

        sendLog(
                "[VPN] Establishing Android VPN interface..."
        );

        vpnInterface =
                builder.establish();

        if (vpnInterface == null) {

            if (VpnService.prepare(this) != null) {

                throw new IllegalStateException(
                        "VPN permission revoked or not granted"
                );
            }

            throw new IllegalStateException(
                    "Android could not create VPN interface"
            );
        }

        int tunFd =
                vpnInterface.getFd();

        if (tunFd < 0) {

            try {
                vpnInterface.close();
            } catch (Exception ignored) {
            }

            vpnInterface =
                    null;

            throw new IllegalStateException(
                    "Android returned invalid TUN file descriptor"
            );
        }

        sendLog(
                "[VPN] Android TUN created. fd=" +
                        tunFd
        );

        // ========================================================
        // HEV CONFIG
        // ========================================================

        File config =
                writeTunConfig(
                        request
                );

        sendLog(
                "[HEV] Config = " +
                        config.getAbsolutePath()
        );

        // ========================================================
        // START HEV
        // ========================================================

        sendLog(
                "[HEV] Starting TProxy..."
        );

        try {

            /*
             * JNI فعلی پروژه:
             *
             * public static native void
             * TProxyStartService(String config_path, int fd);
             *
             * بنابراین خروجی boolean ندارد.
             */

            TProxyService.TProxyStartService(
                    config.getAbsolutePath(),
                    tunFd
            );

            // ====================================================
            // HEV DIAGNOSTICS
            // ====================================================
            String protocolDiag = value(request, "protocol", ConnectionDefaults.PROTOCOL);
            boolean psiphonModeDiag = "psiphon".equals(protocolDiag);
            sendLog("[HEV] TProxy service started successfully");
            sendLog("[HEV] SOCKS target host: " + socks.host);
            sendLog("[HEV] SOCKS target port: " + socks.port);
            sendLog("[HEV] Protocol context: " + protocolDiag);
            // Psiphon rejects both 0x03 (UDP ASSOCIATE) and hev's 0x05 (UDP-in-TCP).
            // We disable all UDP relay and use mapdns instead.
            sendLog("[HEV] UDP relay mode: " + (psiphonModeDiag ? "disabled (mapdns only)" : "udp"));

        } catch (UnsatisfiedLinkError error) {

            sendLog(
                    "[HEV] JNI library could not be loaded"
            );

            try {
                vpnInterface.close();
            } catch (Exception ignored) {
            }

            vpnInterface =
                    null;

            throw new IllegalStateException(
                    "HEV Android JNI bridge could not be loaded",
                    error
            );

        } catch (Throwable error) {

            sendLog(
                    "[HEV] TProxyStartService failed: " +
                            safeMessage(error)
            );

            try {
                vpnInterface.close();
            } catch (Exception ignored) {
            }

            vpnInterface =
                    null;

            throw new IllegalStateException(
                    "HEV failed to start",
                    error
            );
        }

        bridgeStarted =
                true;

        sendLog(
                "[HEV] TProxyStartService completed"
        );

        // ========================================================
        // STATS TEST
        // ========================================================

        worker.execute(
                () -> {

                    try {

                        Thread.sleep(
                                1500
                        );

                        if (!bridgeStarted ||
                                vpnInterface == null) {

                            return;
                        }

                        long[] stats =
                                TProxyService.TProxyGetStats();

                        if (stats != null &&
                                stats.length >= 4) {

                            sendLog(
                                    "[HEV] Initial stats: " +
                                            "TX packets=" +
                                            stats[0] +
                                            " TX bytes=" +
                                            stats[1] +
                                            " RX packets=" +
                                            stats[2] +
                                            " RX bytes=" +
                                            stats[3]
                            );

                        } else {

                            sendLog(
                                    "[HEV] Stats unavailable"
                            );
                        }

                    } catch (Throwable error) {

                        sendLog(
                                "[HEV] Initial stats failed: " +
                                        safeMessage(error)
                        );
                    }
                }
        );

        sendLog(
                "[VPN] HEV Android TUN bridge started"
        );

        // LAN SOCKS + HTTP for every protocol (including after TUN is up)
        try {
            int sp = HostPort.parse(socksAddress(request)).port;
            startLanPublishersIfNeeded(request, sp);
        } catch (Throwable lanErr) {
            sendLog("[LAN] publishers error: " + safeMessage(lanErr));
        }

        sendLog(
                "[VPN] Traffic path: Android TUN -> HEV -> SOCKS5 " +
                        socks.host +
                        ":" +
                        socks.port
        );

        testTunPath();
    }

    private void testTunPath() {

        worker.execute(
                () -> {

                    try {

                        Thread.sleep(
                                3000
                        );

                        if (!active ||
                                stopping ||
                                !bridgeStarted) {

                            return;
                        }

                        sendLog(
                                "[TUN_TEST] Starting real traffic test through TUN interface..."
                        );

                        /*
                         * We open a standard socket connection to api.ipify.org on port 80.
                         * Since we do NOT call protect() on this socket, Android's network stack
                         * will automatically route it through the TUN interface into HEV.
                         */
                        try (
                                Socket socket =
                                        new Socket()
                        ) {

                            socket.connect(
                                    new InetSocketAddress(
                                            "api.ipify.org",
                                            80
                                    ),
                                    10000
                            );

                            socket.setSoTimeout(
                                    10000
                            );

                            OutputStream out =
                                    socket.getOutputStream();

                            InputStream in =
                                    socket.getInputStream();

                            String request =
                                    "GET /?format=json HTTP/1.1\r\n" +
                                            "Host: api.ipify.org\r\n" +
                                            "Connection: close\r\n\r\n";

                            out.write(
                                    request.getBytes(
                                            StandardCharsets.US_ASCII
                                    )
                            );

                            out.flush();

                            BufferedReader reader =
                                    new BufferedReader(
                                            new InputStreamReader(
                                                    in,
                                                    StandardCharsets.UTF_8
                                            )
                                    );

                            String line;

                            StringBuilder response =
                                    new StringBuilder();

                            while (
                                    (line = reader.readLine()) != null
                            ) {

                                response.append(
                                        line
                                );
                            }

                            sendLog(
                                    "[TUN_TEST] TUN interface traffic test SUCCESS!"
                            );

                            sendLog(
                                    "[TUN_TEST] Response: " +
                                            response.toString()
                            );

                        }

                    } catch (Throwable error) {

                        sendLog(
                                "[TUN_TEST] TUN interface traffic test FAILED: " +
                                        safeMessage(error)
                        );
                    }
                }
        );
    }

    // ============================================================
    // AETHER
    // ============================================================

    private void startAether(
            Intent request
    ) throws Exception {

        File executable =
                new File(
                        getApplicationInfo().nativeLibraryDir,
                        "libaether.so"
                );

        if (!executable.isFile()) {

            throw new IllegalStateException(
                    "Aether core is missing for this device architecture"
            );
        }

        boolean enableTor = request.getBooleanExtra("enableTor", false);
        String torMode = value(request, "tor_mode", "through");

        java.util.ArrayList<String> cmd = new java.util.ArrayList<>();
        cmd.add(executable.getAbsolutePath());
        // Tor module (Aether v2+): flags must match docs — carrier + --tor / --tor-only
        if (enableTor) {
            String carrierHint = value(request, "upstream_protocol",
                    value(request, "protocol", ConnectionDefaults.PROTOCOL));
            if ("only".equals(torMode)) {
                cmd.add("--tor-only");
            } else {
                // through: e.g. --wg --tor  or  --gool --tor  or  --masque --tor
                if ("wg".equals(carrierHint)) {
                    cmd.add("--wg");
                } else if ("gool".equals(carrierHint)) {
                    cmd.add("--gool");
                } else if (!"direct".equalsIgnoreCase(carrierHint)) {
                    cmd.add("--masque");
                }
                if ("reverse".equals(torMode)) {
                    cmd.add("--tor-reverse");
                } else {
                    cmd.add("--tor");
                }
            }
            sendLog("[Tor] cmdline(pre-upstream)=" + cmd);
        }

        // Must attach --upstream / --http-proxy BEFORE ProcessBuilder
        String earlyUpstream = request.getStringExtra("aether_upstream");
        if (earlyUpstream != null && !earlyUpstream.trim().isEmpty()) {
            String up = earlyUpstream.trim();
            if (!up.contains("://")) {
                up = "socks5://" + up;
            }
            if (!cmd.contains("--upstream")) {
                cmd.add("--upstream");
                cmd.add(up);
            }
            sendLog("[Tor] cmdline+upstream=" + cmd);
        }

        // Default HTTP proxy ON only for primary non-Tor-chain cores.
        // Secondary Tor exit / Tor carrier must not fight over :18080.
        boolean secondaryEarly = request.getBooleanExtra("aether_secondary", false);
        boolean earlyLanHttp = !secondaryEarly && (
                request.getBooleanExtra("lanSharing", false)
                        || request.getBooleanExtra("enable_http_proxy", true));
        if (request.hasExtra("enable_http_proxy")) {
            earlyLanHttp = request.getBooleanExtra("enable_http_proxy", false)
                    || request.getBooleanExtra("lanSharing", false);
        }
        if (earlyLanHttp && !cmd.contains("--http-proxy")) {
            // Secondary uses a distinct HTTP port if ever enabled
            int httpPort = secondaryEarly ? (LAN_HTTP_INTERNAL_PORT + 1) : LAN_HTTP_INTERNAL_PORT;
            String httpBind = "127.0.0.1:" + httpPort;
            cmd.add("--http-proxy");
            cmd.add(httpBind);
            sendLog("[Aether] cmdline+http-proxy=" + cmd);
        }

        // Explicit SOCKS bind (Aether --bind). Always loopback for Tor.
        if (!cmd.contains("--bind")) {
            String bindSocks = socksAddress(request).replace("0.0.0.0:", "127.0.0.1:");
            if (enableTor) {
                int bp = request.getIntExtra("socksPort", AETHER_DEFAULT_PORT);
                if (bp <= 0) bp = AETHER_DEFAULT_PORT;
                bindSocks = "127.0.0.1:" + bp;
            }
            cmd.add("--bind");
            cmd.add(bindSocks);
            sendLog("[Aether] cmdline+bind=" + cmd);
        }

        ProcessBuilder builder =
                new ProcessBuilder(cmd);

        builder.directory(
                getFilesDir()
        );

        builder.redirectErrorStream(
                true
        );

        Map<String, String> env =
                builder.environment();

        // Resolve protocol once so it stays effectively final for lambdas
        String protocolRaw =
                value(
                        request,
                        "protocol",
                        ConnectionDefaults.PROTOCOL
                );
        String protocol = protocolRaw;
        // Only rewrite carrier protocol for single-process --tor (not --tor-only exit layer)
        if (enableTor
                && "tor".equals(value(request, "ui_protocol", ""))
                && !"only".equals(torMode)
                && !request.getBooleanExtra("aether_secondary", false)) {
            String carrier = value(request, "upstream_protocol", protocolRaw);
            if (carrier != null && !carrier.isEmpty() && !"direct".equalsIgnoreCase(carrier)) {
                protocol = carrier;
            }
        }
        // effectively final copy for lambdas
        final String protocolForLogs = protocol;

        String transport =
                value(
                        request,
                        "transport",
                        "h3"
                );

        env.put(
                "AETHER_PROTOCOL",
                protocol
        );

        // Resolve SOCKS early (needed for Tor bind before AETHER_SOCKS env put below)
        String socks =
                socksAddress(
                        request
                );
        // Tor MUST bootstrap through loopback tunnel proxy. Never rewrite to 0.0.0.0
        // when enableTor — that produces "socks5://0.0.0.0:1819" and port conflicts.
        if (request.getBooleanExtra("lanSharing", false) && !enableTor) {
            socks = socks.replace("127.0.0.1:", "0.0.0.0:");
        } else {
            socks = socks.replace("0.0.0.0:", "127.0.0.1:");
        }

        if (enableTor) {
            // Docs: AETHER_TOR = which mode (tor | tor-reverse | tor-only)
            String aetherTor =
                    "reverse".equals(torMode) ? "tor-reverse"
                            : ("only".equals(torMode) ? "tor-only" : "tor");
            env.put("AETHER_TOR", aetherTor);
            // Docs: AETHER_TOR_BIND / --tor-bind = Tor SOCKS listen address (default 127.0.0.1:1820).
            // MUST NOT equal --bind (WARP SOCKS on :1819) or Aether reports Address already in use.
            String torBind = "127.0.0.1:1820";
            if ("only".equals(torMode)) {
                // --tor-only: single SOCKS is Tor itself on --bind
                torBind = socks.replace("0.0.0.0:", "127.0.0.1:");
            }
            env.put("AETHER_TOR_BIND", torBind);
            File torDir = new File(getFilesDir(), "aether-tor");
            //noinspection ResultOfMethodCallIgnored
            torDir.mkdirs();
            env.put("AETHER_TOR_DIR", torDir.getAbsolutePath());
            env.put("AETHER_TOR_LOG", "info");
            sendLog("[Tor] AETHER_TOR=" + aetherTor
                    + " tor-bind=" + torBind
                    + " warp-socks=" + socks);
        }

        // Mirror upstream into env (CLI already set above when present)
        String aetherUpstream = request.getStringExtra("aether_upstream");
        if (aetherUpstream != null && !aetherUpstream.trim().isEmpty()) {
            String up = aetherUpstream.trim();
            if (!up.contains("://")) {
                up = "socks5://" + up;
            }
            env.put("AETHER_UPSTREAM", up);
            sendLog("[Aether] AETHER_UPSTREAM=" + up + " (Tor must use carrier tunnel)");
        }

        env.put(
                "AETHER_SCAN",
                value(
                        request,
                        "scan",
                        ConnectionDefaults.SCAN
                )
        );

        env.put(
                "AETHER_IP",
                value(
                        request,
                        "ipMode",
                        "v4"
                )
        );

        env.put(
                "AETHER_NOIZE",
                value(
                        request,
                        "obfuscation",
                        "firewall"
                )
        );

        env.put(
                "AETHER_LOG_LEVEL",
                value(
                        request,
                        "logLevel",
                        "info"
                )
        );

        env.put(
                "AETHER_SOCKS",
                socks
        );

        // HTTP CONNECT — skip for Tor secondary / carrier when explicitly disabled
        boolean wantLanHttp;
        if (request.hasExtra("enable_http_proxy")) {
            wantLanHttp = request.getBooleanExtra("enable_http_proxy", false)
                    || request.getBooleanExtra("lanSharing", false);
        } else {
            wantLanHttp = !request.getBooleanExtra("aether_secondary", false)
                    && (request.getBooleanExtra("lanSharing", false)
                    || request.getBooleanExtra("enable_http_proxy", true));
        }
        if (wantLanHttp) {
            boolean secondaryHttp = request.getBooleanExtra("aether_secondary", false);
            int httpPort = secondaryHttp ? (LAN_HTTP_INTERNAL_PORT + 1) : LAN_HTTP_INTERNAL_PORT;
            String httpBind = "127.0.0.1:" + httpPort;
            env.put("AETHER_HTTP_PROXY", httpBind);
            sendLog("[Aether] HTTP CONNECT proxy " + httpBind
                    + (request.getBooleanExtra("lanSharing", false)
                    ? " (LAN will publish 0.0.0.0:" + LAN_HTTP_PORT + ")" : ""));
        } else {
            sendLog("[Aether] HTTP CONNECT proxy disabled for this process");
        }

        boolean secondaryCfg =
                request.getBooleanExtra("aether_secondary", false);
        env.put(
                "AETHER_CONFIG",
                new File(
                        getFilesDir(),
                        secondaryCfg ? "aether-tor-exit.toml" : "aether.toml"
                ).getAbsolutePath()
        );

        env.put(
                "AETHER_QUICK_RECONNECT",
                request.getBooleanExtra(
                        "quickReconnect",
                        true
                )
                        ? "1"
                        : "0"
        );

        if ("masque".equals(
                protocol
        )) {

            env.put(
                    "AETHER_MASQUE_HTTP2",
                    "h2".equals(
                            transport
                    )
                            ? "1"
                            : "0"
            );

            env.put(
                    "AETHER_MASQUE_MTU",
                    String.valueOf(
                            request.getIntExtra(
                                    "mtu",
                                    1500
                            )
                    )
            );
        }

        env.put(
                "AETHER_NETSTACK_TCP_RX",
                "131072"
        );

        env.put(
                "AETHER_NETSTACK_TCP_TX",
                "131072"
        );

        env.put(
                "TMPDIR",
                getCacheDir().getAbsolutePath()
        );

        String peer =
                request.getStringExtra(
                        "peer"
                );

        if (peer != null &&
                !peer.trim().isEmpty()) {

            env.put(
                    "AETHER_PEER",
                    peer.trim()
            );
        }

        masqueH3GatewayUnavailable =
                false;

        boolean secondary =
                request.getBooleanExtra("aether_secondary", false);

        Process process;
        synchronized (runtimeLock) {
            process = builder.start();
            if (secondary) {
                torExitProcess = process;
            } else {
                aetherProcess = process;
            }
        }

        sendLog(
                "[Aether] Core started for " +
                        Build.SUPPORTED_ABIS[0]
        );

        sendLog(
                "[Aether] Protocol = " +
                        protocolLabel(
                                protocol
                        )
        );

        sendLog(
                "[Aether] SOCKS = " +
                        socks
        );

        Thread logs =
                new Thread(
                        () -> readAetherLogs(
                                process,
                                protocolForLogs,
                                transport
                        ),
                        "aether-log-reader"
                );

        logs.setDaemon(
                true
        );

        logs.start();
    }

    // ============================================================
    // AETHER LOGS
    // ============================================================

    private void readAetherLogs(
            Process process,
            String protocol,
            String transport
    ) {

        try (
                BufferedReader reader =
                        new BufferedReader(
                                new InputStreamReader(
                                        process.getInputStream()
                                )
                        )
        ) {

            String line;

            while (
                    (line = reader.readLine()) != null
            ) {

                sendLog(
                        "[Aether] " +
                                line
                );
                // Surface Tor readiness clearly in UI log stream
                if (line != null) {
                    String low = line.toLowerCase();
                    if (low.contains("tor is ready")
                            || low.contains("the way out is open")
                            || low.contains("tor socks5 listening")
                            || low.contains("bootstrapped 100")
                            || (low.contains("tor") && low.contains("100%")
                            && low.contains("connecting successfully"))) {
                        torExitReady = true;
                        sendLog("[Tor] Circuit ready (bootstrap 100%)");
                    }
                    if (low.contains("no tunnel underneath")) {
                        sendLog("[Tor] WARN: Tor is NOT using AETHER_UPSTREAM — check --upstream");
                    }
                }

                String lower =
                        line.toLowerCase(
                                Locale.US
                        );

                if (process == aetherProcess &&
                        "masque".equals(protocol) &&
                        "h3".equals(transport) &&
                        lower.contains(
                                "no usable masque gateway found"
                        )) {

                    masqueH3GatewayUnavailable =
                            true;
                }

                if (!smartBenchmarking) {

                    if (lower.contains(
                            "identity ready"
                    )) {

                        updateState(
                                "scanning",
                                getString(
                                        R.string.service_identity_ready
                                )
                        );
                    }

                    if (lower.contains(
                            "hunting for"
                    )) {

                        updateState(
                                "scanning",
                                getString(
                                        R.string.service_testing_gateways
                                )
                        );
                    }

                    if (lower.contains(
                            "validated"
                    ) ||
                            lower.contains(
                                    "passed handshake"
                            )) {

                        updateState(
                                "securing",
                                getString(
                                        R.string.service_gateway_verified
                                )
                        );
                    }
                }
            }

        } catch (Exception error) {

            if (!stopping) {

                sendLog(
                        "[Aether] Log stream closed: " +
                                safeMessage(error)
                );
            }
        }
    }

    // ============================================================
    // MONITOR AETHER
    // ============================================================

    private void monitorAether(
            Intent request,
            long session
    ) throws Exception {

        int attempts =
                0;

        while (!stopping &&
                generation.get() == session) {

            Process process =
                    aetherProcess;

            if (process == null) {
                return;
            }

            long processStartedAt =
                    System.currentTimeMillis();

            int exitCode =
                    process.waitFor();

            if (stopping ||
                    generation.get() != session) {

                return;
            }

            sendLog(
                    "[Aether] Process exited with code " +
                            exitCode
            );

            if (!request.getBooleanExtra(
                    "quickReconnect",
                    true
            )) {

                throw new IllegalStateException(
                        "Aether stopped unexpectedly (exit " +
                                exitCode +
                                ")"
                );
            }

            waitForUnderlyingNetwork(
                    session
            );

            if (stopping ||
                    generation.get() != session) {

                return;
            }

            if (System.currentTimeMillis() -
                    processStartedAt >=
                    60_000L) {

                attempts =
                        0;
            }

            attempts++;

            if (attempts >
                    MAX_RECONNECT_ATTEMPTS) {

                throw new IllegalStateException(
                        getString(
                                R.string.service_reconnect_failed,
                                MAX_RECONNECT_ATTEMPTS
                        )
                );
            }

            currentEndpoint =
                    "";

            updateState(
                    "reconnecting",
                    getString(
                            R.string.service_reconnecting
                    )
            );

            Thread.sleep(
                    Math.min(
                            20_000L,
                            1_500L <<
                                    (attempts - 1)
                    )
            );

            if (!startAetherWithMasqueFallback(
                    request,
                    SOCKS_TIMEOUT_MS
            )) {

                sendLog(
                        aetherExitMessage(
                                "Aether reconnect attempt did not become ready"
                        )
                );

                Process retry =
                        aetherProcess;

                if (retry != null &&
                        retry.isAlive()) {

                    retry.destroy();
                }

                continue;
            }

            recoveryRestartPending.set(
                    false
            );

            updateState(
                    "connected",
                    getString(
                            R.string.service_restored
                    )
            );

            updateNotification(
                    getString(
                            R.string.service_restored
                    )
            );

            scheduleLocationLookup(
                    request,
                    session
            );
        }
    }

    // ============================================================
    // NETWORK WAIT
    // ============================================================

    private void waitForUnderlyingNetwork(
            long session
    ) throws InterruptedException {

        while (
                networkUnavailable &&
                        !stopping &&
                        generation.get() == session
        ) {

            updateState(
                    "reconnecting",
                    getString(
                            R.string.service_network_lost
                    )
            );

            synchronized (networkLock) {

                networkLock.wait(
                        30_000L
                );
            }
        }
    }

    // ============================================================
    // WAIT SOCKS
    // ============================================================

    private boolean waitForSocks(
            String address,
            long timeoutMs
    ) {

        HostPort target =
                HostPort.parse(
                        address
                );

        long deadline =
                System.currentTimeMillis() +
                        timeoutMs;

        while (
                !stopping &&
                        System.currentTimeMillis() <
                                deadline
        ) {

            Process process =
                    aetherProcess;

            if (process != null &&
                    !process.isAlive()) {

                return false;
            }

            if (masqueH3GatewayUnavailable) {

                return false;
            }

            try (
                    Socket socket =
                            new Socket()
            ) {

                socket.connect(
                        new InetSocketAddress(
                                target.host,
                                target.port
                        ),
                        700
                );

                return true;

            } catch (Exception ignored) {

                try {

                    Thread.sleep(
                            400
                    );

                } catch (InterruptedException error) {

                    Thread.currentThread().interrupt();

                    return false;
                }
            }
        }

        return false;
    }

    // ============================================================
    // WAIT LOCAL SOCKET
    // ============================================================

    private boolean waitForLocalSocket(
            String host,
            int port,
            long timeoutMs
    ) {

        long deadline =
                System.currentTimeMillis() +
                        timeoutMs;

        while (
                !stopping &&
                        System.currentTimeMillis() <
                                deadline
        ) {

            try (
                    Socket socket =
                            new Socket()
            ) {

                socket.connect(
                        new InetSocketAddress(
                                host,
                                port
                        ),
                        500
                );

                return true;

            } catch (Exception ignored) {

                try {

                    Thread.sleep(
                            200
                    );

                } catch (InterruptedException error) {

                    Thread.currentThread().interrupt();

                    return false;
                }
            }
        }

        return false;
    }

    // ============================================================
    // AETHER MASQUE FALLBACK
    // ============================================================

    private boolean startAetherWithMasqueFallback(
            Intent request,
            long timeoutMs
    ) throws Exception {

        startAether(
                request
        );

        String socks =
                socksAddress(
                        request
                );

        if (waitForSocks(
                socks,
                timeoutMs
        )) {

            return true;
        }

        if (stopping ||
                !"masque".equals(
                        value(
                                request,
                                "protocol",
                                ConnectionDefaults.PROTOCOL
                        )
                ) ||
                !"h3".equals(
                        value(
                                request,
                                "transport",
                                "h3"
                        )
                ) ||
                !masqueH3GatewayUnavailable) {

            return false;
        }

        sendLog(
                "[Aether] MASQUE HTTP/3 failed; retrying HTTP/2"
        );

        stopAetherOnly();

        request.putExtra(
                "transport",
                "h2"
        );

        updateState(
                "scanning",
                getString(
                        R.string.service_scanning
                )
        );

        startAether(
                request
        );

        return waitForSocks(
                socksAddress(
                        request
                ),
                timeoutMs
        );
    }

    // ============================================================
    // SMART
    // ============================================================

    private String chooseSmartProtocol(
            Intent request,
            long session
    ) throws Exception {

        String[] protocols = {
                "masque",
                "wg",
                "gool"
        };

        SmartResult best =
                null;

        smartBenchmarking =
                true;

        try {

            for (
                    int index = 0;
                    index < protocols.length;
                    index++
            ) {

                if (stopping ||
                        generation.get() != session) {

                    throw new InterruptedException(
                            "Smart Connect was cancelled"
                    );
                }

                String protocol =
                        protocols[index];

                updateState(
                        "smart-testing",
                        getString(
                                R.string.service_testing_protocol,
                                protocolLabel(
                                        protocol
                                ),
                                index + 1,
                                protocols.length
                        )
                );

                Intent trial =
                        new Intent(
                                request
                        )
                                .putExtra(
                                        "protocol",
                                        protocol
                                )
                                .putExtra(
                                        "quickReconnect",
                                        false
                                );

                SmartResult result =
                        benchmarkProtocol(
                                trial,
                                protocol
                        );

                sendLog(
                        result.summary()
                );

                if (best == null ||
                        result.score >
                                best.score) {

                    best =
                            result;
                }

                stopAetherOnly();

                Thread.sleep(
                        500
                );
            }

        } finally {

            smartBenchmarking =
                    false;

            stopAetherOnly();
        }

        if (best == null ||
                !best.connected) {

            throw new IllegalStateException(
                    "Smart Connect could not establish any available protocol"
            );
        }

        return best.protocol;
    }

    // ============================================================
    // SMART BENCHMARK
    // ============================================================

    private SmartResult benchmarkProtocol(
            Intent request,
            String protocol
    ) {

        long started =
                System.nanoTime();

        try {

            startAether(
                    request
            );

            String socks =
                    socksAddress(
                            request
                    );

            boolean connected =
                    waitForSocks(
                            socks,
                            SMART_PROTOCOL_TIMEOUT_MS
                    );

            long handshakeMs =
                    elapsedMillis(
                            started
                    );

            if (!connected) {

                return SmartResult.failed(
                        protocol,
                        handshakeMs
                );
            }

            long latencyMs =
                    socksConnectMillis(
                            socks,
                            "1.1.1.1",
                            443,
                            4_000
                    );

            long dnsMs =
                    socksConnectMillis(
                            socks,
                            "cloudflare.com",
                            443,
                            5_000
                    );

            int attempts =
                    5;

            int stable =
                    0;

            long latencyTotal =
                    0;

            for (
                    int i = 0;
                    i < attempts;
                    i++
            ) {

                if (stopping) {
                    break;
                }

                try {

                    long probe =
                            socksConnectMillis(
                                    socks,
                                    "1.1.1.1",
                                    443,
                                    4_000
                            );

                    latencyTotal +=
                            probe;

                    stable++;

                } catch (Exception ignored) {
                }

                try {

                    Thread.sleep(
                            600
                    );

                } catch (InterruptedException error) {

                    Thread.currentThread().interrupt();

                    break;
                }
            }

            if (stable > 0) {

                latencyMs =
                        Math.min(
                                latencyMs,
                                latencyTotal /
                                        stable
                        );
            }

            return SmartResult.success(
                    protocol,
                    handshakeMs,
                    latencyMs,
                    dnsMs,
                    stable,
                    attempts
            );

        } catch (Throwable error) {

            return SmartResult.failed(
                    protocol,
                    elapsedMillis(
                            started
                    )
            );
        }
    }

    // ============================================================
    // SOCKS5
    // ============================================================

    private Socket openSocksTunnel(
            String socksAddress,
            String host,
            int port,
            int timeoutMs
    ) throws Exception {

        HostPort proxy =
                HostPort.parse(
                        socksAddress
                );

        Socket socket =
                new Socket();

        try {
            try {
                protect(socket);
            } catch (Throwable ignored) {
            }

            socket.connect(
                    new InetSocketAddress(
                            proxy.host,
                            proxy.port
                    ),
                    timeoutMs
            );

            socket.setSoTimeout(
                    timeoutMs
            );

            InputStream input =
                    socket.getInputStream();

            OutputStream output =
                    socket.getOutputStream();

            // ====================================================
            // GREETING
            // ====================================================

            output.write(
                    new byte[]{
                            5,
                            1,
                            0
                    }
            );

            output.flush();

            byte[] greeting =
                    readExact(
                            input,
                            2
                    );

            if (greeting[0] != 5 ||
                    greeting[1] != 0) {

                throw new IllegalStateException(
                        "SOCKS5 authentication failed"
                );
            }

            // ====================================================
            // CONNECT
            // ====================================================

            byte[] ipv4 =
                    parseIpv4Address(
                            host
                    );

            if (ipv4 != null) {

                output.write(
                        new byte[]{
                                5,
                                1,
                                0,
                                1
                        }
                );

                output.write(
                        ipv4
                );

            } else {

                byte[] hostBytes =
                        host.getBytes(
                                StandardCharsets.US_ASCII
                        );

                if (hostBytes.length > 255) {

                    throw new IllegalArgumentException(
                            "SOCKS5 host is too long"
                    );
                }

                output.write(
                        new byte[]{
                                5,
                                1,
                                0,
                                3,
                                (byte) hostBytes.length
                        }
                );

                output.write(
                        hostBytes
                );
            }

            output.write(
                    new byte[]{
                            (byte) (port >>> 8),
                            (byte) port
                    }
            );

            output.flush();

            byte[] response =
                    readExact(
                            input,
                            4
                    );

            if (response[0] != 5 ||
                    response[1] != 0) {

                throw new IllegalStateException(
                        "SOCKS5 connection failed. REP=" +
                                (response[1] & 0xff)
                );
            }

            int addressLength;

            if (response[3] == 1) {

                addressLength =
                        4;

            } else if (response[3] == 4) {

                addressLength =
                        16;

            } else if (response[3] == 3) {

                addressLength =
                        readExact(
                                input,
                                1
                        )[0] &
                                0xff;

            } else {

                throw new IllegalStateException(
                        "Invalid SOCKS5 response"
                );
            }

            readExact(
                    input,
                    addressLength + 2
            );

            return socket;

        } catch (Throwable error) {

            try {
                socket.close();
            } catch (Exception ignored) {
            }

            throw error;
        }
    }

    private long socksConnectMillis(
            String socksAddress,
            String host,
            int port,
            int timeoutMs
    ) throws Exception {

        long started =
                System.nanoTime();

        try (
                Socket socket =
                        openSocksTunnel(
                                socksAddress,
                                host,
                                port,
                                timeoutMs
                        )
        ) {

            return elapsedMillis(
                    started
            );
        }
    }

    // ============================================================
    // HTTPS THROUGH SOCKS
    // ============================================================

    private String socksHttpGet(
            String socksAddress,
            String host,
            String path
    ) throws Exception {

        try (
                Socket tunnel =
                        openSocksTunnel(
                                socksAddress,
                                host,
                                443,
                                10_000
                        )
        ) {

            SSLSocket ssl =
                    (SSLSocket)
                            ((SSLSocketFactory)
                                    SSLSocketFactory.getDefault())
                                    .createSocket(
                                            tunnel,
                                            host,
                                            443,
                                            true
                                    );

            try {

                ssl.setSoTimeout(
                        10_000
                );

                ssl.startHandshake();

                OutputStream output =
                        ssl.getOutputStream();

                output.write(
                        (
                                "GET " +
                                        path +
                                        " HTTP/1.1\r\n" +
                                        "Host: " +
                                        host +
                                        "\r\n" +
                                        "Connection: close\r\n" +
                                        "Accept: application/json\r\n" +
                                        "\r\n"
                        ).getBytes(
                                StandardCharsets.US_ASCII
                        )
                );

                output.flush();

                BufferedReader reader =
                        new BufferedReader(
                                new InputStreamReader(
                                        ssl.getInputStream(),
                                        StandardCharsets.UTF_8
                                )
                        );

                String line;

                int status =
                        0;

                StringBuilder body =
                        new StringBuilder();

                boolean headers =
                        true;

                while (
                        (line = reader.readLine()) != null
                ) {

                    if (headers) {

                        if (line.startsWith(
                                "HTTP/"
                        )) {

                            String[] parts =
                                    line.split(
                                            " ",
                                            3
                                    );

                            if (parts.length >= 2) {

                                status =
                                        Integer.parseInt(
                                                parts[1]
                                        );
                            }
                        }

                        if (line.isEmpty()) {

                            headers =
                                    false;
                        }

                    } else {

                        body.append(
                                line
                        );
                    }
                }

                if (status < 200 ||
                        status >= 300) {

                    throw new IllegalStateException(
                            "HTTP request returned " +
                                    status
                    );
                }

                return body.toString();

            } finally {

                try {
                    ssl.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    // ============================================================
    // LOCATION
    // ============================================================

    private void scheduleLocationLookup(
            Intent request,
            long session
    ) {

        long lookup =
                locationLookupSequence.incrementAndGet();

        currentEndpoint =
                getString(
                        R.string.location_detecting
                );

        sendStatus(
                currentState,
                currentMessage
        );

        worker.execute(
                () -> {

                    String location =
                            "";

                    for (
                            int attempt = 0;
                            attempt < 4 &&
                                    lookup ==
                                            locationLookupSequence.get() &&
                                    !stopping &&
                                    generation.get() ==
                                            session &&
                                    "connected".equals(
                                            currentState
                                    );
                            attempt++
                    ) {

                        try {

                            String ip =
                                    socksHttpGet(
                                            socksAddress(
                                                    request
                                            ),
                                            "api.ipify.org",
                                            "/?format=json"
                                    );

                            JSONObject ipJson =
                                    new JSONObject(
                                            ip
                                    );

                            String address =
                                    ipJson.optString(
                                            "ip",
                                            ""
                                    ).trim();

                            if (address.isEmpty()) {

                                throw new IllegalStateException(
                                        "VPN public IP was empty"
                                );
                            }

                            String proxy =
                                    socksAddress(
                                            request
                                    );

                            try {

                                location =
                                        locationFromJson(
                                                new JSONObject(
                                                        socksHttpGet(
                                                                proxy,
                                                                "ipapi.co",
                                                                "/" +
                                                                        address +
                                                                        "/json/"
                                                        )
                                                )
                                        );

                            } catch (Throwable primaryError) {

                                sendLog(
                                        "[Location] Primary provider failed: " +
                                                safeMessage(
                                                        primaryError
                                                )
                                );

                                location =
                                        locationFromJson(
                                                new JSONObject(
                                                        socksHttpGet(
                                                                proxy,
                                                                "ipwho.is",
                                                                "/" +
                                                                        address
                                                        )
                                                )
                                        );
                            }

                            if (!location.isEmpty()) {
                                currentExitIp = address;
                                // "🇨🇦 City · 1.2.3.4"
                                location = location + " · " + address;
                                break;
                            } else if (!address.isEmpty()) {
                                currentExitIp = address;
                                location = address;
                                break;
                            }

                        } catch (Throwable error) {

                            if (attempt == 3) {

                                sendLog(
                                        "[Location] Lookup failed: " +
                                                safeMessage(
                                                        error
                                                )
                                );
                            }

                            try {

                                Thread.sleep(
                                        5_000L *
                                                (attempt + 1)
                                );

                            } catch (InterruptedException interrupted) {

                                Thread.currentThread().interrupt();

                                break;
                            }
                        }
                    }

                    if (lookup ==
                            locationLookupSequence.get() &&
                            !stopping &&
                            generation.get() ==
                                    session &&
                            "connected".equals(
                                    currentState
                            )) {

                        if (!location.isEmpty()) {
                            if (activeRequest != null && activeRequest.getBooleanExtra("enableTor", false)) {
                                String up = activeRequest.getStringExtra("tor_upstream_label");
                                if (up == null || up.isEmpty()) up = "WARP";
                                String tag = "Direct".equalsIgnoreCase(up) ? "Tor-only"
                                        : ("WireGuard".equalsIgnoreCase(up) ? "Tor←WG" : "Tor←WARP");
                                if (!location.startsWith("Tor")) {
                                    location = tag + " · " + location;
                                }
                            }
                            currentEndpoint = location;
                            // location may already contain " · ip" from builder below
                            int dot = location.lastIndexOf("·");
                            if (dot >= 0 && dot + 1 < location.length()) {
                                String maybe = location.substring(dot + 1).trim();
                                if (maybe.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) {
                                    currentExitIp = maybe;
                                }
                            }
                        } else {
                            currentEndpoint = getString(
                                    R.string.connection_location_unavailable
                            );
                        }

                        sendStatus(
                                currentState,
                                currentMessage
                        );
                    }
                }
        );
    }

    private static String locationFromJson(
            JSONObject geo
    ) {

        if (geo.optBoolean(
                "error",
                false
        ) ||
                (
                        geo.has("success") &&
                                !geo.optBoolean(
                                        "success",
                                        true
                                )
                )) {

            return "";
        }

        String city =
                geo.optString(
                        "city",
                        ""
                ).trim();

        String country =
                geo.optString(
                                "country_code",
                                geo.optString(
                                        "countryCode",
                                        ""
                                )
                        ).trim()
                        .toUpperCase(
                                Locale.US
                        );

        if (!city.isEmpty() &&
                !country.isEmpty()) {

            return countryFlag(
                    country
            ) +
                    " " +
                    city;
        }

        if (!country.isEmpty()) {

            return countryFlag(
                    country
            ) +
                    " " +
                    country;
        }

        return "";
    }

    private static String countryFlag(
            String country
    ) {

        if (country.length() != 2) {
            return "";
        }

        return new String(
                Character.toChars(
                        0x1F1E6 +
                                country.charAt(0) -
                                'A'
                )
        ) +
                new String(
                        Character.toChars(
                                0x1F1E6 +
                                        country.charAt(1) -
                                        'A'
                        )
                );
    }

    // ============================================================
    // READ EXACT
    // ============================================================

    private static byte[] readExact(
            InputStream input,
            int length
    ) throws Exception {

        byte[] value =
                new byte[length];

        int offset =
                0;

        while (
                offset < length
        ) {

            int read =
                    input.read(
                            value,
                            offset,
                            length - offset
                    );

            if (read < 0) {

                throw new IllegalStateException(
                        "SOCKS5 response ended early"
                );
            }

            offset +=
                    read;
        }

        return value;
    }

    // ============================================================
    // IPV4
    // ============================================================

    private static byte[] parseIpv4Address(
            String host
    ) {

        String[] parts =
                host.split(
                        "\\.",
                        -1
                );

        if (parts.length != 4) {
            return null;
        }

        byte[] address =
                new byte[4];

        for (
                int i = 0;
                i < parts.length;
                i++
        ) {

            try {

                int value =
                        Integer.parseInt(
                                parts[i]
                        );

                if (value < 0 ||
                        value > 255) {

                    return null;
                }

                address[i] =
                        (byte) value;

            } catch (
                    NumberFormatException error
            ) {

                return null;
            }
        }

        return address;
    }

    // ============================================================
    // TIME
    // ============================================================

    private static long elapsedMillis(
            long startedNanos
    ) {

        return TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() -
                        startedNanos
        );
    }

    // ============================================================
    // PROTOCOL LABEL
    // ============================================================

    private static String protocolLabel(
            String protocol
    ) {

        if ("wg".equals(
                protocol
        )) {

            return "WireGuard";
        }

        if ("gool".equals(
                protocol
        )) {

            return "gool / WARP-in-WARP";
        }

        if ("psiphon".equals(
                protocol
        )) {

            return "Psiphon";
        }

        if ("tor".equals(
                protocol
        )) {

            return "Tor";
        }

        return "MASQUE";
    }

    // ============================================================
    // HEV CONFIG
    // ============================================================

    /**
     * Builds the HEV (hev-socks5-tunnel) YAML config and returns the file.
     *
     * Critical for Psiphon:
     * - Psiphon local SOCKS only accepts CMD=0x01 (CONNECT).
     * - hev's proprietary UDP-in-TCP uses CMD=0x05 → causes
     *   "socks5ReadCommand: command was 0x05, not 0x01".
     * - Standard UDP ASSOCIATE (0x03) is also unsupported by Psiphon.
     * Therefore we never enable any UDP relay mode when protocol=psiphon
     * and instead use mapdns so DNS stays on TCP CONNECT.
     */
    private File writeTunConfig(Intent request) throws IOException {
        HostPort socks = HostPort.parse(socksAddress(request));

        String protocol = value(request, "protocol", ConnectionDefaults.PROTOCOL);
        boolean psiphonMode = "psiphon".equals(protocol);

        File configFile = new File(getFilesDir(), "hev_tun.yml");

        writeTunConfig(
                configFile,
                socks.host,
                socks.port,
                psiphonMode
        );

        sendLog(
                "[HEV] Config written: " + configFile.getAbsolutePath() +
                        " (psiphonMode=" + psiphonMode +
                        ", socks=" + socks.host + ":" + socks.port + ")"
        );

        return configFile;
    }

    private void writeTunConfig(
            File configFile,
            String socksHost,
            int socksPort,
            boolean psiphonMode
    ) throws IOException {
        StringBuilder yaml = new StringBuilder();

        yaml.append("tunnel:\n");
        yaml.append("  name: 'tun0'\n");
        yaml.append("  mtu: 8500\n");
        yaml.append("  ipv4: 198.18.0.1\n");
        yaml.append("  ipv6: 'fc00::1'\n");

        yaml.append("socks5:\n");
        yaml.append("  address: '").append(yamlEscape(socksHost)).append("'\n");
        yaml.append("  port: ").append(socksPort).append("\n");

        if (psiphonMode) {
            yaml.append("  udp: 'none'\n"); // Explicitly disable UDP for Psiphon SOCKS
            yaml.append("mapdns:\n");
            yaml.append("  address: '198.18.0.2'\n");
            yaml.append("  port: 53\n");
            yaml.append("  network: '240.0.0.0'\n");
            yaml.append("  netmask: '240.0.0.0'\n");
            yaml.append("  cache-size: 10000\n");
        } else {
            // Full UDP support for Aether / other upstreams that speak
            // standard SOCKS5 UDP ASSOCIATE.
            yaml.append("  udp: 'udp'\n");
        }

        yaml.append("misc:\n");
        yaml.append("  task-stack-size: 81920\n");
        yaml.append("  connect-timeout: 5000\n");
        yaml.append("  read-write-timeout: 60000\n");
        yaml.append("  log-level: 'warn'\n");

        String content = yaml.toString();

        try (FileWriter writer = new FileWriter(configFile)) {
            writer.write(content);
        }

        // Log full config so we can verify UDP is really absent and mapdns is correct.
        sendLog("[HEV] --- hev_tun.yml begin ---\n" + content + "[HEV] --- hev_tun.yml end ---");

        Log.d(TAG, "[TUN] Config generated successfully (Psiphon Mode: " + psiphonMode + ")");
    }

    // ============================================================
    // SPLIT APPS
    // ============================================================


    private void applySplitApps(
            Builder builder,
            Intent request
    ) {

        String mode =
                value(
                        request,
                        "routing",
                        "full"
                );

        String apps =
                value(
                        request,
                        "splitApps",
                        ""
                );

        boolean includeOnly =
                "split-include".equals(
                        mode
                );

        boolean psiphonMode =
                "psiphon".equals(
                        value(
                                request,
                                "protocol",
                                ""
                        )
                );

        /*
         * CRITICAL for Psiphon:
         * خود اپ (و در نتیجه process سایفون + Aether) نباید وارد TUN شود.
         * در غیر این صورت بعد از establishVpn، NetworkMonitor سایفون
         * شبکه را VPN می‌بیند و تانل را terminate می‌کند.
         *
         * حتی در حالت split-include هم باید خود اپ exclude شود.
         */
        try {
            builder.addDisallowedApplication(
                    getPackageName()
            );

            sendLog(
                    "[VPN] Excluding VPN app from TUN: " +
                            getPackageName() +
                            (psiphonMode
                                    ? " (required for Psiphon survival)"
                                    : "")
            );

        } catch (
                PackageManager.NameNotFoundException error
        ) {
            throw new IllegalStateException(
                    "Could not exclude VPN application",
                    error
            );
        }

        if (apps.trim().isEmpty()) {
            return;
        }

        int valid = 0;

        for (
                String packageName :
                apps.split(
                        "[\\r\\n,]+"
                )
        ) {

            packageName =
                    packageName.trim();

            if (packageName.isEmpty()) {
                continue;
            }

            // هرگز خود اپ را دوباره allow نکن
            if (packageName.equals(
                    getPackageName()
            )) {
                continue;
            }

            try {

                if (includeOnly) {

                    builder.addAllowedApplication(
                            packageName
                    );

                } else if (
                        "split-exclude".equals(
                                mode
                        )
                ) {

                    builder.addDisallowedApplication(
                            packageName
                    );
                }

                valid++;

            } catch (
                    PackageManager.NameNotFoundException error
            ) {

                sendLog(
                        "[VPN] Unknown Android package: " +
                                packageName
                );
            }
        }

        if (includeOnly &&
                valid == 0) {

            throw new IllegalArgumentException(
                    "Include selected apps requires at least one valid Android package name"
            );
        }
    }
    // ============================================================
    // PUBLIC ROUTES
    // ============================================================

    private void addPublicRoutes(
            Builder builder
    ) {

        List<Ipv4Range> excluded =
                new ArrayList<>();

        excluded.add(
                Ipv4Range.cidr(
                        "0.0.0.0",
                        8
                )
        );

        excluded.add(
                Ipv4Range.cidr(
                        "10.0.0.0",
                        8
                )
        );

        excluded.add(
                Ipv4Range.cidr(
                        "100.64.0.0",
                        10
                )
        );

        excluded.add(
                Ipv4Range.cidr(
                        "127.0.0.0",
                        8
                )
        );

        excluded.add(
                Ipv4Range.cidr(
                        "169.254.0.0",
                        16
                )
        );

        excluded.add(
                Ipv4Range.cidr(
                        "172.16.0.0",
                        12
                )
        );

        excluded.add(
                Ipv4Range.cidr(
                        "192.0.0.0",
                        24
                )
        );

        excluded.add(
                Ipv4Range.cidr(
                        "192.168.0.0",
                        16
                )
        );

        excluded.add(
                Ipv4Range.cidr(
                        "198.18.0.0",
                        15
                )
        );

        excluded.add(
                Ipv4Range.cidr(
                        "224.0.0.0",
                        3
                )
        );

        Collections.sort(
                excluded
        );

        long cursor =
                0;

        for (
                Ipv4Range range :
                excluded
        ) {

            if (cursor <
                    range.start) {

                addRangeAsRoutes(
                        builder,
                        cursor,
                        range.start - 1
                );
            }

            cursor =
                    Math.max(
                            cursor,
                            range.end + 1
                    );
        }

        if (cursor <=
                0xffffffffL) {

            addRangeAsRoutes(
                    builder,
                    cursor,
                    0xffffffffL
            );
        }

        builder.addRoute(
                "2000::",
                3
        );
    }

    private void addRangeAsRoutes(
            Builder builder,
            long start,
            long end
    ) {

        while (
                start <= end
        ) {

            long alignment =
                    start == 0
                            ? (1L << 32)
                            : Long.lowestOneBit(
                            start
                    );

            long remaining =
                    end -
                            start +
                            1;

            long block =
                    alignment;

            while (
                    block > remaining
            ) {

                block >>>=
                        1;
            }

            int prefix =
                    32 -
                            Long.numberOfTrailingZeros(
                                    block
                            );

            builder.addRoute(
                    Ipv4Range.format(
                            start
                    ),
                    prefix
            );

            start +=
                    block;
        }
    }

    // ============================================================
    // STATS
    // ============================================================

    private void publishStats() {

        if (!active) {
            return;
        }

        long tx = 0;
        long rx = 0;

        if ("psiphon".equals(value(activeRequest, "protocol", ""))) {
            tx = psiphonTx.get();
            rx = psiphonRx.get();
        } else if (bridgeStarted) {
            try {
                long[] stats = TProxyService.TProxyGetStats();
                if (stats != null && stats.length >= 4) {
                    tx = stats[1];
                    rx = stats[3];
                }
            } catch (Throwable error) {
                Log.w(TAG, "Could not read HEV stats", error);
            }
        }

        maybeCheckTunnelHealth();

        Intent intent =
                new Intent(
                        ACTION_STATS
                ).setPackage(
                        getPackageName()
                );

        intent.putExtra(
                "tx",
                tx
        );

        intent.putExtra(
                "rx",
                rx
        );

        intent.putExtra(
                "ping",
                lastPing
        );

        intent.putExtra(
                "connectedAt",
                connectedAt
        );

        if (currentExitIp != null && !currentExitIp.isEmpty()) {
            intent.putExtra("exitIp", currentExitIp);
        }

        intent.putExtra(
                "psiphonSocksPort",
                psiphonSocksPort.get()
        );

        sendBroadcast(
                intent,
                INTERNAL_PERMISSION
        );
    }

    // ============================================================
    // HEALTH
    // ============================================================

    private void maybeCheckTunnelHealth() {

        if (!"connected".equals(
                currentState
        ) ||
                networkUnavailable ||
                System.currentTimeMillis() -
                        lastHealthCheckAt <
                        15_000L ||
                !healthCheckRunning.compareAndSet(
                        false,
                        true
                )) {

            return;
        }

        lastHealthCheckAt =
                System.currentTimeMillis();

        final Intent request =
                activeRequest;

        final long healthSession =
                generation.get();

        worker.execute(
                () -> {

                    try {

                        if (request == null ||
                                stopping ||
                                !active ||
                                generation.get() !=
                                        healthSession ||
                                request !=
                                        activeRequest) {

                            return;
                        }

                        lastPing =
                                socksConnectMillis(
                                        resolvePingSocks(
                                                request
                                        ),
                                        "1.1.1.1",
                                        443,
                                        4_000
                                );

                        consecutiveHealthFailures =
                                0;

                    } catch (Throwable error) {

                        lastPing =
                                -1;

                        if (generation.get() !=
                                healthSession ||
                                request !=
                                        activeRequest ||
                                stopping ||
                                !active) {

                            return;
                        }

                        consecutiveHealthFailures++;

                        sendLog(
                                "[Health] Probe failed (" +
                                        consecutiveHealthFailures +
                                        "/3): " +
                                        safeMessage(
                                                error
                                        )
                        );

                        if (consecutiveHealthFailures >= 3) {

                            consecutiveHealthFailures =
                                    0;

                            if (generation.get() ==
                                    healthSession &&
                                    request ==
                                            activeRequest &&
                                    active &&
                                    !stopping) {

                                requestCoreRecovery(
                                        getString(
                                                R.string.service_tunnel_unresponsive
                                        )
                                );
                            }
                        }

                    } finally {

                        healthCheckRunning.set(
                                false
                        );
                    }
                }
        );
    }

    // ============================================================
    // PSIPHON SOFT RESTART (keep TUN + HEV + Aether)
    // ============================================================

    private void softRestartPsiphonOnly(String reason) {

        if (stopping ||
                lastPsiphonConfig == null ||
                lastPsiphonConfig.isEmpty()) {

            sendLog(
                    "[Psiphon] Soft restart skipped: " +
                            reason
            );
            return;
        }

        sendLog(
                "[Psiphon] Soft-restart only (keep TUN/HEV/Aether): " +
                        reason
        );

        try {
            // stopping را true نکنید تا onDisconnected → recovery کامل نشود
            psiphonCore.stop();
        } catch (Throwable t) {
            sendLog(
                    "[Psiphon] Soft stop warning: " +
                            safeMessage(t)
            );
        }

        psiphonSocksPort.set(0);

        try {
            Thread.sleep(400);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }

        if (stopping) {
            return;
        }

        try {
            // Refresh TUN fd in cached config (Packet Tunnel path)
            if (vpnInterface != null &&
                    lastPsiphonConfig != null &&
                    !lastPsiphonConfig.isEmpty()) {
                try {
                    org.json.JSONObject cfg =
                            new org.json.JSONObject(lastPsiphonConfig);
                    cfg.put(
                            "PacketTunnelTunFileDescriptor",
                            vpnInterface.getFd()
                    );
                    lastPsiphonConfig = cfg.toString();
                } catch (Throwable ignored) {
                }
            }

            psiphonCore.start(
                    lastPsiphonConfig,
                    true
            );
            sendLog(
                    "[Psiphon] Soft-restart issued (vpnMode=true); waiting for CONNECTED..."
            );
        } catch (Throwable t) {
            sendLog(
                    "[Psiphon] Soft-restart FAILED: " +
                            safeMessage(t)
            );
            requestCoreRecovery(
                    "Psiphon soft-restart failed: " +
                            safeMessage(t)
            );
        }
    }

    // ============================================================
    // RECOVERY
    // ============================================================

    private void requestCoreRecovery(
            String reason
    ) {

        if (!recoveryRestartPending.compareAndSet(
                false,
                true
        )) {

            sendLog(
                    "[Recovery] Already pending: " +
                            reason
            );

            return;
        }

        sendLog(
                "[Recovery] Requested: " +
                        reason
        );

        worker.execute(
                () -> {

                    Intent oldRequest =
                            activeRequest;

                    long newSession =
                            generation.incrementAndGet();

                    try {

                        stopping =
                                true;

                        sendLog(
                                "[Recovery] Starting session " +
                                        newSession
                        );

                        if (oldRequest == null) {

                            sendLog(
                                    "[Recovery] No active request"
                            );

                            active =
                                    false;

                            connectionEstablished =
                                    false;

                            return;
                        }

                        synchronized (runtimeLock) {

                            stopRuntime();
                        }

                        active =
                                false;

                        connectionEstablished =
                                false;

                        currentEndpoint =
                                "";

                        locationLookupSequence.incrementAndGet();

                        psiphonSocksPort.set(
                                0
                        );

                        Intent newRequest =
                                new Intent(
                                        oldRequest
                                );

                        String protocol =
                                value(
                                        newRequest,
                                        "protocol",
                                        "masque"
                                );

                        sendLog(
                                "[Recovery] Reconnecting " +
                                        protocolLabel(
                                                protocol
                                        )
                        );

                        activeRequest =
                                newRequest;

                        active =
                                true;

                        stopping =
                                false;

                        runConnection(
                                newRequest,
                                newSession
                        );

                    } catch (Throwable error) {

                        sendLog(
                                "[Recovery] FAILED: " +
                                        safeMessage(
                                                error
                                        )
                        );

                        stopping =
                                true;

                        try {

                            synchronized (runtimeLock) {

                                stopRuntime();
                            }

                        } catch (Throwable cleanupError) {

                            Log.w(
                                    TAG,
                                    "Recovery cleanup failed",
                                    cleanupError
                            );
                        }

                        active =
                                false;

                        connectionEstablished =
                                false;

                        currentEndpoint =
                                "";

                    } finally {

                        recoveryRestartPending.set(
                                false
                        );

                        if (!active) {

                            stopping =
                                    false;
                        }
                    }
                }
        );
    }

    // ============================================================
    // STOP CONNECTION
    // ============================================================

    private void stopConnection(
            boolean userInitiated
    ) {

        stopping =
                true;

        stopRuntime();

        active =
                false;

        connectedAt =
                0;

        currentEndpoint =
                "";

        locationLookupSequence.incrementAndGet();

        activeRequest =
                null;

        connectionEstablished =
                false;

        recoveryRestartPending.set(
                false
        );

        psiphonSocksPort.set(
                0
        );

        lastPsiphonConfig = "";

        lastPing =
                -1;

        consecutiveHealthFailures =
                0;

        if (userInitiated) {

            updateState(
                    "disconnected",
                    getString(
                            R.string.service_disconnected
                    )
            );

            stopForeground(
                    STOP_FOREGROUND_REMOVE
            );

            stopSelf();
        }
    }

    // ============================================================
    // STOP RUNTIME
    // ============================================================

    private void stopRuntime() {
        try {
            lanSocksBridge.stop();
        } catch (Throwable ignored) {
        }


        synchronized (runtimeLock) {

            /*
             * stopping باید قبل از stop Psiphon true باشد
             * تا onDisconnected باعث Recovery نشود.
             */

            if (psiphonCore != null) {

                try {

                    psiphonCore.stop();

                } catch (Throwable error) {

                    Log.w(
                            TAG,
                            "Could not stop Psiphon",
                            error
                    );
                }
            }

            psiphonSocksPort.set(
                    0
            );

            lastPsiphonConfig = "";

            if (bridgeStarted) {

                try {

                    TProxyService.TProxyStopService();

                } catch (Throwable error) {

                    Log.w(
                            TAG,
                            "Could not stop HEV",
                            error
                    );
                }

                bridgeStarted =
                        false;
            }

            try {

                if (vpnInterface != null) {

                    vpnInterface.close();
                }

            } catch (Exception ignored) {
            }

            vpnInterface =
                    null;

            stopAetherOnly();
        }
    }


    private void startAetherSecondary(Intent request) throws Exception {
        torExitReady = false;
        Intent copy = new Intent(request);
        copy.putExtra("aether_secondary", true);
        // Exit layer: --tor-only (+ optional --upstream). Do not inherit carrier protocol.
        copy.putExtra("protocol", "masque");
        copy.putExtra("upstream_protocol", "");
        copy.putExtra("enable_http_proxy", false);
        copy.putExtra("lanSharing", false);
        // Preserve enableTor / tor_mode / aether_upstream / socksPort from caller
        startAether(copy);
    }

    /** Wait for SOCKS; when forTorExit, watch torExitProcess instead of aetherProcess. */
    private boolean waitForSocksProcess(
            String address,
            long timeoutMs,
            boolean forTorExit
    ) {
        HostPort target = HostPort.parse(address);
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!stopping && System.currentTimeMillis() < deadline) {
            Process process = forTorExit ? torExitProcess : aetherProcess;
            if (process != null && !process.isAlive()) {
                return false;
            }
            try (Socket socket = new Socket()) {
                socket.connect(
                        new java.net.InetSocketAddress(target.host, target.port),
                        800
                );
                return true;
            } catch (Exception ignored) {
            }
            try {
                Thread.sleep(400);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private void stopTorExitOnly() {
        Process process;
        synchronized (runtimeLock) {
            process = torExitProcess;
            torExitProcess = null;
        }
        if (process == null) {
            return;
        }
        try {
            process.destroy();
        } catch (Exception ignored) {
        }
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
        sendLog("[Tor] Exit process stopped");
    }

    // ============================================================
    // STOP AETHER
    // ============================================================

    private void stopAetherOnly() {

        stopTorExitOnly();

        Process process =
                aetherProcess;

        aetherProcess =
                null;

        if (process != null) {

            process.destroy();

            try {

                if (!process.waitFor(
                        2,
                        TimeUnit.SECONDS
                )) {

                    process.destroyForcibly();
                }

            } catch (InterruptedException error) {

                Thread.currentThread().interrupt();
            }
        }
    }

    // ============================================================
    // AETHER EXIT
    // ============================================================

    private String torExitMessage(String fallback) {
        Process process = torExitProcess;
        if (process != null && !process.isAlive()) {
            try {
                return fallback + " (tor-exit exit " + process.exitValue() + ")";
            } catch (IllegalThreadStateException ignored) {
            }
        } else if (process == null) {
            return fallback + " (tor-exit process missing)";
        }
        return fallback + " (tor-exit still running but SOCKS not open)";
    }

    private String aetherExitMessage(
            String fallback
    ) {

        Process process =
                aetherProcess;

        if (process != null &&
                !process.isAlive()) {

            try {

                return fallback +
                        " (exit " +
                        process.exitValue() +
                        ")";

            } catch (
                    IllegalThreadStateException ignored
            ) {
            }
        }

        return fallback;
    }

    // ============================================================
    // STATE
    // ============================================================

    private void updateState(
            String state,
            String message
    ) {

        currentState =
                state;

        currentMessage =
                message == null
                        ? ""
                        : message;

        stateStore.edit()
                .putString(
                        "state",
                        currentState
                )
                .putString(
                        "message",
                        currentMessage
                )
                .putString(
                        "endpoint",
                        currentEndpoint
                )
                .apply();

        sendStatus(
                currentState,
                currentMessage
        );

        AethonTileService.requestUpdate(
                this
        );
    }

    private void broadcastUpstreamStatus(
            boolean ok,
            long latency
    ) {

        Intent intent =
                new Intent(
                        ACTION_STATUS
                ).setPackage(
                        getPackageName()
                );

        intent.putExtra(
                "state",
                currentState
        );

        intent.putExtra(
                "upstream_ok",
                ok
        );

        intent.putExtra(
                "upstream_latency",
                latency
        );

        sendBroadcast(
                intent,
                INTERNAL_PERMISSION
        );
    }

    private void sendStatus(
            String state,
            String message
    ) {

        Intent intent =
                new Intent(
                        ACTION_STATUS
                ).setPackage(
                        getPackageName()
                );

        intent.putExtra(
                "state",
                state
        );

        intent.putExtra(
                "message",
                message
        );

        intent.putExtra(
                "endpoint",
                currentEndpoint
        );

        intent.putExtra(
                "psiphonSocksPort",
                psiphonSocksPort.get()
        );

        if (currentExitIp != null && !currentExitIp.isEmpty()) {
            intent.putExtra("exitIp", currentExitIp);
        }

        sendBroadcast(
                intent,
                INTERNAL_PERMISSION
        );
    }

    // ============================================================
    // LOG
    // ============================================================

    private void sendLog(
            String line
    ) {

        if (line == null ||
                line.trim().isEmpty()) {

            return;
        }

        Log.i(
                TAG,
                line
        );

        synchronized (logLock) {

            if (logHistory.length() > 0) {
                logHistory.append(
                        '\n'
                );
            }

            logHistory.append(
                    line
            );

            trimLog(
                    logHistory,
                    24_000
            );

            if (pendingLogs.length() > 0) {
                pendingLogs.append(
                        '\n'
                );
            }

            pendingLogs.append(
                    line
            );
        }
    }

    private void flushLogs() {

        String batch;

        String history;

        synchronized (logLock) {

            if (pendingLogs.length() == 0) {
                return;
            }

            batch =
                    pendingLogs.toString();

            pendingLogs.setLength(
                    0
            );

            history =
                    logHistory.toString();
        }

        long now =
                System.currentTimeMillis();

        if (now -
                lastLogPersistedAt >=
                500) {

            stateStore.edit()
                    .putString(
                            "logs",
                            history
                    )
                    .apply();

            lastLogPersistedAt =
                    now;
        }

        Intent intent =
                new Intent(
                        ACTION_LOG
                )
                        .setPackage(
                                getPackageName()
                        )
                        .putExtra(
                                "lines",
                                batch
                        );

        sendBroadcast(
                intent,
                INTERNAL_PERMISSION
        );
    }

    private static void trimLog(
            StringBuilder value,
            int maxLength
    ) {

        if (value.length() <=
                maxLength) {

            return;
        }

        int cut =
                value.length() -
                        maxLength;

        int newline =
                value.indexOf(
                        "\n",
                        cut
                );

        value.delete(
                0,
                newline >= 0
                        ? newline + 1
                        : cut
        );
    }

    // ============================================================
    // NOTIFICATION
    // ============================================================

    private void createNotificationChannel() {

        NotificationChannel channel =
                new NotificationChannel(
                        CHANNEL_ID,
                        getString(
                                R.string.notification_channel
                        ),
                        NotificationManager.IMPORTANCE_LOW
                );

        channel.setDescription(
                getString(
                        R.string.notification_channel_summary
                )
        );

        getSystemService(
                NotificationManager.class
        ).createNotificationChannel(
                channel
        );
    }

    private Notification notification(
            String text,
            boolean connected
    ) {

        Intent open =
                new Intent(
                        this,
                        MainActivity.class
                )
                        .addFlags(
                                Intent.FLAG_ACTIVITY_CLEAR_TOP |
                                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                        );

        PendingIntent content =
                PendingIntent.getActivity(
                        this,
                        0,
                        open,
                        PendingIntent.FLAG_IMMUTABLE |
                                PendingIntent.FLAG_UPDATE_CURRENT
                );

        Intent stop =
                new Intent(
                        this,
                        AetherVpnService.class
                )
                        .setAction(
                                ACTION_STOP
                        );

        PendingIntent disconnect =
                PendingIntent.getService(
                        this,
                        1,
                        stop,
                        PendingIntent.FLAG_IMMUTABLE |
                                PendingIntent.FLAG_UPDATE_CURRENT
                );

        NotificationCompat.Builder builder =
                new NotificationCompat.Builder(
                        this,
                        CHANNEL_ID
                )
                        .setSmallIcon(
                                R.drawable.ic_aethon_mono
                        )
                        .setContentTitle(
                                getString(
                                        R.string.app_name
                                )
                        )
                        .setContentText(
                                text
                        )
                        .setOngoing(
                                true
                        )
                        .setOnlyAlertOnce(
                                true
                        )
                        .setContentIntent(
                                content
                        )
                        .setCategory(
                                NotificationCompat.CATEGORY_SERVICE
                        )
                        .setPriority(
                                NotificationCompat.PRIORITY_LOW
                        );

        if (connected ||
                active) {

            builder.addAction(
                    0,
                    getString(
                            R.string.disconnect
                    ),
                    disconnect
            );
        }

        return builder.build();
    }

    private void startForegroundCompat(
            Notification notification
    ) {

        if (Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {

            startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            );

        } else {

            startForeground(
                    NOTIFICATION_ID,
                    notification
            );
        }
    }

    private void updateNotification(
            String text
    ) {

        getSystemService(
                NotificationManager.class
        ).notify(
                NOTIFICATION_ID,
                notification(
                        text,
                        true
                )
        );
    }

    // ============================================================
    // REVOKE
    // ============================================================

    @Override
    public void onRevoke() {

        stateStore.edit()
                .putBoolean(
                        "desiredConnected",
                        false
                )
                .apply();

        generation.incrementAndGet();

        stopping =
                true;

        worker.execute(
                () -> stopConnection(true)
        );

        super.onRevoke();
    }

    // ============================================================
    // DESTROY
    // ============================================================

    @Override
    public void onDestroy() {

        stopping =
                true;

        try {

            stopRuntime();

        } catch (Throwable error) {

            Log.w(
                    TAG,
                    "Runtime cleanup during destroy failed",
                    error
            );
        }

        flushLogs();

        synchronized (logLock) {

            stateStore.edit()
                    .putString(
                            "logs",
                            logHistory.toString()
                    )
                    .apply();
        }

        if (VpnConnectionController.canDisconnect(
                currentState
        )) {

            currentState =
                    "disconnected";

            currentMessage =
                    getString(
                            R.string.service_disconnected
                    );

            stateStore.edit()
                    .putString(
                            "state",
                            currentState
                    )
                    .putString(
                            "message",
                            currentMessage
                    )
                    .putString(
                            "endpoint",
                            ""
                    )
                    .apply();

            AethonTileService.requestUpdate(
                    this
            );
        }

        telemetry.shutdownNow();

        worker.shutdownNow();

        try {

            connectivityManager.unregisterNetworkCallback(
                    networkCallback
            );

        } catch (RuntimeException error) {

            Log.w(
                    TAG,
                    "Network callback already unregistered",
                    error
            );
        }

        super.onDestroy();
    }

    // ============================================================
    // HELPERS
    // ============================================================

    private static String value(
            Intent intent,
            String key,
            String fallback
    ) {

        String result =
                intent.getStringExtra(
                        key
                );

        return result == null ||
                result.trim().isEmpty()
                ? fallback
                : result.trim();
    }

    private static String socksAddress(
            Intent intent
    ) {

        String socks =
                intent.getStringExtra(
                        "socks"
                );

        if (socks == null ||
                socks.trim().isEmpty()) {

            int port =
                    intent.getIntExtra(
                            "socksPort",
                            AETHER_DEFAULT_PORT
                    );

            socks =
                    "127.0.0.1:" +
                            port;
        }

        return socks
                .trim()
                .replace(
                        "0.0.0.0:",
                        "127.0.0.1:"
                );
    }

    /** SOCKS endpoint used for latency probes (works for Aether + Psiphon). */
    private String resolvePingSocks(Intent request) {
        if (request == null) return null;
        if ("psiphon".equals(value(request, "protocol", ""))) {
            int p = psiphonSocksPort.get();
            if (p <= 0) {
                p = request.getIntExtra("socksPort", 0);
            }
            if (p > 0) {
                return "127.0.0.1:" + p;
            }
            return "127.0.0.1:" + AETHER_DEFAULT_PORT;
        }
        return socksAddress(request);
    }

    private static String safeMessage(
            Throwable error
    ) {

        String message =
                error.getMessage();

        return message == null ||
                message.trim().isEmpty()
                ? error.getClass()
                .getSimpleName()
                : message;
    }

    private static String yamlEscape(String value) {
        if (value == null) return "";
        return value.replace("'", "''");
    }

    private static String reliableEndpoint(
            Intent request
    ) {

        String peer =
                request.getStringExtra(
                        "peer"
                );

        return peer == null
                ? ""
                : peer.trim();
    }

    // ============================================================
    // SMART RESULT
    // ============================================================

    private static final class SmartResult {

        final String protocol;

        final boolean connected;

        final long handshakeMs;

        final long latencyMs;

        final long dnsMs;

        final int stableProbes;

        final int attempts;

        final double score;

        private SmartResult(
                String protocol,
                boolean connected,
                long handshakeMs,
                long latencyMs,
                long dnsMs,
                int stableProbes,
                int attempts,
                double score
        ) {

            this.protocol =
                    protocol;

            this.connected =
                    connected;

            this.handshakeMs =
                    handshakeMs;

            this.latencyMs =
                    latencyMs;

            this.dnsMs =
                    dnsMs;

            this.stableProbes =
                    stableProbes;

            this.attempts =
                    attempts;

            this.score =
                    score;
        }

        static SmartResult failed(
                String protocol,
                long handshakeMs
        ) {

            return new SmartResult(
                    protocol,
                    false,
                    handshakeMs,
                    -1,
                    -1,
                    0,
                    5,
                    0
            );
        }

        static SmartResult success(
                String protocol,
                long handshakeMs,
                long latencyMs,
                long dnsMs,
                int stableProbes,
                int attempts
        ) {

            double handshakeScore =
                    30.0 *
                            clamp(
                                    1.0 -
                                            handshakeMs /
                                                    (double)
                                                            SMART_PROTOCOL_TIMEOUT_MS
                            );

            double latencyScore =
                    15.0 *
                            clamp(
                                    1.0 -
                                            latencyMs /
                                                    2_000.0
                            );

            double stabilityScore =
                    5.0 *
                            stableProbes /
                            Math.max(
                                    1,
                                    attempts
                            );

            return new SmartResult(
                    protocol,
                    true,
                    handshakeMs,
                    latencyMs,
                    dnsMs,
                    stableProbes,
                    attempts,
                    50.0 +
                            handshakeScore +
                            latencyScore +
                            stabilityScore
            );
        }

        String summary() {

            double loss =
                    attempts == 0
                            ? 100
                            : 100.0 *
                              (
                                      attempts -
                                      stableProbes
                              ) /
                              attempts;

            return String.format(
                    Locale.US,
                    "Smart Connect %s: success=%s handshake=%dms latency=%dms dns=%dms loss=%.0f%% stability=%d/%d score=%.1f",
                    protocolLabel(
                            protocol
                    ),
                    connected,
                    handshakeMs,
                    latencyMs,
                    dnsMs,
                    loss,
                    stableProbes,
                    attempts,
                    score
            );
        }

        private static double clamp(
                double value
        ) {

            return Math.max(
                    0,
                    Math.min(
                            1,
                            value
                    )
            );
        }
    }

    // ============================================================
    // HOST PORT
    // ============================================================

    private static final class HostPort {

        final String host;

        final int port;

        private HostPort(
                String host,
                int port
        ) {

            this.host =
                    host;

            this.port =
                    port;
        }

        static HostPort parse(
                String value
        ) {

            if (value == null) {

                throw new IllegalArgumentException(
                        "SOCKS5 address is missing"
                );
            }

            String input =
                    value.trim();

            String host;

            String portValue;

            if (input.startsWith("[")) {

                int end =
                        input.indexOf(
                                ']'
                        );

                if (end < 0 ||
                        end + 2 >
                                input.length() ||
                        input.charAt(
                                end + 1
                        ) != ':') {

                    throw new IllegalArgumentException(
                            "Invalid SOCKS5 address"
                    );
                }

                host =
                        input.substring(
                                1,
                                end
                        );

                portValue =
                        input.substring(
                                end + 2
                        );

            } else {

                int split =
                        input.lastIndexOf(
                                ':'
                        );

                if (split <= 0) {

                    throw new IllegalArgumentException(
                            "SOCKS5 address must use host:port"
                    );
                }

                host =
                        input.substring(
                                0,
                                split
                        );

                portValue =
                        input.substring(
                                split + 1
                        );
            }

            int port;

            try {

                port =
                        Integer.parseInt(
                                portValue
                        );

            } catch (
                    NumberFormatException error
            ) {

                throw new IllegalArgumentException(
                        "Invalid SOCKS5 port"
                );
            }

            if (host.trim().isEmpty() ||
                    port < 1 ||
                    port > 65535) {

                throw new IllegalArgumentException(
                        "Invalid SOCKS5 address"
                );
            }

            return new HostPort(
                    host.trim(),
                    port
            );
        }
    }

    // ============================================================
    // IPV4 RANGE
    // ============================================================

    private static final class Ipv4Range
            implements Comparable<Ipv4Range> {

        final long start;

        final long end;

        private Ipv4Range(
                long start,
                long end
        ) {

            this.start =
                    start;

            this.end =
                    end;
        }

        static Ipv4Range cidr(
                String address,
                int prefix
        ) {

            long value =
                    parse(
                            address
                    );

            long size =
                    1L <<
                            (32 - prefix);

            return new Ipv4Range(
                    value,
                    value +
                            size -
                            1
            );
        }

        static long parse(
                String address
        ) {

            String[] parts =
                    address.split(
                            "\\."
                    );

            if (parts.length != 4) {

                throw new IllegalArgumentException(
                        "Invalid IPv4 address"
                );
            }

            long value =
                    0;

            for (
                    String part :
                    parts
            ) {

                value =
                        (
                                value << 8
                        ) |
                                Integer.parseInt(
                                        part
                                );
            }

            return value;
        }

        static String format(
                long value
        ) {

            return (
                    (
                            value >>> 24
                    ) &
                            255
            ) +
                    "." +
                    (
                            (
                                    value >>> 16
                            ) &
                                    255
                    ) +
                    "." +
                    (
                            (
                                    value >>> 8
                            ) &
                                    255
                    ) +
                    "." +
                    (
                            value &
                                    255
                    );
        }

        @Override
        public int compareTo(
                Ipv4Range other
        ) {

            return Long.compare(
                    start,
                    other.start
            );
        }
    }
}


