package ir.baran.vpn.psiphon;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import ir.baran.vpn.R;

/**
 * Stable RecyclerView adapter for egress regions.
 * Proper ViewHolder rebinding avoids blank/ghost items after scroll.
 */
public final class RegionListAdapter extends RecyclerView.Adapter<RegionListAdapter.Holder> {

    public static final class Item {
        public final String code;
        public final String flag;
        public final String name;
        public final int serverCount;

        public Item(String code, String flag, String name, int serverCount) {
            this.code = code == null ? "" : code;
            this.flag = flag == null ? "" : flag;
            this.name = name == null ? "" : name;
            this.serverCount = serverCount;
        }
    }

    public interface OnRegionClick {
        void onRegionClick(Item item, int position);
    }

    private final List<Item> items = new ArrayList<>();
    private String selectedCode = "";
    private OnRegionClick listener;

    public RegionListAdapter() {
        setHasStableIds(true);
    }

    public void setItems(List<Item> next) {
        allItems.clear();
        items.clear();
        if (next != null) {
            allItems.addAll(next);
            items.addAll(next);
        }
        notifyDataSetChanged();
    }

    private final List<Item> allItems = new ArrayList<>();

    public void filter(String query) {
        String q = query == null ? "" : query.trim().toLowerCase();
        items.clear();
        if (q.isEmpty()) {
            items.addAll(allItems);
        } else {
            for (Item it : allItems) {
                if (it.name.toLowerCase().contains(q)
                        || it.code.toLowerCase().contains(q)) {
                    items.add(it);
                }
            }
        }
        notifyDataSetChanged();
    }

    public void setSelectedCode(String code) {
        selectedCode = code == null ? "" : code;
        notifyDataSetChanged();
    }

    public void setOnRegionClick(OnRegionClick listener) {
        this.listener = listener;
    }

    @Override
    public long getItemId(int position) {
        // Stable id from code content
        return items.get(position).code.hashCode();
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_region, parent, false);
        return new Holder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        Item item = items.get(position);
        // Always rebind every field (fixes disappear-after-scroll)
        h.flag.setText(item.flag);
        h.name.setText(item.name);
        if (item.serverCount > 0) {
            h.meta.setVisibility(View.VISIBLE);
            h.meta.setText(item.serverCount + " servers");
        } else if (item.code.isEmpty()) {
            h.meta.setVisibility(View.VISIBLE);
            h.meta.setText("Best performance");
        } else {
            h.meta.setVisibility(View.GONE);
            h.meta.setText("");
        }
        boolean selected = item.code.equalsIgnoreCase(selectedCode)
                || (item.code.isEmpty() && (selectedCode == null || selectedCode.isEmpty()));
        h.check.setVisibility(selected ? View.VISIBLE : View.GONE);
        h.itemView.setAlpha(1f);
        h.itemView.setOnClickListener(v -> {
            if (listener != null) {
                int pos = h.getAdapterPosition();
                if (pos == RecyclerView.NO_POSITION) return;
                listener.onRegionClick(item, pos);
            }
        });
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final TextView flag;
        final TextView name;
        final TextView meta;
        final TextView check;

        Holder(@NonNull View itemView) {
            super(itemView);
            flag = itemView.findViewById(R.id.region_flag);
            name = itemView.findViewById(R.id.region_name);
            meta = itemView.findViewById(R.id.region_meta);
            check = itemView.findViewById(R.id.region_check);
        }
    }
}