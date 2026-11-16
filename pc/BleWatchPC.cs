using System;
using System.Collections.Generic;
using System.Drawing;
using System.IO;
using System.Reflection;
using System.Text;
using System.Threading;
using System.Windows.Forms;
using Windows.Devices.Bluetooth.Advertisement;
using Windows.Storage.Streams;

namespace BleWatchPC
{
    class Dev
    {
        public string Key;
        public ulong Addr;
        public string Name = "";
        public int[] Ids = new int[0];
        public string Svc = "";
        public int AppleType = -1;
        public int Cat = Classifier.Unknown;
        public double Rssi;
        public DateTime Last;
    }

    // 제조사 ID, 기기명, 서비스 UUID로 종류를 추정한다. 확정이 아니라 추정이다.
    static class Classifier
    {
        public const int Glasses = 0, Audio = 1, Watch = 2, PhonePc = 3, Unknown = 4, Harmless = 5;

        static readonly string[] GlassesWords = {
            "ray-ban", "rayban", "ray ban", "oakley", "spectacles", "xreal",
            "rokid", "rayneo", "inmo", "even g1", "vuzix", "solos" };
        // 안경 제조사의 기기지만 안경이 아닌 것
        static readonly string[] NotGlasses = { "quest", "portal", "oculus", "vr" };
        static readonly string[] AudioWords = {
            "buds", "airpods", "pods", "earbud", "earphone", "headphone", "headset",
            "wf-", "wh-", "jabra", "bose", "qcy", "soundcore", "tws", "beats", "jbl", "tone" };
        static readonly string[] HarmlessWords = {
            "fridge", "refrigerator", "kimchi", "vacuum", "v/c", "robot", "washer", "dryer", "tv", "monitor", "moving style", "soundbar", "aircon", "air conditioner", "purifier", "oven", "dishwasher", "bespoke", "printer", "styler", "cooktop", "airdresser" };
        static readonly string[] WatchWords = {
            "watch", "band", "fit", "garmin", "amazfit", "mi smart" };

        public static string Label(int cat)
        {
            switch (cat)
            {
                case Glasses: return "안경 의심";
                case Audio: return "오디오 추정";
                case Watch: return "워치 추정";
                case PhonePc: return "폰·태블릿 추정";
                case Harmless: return "무관(가전·PC)";
                default: return "미분류";
            }
        }

        public static int Category(string name, int[] ids, int appleType, string svc)
        {
            string n = (name ?? "").ToLowerInvariant();
            if (!HasWord(n, NotGlasses))
            {
                if (HasWord(n, GlassesWords)) return Glasses;
                foreach (int id in ids)
                    if (id == 0x01AB || id == 0x058E || id == 0x0D53 || id == 0x03C2) return Glasses;
            }
            if (Has(n, HarmlessWords)) return Harmless;
            if (Has(n, AudioWords)) return Audio;
            if (Has(n, WatchWords)) return Watch;
            if (appleType == 0x07) return Audio;
            if (svc.Contains("fe2c")) return Audio;
            foreach (int id in ids)
            {
                if (id == 0x009E || id == 0x0067 || id == 0x0057) return Audio;
                if (id == 0x0087) return Watch;
                if (id == 0x0006) return Harmless; // Windows PC 신호
            }
            if (appleType == 0x10 || appleType == 0x0C || appleType == 0x0F) return PhonePc;
            return Unknown;
        }

        public static string Company(int id)
        {
            switch (id)
            {
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
                default: return "제조사 0x" + id.ToString("X4");
            }
        }

        // 앞뒤가 글자나 숫자로 이어지지 않는, 단어 단위 일치만 인정한다.
        static bool HasWord(string s, string[] words)
        {
            if (s.Length == 0) return false;
            foreach (string w in words)
            {
                int i = s.IndexOf(w, StringComparison.Ordinal);
                while (i >= 0)
                {
                    int end = i + w.Length;
                    bool left = i == 0 || !char.IsLetterOrDigit(s[i - 1]);
                    bool right = end == s.Length || !char.IsLetterOrDigit(s[end]);
                    if (left && right) return true;
                    i = s.IndexOf(w, i + 1, StringComparison.Ordinal);
                }
            }
            return false;
        }

        static bool Has(string s, string[] words)
        {
            if (s.Length == 0) return false;
            foreach (string w in words) if (s.Contains(w)) return true;
            return false;
        }
    }

    // 스캔과 기기 목록. 화면과 분리되어 있어 창 없이도 시험할 수 있다.
    class Scanner
    {
        public readonly Dictionary<string, Dev> Devs = new Dictionary<string, Dev>();
        readonly Dictionary<ulong, string> addrKey = new Dictionary<ulong, string>();
        public string Problem = "";
        public bool Running;
        BluetoothLEAdvertisementWatcher watcher;

        public void Start()
        {
            if (Running) return;
            Problem = "";
            try
            {
                watcher = new BluetoothLEAdvertisementWatcher();
                watcher.ScanningMode = BluetoothLEScanningMode.Active;
                watcher.Received += OnReceived;
                watcher.Stopped += OnStopped;
                watcher.Start();
                Running = true;
            }
            catch (Exception e)
            {
                Problem = "스캔을 시작하지 못했습니다. 블루투스가 켜져 있는지 확인하세요. (" + e.Message + ")";
            }
        }

        public void Stop()
        {
            if (watcher != null && Running)
            {
                try { watcher.Stop(); } catch (Exception) { }
            }
            Running = false;
        }

        void OnStopped(BluetoothLEAdvertisementWatcher w, BluetoothLEAdvertisementWatcherStoppedEventArgs e)
        {
            if (Running)
            {
                Running = false;
                Problem = "스캔이 중단되었습니다 (" + e.Error + "). 블루투스를 켜고 '스캔 시작'을 누르세요.";
            }
        }

        void OnReceived(BluetoothLEAdvertisementWatcher w, BluetoothLEAdvertisementReceivedEventArgs e)
        {
            int rssi = e.RawSignalStrengthInDBm;
            if (rssi <= -127 || rssi > 20) return;

            string name = e.Advertisement.LocalName ?? "";
            List<int> ids = new List<int>();
            int apple = -1;
            foreach (BluetoothLEManufacturerData m in e.Advertisement.ManufacturerData)
            {
                ids.Add(m.CompanyId);
                if (m.CompanyId == 0x004C && m.Data != null && m.Data.Length > 0)
                    apple = DataReader.FromBuffer(m.Data).ReadByte();
            }
            StringBuilder svc = new StringBuilder();
            foreach (Guid g in e.Advertisement.ServiceUuids)
            {
                string s = g.ToString().ToLowerInvariant();
                if (s.StartsWith("0000") && s.EndsWith("-0000-1000-8000-00805f9b34fb"))
                    svc.Append(s.Substring(4, 4)).Append(' ');
            }

            lock (Devs)
            {
                // 주소를 자주 바꾸는 기기가 여러 줄로 나오지 않도록, 이름이 있으면 이름으로 묶는다.
                ulong addr = e.BluetoothAddress;
                string oldKey;
                addrKey.TryGetValue(addr, out oldKey);
                string key = name.Length > 0 ? "N:" + name : (oldKey ?? "A:" + addr.ToString("X12"));
                Dev old = null;
                if (oldKey != null && oldKey != key && oldKey.StartsWith("A:"))
                {
                    if (Devs.TryGetValue(oldKey, out old)) Devs.Remove(oldKey);
                }
                addrKey[addr] = key;
                if (addrKey.Count > 5000) addrKey.Clear();

                Dev d;
                if (!Devs.TryGetValue(key, out d))
                {
                    d = new Dev();
                    d.Key = key;
                    d.Rssi = rssi;
                    if (old != null)
                    {
                        d.Ids = old.Ids;
                        d.AppleType = old.AppleType;
                        d.Svc = old.Svc;
                    }
                    Devs[key] = d;
                }
                else
                {
                    d.Rssi = 0.7 * d.Rssi + 0.3 * rssi; // 흔들림을 줄이는 이동 평균
                }
                d.Addr = addr;
                d.Last = DateTime.UtcNow;
                if (name.Length > 0) d.Name = name;
                if (ids.Count > 0) d.Ids = ids.ToArray();
                if (apple >= 0) d.AppleType = apple;
                if (svc.Length > 0) d.Svc = svc.ToString();
                d.Cat = Classifier.Category(d.Name, d.Ids, d.AppleType, d.Svc);
            }
        }
    }

    class BarPanel : Panel
    {
        public int Percent;
        public Color BarColor = Color.Gray;

        public BarPanel()
        {
            DoubleBuffered = true;
        }

        protected override void OnPaint(PaintEventArgs e)
        {
            e.Graphics.Clear(Color.FromArgb(40, 46, 56));
            int w = Width * Math.Max(0, Math.Min(100, Percent)) / 100;
            using (SolidBrush b = new SolidBrush(BarColor))
                e.Graphics.FillRectangle(b, 0, 0, w, Height);
        }
    }

    class MainForm : Form
    {
        static readonly Color Bg = Color.FromArgb(14, 17, 22), Card = Color.FromArgb(23, 27, 34),
            Fg = Color.FromArgb(230, 233, 238), Dim = Color.FromArgb(139, 147, 161),
            Red = Color.FromArgb(255, 93, 93), Amber = Color.FromArgb(255, 180, 84),
            Green = Color.FromArgb(76, 195, 138);

        const double FadeSec = 10, DropSec = 60, LostSec = 4;

        readonly Scanner scanner = new Scanner();
        readonly HashSet<string> baseline = new HashSet<string>();
        readonly List<Dev> shown = new List<Dev>();
        bool onlyNew, sound, tracking;
        bool suspectsOnly = true;
        const double NearDbm = -70; // 미분류 신호는 이보다 강할 때만 표시
        string trackKey = "";
        string trackName = "";

        Label status, trackTitle, trackRssi, trackHint;
        Button scanBtn, newBtn, soundBtn, filterBtn;
        Panel trackPanel;
        BarPanel trackBar;
        ListView list;
        readonly System.Windows.Forms.Timer tick = new System.Windows.Forms.Timer();
        readonly System.Windows.Forms.Timer beep = new System.Windows.Forms.Timer();

        public MainForm()
        {
            Text = "BLE 감시";
            ClientSize = new Size(960, 720);
            MinimumSize = new Size(700, 500);
            BackColor = Bg;
            ForeColor = Fg;
            Font = new Font("Malgun Gothic", 10f);
            StartPosition = FormStartPosition.CenterScreen;

            Label title = new Label();
            title.Text = "BLE 감시";
            title.Font = new Font("Malgun Gothic", 18f, FontStyle.Bold);
            title.Dock = DockStyle.Top;
            title.Height = 44;
            title.Padding = new Padding(10, 6, 0, 0);

            status = new Label();
            status.Dock = DockStyle.Top;
            status.Height = 28;
            status.ForeColor = Dim;
            status.Padding = new Padding(12, 2, 0, 0);

            FlowLayoutPanel buttons = new FlowLayoutPanel();
            buttons.Dock = DockStyle.Top;
            buttons.Height = 48;
            buttons.Padding = new Padding(8, 4, 0, 0);
            scanBtn = MakeButton("스캔 중지");
            Button baseBtn = MakeButton("기준선 저장");
            newBtn = MakeButton("새 신호만");
            filterBtn = MakeButton("의심 기기만 ✓");
            filterBtn.Width = 150;
            Button infoBtn = MakeButton("안내");
            buttons.Controls.AddRange(new Control[] { scanBtn, baseBtn, newBtn, filterBtn, infoBtn });
            filterBtn.Click += delegate { suspectsOnly = !suspectsOnly; RefreshView(); };

            scanBtn.Click += delegate
            {
                if (scanner.Running) scanner.Stop(); else scanner.Start();
                RefreshView();
            };
            baseBtn.Click += delegate
            {
                lock (scanner.Devs)
                {
                    baseline.Clear();
                    foreach (string a in scanner.Devs.Keys) baseline.Add(a);
                }
                onlyNew = true;
                RefreshView();
            };
            newBtn.Click += delegate
            {
                onlyNew = !onlyNew;
                if (onlyNew && baseline.Count == 0)
                    lock (scanner.Devs) { foreach (string a in scanner.Devs.Keys) baseline.Add(a); }
                RefreshView();
            };
            infoBtn.Click += delegate { ShowInfo(); };

            trackPanel = new Panel();
            trackPanel.Dock = DockStyle.Top;
            trackPanel.Height = 210;
            trackPanel.BackColor = Card;
            trackPanel.Visible = false;

            trackTitle = new Label();
            trackTitle.SetBounds(14, 8, 800, 24);
            trackTitle.ForeColor = Fg;

            trackRssi = new Label();
            trackRssi.SetBounds(14, 34, 420, 76);
            trackRssi.Font = new Font("Consolas", 40f, FontStyle.Bold);

            trackHint = new Label();
            trackHint.SetBounds(440, 52, 400, 48);
            trackHint.ForeColor = Dim;
            trackHint.Font = new Font("Malgun Gothic", 11f);

            trackBar = new BarPanel();
            trackBar.SetBounds(14, 116, 820, 26);
            trackBar.Anchor = AnchorStyles.Left | AnchorStyles.Top | AnchorStyles.Right;

            soundBtn = MakeButton("소리: 꺼짐");
            soundBtn.Location = new Point(14, 156);
            Button untrackBtn = MakeButton("추적 해제");
            untrackBtn.Location = new Point(150, 156);
            soundBtn.Click += delegate { sound = !sound; beep.Enabled = sound && tracking; RefreshView(); };
            untrackBtn.Click += delegate { tracking = false; sound = false; beep.Enabled = false; RefreshView(); };
            trackPanel.Controls.AddRange(new Control[] { trackTitle, trackRssi, trackHint, trackBar, soundBtn, untrackBtn });

            list = new ListView();
            list.Dock = DockStyle.Fill;
            list.View = View.Details;
            list.FullRowSelect = true;
            list.MultiSelect = false;
            list.HideSelection = true;
            list.BackColor = Bg;
            list.ForeColor = Fg;
            list.BorderStyle = BorderStyle.None;
            list.HeaderStyle = ColumnHeaderStyle.Nonclickable;
            list.Columns.Add("종류", 110);
            list.Columns.Add("이름", 200);
            list.Columns.Add("제조사", 130);
            list.Columns.Add("주소", 150);
            list.Columns.Add("세기", 80);
            list.Columns.Add("게이지", 190);
            list.Columns.Add("마지막", 60);
            PropertyInfo db = typeof(Control).GetProperty("DoubleBuffered",
                BindingFlags.Instance | BindingFlags.NonPublic);
            if (db != null) db.SetValue(list, true, null);
            list.OwnerDraw = true;
            list.DrawColumnHeader += delegate(object s, DrawListViewColumnHeaderEventArgs e) { e.DrawDefault = true; };
            list.DrawSubItem += delegate(object s, DrawListViewSubItemEventArgs e)
            {
                using (SolidBrush bg = new SolidBrush(Bg)) e.Graphics.FillRectangle(bg, e.Bounds);
                Dev dv = e.Item.Tag as Dev;
                if (e.ColumnIndex == 5 && dv != null)
                {
                    Rectangle r = new Rectangle(e.Bounds.X + 4, e.Bounds.Y + 5, e.Bounds.Width - 12, e.Bounds.Height - 10);
                    using (SolidBrush back = new SolidBrush(Color.FromArgb(40, 46, 56))) e.Graphics.FillRectangle(back, r);
                    using (SolidBrush fill = new SolidBrush(e.Item.ForeColor == Fg ? Green : e.Item.ForeColor))
                        e.Graphics.FillRectangle(fill, r.X, r.Y, r.Width * Percent(dv.Rssi) / 100, r.Height);
                }
                else
                {
                    TextRenderer.DrawText(e.Graphics, e.SubItem.Text, list.Font,
                        new Rectangle(e.Bounds.X + 2, e.Bounds.Y, e.Bounds.Width - 4, e.Bounds.Height), e.Item.ForeColor,
                        TextFormatFlags.Left | TextFormatFlags.VerticalCenter | TextFormatFlags.EndEllipsis | TextFormatFlags.NoPrefix);
                }
            };
            list.MouseClick += delegate(object s, MouseEventArgs e)
            {
                ListViewItem it = list.GetItemAt(e.X, e.Y);
                if (it == null || !(it.Tag is Dev)) return;
                Dev d = (Dev)it.Tag;
                trackKey = d.Key;
                trackName = d.Name.Length == 0 ? "(이름 없음)" : d.Name;
                tracking = true;
                beep.Enabled = sound;
                RefreshView();
            };

            // Dock은 나중에 추가한 것이 위로 오므로 역순으로 넣는다.
            Controls.Add(list);
            Controls.Add(trackPanel);
            Controls.Add(buttons);
            Controls.Add(status);
            Controls.Add(title);

            tick.Interval = 600;
            tick.Tick += delegate { RefreshView(); };
            beep.Interval = 1000;
            beep.Tick += delegate { Beep(); };

            Shown += delegate { scanner.Start(); tick.Start(); RefreshView(); };
            FormClosing += delegate { tick.Stop(); beep.Stop(); scanner.Stop(); };
        }

        Button MakeButton(string text)
        {
            Button b = new Button();
            b.Text = text;
            b.Size = new Size(128, 36);
            b.FlatStyle = FlatStyle.Flat;
            b.BackColor = Card;
            b.ForeColor = Fg;
            b.FlatAppearance.BorderColor = Color.FromArgb(60, 68, 82);
            return b;
        }

        void Beep()
        {
            if (!sound || !tracking) return;
            double rssi;
            lock (scanner.Devs)
            {
                Dev d;
                if (!scanner.Devs.TryGetValue(trackKey, out d)
                    || (DateTime.UtcNow - d.Last).TotalSeconds > LostSec)
                {
                    beep.Interval = 1500;
                    return;
                }
                rssi = d.Rssi;
            }
            double t = Math.Max(0, Math.Min(1, (rssi + 90) / 50.0));
            beep.Interval = (int)(1400 - 1250 * t); // 가까울수록 빠르게
            ThreadPool.QueueUserWorkItem(delegate { try { Console.Beep(1400, 30); } catch (Exception) { } });
        }

        void RefreshView()
        {
            DateTime now = DateTime.UtcNow;
            int suspects = 0, hidden = 0;
            Dev tracked = null;
            shown.Clear();
            lock (scanner.Devs)
            {
                List<string> drop = new List<string>();
                foreach (Dev d in scanner.Devs.Values)
                {
                    double age = (now - d.Last).TotalSeconds;
                    if (age > DropSec)
                    {
                        if (!(tracking && d.Key == trackKey)) drop.Add(d.Key);
                        continue;
                    }
                    if (tracking && d.Key == trackKey) tracked = d;
                    if (onlyNew && baseline.Contains(d.Key)) continue;
                    if (suspectsOnly && !(tracking && d.Key == trackKey) && !Relevant(d)) { hidden++; continue; }
                    shown.Add(d);
                    if (d.Cat == Classifier.Glasses) suspects++;
                }
                foreach (string a in drop) scanner.Devs.Remove(a);
            }
            shown.Sort(delegate(Dev a, Dev b)
            {
                bool ga = a.Cat == Classifier.Glasses, gb = b.Cat == Classifier.Glasses;
                if (ga != gb) return ga ? -1 : 1;
                return b.Rssi.CompareTo(a.Rssi);
            });

            list.BeginUpdate();
            list.Items.Clear();
            foreach (Dev d in shown)
            {
                double age = (now - d.Last).TotalSeconds;
                ListViewItem it = new ListViewItem(Classifier.Label(d.Cat));
                it.Tag = d;
                it.SubItems.Add(d.Name.Length == 0 ? "(이름 없음)" : d.Name);
                it.SubItems.Add(d.Ids.Length > 0 ? Classifier.Company(d.Ids[0]) : "");
                string extra = "";
                if (baseline.Count > 0 && !baseline.Contains(d.Key)) extra = " 새";
                if (tracking && d.Key == trackKey) extra += " ◀";
                it.SubItems.Add(FormatAddr(d.Addr) + extra);
                it.SubItems.Add(Math.Round(d.Rssi) + " dBm");
                it.SubItems.Add("");
                it.SubItems.Add((int)age + "초");
                if (age > FadeSec) it.ForeColor = Color.FromArgb(90, 96, 108);
                else if (d.Cat == Classifier.Glasses) it.ForeColor = Red;
                else it.ForeColor = StrengthColor(d.Rssi, Fg);
                list.Items.Add(it);
            }
            list.EndUpdate();

            scanBtn.Text = scanner.Running ? "스캔 중지" : "스캔 시작";
            newBtn.Text = onlyNew ? "새 신호만 ✓" : "새 신호만";
            filterBtn.Text = suspectsOnly ? "의심 기기만 ✓" : "의심 기기만";
            soundBtn.Text = sound ? "소리: 켜짐" : "소리: 꺼짐";

            if (scanner.Problem.Length > 0)
            {
                status.ForeColor = Amber;
                status.Text = scanner.Problem;
            }
            else
            {
                status.ForeColor = suspects > 0 ? Red : Dim;
                string s = (scanner.Running ? "스캔 중" : "정지") + " · 표시 " + shown.Count
                    + "개 · 안경 의심 " + suspects + "개";
                if (hidden > 0) s += " · 숨김 " + hidden + "개";
                if (baseline.Count > 0) s += " · 기준선 " + baseline.Count + "개";
                s += " · 줄을 누르면 추적 · ※ 연결 중 광고를 끄는 기기는 안 잡힘";
                status.Text = s;
            }

            trackPanel.Visible = tracking;
            if (!tracking) return;
            trackTitle.Text = "추적 중: " + trackName + (tracked != null ? "  " + FormatAddr(tracked.Addr) : "");
            if (tracked == null || (now - tracked.Last).TotalSeconds > LostSec)
            {
                trackRssi.Text = "--";
                trackRssi.ForeColor = Dim;
                trackBar.Percent = 0;
                trackHint.Text = "신호 끊김. 멀어졌거나 기기 주소가\n바뀌었을 수 있습니다. 다시 골라 주세요.";
            }
            else
            {
                trackRssi.Text = Math.Round(tracked.Rssi) + " dBm";
                trackRssi.ForeColor = StrengthColor(tracked.Rssi, Green);
                trackBar.Percent = Percent(tracked.Rssi);
                trackBar.BarColor = StrengthColor(tracked.Rssi, Green);
                trackHint.Text = Proximity(tracked.Rssi);
            }
            trackBar.Invalidate();
        }

        void ShowInfo()
        {
            MessageBox.Show(this,
                "주변 기기가 내보내는 BLE 광고 신호를 받아 목록과 세기로 보여 줍니다. "
                + "아무 기기에도 접속하지 않고, 통신 내용을 읽지 않습니다.\n\n"
                + "잡히는 것\n"
                + "· BLE 광고를 내는 기기: 일부 스마트 안경, 무선 이어폰, 워치, 폰, PC\n\n"
                + "못 잡는 것\n"
                + "· 폰과 연결된 뒤 광고를 끄는 기기. 스마트 안경 중에도 이런 모델이 있을 수 있고, 그 경우 아무 신호도 뜨지 않습니다\n"
                + "· 유도 루프형 초소형 이어폰의 귓속 수신부\n"
                + "· 녹화만 하는 카메라, 전원이 꺼진 기기\n\n"
                + "'의심 기기만'을 켜면 가전·TV·PC로 보이는 신호와, 종류를 모르는 약한 신호(-70 dBm 미만)를 숨깁니다. 종류를 모르는 강한 신호는 계속 보여 줍니다.\n\n"
                + "주의\n"
                + "· 종류 표시는 제조사 ID와 이름으로 한 추정입니다. '안경 의심'에는 같은 제조사의 다른 기기가 섞일 수 있습니다.\n"
                + "· 세기는 사람 몸과 벽에 따라 크게 흔들립니다. 거리 표시는 어림값입니다.\n"
                + "· 기기 주소는 약 15분마다 바뀔 수 있어, 기준선에 넣은 기기가 새 신호로 다시 나타날 수 있습니다.\n"
                + "· 신호가 잡힌 것은 확인이 필요하다는 단서이지 부정행위의 증거가 아닙니다. 신호가 없다고 기기가 없는 것도 아닙니다.",
                "이 프로그램이 하는 일과 한계", MessageBoxButtons.OK, MessageBoxIcon.Information);
        }

        public static string FormatAddr(ulong a)
        {
            string h = a.ToString("X12");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 12; i += 2)
            {
                if (i > 0) sb.Append(':');
                sb.Append(h, i, 2);
            }
            return sb.ToString();
        }

        static bool Relevant(Dev d)
        {
            if (d.Cat == Classifier.Harmless) return false;
            if (d.Cat == Classifier.Unknown) return d.Rssi >= NearDbm;
            return true;
        }

        static int Percent(double rssi)
        {
            return (int)Math.Max(0, Math.Min(100, (rssi + 100) / 65.0 * 100));
        }

        static Color StrengthColor(double rssi, Color weak)
        {
            if (rssi >= -55) return Red;
            if (rssi >= -70) return Amber;
            return weak;
        }

        static string Proximity(double rssi)
        {
            if (rssi >= -50) return "매우 가까움 (어림 1m 안쪽)";
            if (rssi >= -65) return "가까움 (어림 수 m)";
            if (rssi >= -80) return "중간 거리";
            return "멂 또는 가려짐";
        }
    }

    static class Program
    {
        [STAThread]
        static void Main(string[] args)
        {
            // 시험용: BleWatchPC.exe --probe <초> <결과파일>  (창 없이 스캔 결과만 기록)
            if (args.Length == 3 && args[0] == "--probe")
            {
                Scanner s = new Scanner();
                s.Start();
                Thread.Sleep(int.Parse(args[1]) * 1000);
                s.Stop();
                StringBuilder sb = new StringBuilder();
                sb.AppendLine("problem=" + s.Problem);
                lock (s.Devs)
                {
                    sb.AppendLine("count=" + s.Devs.Count);
                    foreach (Dev d in s.Devs.Values)
                        sb.AppendLine(Classifier.Label(d.Cat) + " | " + d.Name + " | "
                            + (d.Ids.Length > 0 ? Classifier.Company(d.Ids[0]) : "") + " | "
                            + Math.Round(d.Rssi) + " dBm");
                }
                File.WriteAllText(args[2], sb.ToString(), Encoding.UTF8);
                return;
            }
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            Application.Run(new MainForm());
        }
    }
}
