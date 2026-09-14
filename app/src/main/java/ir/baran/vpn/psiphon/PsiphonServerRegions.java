package ir.baran.vpn.psiphon;

import java.util.HashMap;
import java.util.Map;

public class PsiphonServerRegions {
    private static final Map<String, String[]> countryNames = new HashMap<>();

    static {
        countryNames.put("US", new String[]{"United States", "ایالات متحده آمریکا"});
        countryNames.put("CA", new String[]{"Canada", "کانادا"});
        countryNames.put("DE", new String[]{"Germany", "آلمان"});
        countryNames.put("NL", new String[]{"Netherlands", "هلند"});
        countryNames.put("GB", new String[]{"United Kingdom", "انگلستان"});
        countryNames.put("UK", new String[]{"United Kingdom", "انگلستان"});
        countryNames.put("FR", new String[]{"France", "فرانسه"});
        countryNames.put("CH", new String[]{"Switzerland", "سوئیس"});
        countryNames.put("JP", new String[]{"Japan", "ژاپن"});
        countryNames.put("SG", new String[]{"Singapore", "سنگاپور"});
        countryNames.put("SE", new String[]{"Sweden", "سوئد"});
        countryNames.put("PL", new String[]{"Poland", "لهستان"});
        countryNames.put("AT", new String[]{"Austria", "اتریش"});
        countryNames.put("ES", new String[]{"Spain", "اسپانیا"});
        countryNames.put("IT", new String[]{"Italy", "ایتالیا"});
        countryNames.put("FI", new String[]{"Finland", "فنلاند"});
        countryNames.put("BE", new String[]{"Belgium", "بلژیک"});
        countryNames.put("NO", new String[]{"Norway", "نروژ"});
        countryNames.put("IE", new String[]{"Ireland", "ایرلند"});
        countryNames.put("CZ", new String[]{"Czech Republic", "جمهوری چک"});
        countryNames.put("DK", new String[]{"Denmark", "دانمارک"});
        countryNames.put("RO", new String[]{"Romania", "رومانی"});
        countryNames.put("AU", new String[]{"Australia", "استرالیا"});
        countryNames.put("IN", new String[]{"India", "هند"});
        countryNames.put("BR", new String[]{"Brazil", "برزیل"});
        countryNames.put("TR", new String[]{"Turkey", "ترکیه"});
        countryNames.put("UA", new String[]{"Ukraine", "اوکراین"});
        countryNames.put("ID", new String[]{"Indonesia", "اندونزی"});
        countryNames.put("RS", new String[]{"Serbia", "صربستان"});
        countryNames.put("LT", new String[]{"Lithuania", "لیتوانی"});
    }

    public static String getName(String code, boolean persian) {
        if (code == null || code.isEmpty()) return persian ? "بهترین موقعیت (خودکار)" : "Best Performance (Automatic)";
        String[] names = countryNames.get(code.toUpperCase());
        if (names != null) {
            return persian ? names[1] : names[0];
        }
        return code;
    }

    public static String getFlag(String countryCode) {
        if (countryCode == null || countryCode.isEmpty()) return "🌐";
        String code = countryCode.trim().toUpperCase();
        if (code.length() != 2) return "🌐";
        int firstChar = Character.codePointAt(code, 0) - 0x41 + 0x1F1E6;
        int secondChar = Character.codePointAt(code, 1) - 0x41 + 0x1F1E6;
        return new String(Character.toChars(firstChar)) + new String(Character.toChars(secondChar));
    }
}
