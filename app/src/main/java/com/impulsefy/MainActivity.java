package com.impulsefy;

import android.app.Activity;
import android.app.AlertDialog;
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
    private static final int BG = 0xff090d11, CARD = 0xff171f27, TEXT = 0xfff1f5f7;
    private static final int MUTED = 0xffa2aeb8, ACCENT = 0xff85ece2;
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
    private boolean bound, visible, compact, connecting, premiumAllowed = true;
    private volatile boolean destroyed, demo, mainScreen;
    private boolean playlistScreen, seeking;
    private volatile int generation;
    private volatile int sessionGeneration;
    private String selected = "home", nextPath, lastPath, lastKind, accountName = "Sua conta";
    private boolean lastAppend;
    private JSONObject playerState = new JSONObject();
    private long stateAt, pairingExpiresAt;
    private volatile int pairingGeneration;
    private LinearLayout center, loginSteps;
    private FrameLayout qrFrame;
    private TextView pageTitle, pageSubtitle, listLabel, status, account, clock, previewBadge;
    private TextView playerTitle, playerArtist, playerStatus, elapsed, duration, qrStatus, qrCode;
    private ImageView cover, qrImage;
    private GlyphView playButton, previousButton, nextButton;
    private SeekBar seek;
    private Button moreButton, retryButton, pairingButton;
    private EditText query;
    private RowAdapter adapter;
    private ListView list;
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
                qrStatus.setText(left > 0 ? "Use a câmera do celular · expira em " + formatTime(left * 1000) : "Este QR expirou. Gere um novo para conectar.");
                if (left == 0 && pairingButton != null) pairingButton.setText("Gerar novo QR");
            }
            main.postDelayed(this, 1000);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        immersive();
        compact = getResources().getConfiguration().screenHeightDp < 500;
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
        api = new SpotifyApi(auth, path -> {
            PlayerService source = player;
            if (source == null) throw new Exception("Conectando ao Spotify. Tente novamente em instantes.");
            return source.read(path);
        });
    }

    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override public void onWindowFocusChanged(boolean focus) { super.onWindowFocusChanged(focus); if (focus) immersive(); }
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
        LinearLayout root = vertical(); root.setPadding(dp(32), dp(20), dp(32), dp(20)); root.setBackgroundColor(BG);
        LinearLayout heading = horizontal(); heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.addView(brand(), new LinearLayout.LayoutParams(0, -2, 1));
        heading.addView(label("Configure com o carro parado", 11, MUTED));
        clock = label(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date()), 18, TEXT);
        LinearLayout.LayoutParams clockParams = new LinearLayout.LayoutParams(-2, -2); clockParams.leftMargin = dp(16); heading.addView(clock, clockParams);
        root.addView(heading, new LinearLayout.LayoutParams(-1, dp(40)));
        LinearLayout body = horizontal(); LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(-1, 0, 1); bodyParams.topMargin = dp(14); root.addView(body, bodyParams);

        LinearLayout panel = vertical(); panel.setPadding(dp(24), dp(24), dp(24), dp(20)); panel.setBackground(shape(CARD, 0, 16));
        int panelWidth = Math.min(400, (int) (getResources().getConfiguration().screenWidthDp * .42f));
        body.addView(panel, new LinearLayout.LayoutParams(dp(panelWidth), -1));
        TextView title = label("Conecte. Dê play. Vá.", compact ? 23 : 28, TEXT); panel.addView(title);
        TextView subtitle = label("Só uma vez. Tudo pelo seu celular.", 13, MUTED); subtitle.setPadding(0, dp(8), 0, dp(10)); panel.addView(subtitle);
        loginSteps = vertical(); loginSteps.setGravity(Gravity.CENTER_VERTICAL);
        loginSteps.addView(step("01", "Gere seu QR code", "Toque no botão abaixo para começar."));
        loginSteps.addView(step("02", "Conecte pelo celular", "Escaneie e entre na sua conta Spotify."));
        loginSteps.addView(step("03", "Pronto para a estrada", "Suas playlists aparecem aqui."));
        panel.addView(loginSteps, new LinearLayout.LayoutParams(-1, 0, 1));
        qrFrame = new FrameLayout(this); qrFrame.setVisibility(View.GONE);
        panel.addView(qrFrame, new LinearLayout.LayoutParams(-1, 0, 1));
        qrImage = new ImageView(this); qrImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        final ImageView qrView = qrImage;
        qrFrame.addView(qrImage, new FrameLayout.LayoutParams(dp(160), dp(160), Gravity.CENTER));
        qrFrame.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int size = Math.min(dp(160), Math.min(r - l, b - t));
            if (size > 0 && qrView.getLayoutParams().width != size) qrView.setLayoutParams(new FrameLayout.LayoutParams(size, size, Gravity.CENTER));
        });
        qrCode = label("", 11, ACCENT); qrCode.setGravity(Gravity.CENTER); qrCode.setPadding(0, dp(5), 0, dp(5)); qrCode.setVisibility(View.GONE); panel.addView(qrCode);
        qrStatus = label("", 11, MUTED); qrStatus.setGravity(Gravity.CENTER); qrStatus.setMaxLines(2); panel.addView(qrStatus);
        pairingButton = button("Conectar Spotify", true, v -> startPairing());
        LinearLayout.LayoutParams connectParams = new LinearLayout.LayoutParams(-1, dp(48)); connectParams.topMargin = dp(12); panel.addView(pairingButton, connectParams);
        Button preview = button("Conhecer a interface", false, v -> { auth.cancel(); pairingGeneration++; demo = true; showMain(); });
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(-1, dp(42)); previewParams.topMargin = dp(8); panel.addView(preview, previewParams);

        LinearLayout hero = vertical(); hero.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams heroParams = new LinearLayout.LayoutParams(0, -1, 1); heroParams.leftMargin = dp(32); body.addView(hero, heroParams);
        TextView eyebrow = label("BEM-VINDO A BORDO", 10, ACCENT); hero.addView(eyebrow);
        TextView headline = label("Sua música.\nSeu caminho.", compact ? 39 : 48, TEXT); headline.setLineSpacing(0, 1.04f);
        LinearLayout.LayoutParams headlineParams = new LinearLayout.LayoutParams(-1, -2); headlineParams.topMargin = dp(16); hero.addView(headline, headlineParams);
        TextView description = label("Seu Spotify simplificado para o carro.\nSuas músicas, sem complicação.", 15, 0xffb0bec7);
        LinearLayout.LayoutParams descriptionParams = new LinearLayout.LayoutParams(-1, -2); descriptionParams.topMargin = dp(15); hero.addView(description, descriptionParams);
        ImageView scenery = new ImageView(this); scenery.setScaleType(ImageView.ScaleType.CENTER_CROP); scenery.setImageResource(R.drawable.coastal_road);
        scenery.setBackground(shape(CARD, 0, 12)); scenery.setClipToOutline(true); scenery.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams sceneryParams = new LinearLayout.LayoutParams(-1, dp(compact ? 110 : 150)); sceneryParams.topMargin = dp(16); hero.addView(scenery, sceneryParams);
        TextView premium = label("Reprodução com Spotify Premium.", 10, MUTED); premium.setPadding(0, dp(12), 0, 0); hero.addView(premium);
        setContentView(root);
    }

    private View step(String number, String title, String description) {
        LinearLayout row = horizontal(); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(0, dp(6), 0, dp(6));
        TextView index = label(number, 11, ACCENT); index.setGravity(Gravity.CENTER); index.setBackground(shape(0xff294044, 0, 20));
        LinearLayout.LayoutParams indexParams = new LinearLayout.LayoutParams(dp(34), dp(34)); indexParams.rightMargin = dp(12); row.addView(index, indexParams);
        LinearLayout copy = vertical(); copy.addView(label(title, 15, TEXT)); TextView detail = label(description, 11, MUTED); detail.setPadding(0, dp(4), 0, 0); copy.addView(detail);
        row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1)); return row;
    }

    private void startPairing() {
        auth.cancel(); int attempt = ++pairingGeneration; AuthManager owner = auth;
        pairingExpiresAt = 0; qrStatus.setText("Preparando uma conexão segura…");
        qrCode.setText("CONECTE PELO CELULAR"); pairingButton.setText("Preparando…"); pairingButton.setEnabled(false);
        owner.start(new AuthManager.Listener() {
            @Override public void onPairing(String url, String code, long expiresAt) {
                if (destroyed || owner != auth || attempt != pairingGeneration) return;
                network.execute(() -> {
                    Bitmap bitmap = qrBitmap(url);
                    main.post(() -> {
                        if (destroyed || owner != auth || mainScreen || attempt != pairingGeneration) return;
                        pairingExpiresAt = expiresAt < 1_000_000_000_000L ? expiresAt * 1000 : expiresAt;
                        if (bitmap == null) {
                            qrStatus.setText("Não foi possível exibir o QR. Tente novamente."); pairingButton.setText("Tentar novamente"); pairingButton.setEnabled(true); return;
                        }
                        loginSteps.setVisibility(View.GONE); qrFrame.setVisibility(View.VISIBLE); qrCode.setVisibility(View.VISIBLE);
                        qrImage.setImageBitmap(bitmap); qrImage.setBackground(shape(Color.WHITE, Color.WHITE, 10)); qrImage.setContentDescription("QR para conectar sua conta Spotify");
                        qrCode.setText(code == null || code.isEmpty() ? "ABRA A CÂMERA DO CELULAR" : "CÓDIGO  " + code);
                        qrStatus.setText("Escaneie com a câmera do celular para continuar.");
                        pairingButton.setText("Gerar outro QR"); pairingButton.setEnabled(true);
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
                    qrStatus.setText(message == null || message.isEmpty() ? "Não foi possível conectar. Tente novamente." : message);
                    pairingButton.setText("Tentar novamente"); pairingButton.setEnabled(true);
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
        LinearLayout root = horizontal(); root.setBackgroundColor(BG);
        LinearLayout rail = vertical(); rail.setPadding(dp(10), dp(14), dp(10), dp(14)); rail.setGravity(Gravity.CENTER_HORIZONTAL); rail.setBackgroundColor(0xff10161c);
        root.addView(rail, new LinearLayout.LayoutParams(dp(76), -1));
        rail.addView(new GlyphView(this, "impulse", ACCENT), new LinearLayout.LayoutParams(dp(38), dp(36)));
        rail.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1));
        navigation.clear();
        addNav(rail, "home", "Início", "home"); addNav(rail, "library", "Biblioteca", "library");
        addNav(rail, "search", "Buscar", "search"); addNav(rail, "liked", "Curtidas", "heart");
        rail.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1));
        LinearLayout settings = vertical(); settings.setGravity(Gravity.CENTER);
        settings.setBackground(ripple(Color.TRANSPARENT, 0, 12)); settings.setContentDescription("Configurações da conta");
        settings.addView(new GlyphView(this, "settings", MUTED), new LinearLayout.LayoutParams(dp(36), dp(40)));
        account = label(demo ? "Sair da prévia" : accountName, 9, MUTED); account.setMaxLines(1); account.setEllipsize(TextUtils.TruncateAt.END); account.setGravity(Gravity.CENTER);
        settings.addView(account, new LinearLayout.LayoutParams(-1, -2)); settings.setOnClickListener(v -> settings()); rail.addView(settings, new LinearLayout.LayoutParams(-1, dp(56)));

        LinearLayout work = vertical(); work.setPadding(dp(22), dp(16), dp(22), dp(18)); root.addView(work, new LinearLayout.LayoutParams(0, -1, 1));
        LinearLayout heading = horizontal(); heading.setGravity(Gravity.CENTER_VERTICAL); heading.addView(brand(), new LinearLayout.LayoutParams(0, -2, 1));
        previewBadge = label(demo ? "PRÉVIA" : "SPOTIFY", 10, demo ? ACCENT : MUTED); previewBadge.setLetterSpacing(.1f); heading.addView(previewBadge);
        clock = label(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date()), 18, TEXT);
        LinearLayout.LayoutParams clockParams = new LinearLayout.LayoutParams(-2, -2); clockParams.leftMargin = dp(18); heading.addView(clock, clockParams);
        work.addView(heading, new LinearLayout.LayoutParams(-1, dp(30)));
        LinearLayout panes = horizontal(); LinearLayout.LayoutParams panesParams = new LinearLayout.LayoutParams(-1, 0, 1); panesParams.topMargin = dp(14); work.addView(panes, panesParams);
        int playerWidth = getResources().getConfiguration().screenWidthDp < 900 ? 250 : 324;
        panes.addView(makePlayer(), new LinearLayout.LayoutParams(dp(playerWidth), -1));
        LinearLayout content = vertical(); LinearLayout.LayoutParams contentParams = new LinearLayout.LayoutParams(0, -1, 1); contentParams.leftMargin = dp(20); panes.addView(content, contentParams);
        pageTitle = label("Boa viagem.", compact ? 26 : 32, TEXT); pageTitle.setSingleLine(true); pageTitle.setEllipsize(TextUtils.TruncateAt.END); content.addView(pageTitle, new LinearLayout.LayoutParams(-1, -2));
        pageSubtitle = label("Sua música acompanha o caminho.", 13, MUTED); pageSubtitle.setSingleLine(true); pageSubtitle.setEllipsize(TextUtils.TruncateAt.END); pageSubtitle.setPadding(0, dp(5), 0, dp(16)); content.addView(pageSubtitle);
        center = vertical(); content.addView(center, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root); openScreen(selected);
        if (!mainScreen) return;
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
        stopService(new Intent(this, PlayerService.class));
        player = null;
    }

    private void addNav(LinearLayout rail, String key, String title, String glyph) {
        LinearLayout row = vertical(); row.setGravity(Gravity.CENTER);
        row.addView(new GlyphView(this, glyph, MUTED), new LinearLayout.LayoutParams(dp(32), dp(32)));
        TextView text = label(title, 9, MUTED); text.setGravity(Gravity.CENTER); text.setPadding(0, dp(3), 0, 0); row.addView(text);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(58)); params.bottomMargin = dp(8); rail.addView(row, params);
        row.setOnClickListener(v -> openScreen(key)); row.setContentDescription(title); navigation.put(key, row);
    }

    private LinearLayout makePlayer() {
        LinearLayout panel = vertical(); panel.setPadding(dp(18), dp(compact ? 12 : 16), dp(18), dp(12)); panel.setGravity(Gravity.CENTER_HORIZONTAL);
        panel.setBackground(shape(CARD, 0, 16));
        LinearLayout caption = horizontal(); caption.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = label("TOCANDO AGORA", 9, ACCENT); title.setLetterSpacing(.12f); caption.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        GlyphView signal = new GlyphView(this, "signal", ACCENT); caption.addView(signal, new LinearLayout.LayoutParams(dp(23), dp(compact ? 18 : 23))); panel.addView(caption, new LinearLayout.LayoutParams(-1, dp(compact ? 18 : 24)));
        FrameLayout artFrame = new FrameLayout(this);
        LinearLayout.LayoutParams artFrameParams = new LinearLayout.LayoutParams(-1, 0, 1); artFrameParams.topMargin = dp(compact ? 6 : 11); artFrameParams.bottomMargin = dp(compact ? 6 : 12); panel.addView(artFrame, artFrameParams);
        cover = new ImageView(this); cover.setScaleType(ImageView.ScaleType.CENTER_CROP); cover.setBackground(shape(CARD, 0, 11)); cover.setClipToOutline(true);
        cover.setImageDrawable(new Artwork.Placeholder(0)); cover.setContentDescription("Capa da música atual");
        int artSize = dp(compact ? 136 : 180);
        artFrame.addView(cover, new FrameLayout.LayoutParams(artSize, artSize, Gravity.CENTER));
        artFrame.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int size = Math.min(artSize, Math.min(r - l, b - t));
            if (size > 0 && cover.getLayoutParams().width != size) cover.setLayoutParams(new FrameLayout.LayoutParams(size, size, Gravity.CENTER));
        });
        playerTitle = label("O caminho pede música", compact ? 19 : 22, TEXT); playerTitle.setTypeface(medium);
        playerTitle.setSingleLine(true); playerTitle.setEllipsize(TextUtils.TruncateAt.END); playerTitle.setGravity(Gravity.CENTER); panel.addView(playerTitle, new LinearLayout.LayoutParams(-1, -2));
        playerArtist = label("Escolha uma música para começar", 13, MUTED); playerArtist.setSingleLine(true); playerArtist.setEllipsize(TextUtils.TruncateAt.END); playerArtist.setGravity(Gravity.CENTER); playerArtist.setPadding(0, dp(compact ? 3 : 5), 0, dp(compact ? 3 : 4)); panel.addView(playerArtist, new LinearLayout.LayoutParams(-1, -2));
        seek = new SeekBar(this); seek.setMax(1000); seek.setProgressTintList(ColorStateList.valueOf(ACCENT)); seek.setThumbTintList(ColorStateList.valueOf(ACCENT));
        seek.setProgressBackgroundTintList(ColorStateList.valueOf(0xff29323c)); seek.setPadding(dp(8), 0, dp(8), 0); seek.setContentDescription("Posição da música");
        FrameLayout progressPanel = new FrameLayout(this); panel.addView(progressPanel, new LinearLayout.LayoutParams(-1, dp(compact ? 48 : 61)));
        progressPanel.addView(seek, new FrameLayout.LayoutParams(-1, dp(48)));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) { if (fromUser) elapsed.setText(formatTime(playerState.optLong("durationMs") * progress / 1000)); }
            @Override public void onStartTrackingTouch(SeekBar bar) { seeking = true; }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                seeking = false;
                if (!demo && player != null && canPlay()) player.seek((int) (playerState.optLong("durationMs") * bar.getProgress() / 1000));
            }
        });
        LinearLayout times = horizontal(); elapsed = label("0:00", 11, MUTED); duration = label("—", 11, MUTED); duration.setGravity(Gravity.RIGHT);
        times.addView(elapsed, new LinearLayout.LayoutParams(0, -2, 1)); times.addView(duration, new LinearLayout.LayoutParams(0, -2, 1)); times.setPadding(dp(9), 0, dp(9), 0); progressPanel.addView(times, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        LinearLayout transport = horizontal(); transport.setGravity(Gravity.CENTER);
        previousButton = iconButton("previous", "Música anterior", false, v -> { if (canPlay()) player.previous(); });
        playButton = iconButton("play", "Reproduzir", true, v -> { if (canPlay()) player.toggle(); });
        nextButton = iconButton("next", "Próxima música", false, v -> { if (canPlay()) player.next(); });
        transport.addView(previousButton, new LinearLayout.LayoutParams(dp(54), dp(54)));
        LinearLayout.LayoutParams playParams = new LinearLayout.LayoutParams(dp(62), dp(62)); playParams.setMargins(dp(18), 0, dp(18), 0); transport.addView(playButton, playParams);
        transport.addView(nextButton, new LinearLayout.LayoutParams(dp(54), dp(54)));
        LinearLayout.LayoutParams transportParams = new LinearLayout.LayoutParams(-1, dp(compact ? 62 : 72)); transportParams.topMargin = dp(4); panel.addView(transport, transportParams);
        playerStatus = label(demo ? "PRÉVIA · SEM REPRODUÇÃO" : "Conectando ao Spotify…", 10, MUTED); playerStatus.setGravity(Gravity.CENTER); playerStatus.setMaxLines(2); playerStatus.setMinHeight(dp(compact ? 16 : 28)); panel.addView(playerStatus, new LinearLayout.LayoutParams(-1, -2));
        return panel;
    }

    private void openScreen(String screen) {
        if (!mainScreen) return;
        selected = screen; playlistScreen = false; generation++; rows.clear(); nextPath = null;
        for (Map.Entry<String, View> entry : navigation.entrySet()) {
            boolean active = entry.getKey().equals(screen); LinearLayout row = (LinearLayout) entry.getValue();
            row.setBackground(ripple(active ? 0xff223e40 : Color.TRANSPARENT, 0, 12));
            ((GlyphView) row.getChildAt(0)).setColor(active ? ACCENT : MUTED); ((TextView) row.getChildAt(1)).setTextColor(active ? TEXT : MUTED);
            row.setSelected(active);
        }
        lastPath = null; nextPath = null;
        boolean search = screen.equals("search");
        pageTitle.setText(screen.equals("home") ? "Boa viagem." : screen.equals("library") ? "Sua biblioteca" : search ? "Encontre seu som" : "Músicas curtidas");
        pageSubtitle.setText(demo ? "Uma prévia da experiência Impulsefy." : screen.equals("home") ? "Sua música acompanha o caminho." : screen.equals("library") ? "As playlists que vão com você." : search ? "Uma música para cada momento." : "As favoritas, sempre por perto.");
        center.removeAllViews();
        if (screen.equals("home")) addHomeBanner();
        if (search) addSearch();
        LinearLayout listHeader = horizontal(); listHeader.setGravity(Gravity.CENTER_VERTICAL);
        backButton = iconButton("back", "Voltar às playlists", false, v -> openScreen(selected)); backButton.setVisibility(View.GONE);
        listHeader.addView(backButton, new LinearLayout.LayoutParams(dp(48), dp(48)));
        listLabel = label(search ? "RESULTADOS" : screen.equals("liked") ? "FEITAS PARA REPETIR" : "SUAS PLAYLISTS", 10, MUTED); listLabel.setLetterSpacing(.13f);
        listHeader.addView(listLabel, new LinearLayout.LayoutParams(0, -2, 1)); center.addView(listHeader, new LinearLayout.LayoutParams(-1, dp(39)));
        LinearLayout listPanel = vertical(); listPanel.setPadding(0, 0, 0, 0);
        center.addView(listPanel, new LinearLayout.LayoutParams(-1, 0, 1));
        FrameLayout body = new FrameLayout(this); listPanel.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        list = new ListView(this); list.setDivider(new android.graphics.drawable.ColorDrawable(BG)); list.setDividerHeight(dp(8)); list.setSelector(ripple(0x2285ece2, 0, 12)); list.setCacheColorHint(Color.TRANSPARENT);
        list.setClipToPadding(false); list.setPadding(0, dp(2), 0, dp(2)); list.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);
        adapter = new RowAdapter(); list.setAdapter(adapter); list.setOnItemClickListener((parent, view, position, id) -> rowSelected(position));
        body.addView(list, new FrameLayout.LayoutParams(-1, -1));
        status = label("", 14, MUTED); status.setGravity(Gravity.CENTER); status.setPadding(dp(22), dp(14), dp(22), dp(14));
        body.addView(status, new FrameLayout.LayoutParams(-1, -1)); list.setEmptyView(status);
        retryButton = button("Tentar novamente", false, v -> { if (auth.usesNativeCatalog() && !playerState.optBoolean("connected")) connectPlayer(); loadPage(lastPath, lastKind, lastAppend); }); retryButton.setVisibility(View.GONE);
        listPanel.addView(retryButton, new LinearLayout.LayoutParams(-1, dp(48)));
        moreButton = button("Carregar mais", false, v -> { if (nextPath != null) loadPage(nextPath, lastKind, true); }); moreButton.setVisibility(View.GONE);
        listPanel.addView(moreButton, new LinearLayout.LayoutParams(-1, dp(48)));
        if (demo) { fillDemo(screen.equals("liked") || search); return; }
        if (search) status.setText("Qual música vai com você?\nBusque pelo título ou pelo artista.");
        else loadPage(screen.equals("liked") ? "/me/tracks?limit=30" : "/me/playlists?limit=30", screen.equals("liked") ? "track" : "playlist", false);
    }

    private void addHomeBanner() {
        FrameLayout banner = new FrameLayout(this); banner.setBackground(shape(CARD, 0, 14)); banner.setClipToOutline(true);
        ImageView photo = new ImageView(this); photo.setImageResource(R.drawable.coastal_road); photo.setScaleType(ImageView.ScaleType.CENTER_CROP); photo.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        banner.addView(photo, new FrameLayout.LayoutParams(-1, -1));
        View contrast = new View(this); contrast.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xe609151d, 0x3309151d})); banner.addView(contrast, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout copy = vertical(); copy.setGravity(Gravity.CENTER_VERTICAL); copy.setPadding(dp(20), dp(14), dp(20), dp(14));
        copy.addView(label("PRONTO PARA PARTIR", 9, 0xffb2f5ef));
        TextView title = label("A trilha é sua.", compact ? 25 : 29, TEXT); title.setPadding(0, dp(9), 0, dp(7)); copy.addView(title);
        copy.addView(label("Dê play no próximo caminho.", 13, 0xffe0e8ec)); banner.addView(copy, new FrameLayout.LayoutParams(-1, -1));
        center.addView(banner, new LinearLayout.LayoutParams(-1, dp(compact ? 130 : 150)));
    }

    private void addSearch() {
        LinearLayout searchBox = horizontal(); searchBox.setGravity(Gravity.CENTER_VERTICAL); searchBox.setPadding(dp(12), 0, dp(4), 0); searchBox.setBackground(shape(0xff242f39, 0, 12));
        query = new EditText(this); query.setTypeface(regular); query.setTextSize(17); query.setTextColor(TEXT); query.setHintTextColor(MUTED);
        query.setHint("Música ou artista"); query.setSingleLine(true); query.setBackgroundColor(Color.TRANSPARENT); query.setInputType(InputType.TYPE_CLASS_TEXT); query.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        query.setContentDescription("Buscar música ou artista"); query.setOnEditorActionListener((text, action, event) -> { if (action == EditorInfo.IME_ACTION_SEARCH) { search(); return true; } return false; });
        searchBox.addView(query, new LinearLayout.LayoutParams(0, dp(54), 1));
        GlyphView submit = iconButton("search", "Buscar", false, v -> search()); searchBox.addView(submit, new LinearLayout.LayoutParams(dp(54), dp(54)));
        center.addView(searchBox, new LinearLayout.LayoutParams(-1, dp(58)));
    }

    private void search() {
        String value = query.getText().toString().trim(); if (value.isEmpty()) return;
        ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(query.getWindowToken(), 0); query.clearFocus();
        if (demo) { fillDemo(true); return; }
        try { loadPage("/search?q=" + URLEncoder.encode(value, "UTF-8") + "&type=track&limit=10", "track", false); }
        catch (Exception ignored) { status.setText("Não foi possível fazer essa busca."); }
    }

    private void loadPage(String path, String kind, boolean append) {
        if (path == null || destroyed || demo) return;
        AuthManager owner = auth; SpotifyApi sourceApi = api; int session = sessionGeneration;
        if (!owner.isSignedIn()) { sessionExpired(); return; }
        lastPath = path; lastKind = kind; lastAppend = append; int request = ++generation;
        retryButton.setVisibility(View.GONE); moreButton.setVisibility(View.GONE);
        if (!append) { rows.clear(); adapter.notifyDataSetChanged(); status.setText("Carregando sua música…"); }
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
                if (data.optJSONObject("tracks") != null && path.startsWith("/search")) data = data.getJSONObject("tracks");
                JSONArray items = data.optJSONArray("items");
                List<Row> loaded = new ArrayList<>();
                if (items != null) for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.optJSONObject(i); if (item == null) continue;
                    Row row = parseRow(item, kind); if (row != null) loaded.add(row);
                }
                String next = relativePath(data.optString("next", ""));
                main.post(() -> {
                    if (destroyed || !mainScreen || request != generation || owner != auth || session != sessionGeneration) return;
                    rows.addAll(loaded); nextPath = next; adapter.notifyDataSetChanged();
                    status.setText(kind.equals("playlist") ? "Suas playlists aparecerão aqui.\nCrie ou salve uma no Spotify para começar." : "Nenhuma música disponível aqui por enquanto.");
                    moreButton.setText("Carregar mais"); moreButton.setEnabled(true); moreButton.setVisibility(next == null ? View.GONE : View.VISIBLE);
                });
            } catch (Exception error) {
                main.post(() -> {
                    if (destroyed || !mainScreen || request != generation || owner != auth || session != sessionGeneration) return;
                    if (!owner.isSignedIn()) { sessionExpired(); return; }
                    String message = isStatus(error, 403) && path.startsWith("/playlists/") ? "O Spotify não liberou esta playlist.\nTente uma playlist criada por você." : isStatus(error, 401) ? "Sua conexão com o Spotify expirou.\nConecte sua conta novamente nas configurações." : isStatus(error, 429) ? error.getMessage() : "Não foi possível carregar.\nVerifique a conexão e tente novamente.";
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
        Row row = new Row(); row.playlist = kind.equals("playlist");
        JSONObject source = item;
        if (!row.playlist) {
            if (item.has("track") || item.has("item")) { source = item.optJSONObject("item"); if (source == null) source = item.optJSONObject("track"); }
            if (source == null || source.optBoolean("is_local") || "episode".equals(source.optString("type")) || (source.has("is_playable") && !source.optBoolean("is_playable"))) return null;
        }
        row.id = source.optString("id", ""); row.uri = source.optString("uri", ""); row.title = source.optString("name", "Sem título");
        if (row.playlist) {
            if (row.id.isEmpty()) return null;
            JSONObject owner = source.optJSONObject("owner"); row.subtitle = owner == null ? "Playlist" : owner.optString("display_name", "Playlist");
            if (row.subtitle.isEmpty() || row.subtitle.equals("null")) row.subtitle = "Playlist";
            row.image = imageUrl(source.optJSONArray("images"));
        } else {
            if (!row.uri.startsWith("spotify:track:")) return null;
            row.durationMs = source.optLong("duration_ms"); JSONArray artists = source.optJSONArray("artists"); StringBuilder names = new StringBuilder();
            if (artists != null) for (int i = 0; i < artists.length(); i++) { JSONObject artist = artists.optJSONObject(i); if (artist == null) continue; if (names.length() > 0) names.append(", "); names.append(artist.optString("name", "")); }
            row.subtitle = names.length() == 0 ? "Artista desconhecido" : names.toString();
            JSONObject album = source.optJSONObject("album"); row.image = album == null ? "" : imageUrl(album.optJSONArray("images"));
        }
        return row;
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
        if (index < 0 || index >= rows.size()) return;
        Row row = rows.get(index);
        if (row.playlist) {
            playlistScreen = true; pageTitle.setText(row.title); pageSubtitle.setText(demo ? "Playlist de exemplo · prévia" : row.subtitle); listLabel.setText("MÚSICAS"); backButton.setVisibility(View.VISIBLE);
            if (demo) fillDemo(true); else loadPage("/playlists/" + row.id + "/items?limit=50", "track", false);
        } else if (canPlay()) {
            ArrayList<String> queue = new ArrayList<>(); int selectedIndex = 0;
            for (int i = 0; i < rows.size(); i++) { Row candidate = rows.get(i); if (!candidate.playlist && !candidate.uri.isEmpty()) { if (i == index) selectedIndex = queue.size(); queue.add(candidate.uri); } }
            if (!queue.isEmpty()) { player.play(queue, selectedIndex); setPlayerMessage("Preparando sua música…"); }
        }
    }

    private void fillDemo(boolean tracks) {
        rows.clear();
        String[] names = tracks ? new String[]{"Na direção do dia", "Horizonte azul", "Sem pressa", "Depois da curva", "Mais um caminho"} : new String[]{"Horizonte aberto", "No seu ritmo", "Volta para casa", "Novos caminhos"};
        String[] captions = {"Para acompanhar a paisagem", "Um novo ritmo para o dia", "Desacelere. Você está chegando.", "Descubra outra direção"};
        for (int i = 0; i < names.length; i++) { Row row = new Row(); row.title = names[i]; row.subtitle = tracks ? "Artista de exemplo · prévia" : captions[i]; row.playlist = !tracks; row.id = "demo" + i; row.image = ""; row.uri = ""; row.durationMs = (180 + i * 24) * 1000; rows.add(row); }
        adapter.notifyDataSetChanged(); status.setText(""); moreButton.setVisibility(View.GONE); retryButton.setVisibility(View.GONE);
    }

    private void renderDemoPlayer() {
        playerTitle.setText("Na direção do dia"); playerArtist.setText("Faixa de demonstração"); cover.setImageResource(R.drawable.preview_cover);
        playerStatus.setText("PRÉVIA · SEM REPRODUÇÃO"); seek.setProgress(0); seek.setEnabled(false);
        elapsed.setText("0:00"); duration.setText("—"); playButton.setGlyph("play");
    }

    private void loadProfile() {
        AuthManager owner = auth; SpotifyApi sourceApi = api; int session = sessionGeneration;
        network.execute(() -> {
            try {
                JSONObject profile = sourceApi.get("/me"); String name = profile.optString("display_name", "Sua conta");
                boolean allowed = !"free".equals(profile.optString("product"));
                main.post(() -> {
                    if (destroyed || demo || !mainScreen || owner != auth || session != sessionGeneration) return;
                    accountName = name.isEmpty() || name.equals("null") ? "Sua conta" : name; account.setText(accountName); premiumAllowed = allowed;
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
        artwork.bind(cover, state.optString("coverUrl", ""), 0);
        boolean playing = state.optBoolean("playing"), ready = state.optBoolean("connected");
        playButton.setGlyph(playing ? "pause" : "play"); playButton.setContentDescription(playing ? "Pausar" : "Reproduzir");
        playButton.setAlpha(ready ? 1f : .5f); previousButton.setAlpha(ready ? 1f : .4f); nextButton.setAlpha(ready ? 1f : .4f);
        seek.setEnabled(ready && state.optLong("durationMs") > 0);
        String error = state.optString("error", "");
        setPlayerMessage(!premiumAllowed ? "Use Spotify Premium para ouvir no carro." : !error.isEmpty() ? error : state.optBoolean("loading") ? "Preparando sua música…" : ready ? "ÁUDIO NESTE CARRO · SPOTIFY" : "Conectando ao Spotify…");
        updateProgress(); if (changedTrack && adapter != null) adapter.notifyDataSetChanged();
    }

    private void updateProgress() {
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
        if (demo) { new AlertDialog.Builder(this).setTitle("Você está na prévia").setMessage("Conecte seu Spotify para ouvir suas músicas no carro.")
                .setPositiveButton("Conectar Spotify", (dialog, which) -> { showLogin(); startPairing(); }).setNegativeButton("Continuar na prévia", null).setNeutralButton("Voltar", (dialog, which) -> showLogin()).show(); return; }
        new AlertDialog.Builder(this).setTitle(accountName).setItems(new String[]{"Desconectar Spotify"}, (dialog, which) -> logout()).setNegativeButton("Fechar", null).show();
    }

    private void logout() {
        generation++; sessionGeneration++; pairingGeneration++; connecting = false; premiumAllowed = true; accountName = "Sua conta";
        releasePlayer(); auth.logout(); playerState = new JSONObject(); showLogin();
    }

    private void sessionExpired() {
        logout(); qrStatus.setText("Sua sessão expirou. Conecte seu Spotify novamente.");
    }

    @Override public void onBackPressed() {
        if (mainScreen && playlistScreen) { openScreen(selected); return; }
        if (mainScreen && !selected.equals("home")) { openScreen("home"); return; }
        if (mainScreen && demo) { auth.cancel(); showLogin(); return; }
        super.onBackPressed();
    }

    private void setPlayerMessage(String message) { if (mainScreen && playerStatus != null) playerStatus.setText(message); }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }
    private static String formatTime(long millis) { long seconds = Math.max(0, millis / 1000); return (seconds / 60) + ":" + String.format(Locale.US, "%02d", seconds % 60); }

    private static final class Row {
        String id, uri, title, subtitle, image;
        boolean playlist;
        long durationMs;
    }

    private final class RowAdapter extends BaseAdapter {
        @Override public int getCount() { return rows.size(); }
        @Override public Row getItem(int position) { return rows.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View convert, ViewGroup parent) {
            RowHolder holder;
            if (convert == null) {
                LinearLayout row = horizontal(); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(dp(14), dp(10), dp(14), dp(10)); row.setMinimumHeight(dp(74));
                holder = new RowHolder(); holder.art = new ImageView(MainActivity.this); holder.art.setScaleType(ImageView.ScaleType.CENTER_CROP); holder.art.setBackground(shape(CARD, 0, 7)); holder.art.setClipToOutline(true);
                row.addView(holder.art, new LinearLayout.LayoutParams(dp(51), dp(51))); LinearLayout copy = vertical(); copy.setPadding(dp(12), 0, dp(6), 0);
                holder.title = label("", 16, TEXT); holder.title.setTypeface(medium); holder.title.setSingleLine(true); holder.title.setEllipsize(TextUtils.TruncateAt.END); copy.addView(holder.title);
                holder.subtitle = label("", 11, MUTED); holder.subtitle.setSingleLine(true); holder.subtitle.setEllipsize(TextUtils.TruncateAt.END); holder.subtitle.setPadding(0, dp(5), 0, 0); copy.addView(holder.subtitle);
                row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1)); holder.time = label("", 11, MUTED); row.addView(holder.time);
                holder.arrow = new GlyphView(MainActivity.this, "chevron", MUTED); row.addView(holder.arrow, new LinearLayout.LayoutParams(dp(23), dp(32)));
                convert = row; convert.setTag(holder);
            } else holder = (RowHolder) convert.getTag();
            Row item = getItem(position); holder.title.setText(item.title); holder.subtitle.setText(item.subtitle);
            boolean active = !demo && item.uri != null && !item.uri.isEmpty() && item.uri.equals(playerState.optString("uri"));
            holder.title.setTextColor(active ? ACCENT : TEXT); convert.setBackground(shape(active ? 0xff223e40 : CARD, 0, 12));
            holder.time.setText(item.playlist ? "" : formatTime(item.durationMs)); holder.arrow.setVisibility(item.playlist ? View.VISIBLE : View.GONE);
            artwork.bind(holder.art, item.image, position); convert.setContentDescription(item.title + ", " + item.subtitle);
            return convert;
        }
    }
    private static final class RowHolder { ImageView art; TextView title, subtitle, time; GlyphView arrow; }

    private LinearLayout brand() {
        LinearLayout row = horizontal(); row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(label("impulsefy", 20, TEXT)); return row;
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
