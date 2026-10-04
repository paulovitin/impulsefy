package com.impulsefy;

import android.app.Activity;
import android.app.Dialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.AbsListView;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;
import org.json.JSONArray;
import org.json.JSONObject;
import java.net.URI;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** A native, landscape Spotify surface with no embedded browser or UI framework. */
public final class MainActivity extends Activity {
    private static final int BG = 0xff0a0e13, CARD = 0xff121820, TEXT = 0xfff3f6f8;
    private static final int MUTED = 0xff8c9aa8, ACCENT = 0xff4ee6c9;
    private static final int SURFACE_2 = 0xff1a222c, LINE = 0xff243039, ACCENT_DIM = 0xff133731, INK = 0xff06241f;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newFixedThreadPool(2);
    private final List<Row> rows = new ArrayList<>();
    private final Map<String, View> navigation = new HashMap<>();
    private SharedPreferences preferences;
    private AuthManager auth;
    private SpotifyApi api;
    private Artwork artwork;
    private PlayerService player;
    private Typeface regular, medium;
    private boolean bound, visible, connecting, premiumAllowed = true;
    private volatile boolean destroyed, demo, mainScreen;
    private boolean playlistScreen, seeking;
    private volatile int generation;
    private volatile int sessionGeneration;
    private String selected = "home", nextPath, lastPath, lastKind, accountName = "Sua conta", accountId = "", accountProduct = "";
    private Row openedPlaylist, playingPlaylist;
    private int totalItems = -1;
    private boolean searchResults;
    private boolean lastAppend;
    private JSONObject playerState = new JSONObject();
    private long stateAt, pairingExpiresAt;
    private volatile int pairingGeneration;
    private LinearLayout center, pageHeading, listHeading, searchSuggestions;
    private float designScale;
    private FrameLayout qrFrame;
    private TextView pageTitle, pageSubtitle, listLabel, status, account, accountAvatar, clock, previewBadge, collectionEyebrow, homeTitle, homeSubtitle;
    private TextView playerTitle, playerArtist, playerAlbum, playerStatus, elapsed, duration, qrStatus, qrCode, qrPlaceholder;
    private ImageView cover, qrImage;
    private GlyphView playButton, previousButton, nextButton, shuffleButton, repeatButton;
    private LinearLayout likeAction, homeAction;
    private SeekBar volume;
    private boolean changingVolume;
    private int likesRevision;
    private String libraryKind = "playlist", searchKind = "track", searchTerm = "";
    private final Map<String, Boolean> savedTracks = new java.util.LinkedHashMap<String, Boolean>(128, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Boolean> entry) { return size() > 1000; }
    };
    private final java.util.Set<String> savingTracks = new java.util.HashSet<>();
    private final java.util.Set<String> checkingTracks = new java.util.HashSet<>();
    private SeekBar seek;
    private Button moreButton, retryButton, pairingButton;
    private EditText query;
    private RowAdapter adapter;
    private AbsListView list;
    private View backButton;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            if (!bound || destroyed || demo || !mainScreen || !auth.isSignedIn()) return;
            player = ((PlayerService.LocalBinder) binder).getService();
            PlayerService source = player; AuthManager owner = auth; int session = sessionGeneration;
            player.setListener(state -> main.post(() -> {
                if (player == source && auth == owner && session == sessionGeneration) acceptPlayerState(state);
            }));
            acceptPlayerState(player.snapshot());
            connectPlayer();
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            player = null; connecting = false;
            if (mainScreen && !demo) setPlayerMessage("Conexão interrompida. Toque em reproduzir para tentar de novo.");
        }
    };

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (!visible || destroyed) return;
            if (clock != null) clock.setText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date()));
            if (mainScreen) updateProgress();
            else if (pairingExpiresAt > 0 && qrStatus != null) {
                long left = Math.max(0, (pairingExpiresAt - System.currentTimeMillis()) / 1000);
                qrStatus.setText(left > 0 ? "Aponte a câmera do celular · expira em " + formatTime(left * 1000) : "Este QR expirou. Gere um novo para conectar.");
                if (left == 0 && pairingButton != null) setPairingAction("Gerar novo QR");
            }
            main.postDelayed(this, 1000);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        preferences = getSharedPreferences("impulsefy_ui", MODE_PRIVATE);
        Typeface inter = Typeface.createFromAsset(getAssets(), "fonts/inter.ttf");
        regular = Build.VERSION.SDK_INT >= 28 ? Typeface.create(inter, 500, false) : inter;
        medium = regular;
        artwork = new Artwork(this);
        configureAuth();
        demo = BuildConfig.DEBUG && getIntent().getBooleanExtra("demo", false);
        if (demo || auth.isSignedIn()) showMain(); else showLogin();
    }

    private void configureAuth() {
        sessionGeneration++; connecting = false;
        if (auth != null) auth.close();
        auth = new AuthManager(this);
        AuthManager owner = auth; int session = sessionGeneration;
        api = new SpotifyApi(auth, (method, path, body) -> {
            if (owner != auth || session != sessionGeneration || !owner.isSignedIn()) throw new Exception("Sua sessão mudou. Entre novamente.");
            PlayerService source = player;
            if (source == null) throw new Exception("Conectando ao Spotify. Tente novamente em instantes.");
            return source.request(method, path, body);
        });
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent);
        if (BuildConfig.DEBUG && intent.hasExtra("demo")) {
            auth.cancel(); pairingGeneration++; demo = intent.getBooleanExtra("demo", false);
            if (demo || auth.isSignedIn()) showMain(); else showLogin();
        }
    }
    @Override protected void onStart() { super.onStart(); visible = true; main.removeCallbacks(ticker); main.post(ticker); }
    @Override protected void onStop() { visible = false; main.removeCallbacks(ticker); super.onStop(); }
    @Override protected void onDestroy() {
        destroyed = true; generation++; pairingGeneration++;
        main.removeCallbacksAndMessages(null);
        if (player != null) player.setListener(null);
        if (bound) unbindService(connection);
        if (auth != null) auth.close();
        artwork.close(); network.shutdownNow();
        super.onDestroy();
    }

    private void showLogin() {
        mainScreen = false; demo = false; pairingExpiresAt = 0; generation++;
        navigation.clear();
        designScale = Math.min(1f, Math.min(getResources().getConfiguration().screenWidthDp / 1920f,
                getResources().getConfiguration().screenHeightDp / 720f));
        final int background = 0xff0a0e13, surface = 0xff121820, text = 0xfff3f6f8;
        final int muted = 0xff8c9aa8, accent = 0xff4ee6c9, ink = 0xff06241f;
        LinearLayout root = vertical(); root.setPadding(uiDp(36), uiDp(24), uiDp(36), uiDp(28)); root.setBackgroundColor(background);
        LinearLayout heading = horizontal(); heading.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout logo = horizontal(); logo.setGravity(Gravity.CENTER_VERTICAL);
        ImageView mark = new ImageView(this); mark.setImageResource(R.drawable.ic_brand); mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams markParams = new LinearLayout.LayoutParams(uiDp(44), uiDp(44)); markParams.rightMargin = uiDp(12); logo.addView(mark, markParams);
        LinearLayout wordmark = vertical();
        TextView name = uiLabel("Impulsefy", 20, text, 600); name.setLetterSpacing(-.02f); wordmark.addView(name);
        TextView tagline = uiLabel("PARA HAVAL H6", 10, muted, 600); tagline.setLetterSpacing(.16f); wordmark.addView(tagline);
        logo.addView(wordmark); heading.addView(logo, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout notice = horizontal(); notice.setGravity(Gravity.CENTER_VERTICAL); notice.setPadding(uiDp(14), uiDp(8), uiDp(14), uiDp(8)); notice.setBackground(shape(surface, 0, 999));
        GlyphView warning = new GlyphView(this, "alert", muted);
        LinearLayout.LayoutParams warningParams = new LinearLayout.LayoutParams(uiDp(24), uiDp(24)); warningParams.rightMargin = uiDp(8); notice.addView(warning, warningParams);
        notice.addView(uiLabel("Configure com o carro parado", 14, muted, 500)); heading.addView(notice);
        clock = uiLabel(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date()), 26, text, 500);
        LinearLayout.LayoutParams clockParams = new LinearLayout.LayoutParams(-2, -2); clockParams.leftMargin = uiDp(16); heading.addView(clock, clockParams);
        root.addView(heading, new LinearLayout.LayoutParams(-1, uiDp(44)));
        LinearLayout body = horizontal(); LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(-1, 0, 1); bodyParams.topMargin = uiDp(20); root.addView(body, bodyParams);

        LinearLayout panel = horizontal(); panel.setGravity(Gravity.CENTER_VERTICAL); panel.setPadding(uiDp(40), uiDp(40), uiDp(40), uiDp(40)); panel.setBackground(shape(surface, 0, Math.round(28 * designScale)));
        body.addView(panel, new LinearLayout.LayoutParams(0, -1, 1));
        LinearLayout copy = vertical(); panel.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
        TextView title = uiLabel("Conecte. Dê play. Vá.", 36, text, 600); title.setLetterSpacing(-.6f / 36); copy.addView(title);
        TextView subtitle = uiLabel("Só uma vez, pelo seu celular.", 17, muted, 400);
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(-1, -2); subtitleParams.topMargin = uiDp(4); copy.addView(subtitle, subtitleParams);
        LinearLayout codePill = horizontal(); codePill.setGravity(Gravity.CENTER_VERTICAL); codePill.setPadding(uiDp(18), uiDp(10), uiDp(18), uiDp(10)); codePill.setBackground(shape(0x264ee6c9, 0, 999));
        TextView codeLabel = uiLabel("CÓDIGO", 12, accent, 600); codeLabel.setLetterSpacing(1.6f / 12); codePill.addView(codeLabel);
        qrCode = uiLabel("••••••", 20, accent, 700); qrCode.setLetterSpacing(.15f);
        LinearLayout.LayoutParams valueParams = new LinearLayout.LayoutParams(-2, -2); valueParams.leftMargin = uiDp(10); codePill.addView(qrCode, valueParams);
        LinearLayout.LayoutParams codeParams = new LinearLayout.LayoutParams(-2, -2); codeParams.topMargin = uiDp(28); copy.addView(codePill, codeParams);
        qrStatus = uiLabel("Preparando uma conexão segura…", 14, muted, 400); qrStatus.setMaxLines(3);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-1, -2); statusParams.topMargin = uiDp(8); copy.addView(qrStatus, statusParams);
        pairingButton = button("Preparando…", true, v -> startPairing()); pairingButton.setTextSize(17 * designScale); pairingButton.setTypeface(uiTypeface(600)); pairingButton.setTextColor(ink); pairingButton.setBackground(ripple(accent, 0, 999));
        LinearLayout.LayoutParams connectParams = new LinearLayout.LayoutParams(-1, Math.max(dp(48), uiDp(64))); connectParams.topMargin = uiDp(28); copy.addView(pairingButton, connectParams);
        Button preview = button("Conhecer a interface", false, v -> { auth.cancel(); pairingGeneration++; demo = true; showMain(); });
        preview.setTextSize(17 * designScale); preview.setTextColor(text); preview.setBackground(ripple(0xff1a222c, 0, 999));
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(-1, Math.max(dp(48), uiDp(64))); previewParams.topMargin = uiDp(12); copy.addView(preview, previewParams);
        qrFrame = new FrameLayout(this); qrFrame.setBackground(shape(Color.WHITE, 0, Math.round(22 * designScale))); qrFrame.setClipToOutline(true);
        LinearLayout.LayoutParams qrParams = new LinearLayout.LayoutParams(uiDp(320), uiDp(320)); qrParams.leftMargin = uiDp(36); panel.addView(qrFrame, qrParams);
        qrImage = new ImageView(this); qrImage.setScaleType(ImageView.ScaleType.FIT_CENTER); qrImage.setContentDescription("QR para conectar sua conta Spotify");
        qrFrame.addView(qrImage, new FrameLayout.LayoutParams(-1, -1));
        qrPlaceholder = uiLabel("Preparando QR…", 17, background, 500); qrPlaceholder.setGravity(Gravity.CENTER); qrPlaceholder.setPadding(uiDp(20), 0, uiDp(20), 0);
        qrFrame.addView(qrPlaceholder, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout hero = vertical(); hero.setGravity(Gravity.BOTTOM);
        LinearLayout.LayoutParams heroParams = new LinearLayout.LayoutParams(0, -1, 1); heroParams.leftMargin = uiDp(32); body.addView(hero, heroParams);
        LinearLayout heroCopy = vertical(); heroCopy.setPadding(uiDp(8), 0, 0, 0);
        TextView eyebrow = uiLabel("BEM-VINDO A BORDO", 12, accent, 600); eyebrow.setLetterSpacing(2f / 12); heroCopy.addView(eyebrow);
        TextView headline = uiLabel("Sua música.\nSeu caminho.", 64, text, 600); headline.setLetterSpacing(-2f / 64); headline.setLineSpacing(0, 1.05f);
        LinearLayout.LayoutParams headlineParams = new LinearLayout.LayoutParams(-1, -2); headlineParams.topMargin = uiDp(14); heroCopy.addView(headline, headlineParams);
        TextView description = uiLabel("Seu Spotify simplificado para o carro. Playlists, curtidas e busca, sem complicação.", 19, muted, 400);
        LinearLayout.LayoutParams descriptionParams = new LinearLayout.LayoutParams(uiDp(620), -2); descriptionParams.topMargin = uiDp(14); heroCopy.addView(description, descriptionParams);
        hero.addView(heroCopy, new LinearLayout.LayoutParams(-1, -2));
        FrameLayout scenery = new FrameLayout(this); scenery.setBackground(shape(surface, 0, Math.round(22 * designScale))); scenery.setClipToOutline(true);
        ImageView photograph = new ImageView(this); photograph.setScaleType(ImageView.ScaleType.CENTER_CROP); photograph.setImageResource(R.drawable.login_road); photograph.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        scenery.addView(photograph, new FrameLayout.LayoutParams(-1, -1));
        View shade = new View(this); shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xf20a0e13, 0xcc0a0e13, 0x330a0e13})); scenery.addView(shade, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout steps = vertical(); steps.setGravity(Gravity.CENTER_VERTICAL);
        steps.addView(loginStep("1", "Leia o QR com a câmera do celular"));
        LinearLayout.LayoutParams stepParams = new LinearLayout.LayoutParams(-1, uiDp(30)); stepParams.topMargin = uiDp(12); steps.addView(loginStep("2", "Entre no Spotify e confirme"), stepParams);
        LinearLayout.LayoutParams lastStepParams = new LinearLayout.LayoutParams(-1, uiDp(30)); lastStepParams.topMargin = uiDp(12); steps.addView(loginStep("3", "O carro recebe a autorização sozinho"), lastStepParams);
        FrameLayout.LayoutParams stepsParams = new FrameLayout.LayoutParams(-1, -1); stepsParams.leftMargin = uiDp(28); stepsParams.rightMargin = uiDp(20); scenery.addView(steps, stepsParams);
        LinearLayout.LayoutParams sceneryParams = new LinearLayout.LayoutParams(-1, uiDp(220)); sceneryParams.topMargin = uiDp(24); hero.addView(scenery, sceneryParams);
        TextView premium = uiLabel("Reprodução com Spotify Premium · Sem app auxiliar, sem copiar URLs.", 14, muted, 400);
        LinearLayout.LayoutParams premiumParams = new LinearLayout.LayoutParams(-1, -2); premiumParams.topMargin = uiDp(24); hero.addView(premium, premiumParams);
        setContentView(root);
        startPairing();
    }

    private int uiDp(float value) { return dp(value * designScale); }
    private Typeface uiTypeface(int weight) {
        return Build.VERSION.SDK_INT >= 28 ? Typeface.create(regular, weight, false)
                : Typeface.create(regular, weight >= 600 ? Typeface.BOLD : Typeface.NORMAL);
    }
    private TextView uiLabel(String value, float size, int color, int weight) {
        TextView view = label(value, size * designScale, color); view.setTypeface(uiTypeface(weight)); return view;
    }
    private View loginStep(String number, String title) {
        LinearLayout row = horizontal(); row.setGravity(Gravity.CENTER_VERTICAL);
        TextView index = uiLabel(number, 14, 0xff06241f, 700); index.setGravity(Gravity.CENTER); index.setBackground(shape(0xff4ee6c9, 0, 999));
        LinearLayout.LayoutParams indexParams = new LinearLayout.LayoutParams(uiDp(30), uiDp(30)); indexParams.rightMargin = uiDp(12); row.addView(index, indexParams);
        row.addView(uiLabel(title, 17, 0xfff3f6f8, 500), new LinearLayout.LayoutParams(0, -2, 1)); return row;
    }
    private void setPairingAction(String title) {
        android.text.SpannableString text = new android.text.SpannableString("\uFFFC  " + title);
        android.graphics.drawable.Drawable icon = getDrawable(R.drawable.ic_refresh);
        icon.setBounds(0, 0, uiDp(20), uiDp(20));
        text.setSpan(new android.text.style.ImageSpan(icon, android.text.style.ImageSpan.ALIGN_BOTTOM), 0, 1, 0);
        pairingButton.setText(text); pairingButton.setContentDescription(title);
    }

    private void startPairing() {
        auth.cancel(); int attempt = ++pairingGeneration; AuthManager owner = auth;
        pairingExpiresAt = 0; qrStatus.setText("Preparando uma conexão segura…");
        qrCode.setText("••••••"); qrImage.setImageDrawable(null); qrPlaceholder.setText("Preparando QR…"); qrPlaceholder.setVisibility(View.VISIBLE);
        pairingButton.setText("Preparando…"); pairingButton.setContentDescription("Preparando QR"); pairingButton.setEnabled(false);
        owner.start(new AuthManager.Listener() {
            @Override public void onPairing(String url, String code, long expiresAt) {
                if (destroyed || owner != auth || attempt != pairingGeneration) return;
                network.execute(() -> {
                    Bitmap bitmap = qrBitmap(url);
                    main.post(() -> {
                        if (destroyed || owner != auth || mainScreen || attempt != pairingGeneration) return;
                        pairingExpiresAt = expiresAt < 1_000_000_000_000L ? expiresAt * 1000 : expiresAt;
                        if (bitmap == null) {
                            pairingExpiresAt = 0; qrPlaceholder.setText("QR indisponível");
                            qrStatus.setText("Não foi possível exibir o QR. Tente novamente."); setPairingAction("Tentar novamente"); pairingButton.setEnabled(true); return;
                        }
                        qrPlaceholder.setVisibility(View.GONE); qrImage.setImageBitmap(bitmap);
                        qrCode.setText(code == null ? "" : code);
                        qrStatus.setText("Aponte a câmera do celular · expira em " + formatTime(Math.max(0, pairingExpiresAt - System.currentTimeMillis())));
                        setPairingAction("Gerar outro QR"); pairingButton.setEnabled(true);
                    });
                });
            }
            @Override public void onConnected() {
                main.post(() -> {
                    if (destroyed || owner != auth || attempt != pairingGeneration) return;
                    demo = false; premiumAllowed = true; showMain();
                });
            }
            @Override public void onError(String message) {
                main.post(() -> {
                    if (destroyed || owner != auth || mainScreen || attempt != pairingGeneration) return;
                    pairingExpiresAt = 0;
                    qrImage.setImageDrawable(null); qrPlaceholder.setText("Gere um novo QR"); qrPlaceholder.setVisibility(View.VISIBLE);
                    qrStatus.setText(message == null || message.isEmpty() ? "Não foi possível conectar. Tente novamente." : message);
                    setPairingAction("Tentar novamente"); pairingButton.setEnabled(true);
                });
            }
        });
    }

    private Bitmap qrBitmap(String value) {
        try {
            BitMatrix matrix = new MultiFormatWriter().encode(value, BarcodeFormat.QR_CODE, 512, 512,
                    Collections.singletonMap(EncodeHintType.MARGIN, 3));
            int[] pixels = new int[512 * 512];
            for (int y = 0; y < 512; y++) for (int x = 0; x < 512; x++) pixels[y * 512 + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
            return Bitmap.createBitmap(pixels, 512, 512, Bitmap.Config.ARGB_8888);
        } catch (Exception ignored) { return null; }
    }

    private void showMain() {
        mainScreen = true; pairingExpiresAt = 0; playlistScreen = false;
        designScale = Math.min(1f, Math.min(getResources().getConfiguration().screenWidthDp / 1920f,
                getResources().getConfiguration().screenHeightDp / 720f));
        LinearLayout root = horizontal(); root.setBackgroundColor(BG);
        root.addView(makePlayer(), new LinearLayout.LayoutParams(uiDp(640), -1));
        LinearLayout work = vertical(); work.setPadding(uiDp(36), uiDp(20), uiDp(36), uiDp(28));
        root.addView(work, new LinearLayout.LayoutParams(0, -1, 1));
        LinearLayout top = horizontal(); top.setGravity(Gravity.CENTER_VERTICAL);
        HorizontalScrollView navScroll = new HorizontalScrollView(this); navScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout tabs = horizontal(); tabs.setPadding(uiDp(6), uiDp(6), uiDp(6), uiDp(6)); tabs.setBackground(shape(CARD, 0, 999));
        navigation.clear(); addNav(tabs, "home", "Início", "home"); addNav(tabs, "library", "Biblioteca", "library");
        addNav(tabs, "liked", "Curtidas", "heart"); addNav(tabs, "search", "Buscar", "search");
        navScroll.addView(tabs); top.addView(navScroll, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout profile = horizontal(); profile.setGravity(Gravity.CENTER_VERTICAL); profile.setPadding(uiDp(8), uiDp(8), uiDp(16), uiDp(8));
        profile.setBackground(ripple(CARD, 0, 999)); profile.setOnClickListener(v -> settings()); profile.setContentDescription("Configurações da conta"); profile.setFocusable(true);
        accountAvatar = avatar(initial(demo ? Demo.NAME : accountName), 15);
        profile.addView(accountAvatar, new LinearLayout.LayoutParams(uiDp(34), uiDp(34)));
        account = uiLabel(demo ? Demo.NAME : accountName, 15, TEXT, 500); oneLine(account); account.setMaxWidth(uiDp(145));
        LinearLayout.LayoutParams accountParams = new LinearLayout.LayoutParams(-2, -2); accountParams.leftMargin = uiDp(10); profile.addView(account, accountParams);
        LinearLayout.LayoutParams profileParams = new LinearLayout.LayoutParams(-2, -2); profileParams.leftMargin = uiDp(12); top.addView(profile, profileParams);
        clock = uiLabel(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date()), 26, TEXT, 500);
        LinearLayout.LayoutParams timeParams = new LinearLayout.LayoutParams(-2, -2); timeParams.leftMargin = uiDp(16); top.addView(clock, timeParams);
        work.addView(top, new LinearLayout.LayoutParams(-1, uiDp(62)));
        center = vertical(); LinearLayout.LayoutParams content = new LinearLayout.LayoutParams(-1, 0, 1); content.topMargin = uiDp(24); work.addView(center, content);
        setContentView(root); openScreen(selected);
        if (demo) renderDemoPlayer(); else { ensurePlayerService(); acceptPlayerState(player == null ? new JSONObject() : player.snapshot()); if (!auth.usesNativeCatalog()) loadProfile(); connectPlayer(); }
    }

    private void ensurePlayerService() {
        if (demo || !auth.isSignedIn()) return;
        Intent intent = new Intent(this, PlayerService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent); else startService(intent);
        if (!bound) bound = bindService(intent, connection, Context.BIND_AUTO_CREATE);
    }

    private void releasePlayer() {
        if (player != null) { player.setListener(null); player.logout(); }
        if (bound) { unbindService(connection); bound = false; }
        stopService(new Intent(this, PlayerService.class)); player = null;
    }

    private void addNav(LinearLayout tabs, String key, String title, String glyph) {
        LinearLayout tab = action(glyph, title, SURFACE_2, TEXT, 16, v -> openScreen(key));
        tab.setPadding(uiDp(22), 0, uiDp(22), 0);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, uiDp(50)); if (tabs.getChildCount() > 0) params.leftMargin = uiDp(8);
        tabs.addView(tab, params); navigation.put(key, tab);
    }

    private LinearLayout makePlayer() {
        LinearLayout panel = vertical(); panel.setPadding(uiDp(32), uiDp(24), uiDp(32), uiDp(24)); panel.setBackgroundColor(CARD);
        LinearLayout heading = horizontal(); heading.setGravity(Gravity.CENTER_VERTICAL); heading.addView(brand(), new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout source = horizontal(); source.setGravity(Gravity.CENTER_VERTICAL); source.setPadding(uiDp(16), uiDp(10), uiDp(16), uiDp(10)); source.setBackground(shape(ACCENT_DIM, 0, 999));
        source.addView(new GlyphView(this, "signal", ACCENT), new LinearLayout.LayoutParams(uiDp(24), uiDp(24)));
        previewBadge = uiLabel(demo ? "PRÉVIA" : "Spotify", 13, ACCENT, 500); previewBadge.setPadding(uiDp(8), 0, 0, 0); source.addView(previewBadge); heading.addView(source);
        panel.addView(heading, new LinearLayout.LayoutParams(-1, uiDp(44))); spacer(panel);
        LinearLayout now = horizontal(); now.setGravity(Gravity.CENTER_VERTICAL);
        cover = art(20); cover.setImageDrawable(new Artwork.Placeholder(0)); cover.setContentDescription("Capa da música atual");
        now.addView(cover, new LinearLayout.LayoutParams(uiDp(250), uiDp(250)));
        LinearLayout copy = vertical(); LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(0, -2, 1); copyParams.leftMargin = uiDp(24); now.addView(copy, copyParams);
        copy.addView(eyebrow("TOCANDO AGORA", ACCENT));
        playerTitle = uiLabel("O caminho pede música", 32, TEXT, 600); playerTitle.setLetterSpacing(-.5f / 32); playerTitle.setMaxLines(3); playerTitle.setEllipsize(TextUtils.TruncateAt.END); gapAdd(copy, playerTitle, 6);
        playerArtist = uiLabel("Escolha uma música para começar", 20, MUTED, 400); playerArtist.setMaxLines(2); playerArtist.setEllipsize(TextUtils.TruncateAt.END); gapAdd(copy, playerArtist, 6);
        playerAlbum = uiLabel("", 15, MUTED, 400); playerAlbum.setMaxLines(2); playerAlbum.setEllipsize(TextUtils.TruncateAt.END); gapAdd(copy, playerAlbum, 6);
        panel.addView(now, new LinearLayout.LayoutParams(-1, uiDp(250))); spacer(panel);
        LinearLayout progress = vertical(); seek = slider("Posição da música", 1000); seek.setThumbTintList(ColorStateList.valueOf(TEXT));
        progress.addView(seek, new LinearLayout.LayoutParams(-1, uiDp(44)));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean user) { if (user) elapsed.setText(formatTime(playerState.optLong("durationMs") * value / 1000)); }
            @Override public void onStartTrackingTouch(SeekBar bar) { seeking = true; }
            @Override public void onStopTrackingTouch(SeekBar bar) { seeking = false; if (canPlay()) player.seek((int) (playerState.optLong("durationMs") * bar.getProgress() / 1000)); }
        });
        LinearLayout times = horizontal(); elapsed = uiLabel("0:00", 16, MUTED, 500); duration = uiLabel("—", 16, MUTED, 500); duration.setGravity(Gravity.END);
        times.addView(elapsed, new LinearLayout.LayoutParams(0, -2, 1)); times.addView(duration, new LinearLayout.LayoutParams(0, -2, 1)); progress.addView(times);
        panel.addView(progress, new LinearLayout.LayoutParams(-1, uiDp(70)));
        LinearLayout transport = horizontal(); transport.setGravity(Gravity.CENTER_VERTICAL);
        shuffleButton = roundIcon("shuffle", "Ativar aleatório", SURFACE_2, TEXT, v -> { if (canPlay()) player.shuffle(!playerState.optBoolean("shuffle")); });
        previousButton = roundIcon("previous", "Música anterior", SURFACE_2, TEXT, v -> { if (canPlay()) player.previous(); });
        playButton = roundIcon("play", "Reproduzir", ACCENT, INK, v -> { if (canPlay()) player.toggle(); });
        nextButton = roundIcon("next", "Próxima música", SURFACE_2, TEXT, v -> { if (canPlay()) player.next(); });
        repeatButton = roundIcon("repeat", "Repetição desativada", SURFACE_2, TEXT, v -> { if (canPlay()) player.repeat((playerState.optInt("repeat") + 1) % 3); });
        View[] controls = {shuffleButton, previousButton, playButton, nextButton, repeatButton}; int[] sizes = {76, 96, 132, 96, 76};
        for (int i = 0; i < controls.length; i++) { if (i > 0) transport.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1)); transport.addView(controls[i], new LinearLayout.LayoutParams(uiDp(sizes[i]), uiDp(sizes[i]))); }
        panel.addView(transport, new LinearLayout.LayoutParams(-1, uiDp(132))); spacer(panel);
        LinearLayout secondary = horizontal(); secondary.setGravity(Gravity.CENTER_VERTICAL);
        likeAction = action("heart", "Curtir", SURFACE_2, TEXT, 17, v -> toggleLike(currentRow()));
        secondary.addView(likeAction, new LinearLayout.LayoutParams(-2, uiDp(54)));
        LinearLayout volumeBox = horizontal(); volumeBox.setGravity(Gravity.CENTER_VERTICAL); volumeBox.setPadding(uiDp(20), 0, uiDp(20), 0);
        volumeBox.addView(new GlyphView(this, "volume", MUTED), new LinearLayout.LayoutParams(uiDp(38), uiDp(44)));
        volume = slider("Volume do Impulsefy", player == null ? 100 : player.getVolumeMax()); volume.setProgress(player == null ? 100 : player.getVolume()); volume.setEnabled(!demo && player != null);
        volume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean user) { if (user && player != null) player.setVolume(value); }
            @Override public void onStartTrackingTouch(SeekBar bar) { changingVolume = true; }
            @Override public void onStopTrackingTouch(SeekBar bar) { changingVolume = false; }
        });
        volumeBox.addView(volume, new LinearLayout.LayoutParams(0, uiDp(48), 1)); secondary.addView(volumeBox, new LinearLayout.LayoutParams(0, -2, 1));
        secondary.addView(action("queue", "Fila", SURFACE_2, TEXT, 17, v -> showQueue()), new LinearLayout.LayoutParams(-2, uiDp(54)));
        panel.addView(secondary, new LinearLayout.LayoutParams(-1, uiDp(54)));
        playerStatus = uiLabel("Conectando ao Spotify…", 12, MUTED, 400); playerStatus.setGravity(Gravity.CENTER); playerStatus.setMaxLines(2);
        gapAdd(panel, playerStatus, 4); return panel;
    }

    private void openScreen(String screen) {
        if (!mainScreen) return;
        likesRevision++; savedTracks.clear();
        selected = screen; playlistScreen = false; openedPlaylist = null; searchResults = false; searchTerm = ""; searchKind = "track";
        libraryKind = "playlist"; rows.clear(); nextPath = null; lastPath = null; totalItems = -1; generation++;
        for (Map.Entry<String, View> entry : navigation.entrySet()) {
            boolean active = entry.getKey().equals(screen); LinearLayout tab = (LinearLayout) entry.getValue();
            tab.setBackground(ripple(active ? ACCENT : Color.TRANSPARENT, 0, 999));
            ((GlyphView) tab.getChildAt(0)).setColor(active ? INK : MUTED); ((TextView) tab.getChildAt(1)).setTextColor(active ? INK : TEXT); tab.setSelected(active);
        }
        renderPage();
        if (!demo && playerState.optBoolean("connected")) checkLikes(Collections.singletonList(currentRow()));
        if (screen.equals("search")) return;
        if (demo) fillDemo(screen.equals("liked"));
        else loadPage(screen.equals("liked") ? "/me/tracks?limit=30" : "/me/playlists?limit=30", screen.equals("liked") ? "track" : "playlist", false);
    }

    private void renderPage() {
        center.removeAllViews(); homeTitle = null; homeSubtitle = null; homeAction = null; query = null; collectionEyebrow = null;
        pageHeading = horizontal(); pageHeading.setGravity(Gravity.CENTER_VERTICAL);
        if (playlistScreen || selected.equals("liked")) addCollectionHeading();
        else {
            LinearLayout titles = vertical(); pageHeading.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
            pageTitle = uiLabel(selected.equals("home") ? greeting() : selected.equals("library") ? "Sua biblioteca" : "Encontre seu som", 40, TEXT, 600); pageTitle.setLetterSpacing(-.025f); oneLine(pageTitle); titles.addView(pageTitle);
            pageSubtitle = uiLabel(demo ? "Uma prévia da experiência Impulsefy." : selected.equals("home") ? "Sua música acompanha o caminho." : selected.equals("library") ? libraryKind.equals("recent") ? "O que você ouviu neste carro." : "Sua música, sempre por perto." : "Uma música para cada momento.", 17, MUTED, 400); oneLine(pageSubtitle); gapAdd(titles, pageSubtitle, 4);
            if (selected.equals("library")) {
                LinearLayout filters = horizontal();
                addFilter(filters, "Playlists", libraryKind.equals("playlist"), v -> libraryFilter("playlist"));
                addFilter(filters, "Álbuns", libraryKind.equals("album"), v -> libraryFilter("album"));
                addFilter(filters, "Artistas", libraryKind.equals("artist"), v -> libraryFilter("artist"));
                addFilter(filters, "Recentes", libraryKind.equals("recent"), v -> libraryFilter("recent"));
                pageHeading.addView(filters);
            }
            center.addView(pageHeading, new LinearLayout.LayoutParams(-1, uiDp(76)));
        }
        if (selected.equals("home") && !playlistScreen) { gap(center, 24); addHomeBanner(); }
        if (selected.equals("search") && !playlistScreen) { gap(center, 24); addSearch(); }
        if (selected.equals("search") && !playlistScreen && !searchResults) { addSearchSuggestions(); return; }
        listHeading = horizontal(); listHeading.setGravity(Gravity.CENTER_VERTICAL);
        listLabel = eyebrow(selected.equals("home") && !playlistScreen ? "SUAS PLAYLISTS" : selected.equals("search") && !playlistScreen ? "RESULTADOS" : "", MUTED);
        listHeading.addView(listLabel, new LinearLayout.LayoutParams(0, -2, 1));
        if (selected.equals("home") && !playlistScreen) {
            TextView all = uiLabel("Ver todas", 14, ACCENT, 500); all.setGravity(Gravity.CENTER); all.setPadding(uiDp(20), 0, uiDp(4), 0); all.setOnClickListener(v -> openScreen("library")); listHeading.addView(all, new LinearLayout.LayoutParams(-2, uiDp(44)));
        } else if (selected.equals("search") && !playlistScreen) {
            addFilter(listHeading, "Músicas", searchKind.equals("track"), v -> searchFilter("track"));
            addFilter(listHeading, "Artistas", searchKind.equals("artist"), v -> searchFilter("artist"));
            addFilter(listHeading, "Álbuns", searchKind.equals("album"), v -> searchFilter("album"));
        }
        boolean section = selected.equals("home") && !playlistScreen || selected.equals("search") && !playlistScreen;
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(-1, uiDp(section ? 48 : 20)); labelParams.topMargin = uiDp(section ? 8 : 0); center.addView(listHeading, labelParams);
        FrameLayout body = new FrameLayout(this); center.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        boolean tiles = !playlistScreen && (selected.equals("home") || selected.equals("library") && !libraryKind.equals("recent"));
        if (tiles) {
            GridView grid = new GridView(this); grid.setNumColumns(selected.equals("home") ? 3 : 6); grid.setHorizontalSpacing(uiDp(selected.equals("home") ? 12 : 16)); grid.setVerticalSpacing(uiDp(selected.equals("home") ? 12 : 24)); grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH); list = grid;
        } else {
            ListView tracks = new ListView(this); tracks.setDivider(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)); tracks.setDividerHeight(uiDp(8)); list = tracks;
        }
        list.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)); list.setCacheColorHint(Color.TRANSPARENT); list.setClipToPadding(false); list.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);
        adapter = new RowAdapter(); list.setAdapter(adapter); list.setOnItemClickListener((parent, view, position, id) -> rowSelected(position));
        body.addView(list, new FrameLayout.LayoutParams(-1, -1));
        status = uiLabel("", 17, MUTED, 400); status.setGravity(Gravity.CENTER); status.setPadding(uiDp(24), uiDp(12), uiDp(24), uiDp(12)); body.addView(status, new FrameLayout.LayoutParams(-1, -1)); list.setEmptyView(status);
        retryButton = button("Tentar novamente", false, v -> { if (auth.usesNativeCatalog() && !playerState.optBoolean("connected")) connectPlayer(); loadPage(lastPath, lastKind, lastAppend); }); retryButton.setVisibility(View.GONE);
        center.addView(retryButton, new LinearLayout.LayoutParams(-1, uiDp(48)));
        moreButton = button("Carregar mais", false, v -> { if (nextPath != null) loadPage(nextPath, lastKind, true); }); moreButton.setTextSize(14 * designScale); moreButton.setBackground(ripple(Color.TRANSPARENT, LINE, Math.round(14 * designScale))); moreButton.setVisibility(View.GONE);
        LinearLayout.LayoutParams moreParams = new LinearLayout.LayoutParams(-1, uiDp(48)); moreParams.topMargin = uiDp(8); center.addView(moreButton, moreParams);
    }

    private void addCollectionHeading() {
        if (playlistScreen) { backButton = roundIcon("back", "Voltar", SURFACE_2, TEXT, v -> returnFromCollection()); LinearLayout.LayoutParams backParams = new LinearLayout.LayoutParams(uiDp(56), uiDp(56)); backParams.rightMargin = uiDp(24); pageHeading.addView(backButton, backParams); }
        if (openedPlaylist != null) {
            ImageView image = art(18); artwork.bind(image, openedPlaylist.image, 0); pageHeading.addView(image, new LinearLayout.LayoutParams(uiDp(150), uiDp(150)));
        } else {
            FrameLayout liked = new FrameLayout(this); liked.setBackground(gradient(0xff4ee6c9, 0xff2b5fa3, 18)); liked.addView(new GlyphView(this, "heart", INK), new FrameLayout.LayoutParams(uiDp(100), uiDp(100), Gravity.CENTER)); pageHeading.addView(liked, new LinearLayout.LayoutParams(uiDp(150), uiDp(150)));
        }
        LinearLayout titles = vertical(); LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, -2, 1); titleParams.leftMargin = uiDp(24); pageHeading.addView(titles, titleParams);
        collectionEyebrow = eyebrow(openedPlaylist == null ? "SUA COLEÇÃO" : entityLabel(openedPlaylist).toUpperCase(Locale.getDefault()), MUTED); titles.addView(collectionEyebrow);
        pageTitle = uiLabel(openedPlaylist == null ? "Músicas curtidas" : openedPlaylist.title, 40, TEXT, 600); pageTitle.setLetterSpacing(-.025f); oneLine(pageTitle); gapAdd(titles, pageTitle, 6);
        pageSubtitle = uiLabel(openedPlaylist == null ? "As favoritas, sempre por perto." : openedPlaylist.subtitle, 16, MUTED, 400); oneLine(pageSubtitle); gapAdd(titles, pageSubtitle, 6);
        LinearLayout controls = horizontal();
        controls.addView(action("play", "Tocar", ACCENT, INK, 18, v -> playRows(0, false)), new LinearLayout.LayoutParams(-2, uiDp(58)));
        LinearLayout.LayoutParams randomParams = new LinearLayout.LayoutParams(-2, uiDp(58)); randomParams.leftMargin = uiDp(12);
        controls.addView(action("shuffle", "Aleatório", SURFACE_2, TEXT, 18, v -> playRows(rows.isEmpty() ? 0 : new java.util.Random().nextInt(rows.size()), true)), randomParams); gapAdd(titles, controls, 12);
        center.addView(pageHeading, new LinearLayout.LayoutParams(-1, uiDp(174)));
    }

    private void addHomeBanner() {
        FrameLayout banner = new FrameLayout(this); banner.setBackground(shape(CARD, 0, Math.round(22 * designScale))); banner.setClipToOutline(true);
        ImageView photo = new ImageView(this); photo.setImageResource(R.drawable.home_road); photo.setScaleType(ImageView.ScaleType.CENTER_CROP); photo.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); banner.addView(photo, new FrameLayout.LayoutParams(-1, -1));
        View shade = new View(this); shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xe609151d, 0x5509151d})); banner.addView(shade, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout contents = horizontal(); contents.setGravity(Gravity.CENTER_VERTICAL); contents.setPadding(uiDp(32), 0, uiDp(32), 0);
        LinearLayout copy = vertical(); copy.addView(eyebrow("CONTINUAR OUVINDO", TEXT));
        homeTitle = uiLabel("Sua próxima viagem tem trilha", 32, TEXT, 600); oneLine(homeTitle); gapAdd(copy, homeTitle, 8);
        homeSubtitle = uiLabel("Escolha uma playlist para começar.", 15, 0xffd7dfe5, 400); oneLine(homeSubtitle); gapAdd(copy, homeSubtitle, 8);
        contents.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
        homeAction = action("play", "Retomar", TEXT, BG, 17, v -> resumeFromHome()); LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(-2, uiDp(60)); actionParams.leftMargin = uiDp(24); contents.addView(homeAction, actionParams);
        banner.addView(contents, new FrameLayout.LayoutParams(-1, -1)); center.addView(banner, new LinearLayout.LayoutParams(-1, uiDp(184))); updateHomeBanner();
    }

    private void addSearch() {
        LinearLayout box = horizontal(); box.setGravity(Gravity.CENTER_VERTICAL); box.setPadding(uiDp(18), 0, uiDp(12), 0); box.setBackground(shape(CARD, searchResults ? ACCENT : LINE, Math.round(22 * designScale)));
        GlyphView submit = iconButton("search", "Buscar", false, v -> search()); submit.setColor(searchResults ? ACCENT : MUTED); box.addView(submit, new LinearLayout.LayoutParams(uiDp(48), uiDp(52)));
        query = new EditText(this); query.setTypeface(uiTypeface(400)); query.setTextSize(22 * designScale); query.setTextColor(TEXT); query.setHintTextColor(MUTED); query.setHint("Música, artista ou álbum"); query.setText(searchTerm);
        query.setSingleLine(true); query.setBackgroundColor(Color.TRANSPARENT); query.setInputType(InputType.TYPE_CLASS_TEXT); query.setImeOptions(EditorInfo.IME_ACTION_SEARCH); query.setSelectAllOnFocus(false); query.setContentDescription("Buscar música, artista ou álbum");
        query.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(200)});
        query.setOnEditorActionListener((text, action, event) -> { if (action == EditorInfo.IME_ACTION_SEARCH) { search(); return true; } return false; });
        box.addView(query, new LinearLayout.LayoutParams(0, -1, 1));
        GlyphView end = roundIcon(searchResults ? "close" : "mic", searchResults ? "Limpar busca" : "Buscar por voz", searchResults ? SURFACE_2 : ACCENT_DIM, searchResults ? MUTED : ACCENT, v -> { if (searchResults) openScreen("search"); else voiceSearch(); });
        box.addView(end, new LinearLayout.LayoutParams(uiDp(52), uiDp(52))); center.addView(box, new LinearLayout.LayoutParams(-1, uiDp(searchResults ? 66 : 76)));
    }

    private void addSearchSuggestions() {
        searchSuggestions = vertical(); gapAdd(center, searchSuggestions, 28); searchSuggestions.addView(eyebrow("BUSCAS RECENTES", MUTED));
        HorizontalScrollView scroll = new HorizontalScrollView(this); scroll.setHorizontalScrollBarEnabled(false); LinearLayout history = horizontal();
        JSONArray searches = readArray("search_history");
        if (searches.length() == 0) history.addView(uiLabel("Suas buscas aparecerão aqui.", 16, MUTED, 400));
        for (int i = 0; i < searches.length(); i++) {
            String term = searches.optString(i); LinearLayout chip = action("history", term, SURFACE_2, TEXT, 16, v -> { query.setText(term); search(); });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, uiDp(48)); params.rightMargin = uiDp(12); history.addView(chip, params);
        }
        scroll.addView(history); gapAdd(searchSuggestions, scroll, 14); gap(searchSuggestions, 28); searchSuggestions.addView(eyebrow("PARA A ESTRADA", MUTED));
        LinearLayout genres = horizontal(); String[] names = {"Sertanejo", "Gospel", "MPB", "Pop", "Rock", "Infantil"}; String[] icons = {"guitar", "church", "sun", "sparkles", "zap", "smile"};
        int[] from = {0xffc98a3f,0xff4a90d9,0xff3fb8a2,0xffe85fa5,0xff8e6ac8,0xffd9c84a}, to = {0xff56391a,0xff1b3a5e,0xff154d43,0xff5e2043,0xff3b2860,0xff5a5116};
        for (int i = 0; i < names.length; i++) {
            String name = names[i]; LinearLayout genre = vertical(); genre.setPadding(uiDp(18), uiDp(14), uiDp(18), uiDp(18)); genre.setBackground(gradient(from[i], to[i], 18));
            GlyphView icon = new GlyphView(this, icons[i], TEXT); genre.addView(icon, new LinearLayout.LayoutParams(uiDp(48), uiDp(48))); spacer(genre); genre.addView(uiLabel(name, 18, TEXT, 600));
            genre.setContentDescription("Buscar " + name); genre.setFocusable(true); genre.setOnClickListener(v -> { query.setText(name); search(); });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, uiDp(136), 1); if (i > 0) params.leftMargin = uiDp(14); genres.addView(genre, params);
        }
        gapAdd(searchSuggestions, genres, 14);
    }

    private void search() {
        if (query == null) return; String value = query.getText().toString().trim(); if (value.isEmpty()) return;
        ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(query.getWindowToken(), 0); query.clearFocus();
        generation++; searchTerm = value; searchResults = true; playlistScreen = false; rememberSearch(value); rows.clear(); renderPage(); loadSearch();
    }
    private void searchFilter(String kind) { generation++; searchKind = kind; rows.clear(); renderPage(); loadSearch(); }
    private void loadSearch() {
        if (demo) { fillDemo(searchKind.equals("track")); return; }
        try { loadPage("/search?q=" + URLEncoder.encode(searchTerm, "UTF-8") + "&type=" + searchKind + "&limit=30", searchKind, false); }
        catch (Exception error) { status.setText("Não foi possível fazer essa busca."); }
    }
    private void voiceSearch() {
        Intent intent = new Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "pt-BR"); intent.putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Qual música vai com você?");
        try { startActivityForResult(intent, 81); } catch (android.content.ActivityNotFoundException error) { toast("Busca por voz indisponível neste carro. Use o teclado."); query.requestFocus(); ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(query, InputMethodManager.SHOW_IMPLICIT); }
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 81 && result == RESULT_OK && data != null && mainScreen && selected.equals("search") && query != null && !playlistScreen) {
            ArrayList<String> results = data.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS);
            if (results != null && !results.isEmpty()) { query.setText(results.get(0)); search(); }
        }
    }
    private void libraryFilter(String kind) {
        generation++; libraryKind = kind; rows.clear(); nextPath = null; lastPath = null; totalItems = -1; renderPage();
        if (kind.equals("recent")) { JSONArray recent = readArray("recent_tracks"); for (int i = 0; i < recent.length(); i++) { Row row = Row.restore(recent.optJSONObject(i)); if (row != null) rows.add(row); } adapter.notifyDataSetChanged(); status.setText("As músicas ouvidas neste carro aparecerão aqui."); return; }
        if (demo) { fillDemo(libraryKind.equals("recent")); return; }
        loadPage("/me/" + (kind.equals("playlist") ? "playlists" : kind.equals("album") ? "albums" : "artists") + "?limit=30", kind, false);
    }

    private void loadPage(String path, String kind, boolean append) {
        if (path == null || destroyed || demo) return;
        AuthManager owner = auth; SpotifyApi sourceApi = api; int session = sessionGeneration;
        if (!owner.isSignedIn()) { sessionExpired(); return; }
        lastPath = path; lastKind = kind; lastAppend = append; int request = ++generation;
        retryButton.setVisibility(View.GONE); moreButton.setVisibility(View.GONE);
        if (!append) { totalItems = -1; rows.clear(); adapter.notifyDataSetChanged(); status.setText("Carregando sua música…"); }
        else { moreButton.setVisibility(View.VISIBLE); moreButton.setText("Carregando…"); moreButton.setEnabled(false); }
        if (owner.usesNativeCatalog() && !playerState.optBoolean("connected")) {
            status.setText("Conectando ao Spotify…"); return;
        }
        network.execute(() -> {
            if (request != generation || destroyed || owner != auth || session != sessionGeneration) return;
            try {
                JSONObject data;
                try { data = sourceApi.get(path); }
                catch (Exception error) {
                    if (path.startsWith("/playlists/") && path.contains("/items") && isStatus(error, 404)) data = sourceApi.get(path.replace("/items", "/tracks"));
                    else throw error;
                }
                if (path.startsWith("/search") && data.optJSONObject(kind + "s") != null) data = data.getJSONObject(kind + "s");
                final int count = data.optInt("total", -1);
                JSONArray items = data.optJSONArray("items");
                List<Row> loaded = new ArrayList<>();
                if (items != null) for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.optJSONObject(i); if (item == null) continue;
                    Row row = parseRow(item, kind); if (row != null) loaded.add(row);
                }
                String next = relativePath(data.optString("next", ""));
                main.post(() -> {
                    if (destroyed || !mainScreen || request != generation || owner != auth || session != sessionGeneration) return;
                    rows.addAll(loaded); nextPath = next; totalItems = count; adapter.notifyDataSetChanged();
                    updateListHeading(); checkLikes(loaded);
                    status.setText(kind.equals("playlist") ? "Suas playlists aparecerão aqui.\nCrie ou salve uma no Spotify para começar." : "Nenhum resultado disponível aqui por enquanto.");
                    moreButton.setText("Carregar mais"); moreButton.setEnabled(true); moreButton.setVisibility(next == null ? View.GONE : View.VISIBLE);
                });
            } catch (Exception error) {
                main.post(() -> {
                    if (destroyed || !mainScreen || request != generation || owner != auth || session != sessionGeneration) return;
                    if (!owner.isSignedIn()) { sessionExpired(); return; }
                    String message = owner.usesNativeCatalog() && error.getMessage() != null ? error.getMessage() : isStatus(error, 403) && path.startsWith("/playlists/") ? "O Spotify não liberou esta playlist.\nTente uma playlist criada por você." : isStatus(error, 401) ? "Sua conexão com o Spotify expirou.\nConecte sua conta novamente nas configurações." : isStatus(error, 429) ? error.getMessage() : "Não foi possível carregar.\nVerifique a conexão e tente novamente.";
                    status.setText(message); if (append) toast("Não foi possível carregar mais músicas. Tente novamente.");
                    moreButton.setVisibility(View.GONE); retryButton.setVisibility(View.VISIBLE);
                });
            }
        });
    }

    static String relativePath(String value) {
        if (value == null || value.isEmpty() || value.equals("null")) return null;
        try {
            URI uri = new URI(value);
            if (!"https".equals(uri.getScheme()) || !"api.spotify.com".equals(uri.getHost()) || uri.getRawUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443)) return null;
            String path = uri.getRawPath(); if (path == null || !path.startsWith("/v1/")) return null;
            return path.substring(3) + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        } catch (Exception ignored) { return null; }
    }

    private static Row parseRow(JSONObject item, String kind) {
        JSONObject source = item;
        if (kind.equals("track") && (item.has("track") || item.has("item"))) { source = item.optJSONObject("item"); if (source == null) source = item.optJSONObject("track"); }
        if (kind.equals("album") && item.optJSONObject("album") != null) source = item.optJSONObject("album");
        if (source == null || source.optBoolean("is_local") || "episode".equals(source.optString("type")) || source.has("is_playable") && !source.optBoolean("is_playable")) return null;
        Row row = new Row(); row.kind = kind; row.playlist = !kind.equals("track");
        row.id = source.optString("id", ""); row.uri = source.optString("uri", ""); row.title = source.optString("name", "Sem título");
        if (kind.equals("playlist") && row.id.matches("[A-Za-z0-9]{22}")) row.uri = "spotify:playlist:" + row.id;
        if (!row.uri.matches("spotify:" + kind + ":[A-Za-z0-9]{22}")) return null;
        row.id = row.uri.substring(row.uri.lastIndexOf(':') + 1);
        if (kind.equals("playlist")) {
            JSONObject owner = source.optJSONObject("owner"); row.subtitle = owner == null ? "Playlist" : owner.optString("display_name", "Playlist");
            if (row.subtitle.isEmpty() || row.subtitle.equals("null")) row.subtitle = "Playlist";
            row.image = imageUrl(source.optJSONArray("images")); JSONObject tracks = source.optJSONObject("tracks"); row.count = tracks == null ? -1 : tracks.optInt("total", -1);
        } else if (kind.equals("artist")) { row.subtitle = "Artista"; row.image = imageUrl(source.optJSONArray("images")); }
        else {
            JSONArray artists = source.optJSONArray("artists"); StringBuilder names = new StringBuilder();
            if (artists != null) for (int i = 0; i < artists.length(); i++) { JSONObject artist = artists.optJSONObject(i); if (artist == null) continue; if (names.length() > 0) names.append(", "); names.append(artist.optString("name", "")); }
            row.subtitle = names.length() == 0 ? "Artista desconhecido" : names.toString(); row.durationMs = source.optLong("duration_ms");
            if (kind.equals("album")) { row.image = imageUrl(source.optJSONArray("images")); row.count = source.optInt("total_tracks", -1); }
            else { JSONObject album = source.optJSONObject("album"); row.image = album == null ? "" : imageUrl(album.optJSONArray("images")); row.album = album == null ? "" : album.optString("name", ""); }
        }
        row.saved = source.has("saved") ? source.optBoolean("saved") : null; return row;
    }

    private static String imageUrl(JSONArray images) {
        if (images == null || images.length() == 0) return "";
        JSONObject choice = images.optJSONObject(0);
        for (int i = 0; i < images.length(); i++) { JSONObject image = images.optJSONObject(i); if (image != null && image.optInt("width", 0) >= 160 && (choice == null || image.optInt("width", 9999) < choice.optInt("width", 9999))) choice = image; }
        return choice == null ? "" : choice.optString("url", "");
    }

    private static boolean isStatus(Exception error, int code) {
        return error instanceof SpotifyApi.ApiException && ((SpotifyApi.ApiException) error).status == code;
    }

    private void rowSelected(int index) {
        if (index < 0 || index >= rows.size()) return; Row row = rows.get(index);
        if (!row.playlist) { playRows(index, playerState.optBoolean("shuffle")); return; }
        generation++; openedPlaylist = row; playlistScreen = true; rows.clear(); totalItems = row.count; renderPage();
        if (demo) fillDemo(true);
        else loadPage("/" + (row.kind.equals("playlist") ? "playlists" : row.kind.equals("album") ? "albums" : "artists") + "/" + row.id + "/" + (row.kind.equals("playlist") ? "items" : "tracks") + "?limit=50", "track", false);
    }

    private void returnFromCollection() {
        generation++; playlistScreen = false; openedPlaylist = null; rows.clear(); totalItems = -1;
        if (selected.equals("search")) { renderPage(); loadSearch(); }
        else if (selected.equals("library")) libraryFilter(libraryKind);
        else openScreen(selected);
    }

    private void playRows(int index, boolean shuffle) {
        if (!canPlay()) return;
        if (rows.isEmpty()) { toast("Aguarde as músicas carregarem."); return; }
        JSONArray queue = new JSONArray(); int selectedIndex = 0;
        for (int i = 0; i < rows.size() && queue.length() < 2000; i++) {
            Row row = rows.get(i); if (!row.playlist && row.uri.startsWith("spotify:track:")) { if (i == index) selectedIndex = queue.length(); queue.put(row.json()); }
        }
        if (queue.length() == 0) return;
        playingPlaylist = openedPlaylist; player.playItems(queue, selectedIndex, shuffle); setPlayerMessage("Preparando sua música…");
    }

    private void fillDemo(boolean tracks) {
        rows.clear();
        String kind = tracks ? "track" : selected.equals("search") ? searchKind : libraryKind;
        for (int i = 0; i < Demo.SONGS.length; i++) {
            Row row = Demo.row(i, kind); row.saved = selected.equals("liked") || i % 3 == 0; rows.add(row);
        }
        totalItems = rows.size();
        adapter.notifyDataSetChanged(); status.setText(""); moreButton.setVisibility(View.GONE); retryButton.setVisibility(View.GONE); updateListHeading();
    }

    private void renderDemoPlayer() {
        Row track = Demo.row(0, "track");
        playerTitle.setText(track.title); playerArtist.setText(track.subtitle); playerAlbum.setText(track.album); artwork.bind(cover, track.image, 0);
        playerStatus.setText("PRÉVIA · DADOS FICTÍCIOS · SEM REPRODUÇÃO"); seek.setProgress(340); seek.setEnabled(false); elapsed.setText("1:04"); duration.setText(formatTime(track.durationMs)); playButton.setGlyph("play");
        volume.setProgress(45); updateLikeAction(); updateHomeBanner();
    }

    private Row currentRow() {
        if (demo) return Demo.row(0, "track");
        if (!playerState.optString("uri").startsWith("spotify:track:")) return null;
        Row row = new Row(); row.uri = playerState.optString("uri"); row.id = row.uri.substring(row.uri.lastIndexOf(':') + 1); row.title = playerState.optString("title"); row.subtitle = playerState.optString("artist"); row.album = playerState.optString("album"); row.image = playerState.optString("coverUrl"); row.durationMs = playerState.optLong("durationMs"); return row;
    }

    private void checkLikes(List<Row> candidates) {
        if (demo || !auth.isSignedIn()) return;
        List<Row> pending = new ArrayList<>();
        for (Row row : candidates) {
            if (row == null || row.playlist || row.id.isEmpty()) continue;
            if (row.saved != null) savedTracks.put(row.uri, row.saved);
            if (!savedTracks.containsKey(row.uri) && !savingTracks.contains(row.uri) && checkingTracks.add(row.uri)) { pending.add(row); if (pending.size() == 50) break; }
        }
        if (pending.isEmpty()) { updateLikeAction(); return; }
        SpotifyApi source = api; AuthManager owner = auth; int session = sessionGeneration; int revision = likesRevision;
        network.execute(() -> {
            if (destroyed || owner != auth || session != sessionGeneration) return;
            JSONArray result = null;
            try { result = source.get("/me/tracks/contains?ids=" + TextUtils.join(",", ids(pending))).optJSONArray("saved"); } catch (Exception ignored) { }
            JSONArray values = result;
            main.post(() -> {
                if (destroyed || owner != auth || session != sessionGeneration) return;
                for (int i = 0; i < pending.size(); i++) { Row row = pending.get(i); checkingTracks.remove(row.uri); if (revision == likesRevision && values != null && i < values.length() && !savingTracks.contains(row.uri)) savedTracks.put(row.uri, values.optBoolean(i)); }
                updateLikeAction(); if (adapter != null) adapter.notifyDataSetChanged();
                if (revision != likesRevision && mainScreen && !demo) { checkLikes(new ArrayList<>(rows)); checkLikes(Collections.singletonList(currentRow())); }
            });
        });
    }
    private static List<String> ids(List<Row> rows) { List<String> result = new ArrayList<>(); for (Row row : rows) result.add(row.id); return result; }

    private void toggleLike(Row row) {
        if (demo) { toast("Conecte seu Spotify para curtir músicas."); return; }
        if (row == null || row.playlist || row.id.isEmpty()) { toast("Escolha uma música para curtir."); return; }
        if (!savingTracks.add(row.uri)) return; likesRevision++;
        Boolean known = savedTracks.get(row.uri); SpotifyApi source = api; AuthManager owner = auth; int session = sessionGeneration;
        updateLikeAction(); if (adapter != null) adapter.notifyDataSetChanged();
        network.execute(() -> {
            try {
                if (destroyed || owner != auth || session != sessionGeneration) return;
                boolean saved;
                if (known != null) saved = known;
                else { JSONArray values = source.get("/me/tracks/contains?ids=" + row.id).optJSONArray("saved"); if (values == null || values.length() != 1) throw new Exception("Não foi possível consultar suas curtidas. Tente novamente."); saved = values.getBoolean(0); }
                boolean next = !saved; JSONObject body = new JSONObject().put("ids", new JSONArray().put(row.id));
                if (destroyed || owner != auth || session != sessionGeneration) return;
                source.request(next ? "PUT" : "DELETE", "/me/tracks", body);
                main.post(() -> {
                    if (destroyed || owner != auth || session != sessionGeneration) return;
                    savingTracks.remove(row.uri); savedTracks.put(row.uri, next);
                    for (Row visible : rows) if (visible.uri.equals(row.uri)) visible.saved = next;
                    updateLikeAction(); if (adapter != null) adapter.notifyDataSetChanged();
                    toast(next ? "Adicionada às músicas curtidas." : "Removida das músicas curtidas.");
                    if (mainScreen && selected.equals("liked")) loadPage("/me/tracks?limit=30", "track", false);
                });
            } catch (Exception error) {
                main.post(() -> { if (destroyed || owner != auth || session != sessionGeneration) return; savingTracks.remove(row.uri); updateLikeAction(); if (adapter != null) adapter.notifyDataSetChanged(); toast(error.getMessage() == null ? "Não foi possível atualizar a curtida. Tente novamente." : error.getMessage()); });
            }
        });
    }

    private void updateLikeAction() {
        if (likeAction == null || !mainScreen) return; String uri = playerState.optString("uri"); boolean saved = demo || Boolean.TRUE.equals(savedTracks.get(uri)), busy = !demo && savingTracks.contains(uri);
        ((GlyphView) likeAction.getChildAt(0)).setGlyph(saved ? "heart_filled" : "heart"); ((GlyphView) likeAction.getChildAt(0)).setColor(saved ? ACCENT : TEXT);
        ((TextView) likeAction.getChildAt(1)).setText(busy ? "Salvando" : saved ? "Curtida" : "Curtir"); ((TextView) likeAction.getChildAt(1)).setTextColor(saved ? ACCENT : TEXT);
        likeAction.setBackground(ripple(saved ? ACCENT_DIM : SURFACE_2, 0, 999)); likeAction.setEnabled(!busy); likeAction.setContentDescription(saved ? "Remover das músicas curtidas" : "Curtir música");
    }

    private void recordPlayback() {
        Row row = currentRow(); if (row == null || row.title.isEmpty() || !playerState.optBoolean("playing")) return;
        JSONArray recent = readArray("recent_tracks"); JSONObject first = recent.optJSONObject(0);
        if (first != null && row.uri.equals(first.optString("uri"))) return;
        JSONArray next = new JSONArray().put(row.json());
        for (int i = 0; i < recent.length() && next.length() < 60; i++) { JSONObject item = recent.optJSONObject(i); if (item != null && !row.uri.equals(item.optString("uri"))) next.put(item); }
        preferences.edit().putString("recent_tracks", next.toString()).apply();
    }
    private JSONArray readArray(String key) {
        if (demo) return key.equals("search_history") ? new JSONArray(java.util.Arrays.asList("Luna Vale", "Maré Alta", "Farol Aceso")) : new JSONArray();
        try { return new JSONArray(preferences.getString(key, "[]")); } catch (Exception ignored) { return new JSONArray(); }
    }
    private void rememberSearch(String value) {
        if (demo) return; JSONArray old = readArray("search_history"), next = new JSONArray().put(value);
        for (int i = 0; i < old.length() && next.length() < 8; i++) if (!value.equalsIgnoreCase(old.optString(i))) next.put(old.optString(i));
        preferences.edit().putString("search_history", next.toString()).apply();
    }
    private void updateHomeBanner() {
        if (homeTitle == null) return; if (player != null && !player.hasQueueSelection()) playingPlaylist = null; Row track = currentRow(); if (track == null || track.title.isEmpty()) track = Row.restore(readArray("recent_tracks").optJSONObject(0));
        homeTitle.setText(demo ? Demo.PLAYLISTS[0] : track == null ? "Sua próxima viagem tem trilha" : playingPlaylist == null ? track.title : playingPlaylist.title);
        homeSubtitle.setText(track == null ? "Escolha uma playlist para começar." : track.subtitle + " · " + track.title);
        ((TextView) homeAction.getChildAt(1)).setText(track == null ? "Explorar" : playerState.optBoolean("playing") ? "Tocando" : "Retomar");
    }
    private void resumeFromHome() {
        if (demo) { canPlay(); return; }
        if (!playerState.optString("uri").isEmpty()) { if (canPlay() && !playerState.optBoolean("playing")) player.toggle(); return; }
        Row last = Row.restore(readArray("recent_tracks").optJSONObject(0));
        if (last == null) { openScreen("library"); return; }
        if (canPlay()) player.playItems(new JSONArray().put(last.json()), 0, false);
    }
    private void updateListHeading() {
        if (listLabel == null) return;
        if (selected.equals("search") && !playlistScreen) listLabel.setText("RESULTADOS · " + (totalItems >= 0 ? totalItems : rows.size()) + (totalItems < 0 && nextPath != null ? "+" : "") + " " + (searchKind.equals("track") ? "MÚSICAS" : searchKind.equals("artist") ? "ARTISTAS" : "ÁLBUNS"));
        if (playlistScreen || selected.equals("liked")) {
            long sum = 0; for (Row row : rows) sum += row.durationMs;
            String count = totalItems >= 0 ? String.valueOf(totalItems) : rows.size() + (nextPath != null ? "+" : "");
            pageSubtitle.setText(count + " músicas" + (nextPath == null && sum > 0 ? " · " + sum / 60000 + " min" : "") + (openedPlaylist == null ? " · As favoritas, sempre por perto." : ""));
        }
    }

    private void showQueue() {
        JSONArray queue = demo ? Demo.queue() : player == null ? new JSONArray() : player.queueItems();
        LinearLayout content = modalContent(); Dialog dialog = modal(content, 720);
        content.addView(uiLabel("Fila", 28, TEXT, 600));
        gapAdd(content, uiLabel(playerState.optBoolean("shuffle") ? "Seleção neste carro · aleatório ativo" : "Seleção neste carro", 15, MUTED, 400), 8);
        if (queue.length() == 0) gapAdd(content, uiLabel("Escolha uma música no Impulsefy para montar a fila.", 17, MUTED, 400), 24);
        else {
            List<Row> items = new ArrayList<>(); for (int i = 0; i < queue.length(); i++) { Row item = Row.restore(queue.optJSONObject(i)); if (item != null) items.add(item); }
            ListView queueList = new ListView(this); queueList.setDivider(new android.graphics.drawable.ColorDrawable(LINE)); queueList.setDividerHeight(uiDp(1));
            queueList.setAdapter(new BaseAdapter() {
                @Override public int getCount() { return items.size(); }
                @Override public Object getItem(int i) { return items.get(i); }
                @Override public long getItemId(int i) { return i; }
                @Override public View getView(int i, View recycled, ViewGroup parent) {
                    TextView text = recycled instanceof TextView ? (TextView) recycled : uiLabel("", 18, TEXT, 500); Row row = items.get(i);
                    text.setText(row.title + "\n" + row.subtitle); text.setMaxLines(2); text.setEllipsize(TextUtils.TruncateAt.END); text.setPadding(uiDp(12), uiDp(10), uiDp(12), uiDp(10));
                    text.setTextColor(row.uri.equals(playerState.optString("uri")) ? ACCENT : TEXT); text.setMinHeight(uiDp(68)); return text;
                }
            });
            queueList.setOnItemClickListener((parent, view, index, id) -> { if (canPlay()) { player.playItems(queue, index, playerState.optBoolean("shuffle")); dialog.dismiss(); } });
            LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(-1, uiDp(Math.min(330, items.size() * 70))); listParams.topMargin = uiDp(20); content.addView(queueList, listParams);
            int current = 0; for (int i = 0; i < items.size(); i++) if (items.get(i).uri.equals(playerState.optString("uri"))) current = i; queueList.setSelection(current);
        }
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(-1, uiDp(56)); closeParams.topMargin = uiDp(24); content.addView(action("close", "Fechar", TEXT, BG, 17, v -> dialog.dismiss()), closeParams); dialog.show(); sizeModal(dialog, 720);
    }

    private void loadProfile() {
        AuthManager owner = auth; SpotifyApi sourceApi = api; int session = sessionGeneration;
        network.execute(() -> {
            try {
                JSONObject profile = sourceApi.get("/me"); String name = profile.optString("display_name", "Sua conta");
                boolean allowed = !"free".equals(profile.optString("product"));
                main.post(() -> {
                    if (destroyed || demo || !mainScreen || owner != auth || session != sessionGeneration) return;
                    accountName = name.isEmpty() || name.equals("null") ? "Sua conta" : name; account.setText(accountName); accountAvatar.setText(initial(accountName)); premiumAllowed = allowed;
                    accountId = profile.optString("id", ""); accountProduct = profile.optString("product", "");
                    previewBadge.setText(accountProduct.equals("premium") ? "Spotify Premium" : "Spotify");
                    if (selected.equals("home") && !playlistScreen) pageTitle.setText(greeting());
                    if (!allowed) { connecting = false; releasePlayer(); acceptPlayerState(new JSONObject()); setPlayerMessage("Use Spotify Premium para ouvir no carro."); }
                });
            } catch (Exception ignored) {
                main.post(() -> { if (!destroyed && !demo && mainScreen && owner == auth && session == sessionGeneration && !owner.isSignedIn()) sessionExpired(); });
            }
        });
    }

    private void connectPlayer() {
        if (destroyed || demo || !mainScreen || player == null || connecting || !premiumAllowed || !auth.isSignedIn()) return;
        JSONObject current = player.snapshot();
        if (current.optBoolean("connected")) { acceptPlayerState(current); return; }
        if (current.optBoolean("loading")) return;
        connecting = true; setPlayerMessage("Conectando ao Spotify…");
        String deviceId = preferences.getString("device_id", "");
        if (deviceId.isEmpty()) { deviceId = UUID.randomUUID().toString(); preferences.edit().putString("device_id", deviceId).apply(); }
        final String device = deviceId; final PlayerService service = player;
        AuthManager owner = auth; int session = sessionGeneration;
        network.execute(() -> {
            try {
                String token = owner.playbackAccessToken(); String credential = owner.playbackCredential();
                boolean playbackOAuth = owner.usesPlaybackOAuth(); String username = owner.playbackUsername();
                main.post(() -> {
                    if (destroyed || demo || !mainScreen || !premiumAllowed || owner != auth || session != sessionGeneration || service != player) return;
                    if (!owner.isSignedIn()) { sessionExpired(); return; }
                    service.connect(token, credential, device, playbackOAuth, username, owner.usesNativeCatalog());
                });
            } catch (Exception error) {
                main.post(() -> {
                    if (destroyed || demo || !mainScreen || owner != auth || session != sessionGeneration) return;
                    connecting = false;
                    if (!owner.isSignedIn()) sessionExpired(); else setPlayerMessage("Sem conexão. Toque em reproduzir para tentar de novo.");
                });
            }
        });
    }

    private void acceptPlayerState(JSONObject state) {
        if (destroyed || state == null) return;
        String credential = state.optString("credential", "");
        AuthManager owner = auth; int session = sessionGeneration;
        if (!credential.isEmpty() && owner.isSignedIn()) network.execute(() -> {
            try { if (owner == auth && session == sessionGeneration && owner.isSignedIn()) owner.savePlaybackCredential(credential); } catch (Exception ignored) { }
        });
        boolean changedTrack = !playerState.optString("uri", "").equals(state.optString("uri", ""));
        boolean newlyConnected = state.optBoolean("connected") && !playerState.optBoolean("connected");
        playerState = state; stateAt = SystemClock.elapsedRealtime();
        if (mainScreen && !demo && auth.usesNativeCatalog() && !state.optBoolean("connected") && !state.optString("error").isEmpty() && status != null) {
            status.setText(state.optString("error"));
            retryButton.setVisibility(View.VISIBLE);
        }
        if (newlyConnected && mainScreen && !demo && auth.usesNativeCatalog()) {
            loadProfile();
            if (lastPath != null) loadPage(lastPath, lastKind, false);
        }
        if (state.optBoolean("connected") || (!state.optBoolean("loading") && !state.optString("error", "").isEmpty())) connecting = false;
        if (!mainScreen || demo || playerTitle == null) return;
        String title = state.optString("title", ""); String artist = state.optString("artist", "");
        playerTitle.setText(title.isEmpty() ? "O caminho pede música" : title);
        playerArtist.setText(artist.isEmpty() ? "Escolha uma música para começar" : artist);
        playerAlbum.setText(state.optString("album", ""));
        boolean shuffled = state.optBoolean("shuffle"); int repeat = state.optInt("repeat");
        shuffleButton.setColor(shuffled ? ACCENT : TEXT); shuffleButton.setBackground(ripple(shuffled ? ACCENT_DIM : SURFACE_2, 0, 999)); shuffleButton.setSelected(shuffled); shuffleButton.setContentDescription(shuffled ? "Desativar aleatório" : "Ativar aleatório");
        repeatButton.setGlyph(repeat == 2 ? "repeat_one" : "repeat"); repeatButton.setColor(repeat > 0 ? ACCENT : TEXT); repeatButton.setBackground(ripple(repeat > 0 ? ACCENT_DIM : SURFACE_2, 0, 999)); repeatButton.setContentDescription(repeat == 2 ? "Repetir música" : repeat == 1 ? "Repetir fila" : "Repetição desativada");
        if (changedTrack) checkLikes(Collections.singletonList(currentRow()));
        updateLikeAction(); recordPlayback(); updateHomeBanner();
        artwork.bind(cover, state.optString("coverUrl", ""), 0);
        boolean playing = state.optBoolean("playing"), ready = state.optBoolean("connected");
        playButton.setGlyph(playing ? "pause" : "play"); playButton.setContentDescription(playing ? "Pausar" : "Reproduzir");
        playButton.setAlpha(ready ? 1f : .5f); previousButton.setAlpha(ready ? 1f : .4f); nextButton.setAlpha(ready ? 1f : .4f);
        seek.setEnabled(ready && state.optLong("durationMs") > 0);
        String error = state.optString("error", "");
        setPlayerMessage(!premiumAllowed ? "Use Spotify Premium para ouvir no carro." : !error.isEmpty() ? error : state.optBoolean("loading") ? "Preparando sua música…" : ready ? "" : "Conectando ao Spotify…");
        updateProgress(); if (changedTrack && adapter != null) adapter.notifyDataSetChanged();
    }

    private void updateProgress() {
        if (volume != null) {
            volume.setEnabled(!demo && player != null);
            if (!demo && player != null && !changingVolume) {
                volume.setContentDescription(player.hasCarVolume() ? "Volume de mídia do carro" : "Volume do Impulsefy");
                volume.setMax(player.getVolumeMax());
                volume.setProgress(player.getVolume());
            }
        }
        if (demo || seek == null || seeking) return;
        long total = playerState.optLong("durationMs"), position = playerState.optLong("positionMs");
        if (playerState.optBoolean("playing") && !playerState.optBoolean("loading")) position += SystemClock.elapsedRealtime() - stateAt;
        position = Math.max(0, total > 0 ? Math.min(total, position) : position);
        seek.setProgress(total > 0 ? (int) (position * 1000 / total) : 0); elapsed.setText(formatTime(position)); duration.setText(total > 0 ? formatTime(total) : "—");
    }

    private boolean canPlay() {
        if (demo) { toast("Esta é uma prévia. Conecte seu Spotify para ouvir."); return false; }
        if (!premiumAllowed) { toast("Use uma conta Spotify Premium para ouvir no carro."); return false; }
        if (player == null || !playerState.optBoolean("connected")) { toast(playerState.optString("error", "").isEmpty() ? "Conectando ao Spotify. Tente novamente em instantes." : playerState.optString("error")); connectPlayer(); return false; }
        return true;
    }

    private void settings() {
        String nameValue = demo ? Demo.NAME : accountName, idValue = demo ? "maya.costa" : accountId;
        LinearLayout content = modalContent(); Dialog dialog = modal(content, 600);
        LinearLayout header = horizontal(); header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(avatar(initial(nameValue), 32), new LinearLayout.LayoutParams(uiDp(84), uiDp(84)));
        LinearLayout identity = vertical(); LinearLayout.LayoutParams identityParams = new LinearLayout.LayoutParams(0, -2, 1); identityParams.leftMargin = uiDp(20); header.addView(identity, identityParams);
        TextView name = uiLabel(nameValue, 28, TEXT, 600); oneLine(name); identity.addView(name);
        TextView user = uiLabel(idValue.isEmpty() ? "Conta Spotify" : "Conta Spotify · " + idValue, 15, MUTED, 400); oneLine(user); gapAdd(identity, user, 5);
        TextView plan = uiLabel(demo ? "Spotify Premium · prévia" : accountProduct.equals("premium") ? "Spotify Premium" : accountProduct.equals("free") ? "Spotify Free" : "Spotify", 13, ACCENT, 500);
        plan.setPadding(uiDp(12), uiDp(6), uiDp(12), uiDp(6)); plan.setBackground(shape(ACCENT_DIM, 0, 999)); LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(-2, -2); badgeParams.topMargin = uiDp(10); identity.addView(plan, badgeParams); content.addView(header);
        View divider = new View(this); divider.setBackgroundColor(LINE); LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(-1, uiDp(1)); dividerParams.setMargins(0, uiDp(28), 0, uiDp(28)); content.addView(divider, dividerParams);
        content.addView(infoRow("car", "Dispositivo", "Haval H6 · Impulsefy " + BuildConfig.VERSION_NAME));
        gapAdd(content, infoRow("wifi", "Conexão", demo ? "Wi-Fi · demonstração" : connectionLabel()), 14);
        gapAdd(content, infoRow("shield", "Credenciais", demo ? "Conta fictícia · nenhum dado salvo" : "Guardadas com criptografia neste carro"), 14);
        LinearLayout actions = horizontal(); LinearLayout signOut = action("logout", demo ? "Sair da prévia" : "Desconectar", CARD, 0xffff7a7a, 17, v -> { dialog.dismiss(); if (demo) showLogin(); else logout(); }); signOut.setBackground(ripple(CARD, 0xffff7a7a, 999));
        actions.addView(signOut, new LinearLayout.LayoutParams(0, uiDp(60), 1)); LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(0, uiDp(60), 1); closeParams.leftMargin = uiDp(14);
        actions.addView(action("close", "Fechar", TEXT, BG, 17, v -> dialog.dismiss()), closeParams); gapAdd(content, actions, 28); dialog.show(); sizeModal(dialog, 600);
    }
    private String connectionLabel() {
        try {
            android.net.ConnectivityManager manager = (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            android.net.NetworkCapabilities network = manager.getNetworkCapabilities(manager.getActiveNetwork());
            if (network == null || !network.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)) return "Sem conexão com a internet";
            if (!network.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return "Rede conectada · sem acesso à internet";
            if (network.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) return "Wi-Fi";
            if (network.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)) return "Rede móvel";
            if (network.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)) return "Ethernet";
            return "Conectado";
        } catch (RuntimeException error) { return "Conexão indisponível"; }
    }
    private View infoRow(String glyph, String title, String value) {
        LinearLayout row = horizontal(); row.setGravity(Gravity.CENTER_VERTICAL); GlyphView icon = new GlyphView(this, glyph, MUTED); icon.setBackground(shape(SURFACE_2, 0, Math.round(12 * designScale))); row.addView(icon, new LinearLayout.LayoutParams(uiDp(40), uiDp(40)));
        LinearLayout text = vertical(); text.addView(uiLabel(title, 13, MUTED, 400)); gapAdd(text, uiLabel(value, 16, TEXT, 500), 4); LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1); params.leftMargin = uiDp(14); row.addView(text, params); return row;
    }
    private LinearLayout modalContent() { LinearLayout content = vertical(); content.setPadding(uiDp(36), uiDp(36), uiDp(36), uiDp(36)); content.setBackground(shape(CARD, LINE, Math.round(28 * designScale))); return content; }
    private Dialog modal(LinearLayout content, int width) { Dialog dialog = new Dialog(this); dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE); dialog.setContentView(content); dialog.setCanceledOnTouchOutside(true); return dialog; }
    private void sizeModal(Dialog dialog, int width) { android.view.Window window = dialog.getWindow(); if (window == null) return; window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)); window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND); window.setDimAmount(.85f); window.setLayout(uiDp(width), -2); window.setGravity(Gravity.CENTER); }

    private void logout() {
        generation++; sessionGeneration++; pairingGeneration++; connecting = false; premiumAllowed = true; accountName = "Sua conta"; accountId = ""; accountProduct = ""; playingPlaylist = null;
        savedTracks.clear(); checkingTracks.clear(); savingTracks.clear(); preferences.edit().remove("recent_tracks").remove("search_history").apply();
        releasePlayer(); auth.logout(); playerState = new JSONObject(); showLogin();
    }

    private void sessionExpired() {
        logout(); qrStatus.setText("Sua sessão expirou. Conecte seu Spotify novamente.");
    }

    @Override public void onBackPressed() {
        if (mainScreen && playlistScreen) { returnFromCollection(); return; }
        if (mainScreen && !selected.equals("home")) { openScreen("home"); return; }
        if (mainScreen && demo) { auth.cancel(); showLogin(); return; }
        super.onBackPressed();
    }

    private void setPlayerMessage(String message) { if (mainScreen && playerStatus != null) { playerStatus.setText(message); playerStatus.setVisibility(message.isEmpty() ? View.GONE : View.VISIBLE); } }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }
    private static String formatTime(long millis) { long seconds = Math.max(0, millis / 1000); return (seconds / 60) + ":" + String.format(Locale.US, "%02d", seconds % 60); }

    /** Fictional, offline-only fixtures shared by the preview screens. */
    private static final class Demo {
        static final String NAME = "Maya Costa";
        static final String[] SONGS = {"Farol Aceso", "Entre Ondas", "Linhas do Tempo", "Luz de Domingo", "Órbita Azul", "Quase Amanhã", "Estrada de Volta", "Jardim de Concreto", "Vento Sul", "Dias de Âmbar", "Quando o Sol Voltar", "Depois do Horizonte"};
        static final String[] ARTISTS = {"Luna Vale", "Maré Alta", "Theo Ventura", "Clara Sol", "Os Satélites", "Nina Aurora", "Caio Horizonte", "Jardim Elétrico", "Brisa Norte", "Duo Âmbar", "Céu de Outubro", "Atlas Lunar"};
        static final String[] PLAYLISTS = {"Rota do Sol", "Janelas Abertas", "Fim de Tarde", "Noite na Cidade", "Estrada e Voz", "Dias Leves", "Café com Música", "Modo Viagem", "Vento no Rosto", "Longa Distância", "Casa e Canção", "Novos Horizontes"};
        static Row row(int index, String kind) {
            Row row = new Row(); row.kind = kind; row.playlist = !kind.equals("track");
            row.id = "demo" + index; row.uri = "spotify:" + kind + ":" + row.id;
            row.title = kind.equals("track") ? SONGS[index] : kind.equals("artist") ? ARTISTS[index] : kind.equals("album") ? DemoArtwork.TITLES[index] : PLAYLISTS[index];
            row.subtitle = kind.equals("playlist") ? NAME : kind.equals("artist") ? "Artista · catálogo de demonstração" : ARTISTS[index];
            row.album = DemoArtwork.TITLES[index]; row.image = "demo:" + index; row.count = SONGS.length;
            row.durationMs = (188 + index * 13) * 1000L; row.saved = index % 3 == 0;
            return row;
        }
        static JSONArray queue() { JSONArray queue = new JSONArray(); for (int i = 0; i < SONGS.length; i++) queue.put(row(i, "track").json()); return queue; }
    }

    private static final class Row {
        String id = "", uri = "", title = "", subtitle = "", image = "", album = "", kind = "track";
        boolean playlist;
        Boolean saved;
        int count = -1;
        long durationMs;
        JSONObject json() {
            JSONObject value = new JSONObject();
            try { value.put("id", id).put("uri", uri).put("title", title).put("subtitle", subtitle).put("image", image).put("album", album).put("kind", kind).put("durationMs", durationMs).put("count", count); } catch (Exception ignored) { }
            return value;
        }
        static Row restore(JSONObject value) {
            if (value == null || !value.optString("uri").startsWith("spotify:track:")) return null;
            Row row = new Row(); row.uri = value.optString("uri"); row.id = row.uri.substring(row.uri.lastIndexOf(':') + 1); row.title = value.optString("title"); row.subtitle = value.optString("subtitle"); row.image = value.optString("image"); row.album = value.optString("album"); row.durationMs = value.optLong("durationMs"); return row;
        }
    }

    private final class RowAdapter extends BaseAdapter {
        // Each adapter belongs to one page; a recycled tile is never reused as a track row.
        private final boolean tile = !playlistScreen && selected.equals("library") && !libraryKind.equals("recent");
        private final boolean smallCard = !playlistScreen && selected.equals("home");
        @Override public int getCount() { return rows.size(); }
        @Override public Row getItem(int position) { return rows.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View convert, ViewGroup parent) {
            RowHolder holder;
            if (convert == null) {
                holder = new RowHolder(); LinearLayout row = tile ? vertical() : horizontal(); row.setGravity(tile ? Gravity.TOP : Gravity.CENTER_VERTICAL);
                if (!tile) row.setPadding(uiDp(smallCard ? 12 : 14), uiDp(8), uiDp(14), uiDp(8));
                holder.index = uiLabel("", 15, MUTED, 500); holder.index.setGravity(Gravity.CENTER); if (!tile && !smallCard) row.addView(holder.index, new LinearLayout.LayoutParams(uiDp(28), -2));
                holder.art = art(tile ? 16 : 10);
                if (tile) {
                    row.addView(holder.art, new LinearLayout.LayoutParams(-1, uiDp(184)));
                    holder.art.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> { int width = r - l; if (width > 0 && v.getLayoutParams().height != width) { ViewGroup.LayoutParams params = v.getLayoutParams(); params.height = width; v.setLayoutParams(params); } });
                } else { LinearLayout.LayoutParams artParams = new LinearLayout.LayoutParams(uiDp(smallCard ? 64 : 52), uiDp(smallCard ? 64 : 52)); artParams.leftMargin = uiDp(smallCard ? 0 : 12); row.addView(holder.art, artParams); }
                LinearLayout copy = vertical(); if (!tile) copy.setPadding(uiDp(smallCard ? 12 : 16), 0, uiDp(12), 0);
                holder.title = uiLabel("", tile || smallCard ? 16 : 18, TEXT, 600); oneLine(holder.title); copy.addView(holder.title);
                holder.subtitle = uiLabel("", tile || smallCard ? 13 : 14, MUTED, 400); oneLine(holder.subtitle); gapAdd(copy, holder.subtitle, tile ? 6 : 4);
                if (tile) gapAdd(row, copy, 10); else row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
                holder.heart = iconButton("heart", "Curtir música", false, v -> { });
                if (!tile && !smallCard) { holder.heart.setFocusable(false); row.addView(holder.heart, new LinearLayout.LayoutParams(uiDp(48), uiDp(48))); }
                holder.time = uiLabel("", 15, MUTED, 400); holder.time.setGravity(Gravity.END); if (!tile && !smallCard) row.addView(holder.time, new LinearLayout.LayoutParams(uiDp(62), -2));
                holder.arrow = new GlyphView(MainActivity.this, "chevron", MUTED); if (smallCard) row.addView(holder.arrow, new LinearLayout.LayoutParams(uiDp(28), uiDp(36)));
                if (!tile) row.setLayoutParams(new AbsListView.LayoutParams(-1, uiDp(smallCard ? 88 : 68)));
                row.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS); convert = row; convert.setTag(holder);
            } else holder = (RowHolder) convert.getTag();
            Row item = getItem(position); holder.title.setText(item.title); holder.subtitle.setText(item.count >= 0 && item.kind.equals("playlist") ? item.count + " músicas" : item.subtitle);
            boolean active = !demo && !item.uri.isEmpty() && item.uri.equals(playerState.optString("uri"));
            holder.title.setTextColor(active ? ACCENT : TEXT); holder.index.setText(active ? "♪" : String.valueOf(position + 1)); holder.index.setTextColor(active ? ACCENT : MUTED);
            convert.setBackground(tile ? null : ripple(active ? ACCENT_DIM : CARD, 0, Math.round((smallCard ? 18 : 16) * designScale)));
            holder.index.setVisibility(item.playlist ? View.GONE : View.VISIBLE);
            boolean saved = demo ? Boolean.TRUE.equals(item.saved) : Boolean.TRUE.equals(savedTracks.get(item.uri));
            holder.heart.setGlyph(saved ? "heart_filled" : "heart"); holder.heart.setColor(saved ? ACCENT : MUTED); holder.heart.setVisibility(item.playlist ? View.GONE : View.VISIBLE); holder.heart.setEnabled(!savingTracks.contains(item.uri)); holder.heart.setAlpha(savingTracks.contains(item.uri) ? .4f : 1f);
            holder.heart.setContentDescription((saved ? "Descurtir " : "Curtir ") + item.title); holder.heart.setOnClickListener(v -> toggleLike(item));
            holder.time.setText(item.playlist ? "" : formatTime(item.durationMs)); artwork.bind(holder.art, item.image, position);
            convert.setContentDescription(item.title + ", " + item.subtitle); return convert;
        }
    }
    private static final class RowHolder { ImageView art; TextView title, subtitle, time, index; GlyphView arrow, heart; }

    private LinearLayout brand() {
        LinearLayout row = horizontal(); row.setGravity(Gravity.CENTER_VERTICAL); ImageView mark = new ImageView(this); mark.setImageResource(R.drawable.ic_brand); mark.setContentDescription("Impulsefy");
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(uiDp(44), uiDp(44)); logoParams.rightMargin = uiDp(12); row.addView(mark, logoParams);
        LinearLayout copy = vertical(); copy.addView(uiLabel("Impulsefy", 20, TEXT, 600)); TextView tagline = uiLabel("PARA HAVAL H6", 10, MUTED, 600); tagline.setLetterSpacing(.16f); gapAdd(copy, tagline, 3); row.addView(copy); return row;
    }
    private String greeting() { return demo ? "Boa viagem, Maya." : accountName.equals("Sua conta") ? "Boa viagem." : "Boa viagem, " + accountName.split(" ")[0] + "."; }
    private String entityLabel(Row row) { return row.kind.equals("playlist") ? "Playlist · " + row.subtitle : row.kind.equals("album") ? "Álbum · " + row.subtitle : "Artista"; }
    private static String initial(String name) { return name == null || name.isEmpty() ? "S" : name.substring(0, name.offsetByCodePoints(0, 1)).toUpperCase(Locale.getDefault()); }
    private TextView avatar(String initial, int fontSize) { TextView view = uiLabel(initial, fontSize, INK, 600); view.setGravity(Gravity.CENTER); view.setBackground(gradient(ACCENT, 0xff2b8fa3, 999)); return view; }
    private void oneLine(TextView view) { view.setSingleLine(true); view.setEllipsize(TextUtils.TruncateAt.END); }
    private TextView eyebrow(String value, int color) { TextView text = uiLabel(value, 12, color, 600); text.setLetterSpacing(.15f); oneLine(text); return text; }
    private void gap(LinearLayout parent, int amount) { parent.addView(new View(this), new LinearLayout.LayoutParams(1, uiDp(amount))); }
    private void spacer(LinearLayout parent) { parent.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1)); }
    private void gapAdd(LinearLayout parent, View view, int amount) { LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.topMargin = uiDp(amount); parent.addView(view, params); }
    private ImageView art(int radius) { ImageView image = new ImageView(this); image.setScaleType(ImageView.ScaleType.CENTER_CROP); image.setBackground(shape(SURFACE_2, 0, Math.round(radius * designScale))); image.setClipToOutline(true); return image; }
    private GradientDrawable gradient(int from, int to, int radius) { GradientDrawable drawable = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{from, to}); drawable.setCornerRadius(uiDp(radius)); return drawable; }
    private SeekBar slider(String description, int max) {
        SeekBar bar = new SeekBar(this); bar.setMax(max); boolean position = max == 1000;
        android.graphics.drawable.ClipDrawable fill = new android.graphics.drawable.ClipDrawable(shape(ACCENT, 0, 999), Gravity.LEFT, android.graphics.drawable.ClipDrawable.HORIZONTAL);
        android.graphics.drawable.LayerDrawable track = new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{shape(LINE, 0, 999), fill});
        track.setId(0, android.R.id.background); track.setId(1, android.R.id.progress);
        for (int i = 0; i < 2; i++) { track.setLayerHeight(i, uiDp(position ? 10 : 8)); track.setLayerGravity(i, Gravity.CENTER_VERTICAL | Gravity.FILL_HORIZONTAL); }
        bar.setProgressDrawable(track); GradientDrawable thumb = shape(position ? TEXT : ACCENT, 0, 999); thumb.setSize(uiDp(position ? 24 : 16), uiDp(position ? 24 : 16)); bar.setThumb(thumb);
        bar.setSplitTrack(false); bar.setPadding(uiDp(12), 0, uiDp(12), 0); bar.setContentDescription(description); return bar;
    }
    private GlyphView roundIcon(String glyph, String title, int background, int color, View.OnClickListener click) { GlyphView icon = iconButton(glyph, title, false, click); icon.setColor(color); icon.setIconScale(.42f); icon.setBackground(ripple(background, 0, 999)); return icon; }
    private LinearLayout action(String glyph, String title, int background, int color, int fontSize, View.OnClickListener click) {
        LinearLayout view = horizontal(); view.setGravity(Gravity.CENTER); view.setPadding(uiDp(22), 0, uiDp(22), 0); view.setBackground(ripple(background, 0, 999));
        GlyphView icon = new GlyphView(this, glyph, color); view.addView(icon, new LinearLayout.LayoutParams(uiDp(fontSize == 17 ? 36 : 32), uiDp(36)));
        TextView text = uiLabel(title, fontSize, color, 500); oneLine(text); LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(-2, -2); labelParams.leftMargin = uiDp(8); view.addView(text, labelParams); view.setOnClickListener(click); view.setFocusable(true); view.setContentDescription(title); return view;
    }
    private void addFilter(LinearLayout row, String title, boolean active, View.OnClickListener click) {
        TextView filter = uiLabel(title, 14, active ? BG : TEXT, 500); filter.setGravity(Gravity.CENTER); filter.setPadding(uiDp(18), 0, uiDp(18), 0); filter.setBackground(ripple(active ? TEXT : SURFACE_2, active ? 0 : LINE, 999)); filter.setOnClickListener(click); filter.setSelected(active); filter.setFocusable(true);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, uiDp(44)); params.leftMargin = uiDp(8); row.addView(filter, params);
    }

    private LinearLayout horizontal() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.HORIZONTAL); return view; }
    private LinearLayout vertical() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.VERTICAL); return view; }
    private TextView label(String value, float size, int color) { TextView view = new TextView(this); view.setText(value); view.setTextColor(color); view.setTextSize(size); view.setTypeface(regular); view.setIncludeFontPadding(false); return view; }
    private Button button(String value, boolean primary, View.OnClickListener click) {
        Button view = new Button(this); view.setText(value); view.setTypeface(medium); view.setTextSize(14); view.setTextColor(primary ? 0xff102628 : TEXT); view.setAllCaps(false); view.setMinHeight(dp(48)); view.setMinimumHeight(dp(48));
        view.setPadding(dp(12), 0, dp(12), 0); view.setStateListAnimator(null); view.setBackground(ripple(primary ? ACCENT : 0xff242f39, 0, 10)); view.setOnClickListener(click); return view;
    }
    private GlyphView iconButton(String glyph, String description, boolean primary, View.OnClickListener click) {
        GlyphView view = new GlyphView(this, glyph, primary ? 0xff102628 : TEXT); view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES); view.setContentDescription(description);
        view.setFocusable(true); view.setClickable(true); view.setBackground(ripple(primary ? ACCENT : Color.TRANSPARENT, 0, primary ? 32 : 10)); view.setOnClickListener(click); return view;
    }
    private GradientDrawable shape(int fill, int border, int radius) { GradientDrawable drawable = new GradientDrawable(); drawable.setColor(fill); drawable.setCornerRadius(dp(radius)); if (border != 0) drawable.setStroke(dp(1), border); return drawable; }
    private RippleDrawable ripple(int fill, int border, int radius) { return new RippleDrawable(ColorStateList.valueOf(0x3085ece2), shape(fill, border, radius), shape(Color.WHITE, 0, radius)); }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
