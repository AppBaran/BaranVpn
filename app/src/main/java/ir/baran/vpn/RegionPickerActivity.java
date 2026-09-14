package ir.baran.vpn;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import ir.baran.vpn.psiphon.PsiphonServerEntriesParser;
import ir.baran.vpn.psiphon.PsiphonServerRegions;
import ir.baran.vpn.psiphon.RegionListAdapter;

/**
 * Full-screen Psiphon country / region picker (separate activity).
 */
public final class RegionPickerActivity extends AppCompatActivity {

    public static final String EXTRA_REGION_CODE = "region_code";
    public static final String EXTRA_REGION_FLAG = "region_flag";
    public static final String EXTRA_REGION_NAME = "region_name";
    public static final String EXTRA_REGION_COUNT = "region_count";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_region_picker);

        SharedPreferences preferences = getSharedPreferences("aether", MODE_PRIVATE);
        boolean isPersian = "fa".equals(preferences.getString("language", "fa"));
        Map<String, Integer> counts = PsiphonServerEntriesParser.parseServerEntriesCounts(this);
        List<String> codes = PsiphonServerEntriesParser.getParsedRegions(this);
        String current = preferences.getString("psiphon_region_code", "");

        int total = 0;
        for (int c : counts.values()) total += c;

        List<RegionListAdapter.Item> items = new ArrayList<>();
        for (String code : codes) {
            String flag = PsiphonServerRegions.getFlag(code);
            String name = PsiphonServerRegions.getName(code, isPersian);
            int count = code.isEmpty() ? total : (counts.containsKey(code.toUpperCase()) ? counts.get(code.toUpperCase()) : 0);
            items.add(new RegionListAdapter.Item(code, flag, name, count));
        }

        TextView title = findViewById(R.id.region_picker_title);
        if (title != null) {
            title.setText(R.string.psiphon_region_picker_title);
        }
        TextView summary = findViewById(R.id.region_picker_summary);
        if (summary != null) {
            summary.setText(R.string.psiphon_region_picker_summary);
        }

        ImageView back = findViewById(R.id.region_picker_back);
        if (back != null) {
            back.setOnClickListener(v -> {
                setResult(RESULT_CANCELED);
                finish();
            });
        }

        RecyclerView list = findViewById(R.id.region_list);
        TextInputEditText search = findViewById(R.id.region_search);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setItemViewCacheSize(24);
        list.setHasFixedSize(true);

        RegionListAdapter adapter = new RegionListAdapter();
        adapter.setItems(items);
        adapter.setSelectedCode(current);
        list.setAdapter(adapter);

        if (search != null) {
            search.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
                @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
                @Override public void afterTextChanged(Editable s) {
                    adapter.filter(s == null ? "" : s.toString());
                }
            });
        }

        adapter.setOnRegionClick((item, position) -> {
            preferences.edit().putString("psiphon_region_code", item.code).apply();
            Intent data = new Intent();
            data.putExtra(EXTRA_REGION_CODE, item.code);
            data.putExtra(EXTRA_REGION_FLAG, item.flag);
            data.putExtra(EXTRA_REGION_NAME, item.name);
            data.putExtra(EXTRA_REGION_COUNT, item.serverCount);
            setResult(RESULT_OK, data);
            finish();
        });
    }
}
