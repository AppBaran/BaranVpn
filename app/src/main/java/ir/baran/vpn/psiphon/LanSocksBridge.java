package ir.baran.vpn.psiphon;

import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Exposes Psiphon's loopback SOCKS to the LAN.
 *
 * Psiphon's local SOCKS is almost always bound to 127.0.0.1 only (the
 * LocalSOCKSProxyListenInterface=0.0.0.0 config key is ignored by many AARs).
 * This bridge listens on 0.0.0.0:{publicPort} and pipes each TCP session to
 * 127.0.0.1:{psiphonSocksPort}, so both:
 *   • 127.0.0.1:1819
 *   • 192.168.x.x:1819
 * work for SOCKS5 clients (v2ray, browsers, etc.).
 */
public final class LanSocksBridge {

    private static final String TAG = "LanSocksBridge";

    private final AtomicBoolean running = new AtomicBoolean(false);
    private ServerSocket serverSocket;
    private ExecutorService acceptPool;
    private ExecutorService pipePool;
    private int publicPort;
    private int targetPort;

    public synchronized boolean start(int publicPort, int targetPort) {
        stop();
        this.publicPort = publicPort;
        this.targetPort = targetPort;
        try {
            // 0.0.0.0 = all interfaces (loopback + Wi-Fi)
            ServerSocket ss = new ServerSocket();
            ss.setReuseAddress(true);
            ss.bind(new java.net.InetSocketAddress((InetAddress) null, publicPort), 64);
            serverSocket = ss;
            running.set(true);
            acceptPool = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "lan-socks-accept");
                t.setDaemon(true);
                return t;
            });
            pipePool = Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "lan-socks-pipe");
                t.setDaemon(true);
                return t;
            });
            acceptPool.execute(this::acceptLoop);
            Log.i(TAG, "Listening on 0.0.0.0:" + publicPort
                    + " → 127.0.0.1:" + targetPort);
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Failed to bind 0.0.0.0:" + publicPort + " — " + e.getMessage());
            stop();
            return false;
        }
    }

    public synchronized void stop() {
        running.set(false);
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
            serverSocket = null;
        }
        if (acceptPool != null) {
            acceptPool.shutdownNow();
            acceptPool = null;
        }
        if (pipePool != null) {
            pipePool.shutdownNow();
            pipePool = null;
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    public int getPublicPort() {
        return publicPort;
    }

    private void acceptLoop() {
        ServerSocket ss = serverSocket;
        if (ss == null) return;
        while (running.get()) {
            try {
                Socket client = ss.accept();
                try {
                    client.setTcpNoDelay(true);
                    client.setKeepAlive(true);
                } catch (SocketException ignored) {
                }
                ExecutorService pool = pipePool;
                if (pool == null || pool.isShutdown()) {
                    closeQuietly(client);
                    continue;
                }
                try {
                    pool.execute(() -> handleClient(client));
                } catch (java.util.concurrent.RejectedExecutionException e) {
                    closeQuietly(client);
                }
            } catch (IOException e) {
                if (running.get()) {
                    Log.w(TAG, "accept error: " + e.getMessage());
                }
                break;
            }
        }
    }

    private void handleClient(Socket client) {
        Socket upstream = null;
        try {
            upstream = new Socket();
            upstream.setTcpNoDelay(true);
            upstream.connect(
                    new java.net.InetSocketAddress("127.0.0.1", targetPort),
                    8_000
            );
            Socket u = upstream;
            // bidirectional copy
            ExecutorService pool2 = pipePool;
            if (pool2 != null && !pool2.isShutdown()) {
                try {
                    pool2.execute(() -> pipe(client, u));
                } catch (java.util.concurrent.RejectedExecutionException ignored) {
                }
            }
            // if pool gone, still run one direction on this thread
            pipe(u, client);
        } catch (IOException e) {
            Log.d(TAG, "session closed: " + e.getMessage());
        } finally {
            closeQuietly(client);
            closeQuietly(upstream);
        }
    }

    private static void pipe(Socket from, Socket to) {
        try {
            InputStream in = from.getInputStream();
            OutputStream out = to.getOutputStream();
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) >= 0) {
                if (n == 0) continue;
                out.write(buf, 0, n);
                out.flush();
            }
        } catch (IOException ignored) {
        } finally {
            try {
                to.shutdownOutput();
            } catch (Exception ignored) {
            }
        }
    }

    private static void closeQuietly(Socket s) {
        if (s == null) return;
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }
}
