package ir.baran.vpn.psiphon;

import android.content.Context;
import android.net.VpnService;
import android.util.Log;

import ca.psiphon.PsiphonTunnel;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.List;

/**
 * Psiphon tunnel wrapper implementing {@link PsiphonTunnel.HostService}.
 *
 * Critical (same pattern as MSN-GUARD):
 * {@link #bindToDevice(long)} MUST call {@link VpnService#protect(int)} so that
 * after Android TUN is up, Psiphon's own sockets still leave on the real
 * underlying network and do not loop into TUN → HEV → Psiphon SOCKS.
 */
public class PsiphonCore implements PsiphonTunnel.HostService {

    private static final String TAG = "PsiphonCore";

    private final Context context;

    /**
     * When non-null, used for {@link VpnService#protect(int)} in bindToDevice.
     * Must be the live AetherVpnService instance, not ApplicationContext.
     */
    private volatile VpnService vpnService;

    private PsiphonTunnel tunnel;
    private PsiphonListener listener;
    private String configJson;

    public enum State {
        IDLE,
        CONNECTING,
        CONNECTED
    }

    private volatile State currentState = State.IDLE;

    public interface PsiphonListener {
        void onConnected();
        void onDisconnected();
        void onStatusMessage(String message);
        void onSocksProxyPort(int port);
        void onRegionsUpdated(List<String> regions);
        void onBytesTransferred(long sent, long received);
    }

    public PsiphonCore(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * Attach the running VpnService so bindToDevice can protect sockets.
     * Call this from AetherVpnService.onCreate (pass {@code this}).
     */
    public void setVpnService(VpnService service) {
        this.vpnService = service;
    }

    public void setListener(PsiphonListener listener) {
        this.listener = listener;
    }

    private String loadServerEntries() {
        StringBuilder result = new StringBuilder();

        try (
                BufferedReader reader =
                        new BufferedReader(
                                new InputStreamReader(
                                        context.getAssets()
                                                .open("server_entries.txt"),
                                        "UTF-8"
                                )
                        )
        ) {
            String line;

            while ((line = reader.readLine()) != null) {
                line = line.trim();

                if (!line.isEmpty()) {
                    result.append(line).append('\n');
                }
            }

            String entries = result.toString().trim();

            Log.i(
                    TAG,
                    "Embedded server entries loaded: "
                            + entries.length() + " chars"
            );

            return entries;

        } catch (Exception e) {

            Log.e(
                    TAG,
                    "Could not load assets/server_entries.txt",
                    e
            );

            return "";
        }
    }

    public synchronized void start(
            String configJson,
            boolean vpnMode
    ) {

        this.configJson = configJson;

        stopInternal(false);

        currentState = State.CONNECTING;

        try {
            if (tunnel == null) {
                tunnel = PsiphonTunnel.newPsiphonTunnel(this);
            }

            // vpnMode=false: we own TUN via HEV; Psiphon only provides local SOCKS.
            // vpnMode=true would make Psiphon own the TUN (not our architecture).
            try {
                tunnel.setVpnMode(vpnMode);
            } catch (Throwable ignored) {
                // older AAR may not expose setVpnMode
            }

            String serverEntries = loadServerEntries();

            Log.i(TAG, "Starting Psiphon with embedded entries");

            tunnel.startTunneling(serverEntries);

        } catch (Exception e) {
            currentState = State.IDLE;
            Log.e(TAG, "Failed to start Psiphon", e);
            if (listener != null) {
                listener.onStatusMessage(
                        "Psiphon start failed: " + e.getMessage()
                );
                listener.onDisconnected();
            }
            throw new IllegalStateException(
                    "Psiphon start failed: " + e.getMessage(),
                    e
            );
        }
    }

    public synchronized void stop() {
        stopInternal(true);
    }

    private void stopInternal(boolean notify) {
        currentState = State.IDLE;

        if (tunnel != null) {
            try {
                tunnel.stop();
            } catch (Throwable e) {
                Log.w(TAG, "Error stopping Psiphon", e);
            }
        }

        if (notify && listener != null) {
            listener.onDisconnected();
            listener.onStatusMessage("Psiphon stopped");
        }
    }

    public State getState() {
        return currentState;
    }

    // ---------------------------------------------------------------
    // PsiphonTunnel.HostService
    // ---------------------------------------------------------------

    @Override
    public Context getContext() {
        return context;
    }

    @Override
    public String getPsiphonConfig() {
        return configJson != null ? configJson : "{}";
    }

    @Override
    public void loadLibrary(String libName) {
        System.loadLibrary(libName);
    }

    /**
     * MSN-GUARD pattern: protect the fd so it never enters our own TUN.
     * Without this, after establishVpn() Psiphon dials loop into TUN and die.
     */
    @Override
    public void bindToDevice(long fileDescriptor) {
        VpnService vs = vpnService;
        if (vs == null) {
            Log.w(TAG, "bindToDevice: no VpnService attached, cannot protect fd=" + fileDescriptor);
            return;
        }
        if (!vs.protect((int) fileDescriptor)) {
            Log.e(TAG, "VpnService.protect(fd=" + fileDescriptor + ") failed");
            throw new IllegalStateException(
                    "VpnService.protect(fd=" + fileDescriptor + ") failed"
            );
        }
        Log.d(TAG, "bindToDevice: protected fd=" + fileDescriptor);
    }

    public Object getVpnService() {
        // Some library builds query this when vpnMode is considered.
        return vpnService;
    }

    @Override
    public void onDiagnosticMessage(String message) {
        Log.d(TAG, "DIAG: " + message);
        if (listener != null && message != null) {
            listener.onStatusMessage(message);
        }
    }

    @Override
    public void onConnected() {
        currentState = State.CONNECTED;
        Log.i(TAG, "Psiphon CONNECTED");
        if (listener != null) {
            listener.onConnected();
        }
    }

    @Override
    public void onListeningSocksProxyPort(int port) {
        Log.i(TAG, "Psiphon SOCKS listening on port " + port);
        if (listener != null) {
            listener.onSocksProxyPort(port);
        }
    }

    @Override
    public void onBytesTransferred(long sent, long received) {
        if (listener != null) {
            listener.onBytesTransferred(sent, received);
        }
    }

    @Override
    public void onAvailableEgressRegions(List<String> regions) {
        if (listener != null && regions != null) {
            listener.onRegionsUpdated(regions);
        }
    }

    @Override
    public void onListeningHttpProxyPort(int port) {
    }

    @Override
    public void onExiting() {
        Log.i(TAG, "Psiphon exiting");
        currentState = State.IDLE;
        if (listener != null) {
            listener.onDisconnected();
        }
    }

    @Override
    public void onStartedWaitingForNetworkConnectivity() {
        if (listener != null) {
            listener.onStatusMessage("Waiting for network...");
        }
    }

    @Override
    public void onStoppedWaitingForNetworkConnectivity() {
        if (listener != null) {
            listener.onStatusMessage("Network available");
        }
    }
}
