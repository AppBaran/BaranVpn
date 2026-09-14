package ir.baran.vpn;

import android.animation.ValueAnimator;
import android.content.BroadcastReceiver;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.app.StatusBarManager;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.HapticFeedbackConstants;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.ArrayAdapter;
import android.widget.ScrollView;
import android.widget.Toast;
import android.widget.TextView;
import android.widget.ProgressBar;
import android.widget.ImageView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.activity.OnBackPressedCallback;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.os.LocaleListCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import ir.baran.vpn.databinding.ActivityMainBinding;
import ir.baran.vpn.ui.SparklineView;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import ir.baran.vpn.psiphon.PsiphonServerEntriesParser;

import java.util.Map;

import ir.baran.vpn.psiphon.PsiphonServerRegions;
import ir.baran.vpn.psiphon.RegionListAdapter;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

public final class MainActivity extends AppCompatActivity {
    private static final int VPN_REQUEST = 41;
    private static final int NOTIFICATION_REQUEST = 42;
    private static final int APPS_REQUEST = 43;
    private static final int REGION_REQUEST = 44;
    private static final String INTERNAL_PERMISSION = "ir.baran.vpn.permission.INTERNAL";
    private ActivityMainBinding binding;
    private SharedPreferences preferences;
    private String state = "disconnected";
    private boolean upstreamReady = false;
    private String page = "connect";
    private boolean receiverRegistered;
    private String endpoint = "";
    private long lastTotalBytes = 0;
    private long lastRxBytes = 0;
    private long lastTxBytes = 0;
    private long lastRateAt = 0;
    private SparklineView downloadSpark;
    private SparklineView uploadSpark;
    private TextView exitIpValue;
    private String exitIp = "";
    /** Preloaded banner bitmaps keyed by imageUrl for instant display. */
    private final java.util.Map<String, android.graphics.Bitmap> bannerBitmapCache = new java.util.concurrent.ConcurrentHashMap<>();
    private String preloadedBannerUrl = null;
    private boolean updateDialogShowing = false;
    private static final long UPDATE_SNOOZE_MS = 24L * 60L * 60L * 1000L;
    private ValueAnimator shimmerAnimator;
    private final Handler updateHandler = new Handler(Looper.getMainLooper());
    private final Runnable pingPoll = new Runnable() {
        @Override public void run() {
            if ("connected".equals(state)) {
                startService(new Intent(MainActivity.this, AetherVpnService.class).setAction(AetherVpnService.ACTION_QUERY));
            }
            updateHandler.postDelayed(this, 10_000);
        }
    };
    private final Runnable updateProgressPoll = new Runnable() {
        @Override public void run() {
            if (binding == null) return;
        }
    };

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (binding == null) return;
            if (AetherVpnService.ACTION_STATUS.equals(intent.getAction())) {
                endpoint = intent.getStringExtra("endpoint");
                renderState(intent.getStringExtra("state"), intent.getStringExtra("message"));

                if (intent.hasExtra("upstream_ok")) {
                    boolean ok = intent.getBooleanExtra("upstream_ok", false);
                    long latency = intent.getLongExtra("upstream_latency", -1);
                    updateUpstreamStatus(ok, latency);
                }
            }
            else if (AetherVpnService.ACTION_STATS.equals(intent.getAction())) renderStats(intent);
        }
    };


    private void updateUpstreamProxyLabel() {
        TextView label = findViewById(R.id.warp_proxy_label);
        if (label == null) return;
        int protocol = preferences.getInt("protocol", 0);
        String key = protocol == 4 ? "tor_upstream" : "psiphon_upstream";
        String upstream = preferences.getString(key, "WARP");
        if (upstream == null || upstream.trim().isEmpty()) upstream = "WARP";
        // Show selected upstream name next to the status dot
        label.setText(upstream.trim());
    }

    private void updateUpstreamStatus(boolean ok, long latency) {
        TextView statusText = findViewById(R.id.upstream_status_text);
        if (statusText == null) return;

        if (ok) {
            statusText.setText(getString(R.string.upstream_status, latency + "ms"));
            statusText.setTextColor(getResources().getColor(android.R.color.holo_green_light));
        } else {
            statusText.setText(getString(R.string.upstream_status, "Offline"));
            statusText.setTextColor(getResources().getColor(android.R.color.holo_red_light));
        }
    }

    private void onProtocolChanged() {
        if ("connected".equals(state) || "starting".equals(state) || "scanning".equals(state)) {
            connect(); // Restart with new protocol
        }
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        preferences = getSharedPreferences("aether", MODE_PRIVATE);
        applyLanguage(preferences.getString("language", "fa"), false);
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Setup rotating border for metrics box (portrait layout only)
        float density = getResources().getDisplayMetrics().density;
        if (binding.metricsBoxWrapper != null) {
            ir.baran.vpn.ui.RotatingBorderDrawable border =
                    new ir.baran.vpn.ui.RotatingBorderDrawable(1.25f * density, 24 * density);
            binding.metricsBoxWrapper.setBackground(border);
            border.start();
        }

        downloadSpark = findViewById(R.id.download_spark);
        uploadSpark = findViewById(R.id.upload_spark);
        exitIpValue = findViewById(R.id.exit_ip_value);
        if (downloadSpark != null) downloadSpark.setColors(0xFF5B9CFF, 0x335B9CFF);
        if (uploadSpark != null) uploadSpark.setColors(0xFF5CE68F, 0x335CE68F);

        setupPsiphonRegions();
        setupTorUpstream();

        binding.getRoot().setAlpha(0f);
        binding.getRoot().setTranslationY(18f);
        binding.getRoot().post(() -> binding.getRoot().animate().alpha(1f).translationY(0f).setDuration(420).setInterpolator(new DecelerateInterpolator()).start());
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        ViewCompat.setOnApplyWindowInsetsListener(binding.mainContent, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            binding.mainContent.setPadding(bars.left, bars.top, bars.right, 0);
            binding.pageContainer.setPadding(0, 0, 0, 0);
            return insets;
        });
        setupDropdowns();
        restoreSettings();
        setupNavigation();
        setupActions();
        requestNotificationPermission();
        binding.statusVersion.setText(getString(R.string.version_format, BuildConfig.VERSION_NAME));
        renderState("disconnected", getString(R.string.status_ready_message));
        // Remote banners + force-update config
        RemoteConfig.load(this, snapshot -> {
            if (isFinishing()) return;
            maybeShowUpdateDialog(snapshot, false);
            preloadBannerImage();
        });

        startShimmer();
        if (getIntent().getBooleanExtra(AethonTileService.EXTRA_CONNECT_FROM_TILE, false)) {
            getIntent().removeExtra(AethonTileService.EXTRA_CONNECT_FROM_TILE);
            binding.getRoot().post(this::connect);
        }
    }

    private void startShimmer() {
        if (shimmerAnimator != null) shimmerAnimator.cancel();
        shimmerAnimator = ValueAnimator.ofFloat(0.55f, 1.0f);
        shimmerAnimator.setDuration(1600);
        shimmerAnimator.setRepeatMode(ValueAnimator.REVERSE);
        shimmerAnimator.setRepeatCount(ValueAnimator.INFINITE);
        shimmerAnimator.addUpdateListener(a -> {
            float val = (float) a.getAnimatedValue();
            if (binding != null) {
                binding.toolbarTitle.setAlpha(val);
                binding.instagramHeaderButton.setAlpha(val);
                binding.telegramHeaderButton.setAlpha(val);
                float scale = 1f + (val - 0.55f) * 0.05f;
                binding.toolbarTitle.setScaleX(scale);
                binding.toolbarTitle.setScaleY(scale);
            }
        });
        shimmerAnimator.start();
    }

    @Override protected void onDestroy() {
        if (shimmerAnimator != null) shimmerAnimator.cancel();
        super.onDestroy();
    }


    /** Tor module: carrier = WARP | WireGuard | Direct (tor-only). */
    private void setupTorUpstream() {
        MaterialAutoCompleteTextView torProxyInput = findViewById(R.id.tor_proxy_input);
        if (torProxyInput == null) return;
        String[] upstreamOptions = {"WireGuard", "WARP", "Direct"};
        ArrayAdapter<String> upstreamAdapter = new ArrayAdapter<>(this, R.layout.item_dropdown, upstreamOptions);
        torProxyInput.setAdapter(upstreamAdapter);
        String saved = preferences.getString("tor_upstream", "WARP");
        if (saved == null || saved.isEmpty()) saved = "WARP";
        // normalize legacy values
        if ("مستقیم".equals(saved)) saved = "Direct";
        torProxyInput.setText(saved, false);
        torProxyInput.setOnItemClickListener((parent, view, position, id) -> {
            preferences.edit().putString("tor_upstream", upstreamOptions[position]).apply();
            onProtocolChanged();
        });
    }

    private void setupPsiphonRegions() {
        MaterialAutoCompleteTextView regionInput = findViewById(R.id.psiphon_region_input);
        MaterialAutoCompleteTextView proxyInput = findViewById(R.id.psiphon_proxy_input);
        if (regionInput == null || proxyInput == null) return;

        Map<String, Integer> counts = PsiphonServerEntriesParser.parseServerEntriesCounts(this);
        List<String> codes = PsiphonServerEntriesParser.getParsedRegions(this);
        boolean isPersian = "fa".equals(preferences.getString("language", "fa"));

        int totalServers = 0;
        for (int c : counts.values()) totalServers += c;

        List<String> items = new ArrayList<>();
        for (String code : codes) {
            String flag = PsiphonServerRegions.getFlag(code);
            String name = PsiphonServerRegions.getName(code, isPersian);
            Integer count = code.isEmpty() ? totalServers : counts.get(code.toUpperCase());

            String item = flag + " " + name;
            if (count != null && count > 0) {
                item += " (" + count + ")";
            }
            items.add(item);
        }

        // Map display label -> region code (AutoComplete position is unreliable when filtered)
        final java.util.Map<String, String> labelToCode = new java.util.HashMap<>();
        for (int i = 0; i < codes.size(); i++) {
            labelToCode.put(items.get(i), codes.get(i));
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, R.layout.item_dropdown, items);
        regionInput.setAdapter(adapter);

        String savedRegion = preferences.getString("psiphon_region_code", "");
        int index = codes.indexOf(savedRegion);
        if (index >= 0) {
            regionInput.setText(items.get(index), false);
        } else {
            regionInput.setText(items.get(0), false);
            preferences.edit().putString("psiphon_region_code", "").apply();
        }

        regionInput.setOnItemClickListener((parent, view, position, id) -> {
            // Prefer resolving by label text — filtered adapter positions != codes index
            String label = String.valueOf(parent.getItemAtPosition(position));
            String code = labelToCode.containsKey(label) ? labelToCode.get(label) : "";
            if (code == null) code = "";
            preferences.edit().putString("psiphon_region_code", code).apply();
            android.util.Log.i("MainActivity", "Psiphon region selected code=" + code + " label=" + label);
            onProtocolChanged();
        });

        // If user never opens dropdown, still resolve code from current text on focus loss
        regionInput.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) return;
            String label = regionInput.getText() != null ? regionInput.getText().toString().trim() : "";
            String code = labelToCode.containsKey(label) ? labelToCode.get(label) : null;
            if (code != null) {
                preferences.edit().putString("psiphon_region_code", code).apply();
            }
        });

        // Setup Upstream Proxy Dropdown
        String[] upstreamOptions = {"WireGuard", "WARP", "Direct"};
        ArrayAdapter<String> upstreamAdapter = new ArrayAdapter<>(this, R.layout.item_dropdown, upstreamOptions);
        proxyInput.setAdapter(upstreamAdapter);

        String savedUpstream = preferences.getString("psiphon_upstream", "WARP");
        proxyInput.setText(savedUpstream, false);
        updateUpstreamProxyLabel();

        proxyInput.setOnItemClickListener((parent, view, position, id) -> {
            preferences.edit().putString("psiphon_upstream", upstreamOptions[position]).apply();
            updateUpstreamProxyLabel();
            onProtocolChanged();
        });

        // Region opens a proper dialog (no AutoComplete ghost items)
        View regionBtn = findViewById(R.id.psiphon_region_button);
        if (regionBtn != null) {
            regionBtn.setOnClickListener(v -> showRegionDialog());
            String savedCode = preferences.getString("psiphon_region_code", "");
            String flag = PsiphonServerRegions.getFlag(savedCode);
            String name = PsiphonServerRegions.getName(savedCode, isPersian);
            int cnt = savedCode.isEmpty() ? totalServers
                    : (counts.containsKey(savedCode.toUpperCase()) ? counts.get(savedCode.toUpperCase()) : 0);
            updateRegionButtonLabel(savedCode, flag, name, cnt);
        }
    }

    private void setupDropdowns() {
        setAdapter(binding.protocolInput, R.array.protocol_labels);
        setAdapter(binding.scanInput, R.array.scan_labels);
        setAdapter(binding.transportInput, R.array.transport_labels);
        setAdapter(binding.ipInput, R.array.ip_labels);
        setAdapter(binding.obfuscationInput, R.array.obfuscation_labels);
        setAdapter(binding.logInput, R.array.log_labels);
        setAdapter(binding.themeInput, R.array.theme_labels);
        setAdapter(binding.languageInput, R.array.language_labels);
        binding.protocolInput.setOnItemClickListener((p, v, position, id) -> {
            binding.protocolInput.setTag(position);
            updateModeUi();
            saveSettings();
            onProtocolChanged();
        });
        binding.scanInput.setOnItemClickListener((p, v, position, id) -> { binding.scanInput.setTag(position); saveSettings(); });
        binding.transportInput.setOnItemClickListener((p, v, position, id) -> { binding.transportInput.setTag(position); saveSettings(); });
        binding.ipInput.setOnItemClickListener((p, v, position, id) -> { binding.ipInput.setTag(position); saveSettings(); });
        binding.obfuscationInput.setOnItemClickListener((p, v, position, id) -> { binding.obfuscationInput.setTag(position); saveSettings(); });
        binding.logInput.setOnItemClickListener((p, v, position, id) -> { binding.logInput.setTag(position); saveSettings(); });
        binding.themeInput.setOnItemClickListener((p, v, position, id) -> { binding.themeInput.setTag(position); saveSettings(); applyTheme(position); });
        binding.languageInput.setOnItemClickListener((p, v, position, id) -> { preferences.edit().putString("language", position == 1 ? "fa" : "en").apply(); applyLanguage(position == 1 ? "fa" : "en", true); });
    }

    private void setAdapter(MaterialAutoCompleteTextView view, int arrayId) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, R.layout.item_dropdown, getResources().getStringArray(arrayId));
        view.setAdapter(adapter);
    }

    private void setupNavigation() {
        binding.bottomNavigation.setOnItemSelectedListener(item -> { selectPage(item); return true; });
        binding.bottomNavigation.setSelectedItemId(R.id.nav_connect);
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (!"connect".equals(page)) {
                    showPage("connect");
                } else {
                    finish();
                }
            }
        });
    }

    private void selectPage(MenuItem item) {
        int id = item.getItemId();
        showPage(id == R.id.nav_configurations ? "configurations" : id == R.id.nav_settings ? "settings" : id == R.id.nav_about ? "about" : "connect");
    }

    private void showPage(String destination) {
        page = destination;
        binding.homePageScroll.setVisibility("connect".equals(page) ? View.VISIBLE : View.GONE);
        binding.configurationsPage.setVisibility("configurations".equals(page) ? View.VISIBLE : View.GONE);
        binding.settingsPage.setVisibility("settings".equals(page) ? View.VISIBLE : View.GONE);
        binding.aboutPage.setVisibility("about".equals(page) ? View.VISIBLE : View.GONE);
        int checked = "configurations".equals(page) ? R.id.nav_configurations : "settings".equals(page) ? R.id.nav_settings : "about".equals(page) ? R.id.nav_about : R.id.nav_connect;
        if (binding.bottomNavigation.getSelectedItemId() != checked) {
            binding.bottomNavigation.setOnItemSelectedListener(null);
            binding.bottomNavigation.setSelectedItemId(checked);
            binding.bottomNavigation.setOnItemSelectedListener(item -> { selectPage(item); return true; });
        }
        View visible = "configurations".equals(page) ? binding.configurationsPage : "settings".equals(page) ? binding.settingsPage : "about".equals(page) ? binding.aboutPage : binding.homePageScroll;
        if (ValueAnimator.areAnimatorsEnabled()) {
            visible.setAlpha(0f);
            visible.setTranslationY(12f);
            visible.animate().alpha(1f).translationY(0f).setDuration(220).start();
        }
    }

    private void setupActions() {
        binding.tabMasque.setOnClickListener(v -> switchProtocol(0));
        binding.tabWg.setOnClickListener(v -> switchProtocol(1));
        binding.tabWarp.setOnClickListener(v -> switchProtocol(2));
        binding.tabPsiphon.setOnClickListener(v -> switchProtocol(3));
        View tabTor = findViewById(R.id.tab_tor);
        if (tabTor != null) tabTor.setOnClickListener(v -> switchProtocol(4));

        binding.connectButton.setOnClickListener(v -> { v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); if (shouldDisconnect()) disconnect(); else connect(); });
        binding.modeGroup.setOnCheckedChangeListener((group, checkedId) -> { preferences.edit().putString("mode", checkedId == R.id.proxy_mode_button ? "manual" : checkedId == R.id.smart_mode_button ? "smart" : "vpn").apply(); updateModeUi(); });
        binding.splitSwitch.setOnCheckedChangeListener((button, checked) -> { binding.splitContainer.setVisibility(checked ? View.VISIBLE : View.GONE); saveSettings(); });
        binding.routingGroup.setOnCheckedChangeListener((group, checkedId) -> { saveSettings(); updateSelectedCount(); });
        binding.chooseAppsButton.setOnClickListener(v -> openAppSelection());
        binding.advancedToggle.setOnClickListener(v -> { boolean show = binding.advancedContainer.getVisibility() != View.VISIBLE; binding.advancedContainer.setVisibility(show ? View.VISIBLE : View.GONE); binding.advancedToggle.setText(show ? R.string.hide_advanced : R.string.show_advanced); });
        binding.socksInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) { updateSocksGuide(); }
        });
        binding.resetButton.setOnClickListener(v -> resetDefaults());
        binding.lanSharingSwitch.setOnCheckedChangeListener((button, checked) -> {
            saveSettings();
            updateSocksGuide();
            int vis = checked ? View.VISIBLE : View.GONE;
            binding.proxyGuideContainer.setVisibility(vis);
            View httpGuide = findViewById(R.id.http_proxy_guide_container);
            if (httpGuide != null) httpGuide.setVisibility(vis);
        });
        binding.notificationSettingsButton.setOnClickListener(v -> openNotificationSettings());
        binding.locationValue.setOnClickListener(v -> {
            if (preferences.getInt("protocol", 0) == 3) {
                showRegionDialog();
            } else if ("connected".equals(state)) {
                startService(new Intent(this, AetherVpnService.class).setAction(AetherVpnService.ACTION_QUERY));
            }
        });
        binding.addTileButton.setOnClickListener(v -> requestQuickSettingsTile());
        binding.telegramHeaderButton.setOnClickListener(v -> openTelegram());
        binding.instagramHeaderButton.setOnClickListener(v -> openInstagram());
        binding.filmBaranCard.setOnClickListener(v -> openTelegram());
        binding.instagramButton.setOnClickListener(v -> openInstagram());
        binding.telegramButton.setOnClickListener(v -> openTelegram());
        binding.pingValue.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            if (!"connected".equals(state)) return;
            binding.pingValue.setText("…");
            startService(new Intent(this, AetherVpnService.class).setAction(AetherVpnService.ACTION_PING));
        });
        binding.logViewButton.setOnClickListener(v -> showLogDialog());

        View checkUpstream = findViewById(R.id.check_upstream_button);
        if (checkUpstream != null) {
            checkUpstream.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                startService(new Intent(this, AetherVpnService.class).setAction(AetherVpnService.ACTION_TEST_UPSTREAM));
            });
        }

        View psiphonLog = findViewById(R.id.psiphon_log_button);
        if (psiphonLog != null) {
            psiphonLog.setOnClickListener(v -> showLogDialog("[Psiphon]"));
        }
    }

    private void showLogDialog() {
        showLogDialog(null);
    }

    private void showLogDialog(String filter) {
        TextView textView = new TextView(this);
        textView.setTextSize(12f);
        textView.setTypeface(Typeface.MONOSPACE);
        textView.setTextColor(getResources().getColor(R.color.text));
        textView.setPadding(32, 32, 32, 32);

        String initialLogs = preferences.getString("logs", "");
        if (initialLogs.isEmpty()) {
            initialLogs = "No logs recorded yet.";
        }

        if (filter != null) {
            StringBuilder filtered = new StringBuilder();
            for (String line : initialLogs.split("\n")) {
                if (line.contains(filter)) {
                    filtered.append(line).append("\n");
                }
            }
            textView.setText(filtered.length() > 0 ? filtered.toString() : "No matching logs found.");
        } else {
            textView.setText(initialLogs);
        }

        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(textView);

        scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));

        BroadcastReceiver logReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (AetherVpnService.ACTION_LOG.equals(intent.getAction())) {
                    String lines = intent.getStringExtra("lines");
                    if (lines != null && !lines.isEmpty()) {
                        if (filter != null) {
                            for (String line : lines.split("\n")) {
                                if (line.contains(filter)) {
                                    textView.append("\n" + line);
                                }
                            }
                        } else {
                            textView.append("\n" + lines);
                        }
                        scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
                    }
                }
            }
        };
        ContextCompat.registerReceiver(this, logReceiver, new IntentFilter(AetherVpnService.ACTION_LOG), INTERNAL_PERMISSION, null, ContextCompat.RECEIVER_NOT_EXPORTED);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(filter != null ? R.string.psiphon_log : R.string.log_dialog_title)
                .setView(scrollView)
                .setPositiveButton(R.string.copy_logs, null)
                .setNegativeButton(R.string.clear_logs, null)
                .setNeutralButton(android.R.string.cancel, null)
                .create();

        dialog.setOnDismissListener(d -> unregisterReceiver(logReceiver));
        dialog.show();

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("Baran VPN Logs", textView.getText());
            if (clipboard != null) {
                clipboard.setPrimaryClip(clip);
                Toast.makeText(this, R.string.logs_copied, Toast.LENGTH_SHORT).show();
            }
        });

        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> {
            startService(new Intent(this, AetherVpnService.class).setAction(AetherVpnService.ACTION_CLEAR_LOGS));
            preferences.edit().remove("logs").apply();
            textView.setText("");
        });
    }

    private void restoreSettings() {
        String mode = preferences.getString("mode", "vpn");
        binding.modeGroup.check("manual".equals(mode) ? R.id.proxy_mode_button : "smart".equals(mode) ? R.id.smart_mode_button : R.id.vpn_mode_button);
        setSelection(binding.protocolInput, "protocol", ConnectionDefaults.PROTOCOL_INDEX, R.array.protocol_labels);
        setSelection(binding.scanInput, "scan", ConnectionDefaults.SCAN_INDEX, R.array.scan_labels);
        setSelection(binding.transportInput, "transport", 0, R.array.transport_labels);
        setSelection(binding.ipInput, "ip", 0, R.array.ip_labels);
        setSelection(binding.obfuscationInput, "obfuscation", 0, R.array.obfuscation_labels);
        setSelection(binding.logInput, "log", 0, R.array.log_labels);
        setSelection(binding.themeInput, "theme", 2, R.array.theme_labels);
        setSelection(binding.languageInput, "fa".equals(preferences.getString("language", "en")) ? 1 : 0, R.array.language_labels);
        binding.socksInput.setText(preferences.getString("socks", getString(R.string.default_socks_address)));
        binding.peerInput.setText(preferences.getString("peer", "")); binding.mtuInput.setText(preferences.getString("mtu", getString(R.string.default_mtu)));
        binding.dnsSwitch.setChecked(preferences.getBoolean("dnsLeak", true)); binding.killswitchSwitch.setChecked(preferences.getBoolean("killSwitch", false)); binding.reconnectSwitch.setChecked(preferences.getBoolean("quickReconnect", true));
        binding.lanSharingSwitch.setChecked(preferences.getBoolean("lanSharing", false));
        binding.proxyGuideContainer.setVisibility(binding.lanSharingSwitch.isChecked() ? View.VISIBLE : View.GONE);
        View httpGuideInit = findViewById(R.id.http_proxy_guide_container);
        if (httpGuideInit != null) httpGuideInit.setVisibility(binding.lanSharingSwitch.isChecked() ? View.VISIBLE : View.GONE);
        updateSocksGuide();
        boolean split = preferences.getInt("routing", 0) >= 2;
        binding.splitSwitch.setChecked(split);
        binding.splitContainer.setVisibility(split ? View.VISIBLE : View.GONE);
        binding.routingGroup.check(preferences.getInt("routing", 0) == 3 ? R.id.exclude_apps_radio : R.id.include_apps_radio);
        updateModeUi();
        updateSelectedCount();
    }

    private void setSelection(MaterialAutoCompleteTextView view, String key, int fallback, int arrayId) { setSelection(view, preferences.getInt(key, fallback), arrayId); }
    private void setSelection(MaterialAutoCompleteTextView view, int index, int arrayId) { String[] values = getResources().getStringArray(arrayId); index = Math.max(0, Math.min(values.length - 1, index)); view.setText(values[index], false); view.setTag(index); }

    private void updateModeUi() { String mode = preferences.getString("mode", "vpn"); binding.modeSummary.setText("smart".equals(mode) ? R.string.smart_mode_summary : R.string.status_ready_message); binding.protocolLayout.setVisibility("smart".equals(mode) ? View.GONE : View.VISIBLE); binding.transportLayout.setVisibility("smart".equals(mode) || selectedIndex(binding.protocolInput) != 0 ? View.GONE : View.VISIBLE); }

    private void connect() {
        if (RemoteConfig.isForceBlocked(this)) {
            RemoteConfig.Snapshot s = RemoteConfig.getCached();
            if (s != null) maybeShowUpdateDialog(s, true);
            Toast.makeText(this, R.string.force_update_toast, Toast.LENGTH_LONG).show();
            return;
        }
        // No promo banner on landscape / Android TV
        if (isLandscapeOrTelevision()) {
            startVpnConnect();
            return;
        }

        // Show promo banner at most once every 5 minutes (avoid spam when switching modes)
        long now = System.currentTimeMillis();
        long lastBannerAt = preferences.getLong("last_banner_shown_at", 0L);
        final long BANNER_COOLDOWN_MS = 5L * 60L * 1000L;
        if (now - lastBannerAt < BANNER_COOLDOWN_MS) {
            startVpnConnect();
            return;
        }
        RemoteConfig.Banner banner = RemoteConfig.pickRandomActiveBanner();
        if (banner != null) {
            preferences.edit().putLong("last_banner_shown_at", now).apply();
            showBannerDialog(banner, this::startVpnConnect);
        } else {
            startVpnConnect();
        }
    }


    private boolean isLandscapeOrTelevision() {
        Configuration cfg = getResources().getConfiguration();
        boolean land = cfg.orientation == Configuration.ORIENTATION_LANDSCAPE;
        int uiType = cfg.uiMode & Configuration.UI_MODE_TYPE_MASK;
        boolean tv = uiType == Configuration.UI_MODE_TYPE_TELEVISION;
        return land || tv;
    }

    private void startVpnConnect() {
        if (!validSocks(text(binding.socksInput))) {
            binding.socksInput.setError(getString(R.string.invalid_socks));
            return;
        }
        if (binding.splitSwitch.isChecked()
                && selectedPackages().isEmpty()
                && binding.routingGroup.getCheckedRadioButtonId() == R.id.include_apps_radio) {
            Toast.makeText(this, R.string.split_include_empty, Toast.LENGTH_LONG).show();
            return;
        }
        saveSettings();
        if (VpnConnectionController.needsVpn(preferences)) {
            Intent permission = VpnService.prepare(this);
            if (permission != null) {
                startActivityForResult(permission, VPN_REQUEST);
                return;
            }
        }
        VpnConnectionController.connect(this, preferences);
    }

    private void disconnect() {
        // Immediate UI feedback so the orb switches to "disconnecting" spin/stop
        renderState("disconnecting", getString(R.string.status_disconnecting));
        VpnConnectionController.disconnect(this);
    }

    private void openAppSelection() {
        String key = binding.routingGroup.getCheckedRadioButtonId() == R.id.exclude_apps_radio ? "splitExcludeApps" : "splitIncludeApps";
        startActivityForResult(new Intent(this, AppSelectionActivity.class).putExtra(AppSelectionActivity.EXTRA_PACKAGES, preferences.getString(key, "")), APPS_REQUEST);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REGION_REQUEST && resultCode == RESULT_OK && data != null) {
            String code = data.getStringExtra(RegionPickerActivity.EXTRA_REGION_CODE);
            String flag = data.getStringExtra(RegionPickerActivity.EXTRA_REGION_FLAG);
            String name = data.getStringExtra(RegionPickerActivity.EXTRA_REGION_NAME);
            int count = data.getIntExtra(RegionPickerActivity.EXTRA_REGION_COUNT, 0);
            if (code == null) code = "";
            preferences.edit().putString("psiphon_region_code", code).apply();
            updateRegionButtonLabel(code, flag, name, count);
            if (binding != null && binding.psiphonRegionInput != null) {
                binding.psiphonRegionInput.setText((flag == null ? "" : flag + " ") + (name == null ? code : name), false);
            }
            onProtocolChanged();
        }
 super.onActivityResult(requestCode, resultCode, data); if (requestCode == VPN_REQUEST) { if (resultCode == RESULT_OK) VpnConnectionController.connect(this, preferences); else Toast.makeText(this, R.string.vpn_permission_denied, Toast.LENGTH_LONG).show(); } else if (requestCode == APPS_REQUEST) { if (data != null && data.getBooleanExtra(AppSelectionActivity.EXTRA_RETURN_HOME, false)) showPage("connect"); else if (resultCode == RESULT_OK && data != null) { String key = binding.routingGroup.getCheckedRadioButtonId() == R.id.exclude_apps_radio ? "splitExcludeApps" : "splitIncludeApps"; preferences.edit().putString(key, data.getStringExtra(AppSelectionActivity.EXTRA_PACKAGES)).apply(); updateSelectedCount(); saveSettings(); } } }

    private void switchProtocol(int index) {
        preferences.edit().putInt("protocol", index).apply();
        restoreSettings(); // Refresh UI in config page
        updateProtocolTabs();
        onProtocolChanged();
    }

    private void updateProtocolTabs() {
        int selected = preferences.getInt("protocol", ConnectionDefaults.PROTOCOL_INDEX);
        if (binding == null) return;

        binding.tabMasque.setSelected(selected == 0);
        binding.tabWg.setSelected(selected == 1);
        binding.tabWarp.setSelected(selected == 2);
        binding.tabPsiphon.setSelected(selected == 3);
        View tabTor = findViewById(R.id.tab_tor);
        if (tabTor != null) tabTor.setSelected(selected == 4);

        View psiphonSettingsRow = findViewById(R.id.psiphon_settings_row);
        if (psiphonSettingsRow != null) {
            psiphonSettingsRow.setVisibility(selected == 3 ? View.VISIBLE : View.GONE);
        }
        View torSettingsRow = findViewById(R.id.tor_settings_row);
        if (torSettingsRow != null) {
            torSettingsRow.setVisibility(selected == 4 ? View.VISIBLE : View.GONE);
        }
    }

    private void renderState(String newState, String message) {
        state = newState == null ? "disconnected" : newState;
        updateProtocolTabs();
        boolean connected = "connected".equals(state);
        int protocolIndex = preferences.getInt("protocol", 0);
        boolean psiphonActive = protocolIndex == 3;
        boolean torActive = protocolIndex == 4;
        boolean chainActive = psiphonActive || torActive;

        View statusIndicators = findViewById(R.id.psiphon_status_indicators);
        View warpProxyDot = findViewById(R.id.warp_proxy_dot);
        View psiphonCoreDot = findViewById(R.id.psiphon_core_dot);
        TextView coreLabel = null;
        if (psiphonCoreDot != null) {
            ViewGroup coreRow = (ViewGroup) psiphonCoreDot.getParent();
            if (coreRow != null) {
                for (int i = 0; i < coreRow.getChildCount(); i++) {
                    View child = coreRow.getChildAt(i);
                    if (child instanceof TextView) {
                        coreLabel = (TextView) child;
                        break;
                    }
                }
            }
        }

        if (statusIndicators != null) {
            statusIndicators.setVisibility(chainActive && !state.equals("disconnected") && !state.equals("error") ? View.VISIBLE : View.GONE);
        }

        // Track Aether/WARP upstream for the two status dots under the orb
        if ("disconnected".equals(state) || "error".equals(state) || "blocked".equals(state)) {
            upstreamReady = false;
        } else if ("proxy-connected".equals(state) || connected) {
            upstreamReady = true;
        } else if ("proxy-starting".equals(state)) {
            upstreamReady = false;
        }

        if (chainActive && warpProxyDot != null && psiphonCoreDot != null) {
            updateUpstreamProxyLabel();
            if (coreLabel != null) {
                coreLabel.setText(torActive ? "Tor" : "Psiphon");
            }
            // Green when selected upstream is up (stays green while Psiphon/Tor connects)
            boolean warpReady = upstreamReady || "proxy-connected".equals(state);
            // Green only when full tunnel is protected
            boolean coreReady = connected;
            warpProxyDot.setBackgroundResource(warpReady ? R.drawable.status_dot_connected : R.drawable.status_dot);
            psiphonCoreDot.setBackgroundResource(coreReady ? R.drawable.status_dot_connected : R.drawable.status_dot);
        }

        boolean transitioning = "starting".equals(state)
                || "smart-testing".equals(state)
                || "scanning".equals(state)
                || "securing".equals(state)
                || "reconnecting".equals(state)
                || "disconnecting".equals(state)
                || "proxy-starting".equals(state)
                || "proxy-connected".equals(state);
        // Allow cancel while connecting (not only when fully connected)
        binding.connectButton.setEnabled(!"disconnecting".equals(state));
        String orbLabel = connected ? getString(R.string.disconnect) : transitioning ? ("disconnecting".equals(state) ? getString(R.string.disconnecting) : getString(R.string.connecting)) : getString(R.string.connect);
        binding.connectButton.setConnectionState(state, orbLabel);
        binding.connectButton.setContentDescription(orbLabel);
        binding.connectionStatus.setText(connected ? R.string.status_connected : transitioning ? ("disconnecting".equals(state) ? R.string.status_disconnecting : R.string.status_connecting) : ("error".equals(state) || "blocked".equals(state) ? R.string.status_error : R.string.status_disconnected));
        binding.statusDot.setBackgroundResource(connected ? R.drawable.status_dot_connected : transitioning ? R.drawable.status_dot_connecting : R.drawable.status_dot);
        binding.progress.setVisibility(View.GONE);

        boolean isPsiphon = psiphonActive;
        boolean isTor = torActive;
        boolean showNode = isPsiphon || isTor;
        binding.locationLabel.setVisibility(showNode ? View.VISIBLE : View.GONE);
        binding.locationValue.setVisibility(showNode ? View.VISIBLE : View.GONE);
        if (binding.locationBoxSeparator != null) {
            binding.locationBoxSeparator.setVisibility(showNode ? View.VISIBLE : View.GONE);
        }

        if (connected) {
            binding.connectionMessage.setVisibility(View.GONE);
            binding.connectionInfo.setVisibility(View.VISIBLE);
            String loc = endpoint == null || endpoint.isEmpty()
                    ? getString(R.string.connection_location_unavailable)
                    : endpoint;
            // One minimal line under NODE: location + IP (no second line)
            String displayLoc = loc;
            if (preferences.getInt("protocol", 0) == 4) {
                String up = preferences.getString("tor_upstream", "WARP");
                if (up == null) up = "WARP";
                String path = "Direct".equalsIgnoreCase(up) ? "Tor-only"
                        : ("WireGuard".equalsIgnoreCase(up) ? "Tor←WG" : "Tor←WARP");
                if (displayLoc != null && !displayLoc.startsWith("Tor")) {
                    displayLoc = path + " · " + displayLoc;
                }
            }
            if (loc != null && loc.contains("·")) {
                String[] parts = loc.split("·");
                if (parts.length >= 2) {
                    String maybeIp = parts[parts.length - 1].trim();
                    if (maybeIp.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) {
                        exitIp = maybeIp;
                    }
                }
            } else if (exitIp != null && !exitIp.isEmpty() && loc != null
                    && !loc.contains(exitIp)
                    && !getString(R.string.connection_location_unavailable).equals(loc)) {
                displayLoc = loc + " · " + exitIp;
            }
            binding.locationValue.setText(displayLoc);
            // Second IP row stays hidden (avoid duplicate)
            if (exitIpValue != null) {
                exitIpValue.setVisibility(View.GONE);
                exitIpValue.setText("");
            }
        }
        else if (transitioning) {
            // Show phase text for Tor/Psiphon chaining (upstream then core)
            boolean showPhase = message != null && !message.isEmpty()
                    && ("proxy-starting".equals(state) || "proxy-connected".equals(state) || "starting".equals(state));
            if (showPhase) {
                binding.connectionMessage.setText(message);
                binding.connectionMessage.setVisibility(View.VISIBLE);
            } else {
                binding.connectionMessage.setVisibility(View.GONE);
            }
            binding.connectionInfo.setVisibility(View.VISIBLE);
        }
        else { boolean showError = "error".equals(state) || "blocked".equals(state); binding.connectionMessage.setText(message == null ? getString(R.string.status_error) : message); binding.connectionMessage.setVisibility(showError ? View.VISIBLE : View.GONE); binding.connectionInfo.setVisibility(View.VISIBLE); }
        preferences.edit().putString("state", state).putString("message", message == null ? "" : message).apply();
        if (!connected) resetStats();
    }

    private boolean shouldDisconnect() {
        return "connected".equals(state)
                || "starting".equals(state)
                || "smart-testing".equals(state)
                || "scanning".equals(state)
                || "securing".equals(state)
                || "reconnecting".equals(state)
                || "disconnecting".equals(state)
                || "proxy-starting".equals(state)
                || "proxy-connected".equals(state);
    }

    private void renderStats(Intent intent) {
        long tx = Math.max(0, intent.getLongExtra("tx", 0));
        long rx = Math.max(0, intent.getLongExtra("rx", 0));
        long currentTotal = tx + rx;
        long now = System.currentTimeMillis();
        long speed = (lastTotalBytes > 0) ? (currentTotal - lastTotalBytes) : 0;
        lastTotalBytes = currentTotal;

        // Per-direction rates for sparklines (bytes since last sample)
        float dtSec = lastRateAt > 0 ? Math.max(0.2f, (now - lastRateAt) / 1000f) : 1f;
        float rxRate = lastRateAt > 0 ? Math.max(0f, (rx - lastRxBytes) / dtSec) : 0f;
        float txRate = lastRateAt > 0 ? Math.max(0f, (tx - lastTxBytes) / dtSec) : 0f;
        lastRxBytes = rx;
        lastTxBytes = tx;
        lastRateAt = now;

        if (binding.trafficWave != null) {
            binding.trafficWave.setTrafficSpeed(speed);
        }
        if (downloadSpark != null) downloadSpark.push(rxRate);
        if (uploadSpark != null) uploadSpark.push(txRate);

        animateMetric(binding.uploadValue, formatTraffic(tx));
        animateMetric(binding.downloadValue, formatTraffic(rx));
        long ping = intent.getLongExtra("ping", -1);
        String pingText = ping >= 0 ? Long.toString(ping) : "—";
        binding.pingValue.setText(pingText);

        String ip = intent.getStringExtra("exitIp");
        if (ip != null && !ip.isEmpty()) {
            exitIp = ip;
            // Append IP into the single NODE line if missing
            if (binding.locationValue != null && binding.locationValue.getVisibility() == View.VISIBLE) {
                CharSequence cur = binding.locationValue.getText();
                String curStr = cur == null ? "" : cur.toString();
                if (!curStr.isEmpty()
                        && !curStr.contains(ip)
                        && !getString(R.string.connection_location_unavailable).equals(curStr)
                        && !"—".equals(curStr)) {
                    binding.locationValue.setText(curStr + " · " + ip);
                } else if (curStr.isEmpty() || "—".equals(curStr)
                        || getString(R.string.connection_location_unavailable).equals(curStr)) {
                    binding.locationValue.setText(ip);
                }
            }
            if (exitIpValue != null) {
                exitIpValue.setVisibility(View.GONE);
            }
        }
    }

    private void animateMetric(TextView view, String value) {
        if (value.equals(view.getTag())) return;
        view.setTag(value);
        view.animate().cancel();
        view.setAlpha(0.45f);
        view.setScaleX(.96f);
        view.setScaleY(.96f);
        view.setText(value);
        view.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).start();
    }

    private String formatTraffic(long bytes) {
        if (bytes < 1024L * 1024L) return getString(R.string.traffic_kilobytes, bytes / 1024.0);
        if (bytes < 1024L * 1024L * 1024L) return getString(R.string.traffic_megabytes, bytes / (1024.0 * 1024.0));
        return getString(R.string.traffic_gigabytes, bytes / (1024.0 * 1024.0 * 1024.0));
    }

    private void resetStats() {
        binding.uploadValue.setText(R.string.metric_unavailable);
        binding.downloadValue.setText(R.string.metric_unavailable);
        binding.pingValue.setText(getString(R.string.ping_value, getString(R.string.metric_unavailable)));
        binding.locationValue.setText(R.string.connection_location_unavailable);
        lastRxBytes = 0;
        lastTxBytes = 0;
        lastRateAt = 0;
        lastTotalBytes = 0;
        exitIp = "";
        if (downloadSpark != null) downloadSpark.clear();
        if (uploadSpark != null) uploadSpark.clear();
        if (exitIpValue != null) {
            exitIpValue.setText("");
            exitIpValue.setVisibility(View.GONE);
        }
    }

    private void saveSettings() {
        int routing = binding.splitSwitch.isChecked() ? (binding.routingGroup.getCheckedRadioButtonId() == R.id.exclude_apps_radio ? 3 : 2) : 0;
        String include = preferences.getString("splitIncludeApps", ""); String exclude = preferences.getString("splitExcludeApps", "");
        preferences.edit()
                .putInt("protocol", selectedIndex(binding.protocolInput))
                .putInt("scan", selectedIndex(binding.scanInput))
                .putInt("transport", selectedIndex(binding.transportInput))
                .putInt("ip", selectedIndex(binding.ipInput))
                .putInt("obfuscation", selectedIndex(binding.obfuscationInput))
                .putInt("log", selectedIndex(binding.logInput))
                .putInt("theme", selectedIndex(binding.themeInput))
                .putInt("routing", routing)
                .putString("splitApps", routing == 3 ? exclude : include)
                .putString("socks", text(binding.socksInput))
                .putString("peer", text(binding.peerInput))
                .putString("mtu", text(binding.mtuInput))
                .putBoolean("dnsLeak", binding.dnsSwitch.isChecked())
                .putBoolean("killSwitch", binding.killswitchSwitch.isChecked())
                .putBoolean("quickReconnect", binding.reconnectSwitch.isChecked())
                .putBoolean("lanSharing", binding.lanSharingSwitch.isChecked())
                .putString("psiphon_region", binding.psiphonRegionInput.getText().toString())
                .apply();
    }

    private Set<String> selectedPackages() { Set<String> result = new LinkedHashSet<>(); String key = binding.routingGroup.getCheckedRadioButtonId() == R.id.exclude_apps_radio ? "splitExcludeApps" : "splitIncludeApps"; AppSelectionActivity.parsePackages(preferences.getString(key, ""), result); return result; }

    private void updateSocksGuide() {
        String val = text(binding.socksInput);
        int colon = val.lastIndexOf(':');
        String port = (colon > 0 && colon < val.length() - 1) ? val.substring(colon + 1) : "1819";
        String address = binding.lanSharingSwitch.isChecked() ? getLocalIpAddress() : "127.0.0.1";
        if (address == null || address.isEmpty()) address = "127.0.0.1";
        binding.socksGuideInfo.setText("Address: " + address + "\nPort: " + port);

        TextView httpInfo = findViewById(R.id.http_guide_info);
        if (httpInfo != null) {
            httpInfo.setText(getString(R.string.http_proxy_guide_info, address, "8080"));
        }
    }
    private String getLocalIpAddress() {
        try {
            Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
            while (en.hasMoreElements()) {
                NetworkInterface intf = en.nextElement();
                Enumeration<InetAddress> enumIpAddr = intf.getInetAddresses();
                while (enumIpAddr.hasMoreElements()) {
                    InetAddress inetAddress = enumIpAddr.nextElement();
                    if (!inetAddress.isLoopbackAddress() && inetAddress instanceof Inet4Address) {
                        return inetAddress.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) { }
        return "127.0.0.1";
    }
    private void updateSelectedCount() { if (binding == null) return; binding.selectedAppsCount.setText(getResources().getQuantityString(R.plurals.app_picker_selected_count, selectedPackages().size(), selectedPackages().size())); }
    private void resetDefaults() { String language = preferences.getString("language", "fa"); preferences.edit().clear().putString("language", language).putInt("theme", 2).apply(); restoreSettings(); saveSettings(); applyTheme(2); }

    private void applyTheme(int choice) { preferences.edit().putInt("theme", choice).apply(); }
    private static int themeMode(int choice) { return AppCompatDelegate.MODE_NIGHT_YES; }
    private void applyLanguage(String language, boolean recreate) { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("fa".equals(language) ? "fa" : "en")); if (recreate) recreate(); }
    private void requestNotificationPermission() { if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, NOTIFICATION_REQUEST); }
    private void openNotificationSettings() {
        try { startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())); }
        catch (Exception ignored) { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))); }
    }
    private void requestQuickSettingsTile() {
        if (Build.VERSION.SDK_INT >= 33) {
            StatusBarManager manager = getSystemService(StatusBarManager.class);
            manager.requestAddTileService(new ComponentName(this, AethonTileService.class), getString(R.string.tile_name), Icon.createWithResource(this, R.drawable.ic_aethon_mono), getMainExecutor(), result -> Toast.makeText(this, R.string.tile_add_requested, Toast.LENGTH_SHORT).show());
            return;
        }
        try { startActivity(new Intent("android.settings.QUICK_SETTINGS_SETTINGS")); }
        catch (Exception ignored) { Toast.makeText(this, R.string.tile_add_manual, Toast.LENGTH_LONG).show(); }
    }
    private void openInstagram() {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("http://instagram.com/_u/appfilmBaran"));
            intent.setPackage("com.instagram.android");
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://instagram.com/appfilmBaran")));
        }
    }

    private void showRegionDialog() {
        startActivityForResult(new Intent(this, RegionPickerActivity.class), REGION_REQUEST);
    }

    private void updateRegionButtonLabel(String code, String flag, String name, int count) {
        View btn = findViewById(R.id.psiphon_region_button);
        if (!(btn instanceof com.google.android.material.button.MaterialButton)) return;
        com.google.android.material.button.MaterialButton b =
                (com.google.android.material.button.MaterialButton) btn;
        String label = (flag == null ? "" : flag + " ") + (name == null ? code : name);
        if (count > 0) label += " · " + count;
        b.setText(label.trim().isEmpty() ? getString(R.string.psiphon_region_hint) : label);
    }


    private void maybeShowUpdateDialog(RemoteConfig.Snapshot snapshot, boolean forceUi) {
        if (snapshot == null || snapshot.update == null) return;
        if (!RemoteConfig.needsUpdate(this, snapshot.update)) return;
        if (updateDialogShowing) return;

        boolean force = snapshot.update.force;
        // Non-force: do not keep showing the same update
        if (!force && !forceUi) {
            long snoozeUntil = preferences.getLong("update_snooze_until", 0L);
            if (System.currentTimeMillis() < snoozeUntil) return;
            int lastPromptCode = preferences.getInt("update_prompted_code", -1);
            // Same remote code already shown once → wait until snooze expires (set on Later or on first show)
            if (lastPromptCode == snapshot.update.latestCode) return;
        }

        showUpdateDialog(snapshot.update);
    }

    private void showUpdateDialog(RemoteConfig.UpdateInfo info) {
        if (updateDialogShowing) return;
        updateDialogShowing = true;

        View content = getLayoutInflater().inflate(R.layout.dialog_update, null, false);
        TextView version = content.findViewById(R.id.update_version);
        TextView message = content.findViewById(R.id.update_message);
        TextView forceHint = content.findViewById(R.id.update_force_hint);
        com.google.android.material.button.MaterialButton download = content.findViewById(R.id.update_download);
        com.google.android.material.button.MaterialButton later = content.findViewById(R.id.update_later);

        version.setText("v" + info.latestName + "  ·  code " + info.latestCode
                + getString(R.string.current_version_format, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE));
        message.setText(info.message);
        boolean force = info.force && RemoteConfig.needsUpdate(this, info);
        forceHint.setVisibility(force ? View.VISIBLE : View.GONE);
        later.setVisibility(force ? View.GONE : View.VISIBLE);

        androidx.appcompat.app.AlertDialog dialog = new androidx.appcompat.app.AlertDialog.Builder(this)
                .setView(content)
                .setCancelable(!force)
                .create();

        dialog.setOnDismissListener(d -> updateDialogShowing = false);

        // Mark this remote version as prompted (non-force) so it will not reappear every launch
        if (!force) {
            preferences.edit()
                    .putInt("update_prompted_code", info.latestCode)
                    .putLong("update_snooze_until", System.currentTimeMillis() + UPDATE_SNOOZE_MS)
                    .apply();
        }

        download.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(info.downloadUrl)));
            } catch (Exception e) {
                Toast.makeText(this, R.string.error_download_link, Toast.LENGTH_SHORT).show();
            }
        });
        later.setOnClickListener(v -> {
            // Snooze non-force update for 24 hours so it does not keep popping up
            preferences.edit()
                    .putLong("update_snooze_until", System.currentTimeMillis() + UPDATE_SNOOZE_MS)
                    .putInt("update_prompted_code", info.latestCode)
                    .apply();
            dialog.dismiss();
        });
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setLayout(
                    (int) (getResources().getDisplayMetrics().widthPixels * 0.9f),
                    android.view.WindowManager.LayoutParams.WRAP_CONTENT
            );
        }
    }

    private void preloadBannerImage() {
        RemoteConfig.Snapshot snap = RemoteConfig.getCached();
        if (snap == null || snap.banners == null || snap.banners.isEmpty()) {
            // Fallback: try one random active banner
            RemoteConfig.Banner one = RemoteConfig.pickRandomActiveBanner();
            if (one == null || one.imageUrl.isEmpty()) return;
            downloadBannerBitmap(one.imageUrl);
            return;
        }
        for (RemoteConfig.Banner b : snap.banners) {
            if (b != null && b.active && b.imageUrl != null && !b.imageUrl.isEmpty()) {
                downloadBannerBitmap(b.imageUrl);
            }
        }
    }

    private void downloadBannerBitmap(final String url) {
        if (url == null || url.isEmpty()) return;
        if (bannerBitmapCache.containsKey(url)) {
            preloadedBannerUrl = url;
            return;
        }
        preloadedBannerUrl = url;
        new Thread(() -> {
            android.graphics.Bitmap bmp = null;
            try {
                java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                        new java.net.URL(url).openConnection();
                c.setConnectTimeout(8_000);
                c.setReadTimeout(12_000);
                c.setRequestProperty("User-Agent", "AetherVPN/" + BuildConfig.VERSION_NAME);
                c.connect();
                bmp = android.graphics.BitmapFactory.decodeStream(c.getInputStream());
            } catch (Exception ignored) {
            }
            if (bmp != null) {
                bannerBitmapCache.put(url, bmp);
            }
        }, "banner-preload").start();
    }

    private void showBannerDialog(RemoteConfig.Banner banner, Runnable onDismiss) {
        View content = getLayoutInflater().inflate(R.layout.dialog_banner, null, false);
        ImageView image = content.findViewById(R.id.banner_image);
        ProgressBar loading = content.findViewById(R.id.banner_loading);
        com.google.android.material.button.MaterialButton open = content.findViewById(R.id.banner_open);
        com.google.android.material.button.MaterialButton skip = content.findViewById(R.id.banner_skip);
        com.google.android.material.button.MaterialButton close = content.findViewById(R.id.banner_close);

        // Title intentionally not shown
        androidx.appcompat.app.AlertDialog dialog = new androidx.appcompat.app.AlertDialog.Builder(this)
                .setView(content)
                .setCancelable(true)
                .create();

        Runnable finish = () -> {
            try { dialog.dismiss(); } catch (Exception ignored) {}
            if (onDismiss != null) onDismiss.run();
        };

        if (skip != null) skip.setOnClickListener(v -> finish.run());
        if (close != null) close.setOnClickListener(v -> finish.run());
        open.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(banner.targetUrl)));
            } catch (Exception e) {
                Toast.makeText(this, R.string.error_link_open, Toast.LENGTH_SHORT).show();
            }
        });
        image.setOnClickListener(v -> open.performClick());

        dialog.setOnCancelListener(d -> {
            if (onDismiss != null) onDismiss.run();
        });

        // Instant image if preloaded
        android.graphics.Bitmap cached = bannerBitmapCache.get(banner.imageUrl);
        if (cached != null && !cached.isRecycled()) {
            if (loading != null) loading.setVisibility(View.GONE);
            image.setImageBitmap(cached);
        } else {
            if (loading != null) loading.setVisibility(View.VISIBLE);
            new Thread(() -> {
                android.graphics.Bitmap bmp = null;
                try {
                    java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                            new java.net.URL(banner.imageUrl).openConnection();
                    c.setConnectTimeout(10_000);
                    c.setReadTimeout(15_000);
                    c.connect();
                    bmp = android.graphics.BitmapFactory.decodeStream(c.getInputStream());
                } catch (Exception ignored) {
                }
                android.graphics.Bitmap finalBmp = bmp;
                if (finalBmp != null) {
                    bannerBitmapCache.put(banner.imageUrl, finalBmp);
                }
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    if (loading != null) loading.setVisibility(View.GONE);
                    if (finalBmp != null && image != null) image.setImageBitmap(finalBmp);
                });
            }, "banner-load").start();
        }

        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            int w = (int) (getResources().getDisplayMetrics().widthPixels * 0.94f);
            int h = (int) (getResources().getDisplayMetrics().heightPixels * 0.78f);
            dialog.getWindow().setLayout(w, h);
        }
    }

    private void openTelegram() {
        Intent direct = new Intent(Intent.ACTION_VIEW, Uri.parse("tg://resolve?domain=appFilmBaran"));
        for (String packageName : new String[]{"org.telegram.messenger", "org.telegram.messenger.web"}) {
            try {
                direct.setPackage(packageName);
                startActivity(direct);
                return;
            } catch (ActivityNotFoundException ignored) { }
        }
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/appFilmBaran"))); }
        catch (ActivityNotFoundException ignored) { Toast.makeText(this, R.string.telegram_fallback, Toast.LENGTH_SHORT).show(); }
    }
    private int selectedIndex(MaterialAutoCompleteTextView view) { Object tag = view.getTag(); return tag instanceof Integer ? (Integer) tag : 0; }
    private String text(TextInputEditText view) { return view.getText() == null ? "" : view.getText().toString().trim(); }
    private boolean validSocks(String value) { int split = value.lastIndexOf(':'); if (split <= 0) return false; try { int port = Integer.parseInt(value.substring(split + 1)); return port > 0 && port <= 65535; } catch (Exception ignored) { return false; } }

    @Override protected void onStart() {
        super.onStart();
        if (!receiverRegistered) {
            IntentFilter filter = new IntentFilter();
            filter.addAction(AetherVpnService.ACTION_STATUS);
            filter.addAction(AetherVpnService.ACTION_STATS);
            ContextCompat.registerReceiver(this, receiver, filter, INTERNAL_PERMISSION, null, ContextCompat.RECEIVER_NOT_EXPORTED);
            receiverRegistered = true;
        }
        startService(new Intent(this, AetherVpnService.class).setAction(AetherVpnService.ACTION_QUERY));
        updateHandler.removeCallbacks(pingPoll);
        updateHandler.postDelayed(pingPoll, 10_000);
    }

    @Override protected void onStop() {
        if (receiverRegistered) {
            unregisterReceiver(receiver);
            receiverRegistered = false;
        }
        updateHandler.removeCallbacks(pingPoll);
        super.onStop();
    }

}