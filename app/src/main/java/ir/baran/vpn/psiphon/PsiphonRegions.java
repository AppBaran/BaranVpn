package ir.baran.vpn.psiphon;

import java.util.ArrayList;
import java.util.List;

public class PsiphonRegions {
    public static class Region {
        public final String code;
        public final String name;
        public final String flag;

        public Region(String code, String name, String flag) {
            this.code = code;
            this.name = name;
            this.flag = flag;
        }
    }

    public static List<Region> getDefaults(boolean persian) {
        List<Region> regions = new ArrayList<>();
        String[] codes = {"", "AT", "AU", "BE", "CA", "CH", "DE", "DK", "ES", "FI", "FR", "GB", "ID", "IE", "IN", "IT", "JP", "LT", "NL", "NO", "PL", "RO", "RS", "SE", "SG", "US"};
        for (String code : codes) {
            regions.add(new Region(code, PsiphonServerRegions.getName(code, persian), PsiphonServerRegions.getFlag(code)));
        }
        return regions;
    }
}
