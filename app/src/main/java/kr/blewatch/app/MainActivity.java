package kr.blewatch.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.SparseArray;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

@SuppressLint({"MissingPermission", "SetTextI18n"})
public class MainActivity extends Activity {

    private static final int REQ_PERMS = 7;
    private static final long TICK_MS = 500;
    private static final long FADE_MS = 10_000;          // 이 시간 동안 안 보이면 흐리게
    private static final long DROP_MS = 60_000;          // 이 시간 동안 안 보이면 목록에서 제거
    private static final long LOST_MS = 4_000;           // 추적 중 신호 끊김 판정
    private static final long RESTART_MS = 20 * 60_000L; // 안드로이드의 장시간 스캔 제한 회피

    private static final int BG = 0xFF0E1116, CARD = 0xFF171B22, FG = 0xFFE6E9EE,
            DIM = 0xFF8B93A1, RED = 0xFFFF5D5D, AMBER = 0xFFFFB454, GREEN = 0xFF4CC38A;

    private static final String BASE_UUID_TAIL = "-0000-1000-8000-00805f9b34fb";

    static final class Dev {
        String key;
        String addr;
        String name = "";
        int[] ids = new int[0];
        String svc = "";
        int appleType = -1;
        int cat = Classifier.UNKNOWN;
        double rssi;
        long lastSeen;
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final HashMap<String, Dev> devs = new HashMap<>();
    private final HashSet<String> baseline = new HashSet<>();
    private final ArrayList<Dev> shown = new ArrayList<>();

    private BluetoothLeScanner scanner;
    private boolean scanning;
    private boolean wantScan = true;
    private long scanStartedAt;
    private String problem = "";

    private boolean onlyNew;
    private boolean suspectsOnly = true;
    private static final double NEAR_DBM = -70; // 미분류 신호는 이보다 강할 때만 표시
    private boolean vibrate;
    private String trackAddr;
    private String trackName = "";

    private TextView statusView, trackTitle, trackRssi, trackHint;
    private Button scanBtn, newBtn, vibBtn, filterBtn;
    private LinearLayout trackPanel;
    private ProgressBar trackBar;
    private RowAdapter adapter;
    private Vibrator vib;

    // ---------- 스캔 ----------

    private final ScanCallback callback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            handle(result);
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            for (ScanResult r : results) handle(r);
        }

        @Override
        public void onScanFailed(int errorCode) {
            scanning = false;
            problem = "스캔 실패 (코드 " + errorCode + "). 블루투스를 껐다 켜 보세요.";
        }
    };

    private void handle(ScanResult r) {
        if (r == null || r.getDevice() == null) return;
        String addr = r.getDevice().getAddress();
        if (addr == null) return;
        int raw = r.getRssi();
        if (raw <= -127 || raw > 20) return; // 신호 없음을 뜻하는 값은 평균에 넣지 않는다
        long now = SystemClock.elapsedRealtime();

        // 주소를 자주 바꾸는 기기가 여러 줄로 나오지 않도록, 이름이 있으면 이름으로 묶는다.
        ScanRecord rec0 = r.getScanRecord();
        String nm0 = rec0 == null ? null : rec0.getDeviceName();
        String key = (nm0 != null && !nm0.isEmpty()) ? "N:" + nm0 : addr;
        Dev d = devs.get(key);
        if (d == null) {
            d = new Dev();
            d.key = key;
            d.rssi = raw;
            devs.put(key, d);
        } else {
            d.rssi = 0.7 * d.rssi + 0.3 * raw; // 흔들림을 줄이는 이동 평균
        }
        d.addr = addr;
        d.lastSeen = now;

        ScanRecord rec = r.getScanRecord();
        if (rec != null) {
            String nm = rec.getDeviceName();
            if (nm != null && !nm.isEmpty()) d.name = nm;

            SparseArray<byte[]> m = rec.getManufacturerSpecificData();
            if (m != null && m.size() > 0) {
                int[] ids = new int[m.size()];
                for (int i = 0; i < m.size(); i++) {
                    ids[i] = m.keyAt(i);
                    if (ids[i] == 0x004C) {
                        byte[] b = m.valueAt(i);
                        if (b != null && b.length > 0) d.appleType = b[0] & 0xFF;
                    }
                }
                d.ids = ids;
            }

            List<ParcelUuid> uuids = rec.getServiceUuids();
            if (uuids != null && !uuids.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (ParcelUuid u : uuids) {
                    String s = u.toString().toLowerCase(Locale.ROOT);
                    if (s.startsWith("0000") && s.endsWith(BASE_UUID_TAIL)) {
                        sb.append(s, 4, 8).append(' ');
                    }
                }
                if (sb.length() > 0) d.svc = sb.toString();
            }
        }
        d.cat = Classifier.category(d.name, d.ids, d.appleType, d.svc);
    }

    private void startScan() {
        if (scanning) return;
        problem = "";
        if (!granted()) {
            problem = "권한이 필요합니다. '스캔 시작'을 눌러 허용해 주세요.";
            return;
        }
        BluetoothManager bm = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        BluetoothAdapter adapterBt = bm == null ? null : bm.getAdapter();
        if (adapterBt == null) {
            problem = "이 기기는 블루투스를 지원하지 않습니다.";
            return;
        }
        try {
            boolean on;
            try {
                on = adapterBt.isEnabled();
            } catch (SecurityException e) {
                // 일부 기기는 상태 확인에 연결 권한을 요구한다. 권한을 늘리지 않고 그대로 진행한다.
                // 블루투스가 꺼져 있으면 아래에서 스캐너가 null로 나와 걸러진다.
                on = true;
            }
            if (!on) {
                problem = "블루투스를 켠 뒤 '스캔 시작'을 눌러 주세요.";
                return;
            }
            scanner = adapterBt.getBluetoothLeScanner();
            if (scanner == null) {
                problem = "스캐너를 열 수 없습니다. 블루투스가 켜져 있는지 확인해 주세요.";
                return;
            }
            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .setLegacy(false)
                    .setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
                    .setReportDelay(0)
                    .build();
            scanner.startScan(null, settings, callback);
            scanning = true;
            scanStartedAt = SystemClock.elapsedRealtime();
            if (Build.VERSION.SDK_INT >= 28 && Build.VERSION.SDK_INT < 31) {
                LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
                if (lm != null && !lm.isLocationEnabled()) {
                    problem = "위치 서비스가 꺼져 있으면 신호가 안 잡힙니다. 위치를 켜 주세요.";
                }
            }
        } catch (SecurityException e) {
            problem = "권한이 거부되었습니다. 설정에서 '근처 기기' 권한을 허용해 주세요.";
        } catch (RuntimeException e) {
            problem = "스캔을 시작하지 못했습니다: " + e.getMessage();
        }
    }

    private void stopScan() {
        if (scanner != null && scanning) {
            try {
                scanner.stopScan(callback);
            } catch (RuntimeException ignored) {
                // 블루투스가 꺼진 경우 등. 상태만 정리한다.
            }
        }
        scanning = false;
    }

    // ---------- 권한 ----------

    private String[] perms() {
        if (Build.VERSION.SDK_INT >= 31) {
            return new String[]{Manifest.permission.BLUETOOTH_SCAN};
        }
        return new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION};
    }

    private boolean granted() {
        if (Build.VERSION.SDK_INT >= 31) {
            // Android 12 이상은 위치 권한 없이 스캔한다 (매니페스트의 neverForLocation).
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == REQ_PERMS && wantScan) startScan();
    }

    // ---------- 화면 ----------

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        vib = (Vibrator) getSystemService(VIBRATOR_SERVICE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setFitsSystemWindows(true);
        int p = dp(12);
        root.setPadding(p, p, p, 0);

        TextView title = text("BLE 감시", 22, FG);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        statusView = text("", 13, DIM);
        statusView.setPadding(0, dp(2), 0, dp(8));
        root.addView(statusView);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        scanBtn = button("스캔 중지");
        Button baseBtn = button("기준선 저장");
        newBtn = button("새 신호만");
        Button infoBtn = button("안내");
        for (Button b : new Button[]{scanBtn, baseBtn, newBtn, infoBtn}) {
            buttons.addView(b, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        root.addView(buttons);

        filterBtn = button("의심 기기만 ✓");
        Button shareBtn = button("앱 공유");
        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.addView(filterBtn, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 2f));
        row2.addView(shareBtn, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(row2);
        shareBtn.setOnClickListener(v -> shareApp());
        filterBtn.setOnClickListener(v -> {
            suspectsOnly = !suspectsOnly;
            refresh();
        });

        scanBtn.setOnClickListener(v -> {
            if (scanning) {
                wantScan = false;
                stopScan();
            } else {
                wantScan = true;
                if (granted()) startScan();
                else requestPermissions(perms(), REQ_PERMS);
            }
            refresh();
        });
        baseBtn.setOnClickListener(v -> {
            baseline.clear();
            baseline.addAll(devs.keySet());
            onlyNew = true;
            refresh();
        });
        newBtn.setOnClickListener(v -> {
            onlyNew = !onlyNew;
            if (onlyNew && baseline.isEmpty()) baseline.addAll(devs.keySet());
            refresh();
        });
        infoBtn.setOnClickListener(v -> showInfo());

        // 추적 패널
        trackPanel = new LinearLayout(this);
        trackPanel.setOrientation(LinearLayout.VERTICAL);
        trackPanel.setBackgroundColor(CARD);
        trackPanel.setPadding(dp(14), dp(12), dp(14), dp(8));
        trackPanel.setVisibility(View.GONE);

        trackTitle = text("", 15, FG);
        trackPanel.addView(trackTitle);

        trackRssi = text("", 54, FG);
        trackRssi.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        trackRssi.setGravity(Gravity.CENTER_HORIZONTAL);
        trackPanel.addView(trackRssi, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        trackBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        trackBar.setMax(100);
        trackPanel.addView(trackBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(14)));

        trackHint = text("", 14, DIM);
        trackHint.setGravity(Gravity.CENTER_HORIZONTAL);
        trackHint.setPadding(0, dp(6), 0, dp(4));
        trackPanel.addView(trackHint, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout trackButtons = new LinearLayout(this);
        trackButtons.setOrientation(LinearLayout.HORIZONTAL);
        vibBtn = button("진동: 꺼짐");
        Button untrackBtn = button("추적 해제");
        trackButtons.addView(vibBtn, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        trackButtons.addView(untrackBtn, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        trackPanel.addView(trackButtons);

        vibBtn.setOnClickListener(v -> {
            vibrate = !vibrate;
            ui.removeCallbacks(buzz);
            if (vibrate) ui.post(buzz);
            refresh();
        });
        untrackBtn.setOnClickListener(v -> {
            trackAddr = null;
            vibrate = false;
            ui.removeCallbacks(buzz);
            refresh();
        });

        LinearLayout.LayoutParams panelLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        panelLp.topMargin = dp(8);
        root.addView(trackPanel, panelLp);

        ListView list = new ListView(this);
        list.setDividerHeight(dp(1));
        adapter = new RowAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= shown.size()) return;
            Dev d = shown.get(position);
            trackAddr = d.key;
            trackName = displayName(d);
            refresh();
        });
        LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        listLp.topMargin = dp(8);
        root.addView(list, listLp);

        setContentView(root);

        if (!granted()) requestPermissions(perms(), REQ_PERMS);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (wantScan) startScan();
        ui.post(tick);
        if (vibrate) ui.post(buzz);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(tick);
        ui.removeCallbacks(buzz);
        stopScan();
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            long now = SystemClock.elapsedRealtime();
            if (scanning && now - scanStartedAt > RESTART_MS) {
                stopScan();
                startScan();
            }
            refresh();
            ui.postDelayed(this, TICK_MS);
        }
    };

    private final Runnable buzz = new Runnable() {
        @Override
        public void run() {
            if (!vibrate || trackAddr == null) return;
            long delay = 1500;
            Dev d = devs.get(trackAddr);
            if (d != null && SystemClock.elapsedRealtime() - d.lastSeen < LOST_MS) {
                if (vib != null) {
                    vib.vibrate(VibrationEffect.createOneShot(35,
                            VibrationEffect.DEFAULT_AMPLITUDE));
                }
                double t = Math.max(0, Math.min(1, (d.rssi + 90) / 50.0));
                delay = (long) (1400 - 1250 * t); // 가까울수록 빠르게
            }
            ui.postDelayed(this, delay);
        }
    };

    private void refresh() {
        long now = SystemClock.elapsedRealtime();

        Iterator<Dev> it = devs.values().iterator();
        while (it.hasNext()) {
            Dev d = it.next();
            if (now - d.lastSeen > DROP_MS && !d.key.equals(trackAddr)) it.remove();
        }

        shown.clear();
        int suspects = 0;
        int hidden = 0;
        for (Dev d : devs.values()) {
            if (now - d.lastSeen > DROP_MS) continue;
            if (onlyNew && baseline.contains(d.key)) continue;
            if (suspectsOnly && !d.key.equals(trackAddr) && !relevant(d)) {
                hidden++;
                continue;
            }
            shown.add(d);
            if (d.cat == Classifier.GLASSES) suspects++;
        }
        Collections.sort(shown, (a, b) -> {
            boolean ga = a.cat == Classifier.GLASSES, gb = b.cat == Classifier.GLASSES;
            if (ga != gb) return ga ? -1 : 1;
            return Double.compare(b.rssi, a.rssi);
        });
        adapter.notifyDataSetChanged();

        scanBtn.setText(scanning ? "스캔 중지" : "스캔 시작");
        newBtn.setText(onlyNew ? "새 신호만 ✓" : "새 신호만");
        filterBtn.setText(suspectsOnly ? "의심 기기만 ✓" : "의심 기기만 (지금: 전체 표시)");
        vibBtn.setText(vibrate ? "진동: 켜짐" : "진동: 꺼짐");

        if (!problem.isEmpty()) {
            statusView.setTextColor(AMBER);
            statusView.setText(problem);
        } else {
            statusView.setTextColor(suspects > 0 ? RED : DIM);
            String s = (scanning ? "스캔 중" : "정지") + " · 표시 " + shown.size() + "개"
                    + " · 안경 의심 " + suspects + "개";
            if (hidden > 0) s += " · 숨김 " + hidden + "개";
            if (!baseline.isEmpty()) s += " · 기준선 " + baseline.size() + "개";
            statusView.setText(s + "\n※ 연결 중 광고를 끄는 기기는 잡히지 않습니다");
        }

        if (trackAddr == null) {
            trackPanel.setVisibility(View.GONE);
            return;
        }
        trackPanel.setVisibility(View.VISIBLE);
        trackTitle.setText("추적 중: " + trackName);
        Dev t = devs.get(trackAddr);
        if (t == null || now - t.lastSeen > LOST_MS) {
            trackRssi.setText("--");
            trackRssi.setTextColor(DIM);
            trackBar.setProgress(0);
            trackHint.setText("신호 끊김. 멀어졌거나 기기 주소가 바뀌었을 수 있습니다.\n"
                    + "목록에서 다시 골라 주세요.");
            return;
        }
        int v = (int) Math.round(t.rssi);
        trackRssi.setText(v + " dBm");
        trackRssi.setTextColor(strengthColor(t.rssi));
        trackBar.setProgress(percent(t.rssi));
        trackHint.setText(proximity(t.rssi));
    }

    /** 이 앱의 설치 파일을 카카오톡 등 다른 앱으로 보낸다. */
    private void shareApp() {
        try {
            Uri uri = Uri.parse("content://" + ApkProvider.AUTHORITY + "/" + ApkProvider.FILE_NAME);
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType(ApkProvider.MIME);
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.putExtra(Intent.EXTRA_TEXT, "[BLE 감시] 주변 블루투스 기기 탐지 앱 (시험판)\n\n"
                    + "■ 설치 (Android 8 이상, iPhone 불가)\n"
                    + "1. 함께 보낸 BleWatch.apk 파일을 눌러 엽니다.\n"
                    + "2. 경고가 나오면\n"
                    + "- 갤럭시: 설정 > 보안 및 개인정보 보호 > 보안 위험 자동 차단 끄기 (설치 후 다시 켜기)\n"
                    + "- \"출처를 알 수 없는 앱\": 설정에서 \"이 출처 허용\"\n"
                    + "- Play 프로텍트: 세부정보 더보기 > 무시하고 설치\n"
                    + "3. 앱을 열고 \"근처 기기\" 권한을 허용하면 바로 스캔됩니다.\n\n"
                    + "■ 꼭 알아 두세요\n"
                    + "- 인터넷 권한이 없어 어떤 정보도 밖으로 보내지 않습니다.\n"
                    + "- 폰과 연결된 뒤 신호를 끄는 기기는 안 잡힙니다.\n"
                    + "- 신호가 잡힌 것은 확인이 필요하다는 단서이지 부정행위의 증거가 아닙니다.\n"
                    + "- 수험생은 시험장에서 쓸 수 없습니다. 감독관은 시험장 본부 허가를 받고 쓰세요.");
            send.setClipData(ClipData.newRawUri("BleWatch", uri));
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send, "앱 공유"));
        } catch (RuntimeException e) {
            problem = "공유를 열지 못했습니다: " + e.getMessage();
            refresh();
        }
    }

    private void showInfo() {
        new AlertDialog.Builder(this)
                .setTitle("이 앱이 하는 일과 한계")
                .setMessage("주변 기기가 내보내는 BLE 광고 신호를 받아 목록과 세기로 보여 줍니다. "
                        + "아무 기기에도 접속하지 않고, 통신 내용을 읽지 않습니다.\n\n"
                        + "잡히는 것\n"
                        + "· BLE 광고를 내는 기기: 일부 스마트 안경, 무선 이어폰, 워치, 폰, PC\n\n"
                        + "못 잡는 것\n"
                        + "· 폰과 연결된 뒤 광고를 끄는 기기. 스마트 안경 중에도 이런 모델이 있을 수 있고, 그 경우 아무 신호도 뜨지 않습니다\n"
                        + "· 유도 루프형 초소형 이어폰의 귓속 수신부\n"
                        + "· 녹화만 하는 카메라, 전원이 꺼진 기기\n\n"
                        + "'의심 기기만'을 켜면 가전·TV·PC로 보이는 신호와, 종류를 모르는 약한 신호"
                        + "(-70 dBm 미만)를 숨깁니다. 종류를 모르는 강한 신호는 계속 보여 줍니다.\n\n"
                        + "주의\n"
                        + "· 종류 표시는 제조사 ID와 이름으로 한 추정입니다. '안경 의심'에는 "
                        + "같은 제조사의 다른 기기가 섞일 수 있습니다.\n"
                        + "· 세기는 사람 몸과 벽에 따라 크게 흔들립니다. 거리 표시는 어림값입니다.\n"
                        + "· 기기 주소는 약 15분마다 바뀔 수 있어, 기준선에 넣은 기기가 "
                        + "새 신호로 다시 나타날 수 있습니다.\n"
                        + "· 신호가 잡힌 것은 확인이 필요하다는 단서이지 부정행위의 증거가 "
                        + "아닙니다. 신호가 없다고 기기가 없는 것도 아닙니다.\n\n"
                        + "사용 순서\n"
                        + "1. 입실 전 빈 공간에서 '기준선 저장'\n"
                        + "2. 이후 새로 나타난 신호만 확인\n"
                        + "3. 목록에서 신호를 눌러 추적, 세기가 커지는 쪽으로 이동")
                .setPositiveButton("확인", null)
                .show();
    }

    // ---------- 표시 도우미 ----------

    private static String displayName(Dev d) {
        return d.name.isEmpty() ? "(이름 없음)" : d.name;
    }

    private static boolean relevant(Dev d) {
        if (d.cat == Classifier.HARMLESS) return false;
        if (d.cat == Classifier.UNKNOWN) return d.rssi >= NEAR_DBM;
        return true;
    }

    static final class BarView extends View {
        int percent;
        int color = GREEN;
        private final Paint paint = new Paint();

        BarView(Context c) {
            super(c);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            paint.setColor(0xFF282E38);
            canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
            paint.setColor(color);
            canvas.drawRect(0, 0, getWidth() * percent / 100f, getHeight(), paint);
        }
    }

    private static int percent(double rssi) {
        return (int) Math.max(0, Math.min(100, (rssi + 100) / 65.0 * 100));
    }

    private static int strengthColor(double rssi) {
        if (rssi >= -55) return RED;
        if (rssi >= -70) return AMBER;
        return GREEN;
    }

    private static String proximity(double rssi) {
        if (rssi >= -50) return "매우 가까움 (어림 1m 안쪽)";
        if (rssi >= -65) return "가까움 (어림 수 m)";
        if (rssi >= -80) return "중간 거리";
        return "멂 또는 가려짐";
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    private Button button(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        b.setPadding(dp(2), 0, dp(2), 0);
        return b;
    }

    private final class RowAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return shown.size();
        }

        @Override
        public Object getItem(int position) {
            return shown.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convert, ViewGroup parent) {
            TextView[] h;
            if (convert == null) {
                LinearLayout row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(4), dp(10), dp(4), dp(10));

                LinearLayout left = new LinearLayout(MainActivity.this);
                left.setOrientation(LinearLayout.VERTICAL);
                TextView t1 = text("", 16, FG);
                TextView t2 = text("", 12, DIM);
                left.addView(t1);
                left.addView(t2);
                BarView newBar = new BarView(MainActivity.this);
                LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(7));
                barLp.topMargin = dp(5);
                left.addView(newBar, barLp);
                left.setTag(newBar);
                row.addView(left, new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                TextView t3 = text("", 18, FG);
                t3.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
                t3.setPadding(dp(8), 0, 0, 0);
                row.addView(t3);

                h = new TextView[]{t1, t2, t3};
                row.setTag(h);
                convert = row;
            } else {
                h = (TextView[]) convert.getTag();
            }

            Dev d = shown.get(position);
            long age = SystemClock.elapsedRealtime() - d.lastSeen;

            h[0].setText("[" + Classifier.categoryLabel(d.cat) + "] " + displayName(d));
            h[0].setTextColor(d.cat == Classifier.GLASSES ? RED : FG);

            StringBuilder sub = new StringBuilder();
            if (d.ids.length > 0) sub.append(Classifier.company(d.ids[0])).append(" · ");
            sub.append(d.addr).append(" · ").append(age / 1000).append("초 전");
            if (!baseline.isEmpty() && !baseline.contains(d.key)) sub.append(" · 새 신호");
            if (d.key.equals(trackAddr)) sub.append(" · 추적 중");
            h[1].setText(sub.toString());

            h[2].setText(Math.round(d.rssi) + " dBm");
            h[2].setTextColor(strengthColor(d.rssi));

            BarView bar = (BarView) ((View) h[0].getParent()).getTag();
            bar.percent = percent(d.rssi);
            bar.color = strengthColor(d.rssi);
            bar.invalidate();

            convert.setAlpha(age > FADE_MS ? 0.45f : 1f);
            return convert;
        }
    }
}
