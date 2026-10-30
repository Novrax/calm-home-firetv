package tv.calm.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaPlayer;
import android.os.Process;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Hardware-decoded Apple TV Aerial video wallpaper rendered into an underlay SurfaceView.
 *
 * Uses the MT8696D hardware decoder + SurfaceFlinger hardware overlay plane (zero GPU shader
 * passes per frame). Optimized for instant app launch and zero UI-thread stalls:
 * - Asynchronous MediaPlayer stop/release on a background thread so HomeActivity.onPause()
 *   and app launches complete in <0.5ms on the UI thread.
 * - Exact wall-clock scheduling for the end-of-stream cross-dissolve instead of polling
 *   MediaPlayer over Binder every 900ms during scrolling.
 * - Background video caching immediately yields if the user opens an app (resuming via HTTP
 *   Range when back on Home), preserving 100% of Wi-Fi and flash I/O for app launches.
 */
final class AerialView extends FrameLayout implements SurfaceHolder.Callback {

    static final class Scene {
        final String id, title, subtitle, location, description, url;
        final int colorA, colorB;

        Scene(String id, String title, String subtitle, String location, String description,
              String url, int colorA, int colorB) {
            this.id = id;
            this.title = title;
            this.subtitle = subtitle;
            this.location = location;
            this.description = description;
            this.url = url;
            this.colorA = colorA;
            this.colorB = colorB;
        }
    }

    static final Scene[] SCENES = {
            // Landscapes
            new Scene("redwoods_river", "Redwoods River — Trinity River", "Northern California",
                    "TRINITY RIVER · NORTHERN CALIFORNIA",
                    "A calm emerald river winding through mist-veiled ancient redwood forest.",
                    "http://sylvan.apple.com/itunes-assets/Aerials116/v4/e6/1a/ca/e61acac6-1c10-41a6-b796-7dc03fbc4517/R013_C039_F01_2K_HEVC.mov",
                    0xFF1D4634, 0xFF0D231A),
            new Scene("redwoods_canopy", "Redwoods Forest — Del Norte Coast", "Coastal Redwoods, California",
                    "DEL NORTE COAST REDWOODS · CALIFORNIA",
                    "Morning light filtering across the towering canopy of old-growth coastal redwoods.",
                    "http://sylvan.apple.com/itunes-assets/Aerials116/v4/e6/1a/ca/e61acac6-1c10-41a6-b796-7dc03fbc4517/R010_C003_F02_2K_HEVC.mov",
                    0xFF214A37, 0xFF0F241B),
            new Scene("hawaii_forest", "Rainforest Valley — Waimanu River", "Kohala Forest Reserve, Hawai‘i",
                    "KOHALA FOREST RESERVE · HAWAI‘I",
                    "Deep tropical rainforest canyon where a quiet river winds out to the Pacific.",
                    "http://sylvan.apple.com/Aerials/2x/Videos/comp_H005_C012_PS_v01_SDR_PS_20180925_SDR_2K_HEVC.mov",
                    0xFF1B4D3E, 0xFF0D261E),
            new Scene("hawaii", "Hawai‘i — Kohala Coast", "Big Island, Hawai‘i",
                    "KOHALA COAST · BIG ISLAND, HAWAI‘I",
                    "Lush volcanic sea cliffs and emerald valleys along the northern shoreline.",
                    "http://sylvan.apple.com/Aerials/2x/Videos/comp_H012_C009_PS_v01_SDR_PS_20180925_SDR_2K_HEVC.mov",
                    0xFF1D4D4F, 0xFF0E2629),
            new Scene("hawaii_umi", "Hawai‘i — Pu‘u O ‘Umi", "Kohala Mountains, Hawai‘i",
                    "PU‘U O ‘UMI RESERVE · HAWAI‘I",
                    "Soaring above the misty green ridges of the Pu‘u O ‘Umi Natural Area Reserve.",
                    "http://sylvan.apple.com/Aerials/2x/Videos/comp_H004_C009_PS_v01_SDR_PS_20180925_SDR_2K_HEVC.mov",
                    0xFF1F4D3A, 0xFF0E251C),
            new Scene("patagonia", "Patagonia — Lago Nordenskjöld", "Torres del Paine, Chile",
                    "TORRES DEL PAINE · PATAGONIA, CHILE",
                    "Turquoise glacial waters of Lago Nordenskjöld beneath rugged Andean peaks.",
                    "http://sylvan.apple.com/Aerials/2x/Videos/P006_C002_UHD_SDR_2K_HEVC.mov",
                    0xFF1F4E5F, 0xFF0E2433),
            new Scene("patagonia_paine", "Patagonia — Paine Massif", "Magallanes Region, Chile",
                    "LAGO NORDENSKJÖLD · PATAGONIA, CHILE",
                    "Traveling along the shore of Lago Nordenskjöld beneath the granite peaks of Torres del Paine.",
                    "http://sylvan.apple.com/Aerials/2x/Videos/P005_C002_UHD_SDR_2K_HEVC.mov",
                    0xFF274B5A, 0xFF102230),
            new Scene("yosemite", "Yosemite — Half Dome", "Sierra Nevada, California",
                    "YOSEMITE NATIONAL PARK · CALIFORNIA",
                    "Soaring past the sheer granite face of Half Dome toward Nevada Fall.",
                    "http://sylvan.apple.com/Aerials/2x/Videos/Y004_C015_SDR_2K_HEVC.mov",
                    0xFF2E4057, 0xFF162230),
            new Scene("grand_canyon", "Grand Canyon — Colorado River", "Burnt Canyon, Arizona",
                    "GRAND CANYON · ARIZONA",
                    "Warm desert light over terraced red sandstone cliffs above the Colorado River.",
                    "http://sylvan.apple.com/Aerials/2x/Videos/G008_C015_UHD_SDR_2K_HEVC.mov",
                    0xFF5A3828, 0xFF261812),
            new Scene("scotland", "Scotland — Loch Moidart", "West Highlands, Scotland",
                    "LOCH MOIDART · WEST HIGHLANDS, SCOTLAND",
                    "Tidal river channel and wooded islands surrounding medieval Castle Tioram.",
                    "http://sylvan.apple.com/Aerials/2x/Videos/S006_C007_SDR_2K_HEVC.mov",
                    0xFF23443C, 0xFF112320),
            new Scene("greenland", "Greenland — Icefjord", "Nuussuaq Peninsula, Greenland",
                    "NUUSSUAQ PENINSULA · GREENLAND",
                    "Towering Arctic icebergs drifting silently through a deep coastal fjord.",
                    "http://sylvan.apple.com/Aerials/2x/Videos/comp_GL_G004_C010_PSNK_v04_SDR_PS_FINAL_20180709_SDR_2K_HEVC.mov",
                    0xFF244963, 0xFF0F2233),
            new Scene("greenland_ilulissat", "Ilulissat — Icefjord Coast", "West Coast, Greenland",
                    "ILULISSAT ICEFJORD · GREENLAND",
                    "Gliding above the ice-choked waters of the Ilulissat Icefjord.",
                    "http://sylvan.apple.com/Aerials/2x/Videos/comp_GL_G002_C002_PSNK_v03_SDR_PS_20180925_SDR_2K_HEVC.mov",
                    0xFF2A4A63, 0xFF112233),
            new Scene("greenland_icebergs", "Ilulissat — Iceberg Field", "Disko Bay, Greenland",
                    "ILULISSAT ICEFJORD · GREENLAND",
                    "Drifting over a vast field of icebergs calved from the Greenland ice sheet.",
                    "http://sylvan.apple.com/Aerials/2x/Videos/comp_GL_G010_C006_PSNK_NOSUN_v12_SDR_PS_FINAL_20180709_SDR_2K_HEVC.mov",
                    0xFF304E66, 0xFF132433),
            new Scene("great_wall", "Great Wall — Mutianyu", "Huairou District, Beijing",
                    "MUTIANYU GREAT WALL · CHINA",
                    "The Great Wall winding along forested mountain ridges north of Beijing.",
                    "http://sylvan.apple.com/Aerials/2x/Videos/comp_C003_C003_PS_v01_SDR_PS_20180925_SDR_2K_HEVC.mov",
                    0xFF3E4A32, 0xFF1C2216),
            new Scene("great_wall_ridge", "Great Wall — Watchtowers", "Huairou District, Beijing",
                    "MUTIANYU GREAT WALL · CHINA",
                    "Following the ancient watchtowers of the Great Wall across rolling hills.",
                    "http://sylvan.apple.com/Aerials/2x/Videos/comp_C001_C005_PS_v01_SDR_PS_20180925_SDR_2K_HEVC.mov",
                    0xFF44503A, 0xFF1E2419),
            new Scene("liwa", "Liwa Oasis — Empty Quarter", "Abu Dhabi, United Arab Emirates",
                    "LIWA OASIS · UNITED ARAB EMIRATES",
                    "Golden dunes of the Empty Quarter rolling past the edge of Liwa Oasis.",
                    "http://sylvan.apple.com/Aerials/2x/Videos/comp_LW_L001_C006_PSNK_DENOISE_v02_SDR_PS_FINAL_20180709_SDR_2K_HEVC.mov",
                    0xFF6A4A26, 0xFF2C1E10),
            // Underwater
            new Scene("humpback", "Humpback Whale — South Pacific", "French Polynesia",
                    "SOUTH PACIFIC · FRENCH POLYNESIA",
                    "A humpback whale gliding gracefully through sunlit deep-blue water.",
                    "http://sylvan.apple.com/Aerials/2x/Videos/g201_WH_D004_L014_SDR_20191031_SDR_2K_HEVC.mov",
                    0xFF14466B, 0xFF081E33),
            new Scene("dolphins", "Dolphins — Pacific Coast", "California, United States",
                    "PACIFIC COAST · CALIFORNIA",
                    "A pod of short-beaked common dolphins racing just beneath the surface of the open sea.",
                    "http://sylvan.apple.com/Aerials/2x/Videos/DL_B002_C011_SDR_20191122_SDR_2K_HEVC.mov",
                    0xFF1A4F73, 0xFF0A2236),
            new Scene("golden_jellies", "Golden Jellyfish — Palau", "Western Pacific, Palau",
                    "GOLDEN JELLYFISH · PALAU",
                    "A bloom of golden jellyfish pulsing slowly through sunlit blue water.",
                    "http://sylvan.apple.com/Aerials/2x/Videos/PA_A002_C009_SDR_20190730_ALT01_SDR_2K_HEVC.mov",
                    0xFF2F5A5C, 0xFF11282A),
            new Scene("palau_coral", "Coral Reef — Palau", "Western Pacific, Palau",
                    "CORAL REEF · PALAU",
                    "Vibrant coral gardens swaying in clear tropical currents.",
                    "http://sylvan.apple.com/Aerials/2x/Videos/PA_A004_C003_SDR_20190719_SDR_2K_HEVC.mov",
                    0xFF1F5B66, 0xFF0C282E),
            new Scene("reef_sharks", "Reef Sharks — South Pacific", "French Polynesia",
                    "SOUTH PACIFIC · FRENCH POLYNESIA",
                    "Gray reef sharks circling slowly over a bright coral channel.",
                    "http://sylvan.apple.com/Aerials/2x/Videos/FK_U009_C004_SDR_20191220_SDR_2K_HEVC.mov",
                    0xFF235068, 0xFF0D2230),
            // Cities
            new Scene("london", "London — Tower Bridge", "River Thames, London",
                    "RIVER THAMES · LONDON, ENGLAND",
                    "Evening reflections along the River Thames gliding past Tower Bridge.",
                    "http://sylvan.apple.com/Aerials/2x/Videos/comp_L012_c002_PS_v01_SDR_PS_20180925_SDR_2K_HEVC.mov",
                    0xFF28384C, 0xFF121A26),
            new Scene("london_thames", "London — River Thames", "City of London, England",
                    "TOWER BRIDGE · LONDON, ENGLAND",
                    "Approaching Tower Bridge along the winding River Thames.",
                    "http://sylvan.apple.com/Aerials/2x/Videos/comp_L010_C006_PS_v01_SDR_PS_20180925_SDR_2K_HEVC.mov",
                    0xFF2B3A4E, 0xFF131B27),
            new Scene("london_approach", "London — Thames Approach", "Southwark, London",
                    "TOWER BRIDGE · LONDON, ENGLAND",
                    "A sweeping approach to Tower Bridge across the heart of the city.",
                    "http://sylvan.apple.com/Aerials/2x/Videos/comp_L004_C011_PS_v01_SDR_PS_20180925_SDR_2K_HEVC.mov",
                    0xFF2F3C4C, 0xFF151C26),
            new Scene("new_york", "New York — Times Square", "Midtown Manhattan, New York",
                    "7TH AVENUE · NEW YORK CITY",
                    "Heading down 7th Avenue toward the glowing lights of Times Square.",
                    "http://sylvan.apple.com/Aerials/2x/Videos/comp_N013_C004_PS_v01_SDR_PS_20180925_F1970F7193_SDR_2K_HEVC.mov",
                    0xFF3A2E4A, 0xFF1A1424),
            new Scene("hong_kong", "Hong Kong — Victoria Harbour", "Kowloon to Central, Hong Kong",
                    "VICTORIA HARBOUR · HONG KONG",
                    "Crossing Victoria Harbour toward the towering skyline of Central Hong Kong.",
                    "http://sylvan.apple.com/Aerials/2x/Videos/comp_HK_B005_C011_PSNK_v16_SDR_PS_20180914_SDR_2K_HEVC.mov",
                    0xFF24384F, 0xFF101A27),
            // Earth from orbit
            new Scene("korea_japan_night", "Korea & Japan — Night from Orbit", "From the Space Station",
                    "KOREA & JAPAN AT NIGHT · FROM ORBIT",
                    "Moving southeast over Siberia and Mongolia toward the city lights of Korea and Japan.",
                    "http://sylvan.apple.com/Aerials/2x/Videos/comp_GMT026_363A_103NC_E1027_KOREA_JAPAN_NIGHT_v18_SDR_PS_20180907_SDR_2K_HEVC.mov",
                    0xFF1A2140, 0xFF0A0E1E),
            new Scene("africa_night", "Sahara — Night from Orbit", "From the Space Station",
                    "THE SAHARA AT NIGHT · FROM ORBIT",
                    "Traveling northeast across the Sahara at night, high above North Africa.",
                    "http://sylvan.apple.com/Aerials/2x/Videos/comp_GMT312_162NC_139M_1041_AFRICA_NIGHT_v14_SDR_FINAL_20180706_SDR_2K_HEVC.mov",
                    0xFF2A2238, 0xFF100C1A),
            new Scene("iran_sunglint", "Iran — Sunlight from Orbit", "From the Space Station",
                    "COAST OF IRAN · FROM ORBIT",
                    "The sun’s reflection sweeping along the coast of Iran toward Afghanistan.",
                    "http://sylvan.apple.com/Aerials/2x/Videos/comp_A083_C002_1130KZ_v04_SDR_PS_FINAL_20180725_SDR_2K_HEVC.mov",
                    0xFF1E3552, 0xFF0C1828),
            new Scene("china_orbit", "China — Earth from Orbit", "From the Space Station",
                    "CENTRAL ASIA TO CHINA · FROM ORBIT",
                    "Over central Asia traveling toward the coast of China, seen from the Space Station.",
                    "http://sylvan.apple.com/Aerials/2x/Videos/comp_GMT329_113NC_396B_1105_CHINA_v04_SDR_FINAL_20180706_F900F2700_SDR_2K_HEVC.mov",
                    0xFF1B3150, 0xFF0B1626),
            new Scene("antarctica_aurora", "Antarctica — Aurora Australis", "From the Space Station",
                    "AURORA AUSTRALIS · ANTARCTICA",
                    "Ribbons of the southern lights shimmering above Antarctica, seen from orbit.",
                    "http://sylvan.apple.com/Aerials/2x/Videos/comp_GMT110_112NC_364D_1054_AURORA_ANTARTICA__COMP_FINAL_v34_PS_SDR_20181107_SDR_2K_HEVC.mov",
                    0xFF163F3A, 0xFF08181F),
            new Scene("glow", "Dynamic App Glow", "Adaptive tint · No video",
                    "CALM HOME · AMBIENT GLOW",
                    "Soft atmospheric light adaptive to your currently focused app.",
                    null, 0xFF3B2A55, 0xFF1C2448),
    };

    static final int END_LOOP = 0, END_CYCLE = 1, END_SHUFFLE = 2;
    static final String[] END_MODE_LABELS = {"Smooth Loop", "Cycle All", "Shuffle"};

    /** What happens when a clip ends. Falls back to the older on/off "aerial_cycle" pref. */
    static int endMode(SharedPreferences prefs) {
        int fallback = prefs.getBoolean("aerial_cycle", false) ? END_CYCLE : END_LOOP;
        return Math.max(END_LOOP, Math.min(prefs.getInt("aerial_end_mode", fallback), END_SHUFFLE));
    }

    /** Advances Loop → Cycle → Shuffle → Loop and returns the new mode. */
    static int nextEndMode(SharedPreferences prefs) {
        int next = (endMode(prefs) + 1) % END_MODE_LABELS.length;
        prefs.edit().putInt("aerial_end_mode", next).apply();
        return next;
    }

    /** Scene ids in v6.0's order, to carry over a scene saved by index before ids were stored. */
    private static final String[] LEGACY_ORDER = {"redwoods_river", "redwoods_canopy", "hawaii_forest",
            "patagonia", "yosemite", "scotland", "iceland", "greenland", "grand_canyon", "hawaii", "london", "glow"};

    /** The chosen scene, saved by id so reordering or removing scenes never swaps it for another. */
    static int savedSceneIndex(SharedPreferences prefs) {
        String id = prefs.getString("aerial_scene_id", null);
        if (id == null) {
            int legacy = prefs.getInt("aerial_scene_v2", 0);
            id = legacy >= 0 && legacy < LEGACY_ORDER.length ? LEGACY_ORDER[legacy] : null;
        }
        int idx = id != null ? indexOf(id) : -1;
        return idx >= 0 ? idx : 0;
    }

    private final SurfaceView surfaceView;
    private final AmbientView ambient;
    private final SharedPreferences prefs;
    private final File cacheDir;
    private MediaPlayer player;
    private boolean surfaceReady;
    private volatile boolean resumed;
    private boolean dissolvingEos;
    private int sceneIndex;
    private int videoDurationMs;
    private int playStartPosMs;
    private long playStartUptimeMs;
    private volatile String downloadingId;
    private Runnable onSceneChanged;

    private final Runnable eosCheckRunnable = new Runnable() {
        @Override
        public void run() {
            removeCallbacks(this);
            if (player == null || !resumed || !isLive() || dissolvingEos) return;
            try {
                int dur = videoDurationMs > 0 ? videoDurationMs : player.getDuration();
                int pos = player.getCurrentPosition();
                if (dur > 6000 && pos > 0) {
                    int rem = dur - pos;
                    if (rem <= 2100) {
                        triggerSmoothEndTransition();
                        return;
                    }
                    playStartPosMs = pos;
                    playStartUptimeMs = SystemClock.uptimeMillis();
                    postDelayed(this, Math.max(600L, rem - 2050L));
                    return;
                }
            } catch (Exception ignored) {
            }
            postDelayed(this, 2000L);
        }
    };

    private final Runnable retryRunnable = new Runnable() {
        @Override
        public void run() {
            if (resumed && surfaceReady && isLive() && player == null) {
                startPlayback();
            }
        }
    };

    AerialView(Context c, AmbientView ambient, SharedPreferences prefs) {
        super(c);
        this.ambient = ambient;
        this.prefs = prefs;
        int initialScene = savedSceneIndex(prefs);
        this.sceneIndex = Math.max(0, Math.min(initialScene, SCENES.length - 1));
        File base = c.getExternalCacheDir();
        cacheDir = new File(base != null ? base : c.getCacheDir(), "aerials");
        cacheDir.mkdirs();

        surfaceView = new SurfaceView(c);
        surfaceView.getHolder().addCallback(this);
        addView(surfaceView, new LayoutParams(-1, -1));
        addView(ambient, new LayoutParams(-1, -1));
        applySceneMode();
    }

    void setOnSceneChangedListener(Runnable r) { this.onSceneChanged = r; }
    int sceneIndex() { return sceneIndex; }
    Scene scene() { return SCENES[sceneIndex]; }
    boolean isLive() { return SCENES[sceneIndex].url != null; }

    boolean isCached(int idx) {
        if (idx < 0 || idx >= SCENES.length || SCENES[idx].url == null) return false;
        File f = new File(cacheDir, SCENES[idx].id + ".mov");
        return f.exists() && f.length() > 5_000_000L;
    }

    void setSceneIndex(int idx) {
        idx = Math.max(0, Math.min(idx, SCENES.length - 1));
        prefs.edit().putString("aerial_scene_id", SCENES[idx].id).apply();
        if (idx == sceneIndex && (player != null || !isLive())) return;
        sceneIndex = idx;
        prefs.edit().putString("aerial_pos_id", SCENES[idx].id).putInt("aerial_pos_ms", 0).apply();
        applySceneMode();
        if (onSceneChanged != null) onSceneChanged.run();
        if (resumed && surfaceReady) startPlayback();
    }

    private void applySceneMode() {
        Scene s = SCENES[sceneIndex];
        if (s.url == null) {
            stopPlayback(false);
            surfaceView.setVisibility(View.GONE);
            ambient.setVideoMode(false);
        } else {
            surfaceView.setVisibility(View.VISIBLE);
            ambient.setVideoMode(true);
            ambient.setColors(s.colorA, s.colorB);
        }
    }

    void onHomeResume() {
        resumed = true;
        int savedIdx = savedSceneIndex(prefs);
        if (savedIdx != sceneIndex) {
            sceneIndex = savedIdx;
            applySceneMode();
            if (onSceneChanged != null) onSceneChanged.run();
        }
        if (isLive() && surfaceReady && player == null) startPlayback();
    }

    void onHomePause() {
        resumed = false;
        removeCallbacks(retryRunnable);
        stopPlayback(true);
    }

    /**
     * Called immediately when the user clicks an app tile, before startActivity(), so the
     * hardware HEVC decoder begins releasing on a background thread right away without
     * blocking HomeActivity.onPause().
     */
    void prepareForAppLaunch() {
        resumed = false;
        removeCallbacks(retryRunnable);
        stopPlayback(true);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        surfaceReady = true;
        if (resumed && isLive() && player == null) startPlayback();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceReady = false;
        removeCallbacks(retryRunnable);
        stopPlayback(true);
    }

    private void scheduleEosCheck(int dur, int pos) {
        removeCallbacks(eosCheckRunnable);
        if (dur > 6000 && pos >= 0 && pos < dur - 2100) {
            postDelayed(eosCheckRunnable, Math.max(800L, (dur - pos) - 2100L));
        } else {
            postDelayed(eosCheckRunnable, 2000L);
        }
    }

    /**
     * tvOS-style smooth cross-dissolve ~2s before the video ends:
     * Gently fades through the atmospheric backdrop instead of a jarring frame-0 jump cut.
     */
    private void triggerSmoothEndTransition() {
        if (dissolvingEos) return;
        dissolvingEos = true;
        removeCallbacks(eosCheckRunnable);
        ambient.setVideoPlaying(false, 1500);
        postDelayed(() -> {
            if (!resumed || !isLive()) return;
            int mode = endMode(prefs);
            if (mode != END_LOOP) {
                int liveCount = SCENES.length - 1; // exclude Dynamic App Glow
                int next = mode == END_SHUFFLE ? nextShuffled() : (sceneIndex + 1) % liveCount;
                sceneIndex = next;
                prefs.edit()
                        .putString("aerial_scene_id", SCENES[next].id)
                        .putString("aerial_pos_id", SCENES[next].id)
                        .putInt("aerial_pos_ms", 0)
                        .apply();
                ambient.setColors(SCENES[next].colorA, SCENES[next].colorB);
                if (onSceneChanged != null) onSceneChanged.run();
                startPlayback();
            } else if (player != null) {
                try {
                    prefs.edit().putInt("aerial_pos_ms", 0).apply();
                    player.seekTo(0);
                    playStartPosMs = 0;
                    playStartUptimeMs = SystemClock.uptimeMillis();
                    if (!player.isPlaying()) player.start();
                    postDelayed(() -> {
                        dissolvingEos = false;
                        if (player != null && resumed) {
                            ambient.setVideoPlaying(true, 1600);
                            scheduleEosCheck(videoDurationMs, 0);
                        }
                    }, 220);
                } catch (Exception e) {
                    startPlayback();
                }
            } else {
                startPlayback();
            }
        }, 1450);
    }

    /**
     * Shuffle bag: every live scene plays once in random order before any repeats. The bag holds
     * scene ids (not indices) so adding scenes later never points it at the wrong video.
     */
    private int nextShuffled() {
        int liveCount = SCENES.length - 1;
        List<String> bag = new ArrayList<>();
        for (String id : prefs.getString("aerial_shuffle_bag", "").split(",")) {
            int i = indexOf(id);
            if (i >= 0 && i < liveCount && i != sceneIndex) bag.add(id);
        }
        if (bag.isEmpty()) {
            for (int i = 0; i < liveCount; i++) if (i != sceneIndex) bag.add(SCENES[i].id);
            Collections.shuffle(bag);
        }
        int next = indexOf(bag.remove(0));
        prefs.edit().putString("aerial_shuffle_bag", TextUtils.join(",", bag)).apply();
        return next;
    }

    private static int indexOf(String id) {
        for (int i = 0; i < SCENES.length; i++) if (SCENES[i].id.equals(id)) return i;
        return -1;
    }

    private void startPlayback() {
        removeCallbacks(retryRunnable);
        stopPlayback(false);
        Scene s = SCENES[sceneIndex];
        if (s.url == null || !surfaceReady) return;

        File cached = new File(cacheDir, s.id + ".mov");
        boolean cachedOk = cached.exists() && cached.length() > 5_000_000L;
        String source = cachedOk ? cached.getAbsolutePath() : s.url;

        dissolvingEos = false;
        videoDurationMs = 0;
        playStartPosMs = 0;
        playStartUptimeMs = 0L;
        ambient.setVideoPlaying(false, 220);
        try {
            MediaPlayer mp = new MediaPlayer();
            player = mp;
            mp.setSurface(surfaceView.getHolder().getSurface());
            mp.setDataSource(source);
            mp.setVolume(0f, 0f);
            mp.setLooping(false); // Managed via triggerSmoothEndTransition() to prevent hard cut
            mp.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING);
            mp.setOnInfoListener((p, what, extra) -> {
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START && !dissolvingEos) {
                    ambient.setVideoPlaying(true, 850);
                }
                return false;
            });
            mp.setOnPreparedListener(p -> {
                if (!resumed || !isLive() || player != p) { stopPlayback(false); return; }
                String savedScene = prefs.getString("aerial_pos_id", "");
                int savedPos = prefs.getInt("aerial_pos_ms", 0);
                int dur = p.getDuration();
                videoDurationMs = dur;
                int startPos = 0;
                if (savedScene.equals(SCENES[sceneIndex].id) && savedPos > 1500 && (dur <= 0 || savedPos < dur - 4500)) {
                    try {
                        p.seekTo(savedPos);
                        startPos = savedPos;
                    } catch (Exception ignored) {}
                }
                playStartPosMs = startPos;
                playStartUptimeMs = SystemClock.uptimeMillis();
                p.start();
                postDelayed(() -> {
                    if (player == p && resumed && !dissolvingEos) {
                        ambient.setVideoPlaying(true, 850);
                    }
                }, 260);
                scheduleEosCheck(dur, startPos);
                if (!cachedOk) {
                    postDelayed(() -> {
                        if (resumed && SCENES[sceneIndex] == s) cacheInBackground(s);
                    }, 3500);
                }
            });
            mp.setOnCompletionListener(p -> {
                if (!resumed || !isLive()) return;
                if (!dissolvingEos) {
                    triggerSmoothEndTransition();
                }
            });
            mp.setOnErrorListener((p, what, extra) -> {
                if (player == p) stopPlayback(false);
                if (resumed && surfaceReady && isLive()) {
                    removeCallbacks(retryRunnable);
                    postDelayed(retryRunnable, 2500);
                }
                return true;
            });
            mp.prepareAsync();
        } catch (Exception e) {
            stopPlayback(false);
            if (resumed && surfaceReady && isLive()) {
                removeCallbacks(retryRunnable);
                postDelayed(retryRunnable, 2500);
            }
        }
    }

    private void stopPlayback(boolean savePosition) {
        removeCallbacks(eosCheckRunnable);
        dissolvingEos = false;
        ambient.setVideoPlaying(false, 0);
        if (player != null) {
            final MediaPlayer mp = player;
            player = null;
            if (savePosition && playStartUptimeMs > 0L) {
                int estPos = playStartPosMs + (int) Math.max(0L, SystemClock.uptimeMillis() - playStartUptimeMs);
                int dur = videoDurationMs;
                if (estPos > 1500 && (dur <= 0 || estPos < dur - 4000)) {
                    prefs.edit()
                            .putString("aerial_pos_id", SCENES[sceneIndex].id)
                            .putInt("aerial_pos_ms", estPos)
                            .apply();
                }
            }
            playStartUptimeMs = 0L;
            try { mp.setSurface(null); } catch (Exception ignored) {}
            new Thread(() -> {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                try { mp.stop(); } catch (Exception ignored) {}
                try { mp.release(); } catch (Exception ignored) {}
            }, "aerial-release").start();
        }
    }

    private void cacheInBackground(Scene s) {
        if (s.id.equals(downloadingId)) return;
        downloadingId = s.id;
        new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_LOWEST);
            File target = new File(cacheDir, s.id + ".mov");
            File tmp = new File(cacheDir, s.id + ".tmp");
            if (target.exists() && target.length() > 5_000_000L) {
                downloadingId = null;
                return;
            }
            // Keep up to 3 cached videos so favorite scenes play from flash storage immediately on wake.
            File[] existing = cacheDir.listFiles((d, n) -> n.endsWith(".mov") && !n.equals(s.id + ".mov"));
            if (existing != null && existing.length >= 3) {
                File oldest = existing[0];
                for (File f : existing) if (f.lastModified() < oldest.lastModified()) oldest = f;
                oldest.delete();
            }
            HttpURLConnection conn = null;
            try {
                long existingBytes = tmp.exists() ? tmp.length() : 0L;
                conn = (HttpURLConnection) new URL(s.url).openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(15000);
                if (existingBytes > 0) {
                    conn.setRequestProperty("Range", "bytes=" + existingBytes + "-");
                }
                int code = conn.getResponseCode();
                boolean append = (code == HttpURLConnection.HTTP_PARTIAL && existingBytes > 0);
                if (!append && existingBytes > 0) {
                    tmp.delete();
                }
                try (InputStream in = conn.getInputStream();
                     FileOutputStream out = new FileOutputStream(tmp, append)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while (resumed && (n = in.read(buf)) != -1) {
                        out.write(buf, 0, n);
                    }
                }
                if (resumed && tmp.length() > 5_000_000L) tmp.renameTo(target);
            } catch (Exception ignored) {
                // Keep partial .tmp so next resume can finish via HTTP Range.
            } finally {
                if (conn != null) conn.disconnect();
                downloadingId = null;
            }
        }, "aerial-cache").start();
    }
}
