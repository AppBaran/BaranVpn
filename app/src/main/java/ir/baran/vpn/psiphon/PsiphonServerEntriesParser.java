package ir.baran.vpn.psiphon;

import android.content.Context;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class PsiphonServerEntriesParser {

    public static Map<String, Integer> parseServerEntriesCounts(Context context) {
        Map<String, Integer> regionCounts = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(context.getAssets().open("server_entries.txt"), "UTF-8"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.length() == 0) continue;

                String region = extractRegionFromHexLine(trimmed);
                if (region != null && region.length() > 0) {
                    String upper = region.toUpperCase();
                    int current = regionCounts.containsKey(upper) ? regionCounts.get(upper) : 0;
                    regionCounts.put(upper, current + 1);
                }
            }
        } catch (Exception e) {
            // Error reading or parsing
        }
        return regionCounts;
    }

    public static List<String> getParsedRegions(Context context) {
        Map<String, Integer> counts = parseServerEntriesCounts(context);
        Map<String, Integer> sortedCounts = new TreeMap<>(counts);
        
        List<String> regions = new ArrayList<>();
        regions.add(""); // Best Performance (Automatic)

        for (String code : sortedCounts.keySet()) {
            regions.add(code);
        }

        return regions;
    }

    private static String extractRegionFromHexLine(String hexLine) {
        try {
            byte[] bytes = hexStringToByteArray(hexLine);
            String decoded = new String(bytes, "UTF-8");
            int jsonStart = decoded.indexOf('{');
            if (jsonStart != -1) {
                String jsonStr = decoded.substring(jsonStart);
                JSONObject json = new JSONObject(jsonStr);
                if (json.has("region")) {
                    return json.getString("region");
                } else if (json.has("egressRegion")) {
                    return json.getString("egressRegion");
                }
            }
        } catch (Exception e) {
            // Ignore
        }
        return null;
    }

    private static byte[] hexStringToByteArray(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                                 + Character.digit(s.charAt(i+1), 16));
        }
        return data;
    }
}
