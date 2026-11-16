package kr.blewatch.app;

import java.util.Locale;

/**
 * 광고 패킷의 제조사 ID, 기기명, 서비스 UUID로 기기 종류를 추정한다.
 * 모두 추정이며 확정이 아니다. 실제 기기로 측정해 보정해야 한다.
 */
final class Classifier {
    static final int GLASSES = 0, AUDIO = 1, WATCH = 2, PHONE_PC = 3, UNKNOWN = 4, HARMLESS = 5;

    // 이름은 단어 단위로만 맞춘다. "meta", "glass"처럼 흔한 말은 넣지 않고 제조사 ID로 대신한다.
    private static final String[] GLASSES_WORDS = {
            "ray-ban", "rayban", "ray ban", "oakley", "spectacles", "xreal",
            "rokid", "rayneo", "inmo", "even g1", "vuzix", "solos"};
    // 안경 제조사의 기기지만 안경이 아닌 것
    private static final String[] NOT_GLASSES = {"quest", "portal", "oculus", "vr"};
    private static final String[] AUDIO_WORDS = {
            "buds", "airpods", "pods", "earbud", "earphone", "headphone", "headset",
            "wf-", "wh-", "jabra", "bose", "qcy", "soundcore", "tws", "beats", "jbl", "tone"};
    private static final String[] HARMLESS_WORDS = {
            "fridge", "refrigerator", "kimchi", "vacuum", "v/c", "robot", "washer", "dryer",
            "tv", "monitor", "moving style", "soundbar", "aircon", "air conditioner",
            "purifier", "oven", "dishwasher", "bespoke", "printer", "styler", "cooktop",
            "airdresser"};
    private static final String[] WATCH_WORDS = {
            "watch", "band", "fit", "garmin", "amazfit", "mi smart"};

    private Classifier() {}

    static String categoryLabel(int cat) {
        switch (cat) {
            case GLASSES: return "안경 의심";
            case AUDIO: return "오디오 추정";
            case WATCH: return "워치 추정";
            case PHONE_PC: return "폰·태블릿 추정";
            case HARMLESS: return "무관(가전·PC)";
            default: return "미분류";
        }
    }

    static int category(String name, int[] companyIds, int appleType, String serviceUuids) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (!hasWord(n, NOT_GLASSES)) {
            if (hasWord(n, GLASSES_WORDS)) return GLASSES;
            for (int id : companyIds) {
                // Meta, Meta Platforms Technologies, Luxottica, Snap
                if (id == 0x01AB || id == 0x058E || id == 0x0D53 || id == 0x03C2) return GLASSES;
            }
        }
        if (containsAny(n, HARMLESS_WORDS)) return HARMLESS;
        if (containsAny(n, AUDIO_WORDS)) return AUDIO;
        if (containsAny(n, WATCH_WORDS)) return WATCH;
        if (appleType == 0x07) return AUDIO;                       // Apple 근접 페어링(에어팟류)
        if (serviceUuids.contains("fe2c")) return AUDIO;           // Google Fast Pair
        for (int id : companyIds) {
            if (id == 0x009E || id == 0x0067 || id == 0x0057) return AUDIO; // Bose, GN(Jabra), Harman
            if (id == 0x0087) return WATCH;                                 // Garmin
            if (id == 0x0006) return HARMLESS;                              // Windows PC 신호
        }
        if (appleType == 0x10 || appleType == 0x0C || appleType == 0x0F) return PHONE_PC;
        return UNKNOWN;
    }

    static String company(int id) {
        switch (id) {
            case 0x004C: return "Apple";
            case 0x0006: return "Microsoft";
            case 0x0075: return "Samsung";
            case 0x00E0: return "Google";
            case 0x01AB: return "Meta";
            case 0x058E: return "Meta Technologies";
            case 0x0D53: return "Luxottica";
            case 0x03C2: return "Snap";
            case 0x012D: return "Sony";
            case 0x009E: return "Bose";
            case 0x0067: return "GN(Jabra)";
            case 0x0057: return "Harman";
            case 0x038F: return "Xiaomi";
            case 0x027D: return "Huawei";
            case 0x0087: return "Garmin";
            case 0x00C4: return "LG";
            case 0x0059: return "Nordic";
            case 0x000A: return "Qualcomm";
            case 0x000F: return "Broadcom";
            case 0x005D: return "Realtek";
            default: return String.format(Locale.ROOT, "제조사 0x%04X", id);
        }
    }

    private static boolean containsAny(String s, String[] words) {
        if (s.isEmpty()) return false;
        for (String w : words) if (s.contains(w)) return true;
        return false;
    }

    /** 앞뒤가 글자나 숫자로 이어지지 않는, 단어 단위 일치만 인정한다. */
    private static boolean hasWord(String s, String[] words) {
        if (s.isEmpty()) return false;
        for (String w : words) {
            int i = s.indexOf(w);
            while (i >= 0) {
                int end = i + w.length();
                boolean left = i == 0 || !Character.isLetterOrDigit(s.charAt(i - 1));
                boolean right = end == s.length() || !Character.isLetterOrDigit(s.charAt(end));
                if (left && right) return true;
                i = s.indexOf(w, i + 1);
            }
        }
        return false;
    }
}
