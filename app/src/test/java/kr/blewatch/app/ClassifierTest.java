package kr.blewatch.app;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ClassifierTest {
    private static final int[] NONE = {};
    private static final int[] META = {0x01AB};
    private static final int[] META_TECH = {0x058E};
    private static final int[] LUXOTTICA = {0x0D53};
    private static final int[] SNAP = {0x03C2};
    private static final int[] SAMSUNG = {0x0075};
    private static final int[] APPLE = {0x004C};
    private static final int[] MICROSOFT = {0x0006};

    private static int cat(String name, int[] ids) {
        return Classifier.category(name, ids, -1, "");
    }

    // ---- 안경으로 잡아야 하는 것 ----

    @Test
    public void glassesByName() {
        assertEquals(Classifier.GLASSES, cat("Ray-Ban Meta 01AB", NONE));
        assertEquals(Classifier.GLASSES, cat("RayBan Stories", NONE));
        assertEquals(Classifier.GLASSES, cat("XREAL One", NONE));
        assertEquals(Classifier.GLASSES, cat("Rokid-Max", NONE));
        assertEquals(Classifier.GLASSES, cat("INMO_Air2", NONE));
        assertEquals(Classifier.GLASSES, cat("Even G1_45_L", NONE));
        assertEquals(Classifier.GLASSES, cat("Spectacles", SNAP));
    }

    @Test
    public void glassesByCompanyIdWithoutName() {
        assertEquals(Classifier.GLASSES, cat("", META));
        assertEquals(Classifier.GLASSES, cat(null, META_TECH));
        assertEquals(Classifier.GLASSES, cat("", LUXOTTICA));
        assertEquals(Classifier.GLASSES, cat("", SNAP));
    }

    // ---- 안경으로 잡으면 안 되는 것 (오탐 방지) ----

    @Test
    public void commonWordsAreNotGlasses() {
        assertEquals(Classifier.UNKNOWN, cat("metallic speaker", NONE));
        assertEquals(Classifier.UNKNOWN, cat("Glassdoor Beacon", NONE));
        assertEquals(Classifier.UNKNOWN, cat("Meta", NONE));
        assertEquals(Classifier.UNKNOWN, cat("xrealistic", NONE));
        assertEquals(Classifier.UNKNOWN, cat("Brokid", NONE));
    }

    @Test
    public void metaDevicesThatAreNotGlasses() {
        assertEquals(Classifier.UNKNOWN, cat("Meta Quest 3", META));
        assertEquals(Classifier.UNKNOWN, cat("Oculus Quest 2", META_TECH));
        assertEquals(Classifier.UNKNOWN, cat("Portal", META));
        assertEquals(Classifier.UNKNOWN, cat("Pico VR", NONE));
    }

    // ---- 그 밖의 분류 ----

    @Test
    public void audio() {
        assertEquals(Classifier.AUDIO, cat("Galaxy Buds2 Pro", SAMSUNG));
        assertEquals(Classifier.AUDIO, cat("AirPods Pro", APPLE));
        assertEquals(Classifier.AUDIO, cat("WF-1000XM5", NONE));
        assertEquals(Classifier.AUDIO, Classifier.category("", APPLE, 0x07, ""));
        assertEquals(Classifier.AUDIO, Classifier.category("", NONE, -1, "fe2c "));
        assertEquals(Classifier.AUDIO, cat("", new int[]{0x009E}));
    }

    @Test
    public void watch() {
        assertEquals(Classifier.WATCH, cat("Galaxy Watch4 (H0DN)", SAMSUNG));
        assertEquals(Classifier.WATCH, cat("Mi Smart Band 8", NONE));
        assertEquals(Classifier.WATCH, cat("", new int[]{0x0087}));
    }

    @Test
    public void phone() {
        assertEquals(Classifier.PHONE_PC, Classifier.category("", APPLE, 0x10, ""));
    }

    @Test
    public void harmless() {
        assertEquals(Classifier.HARMLESS, cat("Fridge", SAMSUNG));
        assertEquals(Classifier.HARMLESS, cat("Kimchi Ref", SAMSUNG));
        assertEquals(Classifier.HARMLESS, cat("Robot Vacuum", SAMSUNG));
        assertEquals(Classifier.HARMLESS, cat("Jet V/C", SAMSUNG));
        assertEquals(Classifier.HARMLESS, cat("[TV] Samsung 7 Series", SAMSUNG));
        assertEquals(Classifier.HARMLESS, cat("32\" Moving Style M7", SAMSUNG));
        assertEquals(Classifier.HARMLESS, cat("", MICROSOFT));
    }

    @Test
    public void unknownStaysUnknown() {
        assertEquals(Classifier.UNKNOWN, cat("", NONE));
        assertEquals(Classifier.UNKNOWN, cat("", SAMSUNG));
        assertEquals(Classifier.UNKNOWN, cat("AD_401_CST_1A4001_WW_c117", NONE));
        assertEquals(Classifier.UNKNOWN, Classifier.category("", APPLE, 0x12, ""));
    }

    @Test
    public void glassesNameWinsOverHarmlessWords() {
        // 안경 이름에 가전 단어가 섞여 있어도 숨기지 않는다.
        assertEquals(Classifier.GLASSES, cat("Ray-Ban TV", NONE));
    }
}
