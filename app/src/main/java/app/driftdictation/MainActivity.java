package app.driftdictation;

import static app.driftdictation.I18n.plural;
import static app.driftdictation.I18n.tr;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.text.*;
import android.view.*;
import android.widget.*;
import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.text.DateFormat;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONObject;

public final class MainActivity extends Activity {
  private final Handler main = new Handler(Looper.getMainLooper());
  private final ExecutorService io = Executors.newSingleThreadExecutor();
  private final Deque<String> stack = new ArrayDeque<>();
  private Ui ui;
  private Vault vault;
  private SharedPreferences prefs;
  private Profile profile;
  private ModelCatalog catalog;
  private LinearLayout content;
  private String page = "", noteId = "", redoSession = "", doc = "privacy", templateId = "";
  static final String SUPPORT_EMAIL = "support@drift.example"; // Placeholder until the domain exists.
  private long seenRevision = Long.MIN_VALUE;
  private boolean loading, allowScreenshots;
  private int usageMonth;
  private List<NoteList.Note> notes = new ArrayList<>();
  // Live views of the current page.
  private Button record;
  private TextView status, noteStatus;
  private LinearLayout recovery, recentBox, notesBox, monthBox, wordsBox, modelList;
  private EditText editor, practice;
  private String editorBase = "", modelsSort = "recommended", modelsQuery = "";
  private boolean applying;
  /** The open note shows what you said instead of its tidied-up text; only while you look. */
  private boolean showOriginal;
  /** Settings → bb: the mode being looked at, a result to show once, and "change the chief". */
  private String bbModeShown = "";
  private String bbNotice = "";
  private boolean bbNoticeOk, changingChief;
  private TextView skillsLine;
  /** The name field waits for a pause in typing before the skills are updated in bb. */
  private final Runnable skillsAfterName = () -> ChiefSkills.sync(this, false);
  private final Runnable refresh =
      new Runnable() {
        public void run() {
          renderStatus();
          main.postDelayed(this, 750);
        }
      };

  @Override
  public void onCreate(Bundle saved) {
    super.onCreate(saved);
    I18n.init(this);
    QuietNotifications.initialize(this);
    ui = new Ui(this);
    vault = new Vault(this);
    prefs = getSharedPreferences("settings", 0);
    profile = new Profile(prefs);
    profile.forgetRetired();
    // Note text is hidden from screenshots and recents; debug builds may lift that for UI checks.
    allowScreenshots = BuildConfig.DEBUG && getIntent().getBooleanExtra("debugScreenshots", false);
    if ("always".equals(prefs.getString("bubbleMode", "")))
      prefs.edit().putString("bubbleMode", "recording").apply();
    try {
      new AudioArchive(this).prune();
      new Attachments(this).prune();
      catalog = new ModelCatalog(this);
      try (HistoryStore h = new HistoryStore(this)) {
        h.migrate(this);
      }
    } catch (Exception ex) {
      toast(tr("Could not load saved data. Your data has not been removed."));
    }
    // Left over from earlier versions: an OpenRouter key from before Drift's server (back then
    // under another name), the account at that server, in-app agents and their MCP servers, and
    // the model list the server sent.
    for (String old : new String[] {"apiKey", "accountToken", "agents", "mcpServers"})
      vault.remove(old);
    getSharedPreferences("catalog", 0).edit().clear().apply();
    if (BuildConfig.DEBUG && getIntent().getBooleanExtra("debugSeed", false)) seedSamples();
    String restored = saved == null ? null : saved.getString("page");
    if (restored != null && !restored.isEmpty()) {
      // Rotation or a language change recreates the activity; come back to the same page.
      ArrayList<String> back = saved.getStringArrayList("stack");
      if (back != null) stack.addAll(back);
      noteId = saved.getString("noteId", "");
      doc = saved.getString("doc", "privacy");
      templateId = saved.getString("templateId", "");
      usageMonth = saved.getInt("usageMonth");
      show(restored);
    } else show(prefs.getBoolean("onboarded", false) ? "home" : "welcome");
    if (saved == null) openFromIntent(getIntent());
    getOnBackInvokedDispatcher().registerOnBackInvokedCallback(0, this::goBack);
  }

  @Override
  protected void onSaveInstanceState(Bundle out) {
    super.onSaveInstanceState(out);
    if (page.equals("note")) saveEdits();
    out.putString("page", page);
    out.putStringArrayList("stack", new ArrayList<>(stack));
    out.putString("noteId", noteId);
    out.putString("doc", doc);
    out.putString("templateId", templateId);
    out.putInt("usageMonth", usageMonth);
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    openFromIntent(intent);
  }

  /** The tile or the floating button open Talk Router on a page, e.g. the key when there is none. */
  private void openFromIntent(Intent intent) {
    String target = intent == null ? null : intent.getStringExtra("open");
    if ("bb".equals(target)) {
      // Back from signing in to bb Connect in the browser (BbSignInActivity).
      bbNotice = String.valueOf(intent.getStringExtra("bbResult"));
      bbNoticeOk = intent.getBooleanExtra("bbOk", false);
      bbModeShown = BbBridge.CONNECT;
      intent.removeExtra("open");
      if (prefs.getBoolean("onboarded", false)) {
        stack.clear();
        stack.push("home");
        stack.push("settings");
        show("bb");
      } else show("bb1");
      return;
    }
    if (target != null && prefs.getBoolean("onboarded", false)) {
      // From the tile: recording isn't possible right now, e.g. without a key.
      String message = intent.getStringExtra("message");
      intent.removeExtra("open");
      intent.removeExtra("message");
      stack.clear();
      stack.push("home");
      show(target.equals("key") ? "key" : "home");
      if (message != null) toast(message);
    }
  }

  /**
   * Debug builds only: sample notes for screenshots and manual checks, in German when the app runs
   * in German and in English otherwise. Not translated through tr(), so they stay out of the
   * translation files.
   */
  private void seedSamples() {
    boolean de = Locale.getDefault().getLanguage().equals("de");
    String[][] samples =
        de
            ? new String[][] {
              {"microsoft/mai-transcribe-2", "Tag eins früh Alfama, an Tag zwei die Fähre nach Cacilhas. Den Aussichtspunkt für Donnerstag einplanen, nicht Freitag.", "42"},
              {"microsoft/mai-transcribe-2", "Morgen um zehn mit Jana telefonieren, danach die Rechnung für September schicken.", "18"},
              {"openai/gpt-transcribe", "Idee fürs Onboarding: zuerst den Button zeigen, den Schlüssel später erklären.", "72"}
            }
            : new String[][] {
              {"microsoft/mai-transcribe-2", "Day one Alfama early, then the ferry to Cacilhas on day two. Plan the viewpoint for Thursday, not Friday.", "42"},
              {"microsoft/mai-transcribe-2", "Call Jana tomorrow at ten, then send the invoice for September.", "18"},
              {"openai/gpt-transcribe", "Idea for the onboarding: show the button first, explain the key later.", "72"}
            };
    String said =
        de
            ? "ähm also Herr Weber, der Küchenhahn tropft seit Montag, könnte jemand vorbeikommen, ich bin ab fünf zu Hause, mach daraus bitte eine höfliche Mail"
            : "um so Mr Weber the kitchen tap is dripping since Monday, could someone come, I'm home after five, make that a polite email please";
    String processed =
        de
            ? "Sehr geehrter Herr Weber,\n\nder Wasserhahn in der Küche tropft seit Montag. Könnte sich das diese Woche jemand ansehen? An den meisten Tagen bin ich ab 17 Uhr zu Hause.\n\nViele Grüße"
            : "Dear Mr. Weber,\n\nThe kitchen tap has been dripping since Monday. Could someone take a look this week? I'm home after 5 pm on most days.\n\nBest regards";
    try (HistoryStore h = new HistoryStore(this)) {
      long now = System.currentTimeMillis();
      for (int i = 0; i < samples.length; i++) {
        HistoryEntry e =
            HistoryEntry.create(
                UUID.randomUUID().toString(), samples[i][0], Double.parseDouble(samples[i][2]));
        e.json
            .put("created", now - (i + 1) * 20 * 3_600_000L)
            .put("text", samples[i][1])
            .put("raw", samples[i][1])
            .put("status", "complete")
            .put("requests", 1)
            .put("processingMs", 2400)
            .put("knownCost", String.valueOf(0.0001 * Double.parseDouble(samples[i][2]) / 2));
        if (i < 2) {
          // The last sample stays as notes from before the activity log were.
          BigDecimal check = new BigDecimal("0.00002");
          e.step("speech", samples[i][0], 1700, e.cost().subtract(check), null);
          e.step("jev", Jev.MODEL, 400, check, null).put("answer", "refine 0.06");
          e.json.put("jevCost", check.toPlainString());
        }
        h.save(e);
      }
      HistoryEntry email = HistoryEntry.create(UUID.randomUUID().toString(), samples[1][0], 21);
      email
          .json
          .put("created", now - 3_600_000L)
          .put("raw", said)
          .put("original", said)
          .put("processed", processed)
          .put("text", processed)
          .put("version", "processed")
          .put("status", "complete")
          .put("requests", 1)
          .put("knownCost", "0.00107")
          .put("textModel", TextModel.MODEL)
          .put("textCost", "0.000323")
          .put("jevCost", "0.00002");
      email.step("speech", email.model(), 1900, new BigDecimal("0.000727"), null);
      email.step("jev", Jev.MODEL, 400, new BigDecimal("0.00002"), null).put("answer", "refine 0.97");
      email.step("refine", TextModel.MODEL, 2100, new BigDecimal("0.000323"), null);
      h.save(email);
      // Short silent audio, so "Redo with a smarter model" is available as it is on real notes.
      File silence = new File(getCacheDir(), "sample.pcm");
      try (java.io.FileOutputStream out = new java.io.FileOutputStream(silence)) {
        out.write(new byte[AudioFiles.BYTES_PER_SECOND * 2]);
      }
      new AudioArchive(this).retain(email.id(), silence);
      prefs.edit().putLong("historyRevision", System.nanoTime()).apply();
    } catch (Exception ex) {
      toast("Could not add samples");
    }
  }

  // ---------------------------------------------------------------- navigation

  private void open(String next) {
    stack.push(page);
    show(next);
  }

  private void goBack() {
    if (stack.isEmpty()) finish();
    else show(stack.pop());
  }

  private void show(String next) {
    if (page.equals("note")) saveEdits();
    page = next;
    if (!allowScreenshots
        && (next.equals("note") || next.equals("notes") || next.equals("activity")))
      getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
    record = null;
    status = null;
    noteStatus = null;
    skillsLine = null;
    recovery = recentBox = notesBox = monthBox = wordsBox = modelList = null;
    editor = practice = null;
    LinearLayout root = ui.vertical();
    root.setBackgroundColor(next.equals("settings") ? Ui.SOFT_BG : Ui.BG);
    root.setOnApplyWindowInsetsListener(
        (v, insets) -> {
          var i = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
          v.setPadding(i.left, i.top, i.right, i.bottom);
          return insets;
        });
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    root.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
    content = ui.vertical();
    content.setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(28));
    scroll.addView(content, new ScrollView.LayoutParams(-1, -1));
    switch (next) {
      case "welcome" -> welcomePage();
      case "key1" -> keyPage(true);
      case "allow1" -> allowPage();
      case "how1" -> howPage();
      case "start1" -> startPage();
      case "insert1" -> insertPage();
      case "start" -> startSettingsPage();
      case "try1" -> tryPage();
      case "bb1" -> bbPage(true);
      case "chief1" -> chiefPage(true);
      case "ntfy1" -> ntfyPage(true);
      case "bb" -> bbPage(false);
      case "chief" -> chiefPage(false);
      case "ntfy" -> ntfyPage(false);
      case "setup" -> setupPage();
      case "notes" -> notesPage();
      case "note" -> notePage();
      case "usage" -> usagePage();
      case "settings" -> settingsPage();
      case "key" -> keyPage(false);
      case "anthropic" -> anthropicKeyPage();
      case "about" -> aboutPage();
      case "words" -> wordsPage();
      case "models" -> modelsPage();
      case "refine", "processing" -> refinePage();
      case "legal" -> legalPage();
      case "doc" -> docPage();
      case "templates" -> templatesPage();
      case "activity" -> activityPage();
      case "template" -> templatePage();
      default -> homePage();
    }
    setContentView(root);
    seenRevision = prefs.getLong("historyRevision", 0);
    renderStatus();
  }

  private View spacer() {
    View v = new View(this);
    content.addView(v, new LinearLayout.LayoutParams(-1, 0, 1));
    return v;
  }

  private void heading(String back, String title, String subtitle) {
    if (back != null) content.addView(ui.back(back, v -> goBack()));
    ui.add(content, ui.title(title, 30), back == null ? 8 : 4);
    if (subtitle != null) ui.add(content, ui.text(subtitle, 15, Ui.MUTED), 8);
  }

  /** Setup pages in order, after the welcome page; the last three (bb) are optional. */
  private static final List<String> SETUP =
      List.of("key1", "allow1", "how1", "start1", "insert1", "try1", "bb1", "chief1", "ntfy1");

  /** Progress dots for the setup page shown now, and an optional Skip to the next page. */
  private void steps(boolean skippable) {
    List<String> pages = SETUP;
    int current = Math.max(0, pages.indexOf(page));
    LinearLayout row = ui.horizontal();
    for (int i = 0; i < pages.size(); i++) {
      View dot = new View(this);
      dot.setBackground(ui.shape(i <= current ? Ui.TEAL : Ui.LINE, 0, 3));
      LinearLayout.LayoutParams l =
          new LinearLayout.LayoutParams(ui.dp(i == current ? 22 : 6), ui.dp(6));
      l.rightMargin = ui.dp(6);
      row.addView(dot, l);
    }
    row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
    if (skippable) {
      TextView skip = ui.text(tr("Skip"), 15, Ui.MUTED);
      skip.setMinHeight(ui.dp(44));
      skip.setGravity(Gravity.CENTER_VERTICAL);
      skip.setOnClickListener(v -> nextSetupPage());
      row.addView(skip);
    }
    ui.add(content, row, 8);
  }

  private void nextSetupPage() {
    List<String> pages = SETUP;
    int i = pages.indexOf(page);
    if (i >= 0 && i + 1 < pages.size()) open(pages.get(i + 1));
    else finishSetup();
  }

  private void finishSetup() {
    prefs.edit().putBoolean("onboarded", true).apply();
    stack.clear();
    show("home");
  }

  private void continueButton(String label) {
    spacer();
    ui.add(content, ui.button(label, Ui.Kind.PRIMARY, v -> nextSetupPage()), 24);
  }

  // ---------------------------------------------------------------- onboarding

  private void welcomePage() {
    FrameLayout art = new FrameLayout(this);
    art.setBackground(ui.shape(Ui.PANEL, 0, 28));
    LinearLayout page1 = ui.card(Ui.BG, Ui.LINE);
    page1.addView(ui.label(tr("Article")));
    View photo = new View(this);
    photo.setBackground(ui.shape(0xffd8dcd3, 0, 8));
    page1.addView(photo, new LinearLayout.LayoutParams(-1, ui.dp(70)));
    FrameLayout.LayoutParams p1 = new FrameLayout.LayoutParams(ui.dp(170), ui.dp(200));
    p1.leftMargin = ui.dp(28);
    p1.topMargin = ui.dp(30);
    page1.setRotation(-4);
    art.addView(page1, p1);
    TextView pill = ui.strong("●   2:14    ■", 16);
    pill.setTextColor(Ui.BG);
    pill.setBackground(ui.shape(Ui.REC, 0, 999));
    pill.setPadding(ui.dp(18), ui.dp(12), ui.dp(18), ui.dp(12));
    FrameLayout.LayoutParams pp =
        new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
    pp.rightMargin = ui.dp(28);
    pp.bottomMargin = ui.dp(32);
    art.addView(pill, pp);
    content.addView(art, new LinearLayout.LayoutParams(-1, ui.dp(300)));
    ui.add(content, ui.title(tr("Talk anywhere."), 34), 28);
    ui.add(
        content,
        ui.text(
            tr("Start Talk Router, then keep using any app: read, look things up, switch. When you stop, your words are written down, ready to insert where you type. Then decide: ✨ Refine makes them the text you need, and your chief of staff takes on tasks."),
            16,
            Ui.MUTED),
        12);
    spacer();
    ui.add(content, ui.button(tr("Get started"), Ui.Kind.PRIMARY, v -> open(SETUP.get(0))), 24);
    TextView note =
        ui.text(tr("Open source. Your own OpenRouter key, no Talk Router server in between."), 13, Ui.MUTED);
    note.setGravity(Gravity.CENTER);
    ui.add(content, note, 12);
  }

  /** The user's OpenRouter key: the first setup page, and Settings → OpenRouter key. */
  private void keyPage(boolean onboarding) {
    boolean saved = OpenRouter.hasKey(this);
    if (onboarding) {
      steps(false);
      heading(
          null,
          tr("Your OpenRouter key"),
          tr("Talk Router calls the speech and text models through OpenRouter, with your own key. You pay OpenRouter for what you use; Talk Router has no server and takes no cut."));
    } else
      heading(
          tr("Settings"),
          tr("OpenRouter key"),
          tr("Talk Router calls the speech and text models through OpenRouter with this key."));
    if (saved)
      ui.add(content, ui.text(tr("✓  A key is saved on this phone, encrypted."), 14, Ui.TEAL), 16);
    EditText input = ui.input("", saved ? tr("Paste a new key to replace it") : "sk-or-v1-…", false);
    // Hidden like a password: the key is a secret, also on screen.
    android.graphics.Typeface face = input.getTypeface();
    input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
    input.setTypeface(face); // Password fields switch to monospace otherwise.
    ui.add(content, input, 16);
    TextView result = ui.add(content, ui.text("", 13, Ui.MUTED), 6);
    result.setVisibility(View.GONE);
    Button[] check = {null};
    check[0] =
        ui.add(
            content,
            ui.button(
                tr("Check and save"),
                saved ? Ui.Kind.OUTLINE : Ui.Kind.PRIMARY,
                v -> checkKey(input.getText().toString().trim(), check[0], result, onboarding)),
            10);
    ui.add(
        content,
        ui.button(
            tr("Get a key on openrouter.ai"),
            Ui.Kind.QUIET,
            v -> openUrl("https://openrouter.ai/keys")),
        4);
    ui.add(
        content,
        ui.text(
            tr("Create a key there and copy it, then paste it above. Add a few dollars of credits on OpenRouter too: a one-minute note usually costs well under one cent."),
            13,
            Ui.MUTED),
        4);
    if (onboarding) {
      if (saved) continueButton(tr("Continue"));
      return;
    }
    if (saved) {
      Button remove =
          ui.button(
              tr("Remove key"),
              Ui.Kind.QUIET,
              v ->
                  new AlertDialog.Builder(this)
                      .setTitle(tr("Remove your OpenRouter key?"))
                      .setMessage(tr("Talk Router can't dictate until you add a key again."))
                      .setPositiveButton(
                          tr("Remove"),
                          (d, w) -> {
                            vault.remove(OpenRouter.KEY);
                            show("key");
                          })
                      .setNegativeButton(tr("Keep"), null)
                      .show());
      remove.setTextColor(Ui.WARN);
      ui.add(content, remove, 18);
    }
    ui.add(
        content,
        ui.button(
            tr("Credits and usage on openrouter.ai"),
            Ui.Kind.QUIET,
            v -> openUrl("https://openrouter.ai/settings/credits")),
        saved ? 0 : 18);
  }

  /** Asks OpenRouter whether the key works, then saves it; setup moves on by itself. */
  private void checkKey(String key, Button check, TextView result, boolean onboarding) {
    if (key.isEmpty()) {
      toast(tr("Paste your key first"));
      return;
    }
    check.setEnabled(false);
    result.setVisibility(View.VISIBLE);
    result.setTextColor(Ui.MUTED);
    result.setText(tr("Checking…"));
    io.execute(
        () -> {
          String failed = null;
          try {
            OpenRouter.check(key);
            vault.put(OpenRouter.KEY, key);
          } catch (Exception ex) {
            failed = DictationService.friendly(ex);
          }
          String message = failed;
          main.post(
              () -> {
                if (message != null) {
                  check.setEnabled(true);
                  result.setTextColor(Ui.WARN);
                  result.setText(message);
                  return;
                }
                toast(tr("Key saved"));
                if (onboarding) nextSetupPage();
                else show("key");
              });
        });
  }

  /** Settings → Anthropic key (optional): Refine goes to Anthropic directly, on its credits. */
  private void anthropicKeyPage() {
    boolean saved = Anthropic.hasKey(this);
    heading(
        tr("Settings"),
        tr("Anthropic key"),
        tr("Optional. With your own Anthropic key, ✨ Refine goes to Claude directly and is paid from your Anthropic credits. Speech and Jev stay on OpenRouter. If Anthropic fails, Refine goes through OpenRouter instead."));
    if (saved)
      ui.add(content, ui.text(tr("✓  A key is saved on this phone, encrypted."), 14, Ui.TEAL), 16);
    EditText input = ui.input("", saved ? tr("Paste a new key to replace it") : "sk-ant-…", false);
    android.graphics.Typeface face = input.getTypeface();
    input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
    input.setTypeface(face);
    ui.add(content, input, 16);
    TextView result = ui.add(content, ui.text("", 13, Ui.MUTED), 6);
    result.setVisibility(View.GONE);
    Button[] check = {null};
    check[0] =
        ui.add(
            content,
            ui.button(
                tr("Check and save"),
                saved ? Ui.Kind.OUTLINE : Ui.Kind.PRIMARY,
                v -> {
                  String key = input.getText().toString().trim();
                  if (key.isEmpty()) {
                    toast(tr("Paste your key first"));
                    return;
                  }
                  check[0].setEnabled(false);
                  result.setVisibility(View.VISIBLE);
                  result.setTextColor(Ui.MUTED);
                  result.setText(tr("Checking…"));
                  io.execute(
                      () -> {
                        String failed = null;
                        try {
                          Anthropic.check(key);
                          vault.put(Anthropic.KEY, key);
                        } catch (Exception ex) {
                          failed = DictationService.friendly(ex);
                        }
                        String message = failed;
                        main.post(
                            () -> {
                              if (message != null) {
                                check[0].setEnabled(true);
                                result.setTextColor(Ui.WARN);
                                result.setText(message);
                                return;
                              }
                              toast(tr("Key saved"));
                              show("anthropic");
                            });
                      });
                }),
            10);
    ui.add(
        content,
        ui.button(tr("Get a key on console.anthropic.com"), Ui.Kind.QUIET, v -> openUrl("https://console.anthropic.com/settings/keys")),
        4);
    if (saved) {
      Button remove =
          ui.button(
              tr("Remove key"),
              Ui.Kind.QUIET,
              v ->
                  new AlertDialog.Builder(this)
                      .setTitle(tr("Remove your Anthropic key?"))
                      .setMessage(tr("Refine goes through OpenRouter again."))
                      .setPositiveButton(
                          tr("Remove"),
                          (d, w) -> {
                            vault.remove(Anthropic.KEY);
                            show("anthropic");
                          })
                      .setNegativeButton(tr("Keep"), null)
                      .show());
      remove.setTextColor(Ui.WARN);
      ui.add(content, remove, 18);
    }
  }

  private void allowPage() {
    steps(false);
    heading(null, tr("Microphone and privacy"), null);
    microphoneCard();
    dataCard();
    spacer();
    Button next =
        ui.add(
            content,
            ui.button(
                tr("Continue"),
                Ui.Kind.PRIMARY,
                v -> {
                  if (!microphone()) toast(tr("Allow the microphone first"));
                  else nextSetupPage();
                }),
            24);
    next.setAlpha(microphone() ? 1f : 0.5f);
  }

  private void microphoneCard() {
    LinearLayout mic = ui.add(content, ui.card(Ui.BG, Ui.LINE), 12);
    mic.addView(ui.strong(tr("Microphone"), 16));
    ui.add(mic, ui.text(tr("Only while you record."), 14, Ui.MUTED), 4);
    if (microphone()) ui.add(mic, ui.text(tr("✓  Allowed"), 14, Ui.TEAL), 8);
    else ui.add(mic, ui.button(tr("Allow microphone"), Ui.Kind.PRIMARY, v -> askMicrophone()), 10);
  }

  /** What leaves the phone, and where it goes. */
  private void dataCard() {
    LinearLayout data = ui.add(content, ui.card(Ui.TEAL_TINT, Ui.BAR), 12);
    data.addView(ui.strong(tr("What leaves your phone"), 16));
    ui.add(
        data,
        ui.text(
            tr("• Your notes, audio, words and templates stay on this phone.\n• To write down what you said, your recording goes straight to OpenRouter with your key, and on to the speech model's provider. The text goes the same way to Jev, a fast model that decides whether to refine it, with your template names. When it's refined, it goes to the text model, with what you told Talk Router about you, your words, the template you named and what you attached.\n• If you connect bb, a task you hand to your chief of staff goes to your bb, with the text you pasted.\n• Talk Router has no server and collects nothing. OpenRouter and the model providers handle the data under their own policies."),
            14,
            Ui.BODY),
        6);
    ui.add(
        data, ui.button(tr("Read the privacy policy"), Ui.Kind.QUIET, v -> openDoc("privacy")), 6);
  }

  private void aboutPage() {
    heading(
        tr("Settings"),
        tr("About you"),
        tr("Helps Talk Router spell names right and process each note for its purpose."));
    ui.add(content, ui.strong(tr("What you usually dictate"), 15), 20);
    Ui.Flow topics = ui.add(content, ui.flow(), 12);
    for (String t : Profile.TOPICS) {
      TextView chip = ui.chip(tr(t), profile.topics().contains(t), null);
      chip.setOnClickListener(
          v -> {
            List<String> now = new ArrayList<>(profile.topics());
            boolean on = !now.remove(t);
            if (on) now.add(t);
            profile.setTopics(now);
            ui.styleChip(chip, on);
          });
      topics.addView(chip);
    }
    ui.add(content, ui.strong(tr("Anything else Talk Router should know?"), 15), 22);
    EditText about =
        ui.input(
            profile.about(),
            tr("For example: I build an app called Talk Router. Jana is a colleague. Write numbers as digits."),
            true);
    about.setFilters(new InputFilter[] {new InputFilter.LengthFilter(2000)});
    ui.add(content, about, 10);
    autosave(about, s -> profile.setAbout(s));
  }

  private void wordsPage() {
    heading(
        tr("Settings"),
        tr("Words"),
        tr("Used to spell names and terms right, in transcription and processing."));
    LinearLayout addRow = ui.horizontal();
    EditText input = ui.input("", tr("Add a word or name"), false);
    addRow.addView(input, new LinearLayout.LayoutParams(0, -2, 1));
    Button add =
        ui.button(
            tr("Add"),
            Ui.Kind.OUTLINE,
            v -> {
              profile.addWord(input.getText().toString());
              input.setText("");
              renderWords();
            });
    LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(-2, -2);
    al.leftMargin = ui.dp(8);
    addRow.addView(add, al);
    input.setOnEditorActionListener(
        (v, action, event) -> {
          add.performClick();
          return true;
        });
    ui.add(content, addRow, 18);
    wordsBox = ui.add(content, ui.vertical(), 8);
    renderWords();
  }

  private void renderWords() {
    if (wordsBox == null) return;
    wordsBox.removeAllViews();
    List<String> words = profile.words();
    ui.add(wordsBox, ui.label(tr("Your words")), 22);
    if (words.isEmpty()) ui.add(wordsBox, ui.text(tr("No words yet."), 14, Ui.MUTED), 8);
    else {
      Ui.Flow flow = ui.add(wordsBox, ui.flow(), 10);
      for (String w : words) {
        TextView chip =
            ui.chip(
                w + "   ✕",
                false,
                v -> {
                  profile.removeWord(w);
                  renderWords();
                });
        chip.setBackground(ui.shape(Ui.PANEL, 0, 999));
        chip.setContentDescription(tr("Remove %1$s", w));
        flow.addView(chip);
      }
    }
  }

  private static String exampleBefore() {
    return tr("Dear Mr. Weber, uh, the tap in the kitchen, no actually the one in the bathroom, has been dripping since Monday. Oh and make that a bit more formal please.");
  }

  private static String exampleAfter() {
    return tr("Dear Mr. Weber,\n\nI would like to let you know that the bathroom tap has been dripping since Monday.");
  }

  /** What happens after you stop: the transcript first, then the floating panel's choices. */
  private void howPage() {
    steps(false);
    heading(
        null,
        tr("Transcript first, then you decide"),
        tr("When you stop, your words are written down and a small panel floats over the app you're in."));
    LinearLayout panel = ui.add(content, ui.card(Ui.BG, Ui.LINE), 16);
    String[][] rows = {
      {tr("Insert"), tr("Puts the text at the cursor, where you're typing.")},
      {tr("✨ Refine"), tr("%1$s makes it the text you need: an email, a reply, a tidy note, your template. Then Insert refined.", TextModel.NAME)},
      {tr("🧑‍💼 Chief"), tr("Hands it to your chief of staff in bb, if you set one up. Then Open chat ↗.")},
      {"✕", tr("Closes the panel. The note stays in Talk Router.")}
    };
    for (int i = 0; i < rows.length; i++) {
      ui.add(panel, ui.strong(rows[i][0], 15), i == 0 ? 0 : 12);
      ui.add(panel, ui.text(rows[i][1], 14, Ui.MUTED), 2);
    }
    ui.add(
        content,
        ui.text(
            tr("You can tap Refine or Chief while you're still talking. Ask in the same breath (“make this an email”, “use my apartment application”) and Refine starts by itself. Tasks go to your chief by themselves only when you address it: “chief of staff, …”."),
            14,
            Ui.BODY),
        14);
    example();
    ui.add(
        content,
        ui.text(
            tr("Each note shows what it cost, as OpenRouter reports it. Jev's decision costs about $0.00002, a minute of speech with the default model about $0.002."),
            13,
            Ui.MUTED),
        12);
    continueButton(tr("Continue"));
  }

  /** How to start and stop from any app: the tile or a volume key, and the floating panel. */
  private void startPage() {
    steps(true);
    heading(
        null,
        tr("Start from any app"),
        tr("You don't need to open Talk Router. Start and stop wherever you are."));
    tileCard(tr("1 · Quick Settings tile"));
    volumeCard(tr("2 · Or hold a volume key"));
    overlayCard(tr("3 · Floating panel"));
    continueButton(tr("Continue"));
  }

  /** Setup: the Accessibility permission, for Insert and the volume key. */
  private void insertPage() {
    steps(true);
    heading(
        null,
        tr("Insert where you type"),
        tr("With one permission, Insert puts your text right at the cursor, and holding a volume key starts a dictation. Without it, Talk Router copies the text and you paste it."));
    accessCard();
    continueButton(tr("Continue"));
  }

  /** Settings → Start and insert. */
  private void startSettingsPage() {
    heading(tr("Settings"), tr("Start and insert"), tr("Start with the tile or a volume key; Insert puts the text where you type."));
    tileCard(tr("Quick Settings tile"));
    volumeCard(tr("Hold a volume key"));
    accessCard();
  }

  private String startLabel() {
    String key = prefs.getString(TalkAccessibility.VOLUME_KEY, TalkAccessibility.OFF);
    String start =
        switch (key) {
          case TalkAccessibility.DOWN -> tr("Tile or hold volume down");
          case TalkAccessibility.UP -> tr("Tile or hold volume up");
          default -> tr("Tile");
        };
    return start + " · " + (TalkAccessibility.enabled(this) ? tr("Insert on") : tr("Insert copies"));
  }

  private void tileCard(String title) {
    LinearLayout tile = ui.add(content, ui.card(Ui.BG, Ui.LINE), 16);
    tile.addView(ui.strong(title, 16));
    ui.add(
        tile,
        ui.text(
            tr("Swipe down from the top of the screen and tap Talk Router to start, tap it again to stop. Android adds new tiles at the end: swipe left to the last page, or tap the pencil and drag Talk Router to the front."),
            14,
            Ui.MUTED),
        4);
    if (prefs.getBoolean("tileAdded", false))
      ui.add(tile, ui.text(tr("✓  Added"), 14, Ui.TEAL), 8);
    else
      ui.add(tile, ui.button(tr("Add Talk Router to Quick Settings"), Ui.Kind.PRIMARY, v -> addTile()), 10);
  }

  /** Off, volume down or volume up, and how long to hold it. */
  private void volumeCard(String title) {
    LinearLayout card = ui.add(content, ui.card(Ui.BG, Ui.LINE), 12);
    card.addView(ui.strong(title, 16));
    ui.add(
        card,
        ui.text(
            tr("Hold the key to start, hold it or press it again to stop. A short press still changes the volume. Works while the screen is on and unlocked, not during calls."),
            14,
            Ui.MUTED),
        4);
    String key = prefs.getString(TalkAccessibility.VOLUME_KEY, TalkAccessibility.OFF);
    Ui.Flow keys = ui.add(card, ui.flow(), 10);
    String[][] options = {
      {TalkAccessibility.OFF, tr("Off")},
      {TalkAccessibility.DOWN, tr("Volume down")},
      {TalkAccessibility.UP, tr("Volume up")}
    };
    for (String[] o : options)
      keys.addView(
          ui.chip(
              o[1],
              key.equals(o[0]),
              v -> {
                prefs.edit().putString(TalkAccessibility.VOLUME_KEY, o[0]).apply();
                show(page);
              }));
    if (key.equals(TalkAccessibility.OFF)) return;
    ui.add(card, ui.text(tr("Hold for"), 13, Ui.MUTED), 12);
    int hold = TalkAccessibility.holdMs(prefs);
    Ui.Flow holds = ui.add(card, ui.flow(), 6);
    for (int ms : new int[] {300, 500, 800, 1000})
      holds.addView(
          ui.chip(
              String.format(Locale.getDefault(), "%.1f s", ms / 1000.0),
              hold == ms,
              v -> {
                prefs.edit().putInt(TalkAccessibility.HOLD_MS, ms).apply();
                show(page);
              }));
    if (!TalkAccessibility.enabled(this))
      ui.add(card, ui.text(tr("Needs the Accessibility permission: see Insert."), 13, Ui.WARN), 10);
  }

  /** The Accessibility permission: what it's for, what it doesn't do, and where to turn it on. */
  private void accessCard() {
    LinearLayout card = ui.add(content, ui.card(Ui.TEAL_TINT, Ui.BAR), 12);
    card.addView(ui.strong(tr("Insert · Accessibility permission"), 16));
    ui.add(
        card,
        ui.text(
            tr("Talk Router uses Android's Accessibility permission for two things only: to put your text at the cursor when you tap Insert, and to notice when you hold the volume key you chose. It asks for no accessibility events and reads nothing on your screen; it looks up the text field you're typing in only when you tap Insert. Talk Router is open source, so you can check."),
            14,
            Ui.BODY),
        4);
    if (TalkAccessibility.enabled(this)) {
      ui.add(card, ui.text(tr("✓  On"), 14, Ui.TEAL), 8);
      return;
    }
    ui.add(
        card,
        ui.button(tr("Open Accessibility settings"), Ui.Kind.PRIMARY, v -> openAccessibility()),
        10);
    ui.add(
        card,
        ui.text(
            tr("Tap Talk Router there and switch it on. If Android says the setting is restricted, open App info → ⋮ → Allow restricted settings, then try again."),
            12,
            Ui.MUTED),
        8);
  }

  /** Android's Accessibility settings, with Talk Router highlighted where Settings supports it. */
  private void openAccessibility() {
    String me = new ComponentName(this, TalkAccessibility.class).flattenToString();
    Bundle args = new Bundle();
    args.putString(":settings:fragment_args_key", me);
    try {
      startActivity(
          new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
              .putExtra(":settings:fragment_args_key", me)
              .putExtra(":settings:show_fragment_args", args));
    } catch (ActivityNotFoundException ex) {
      toast(tr("Open Android settings → Accessibility → Talk Router."));
    }
  }

  private void overlayCard(String title) {
    LinearLayout overlay = ui.add(content, ui.card(Ui.BG, Ui.LINE), 12);
    overlay.addView(ui.strong(title, 16));
    ui.add(
        overlay,
        ui.text(
            tr("While you dictate, a small panel stays on top of other apps: tap ■ to stop, then Insert, Refine or Chief. To allow it, Android shows a list of apps: tap Talk Router, switch it on, and come back."),
            14,
            Ui.MUTED),
        4);
    if (Settings.canDrawOverlays(this)) {
      ui.add(overlay, ui.text(tr("✓  Allowed"), 14, Ui.TEAL), 8);
      return;
    }
    ui.add(
        overlay,
        ui.button(
            tr("Allow display over other apps"),
            Ui.Kind.OUTLINE,
            v ->
                startActivity(
                    new Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())))),
        10);
    ui.add(
        overlay,
        ui.text(
            tr("Optional. If Android won't let you turn it on, open App info → ⋮ → Allow restricted settings, then try again."),
            12,
            Ui.MUTED),
        8);
  }

  /** The last setup page: a first dictation, pasted into a box, the way it works in any app. */
  private void tryPage() {
    steps(false);
    heading(null, tr("Try it once"), null);
    ui.add(
        content,
        ui.text(
            tr("1. Tap Start and say a sentence.\n2. Tap Stop. Talk Router writes it down.\n3. Tap the box below, then 📥 Insert on the floating panel. Your clipboard stays as it was."),
            15,
            Ui.BODY),
        12);
    record = ui.add(content, ui.button(tr("Start"), Ui.Kind.PRIMARY, v -> toggleRecording()), 16);
    record.setMinHeight(ui.dp(56));
    status = ui.add(content, ui.text("", 13, Ui.MUTED), 6);
    status.setGravity(Gravity.CENTER);
    practice = ui.input("", tr("Insert here"), true);
    practice.setMinLines(3);
    practice.setBackground(ui.shape(Ui.PANEL, 0, 16));
    practice.setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(14));
    ui.add(content, practice, 16);
    TextView done =
        ui.add(
            content,
            ui.text(
                tr("That's it. It works the same in any app: start, talk, stop, insert. Your notes are saved in Talk Router, where you can also copy them or switch back to exactly what you said."),
                15,
                Ui.TEAL),
            12);
    done.setVisibility(View.GONE);
    Button finish = ui.button(tr("Continue"), Ui.Kind.PRIMARY, v -> nextSetupPage());
    Button skip = ui.button(tr("Skip"), Ui.Kind.QUIET, v -> nextSetupPage());
    finish.setVisibility(View.GONE);
    practice.addTextChangedListener(
        after(
            t -> {
              boolean pasted = !t.isBlank();
              done.setVisibility(pasted ? View.VISIBLE : View.GONE);
              finish.setVisibility(pasted ? View.VISIBLE : View.GONE);
              skip.setVisibility(pasted ? View.GONE : View.VISIBLE);
            }));
    spacer();
    ui.add(content, finish, 24);
    ui.add(content, skip, 0);
  }

  private void example() {
    LinearLayout before = ui.add(content, ui.card(Ui.PANEL, 0), 18);
    before.addView(ui.label(tr("You say")));
    ui.add(before, ui.text(tr("“%1$s”", exampleBefore()), 14, Ui.BODY), 6);
    LinearLayout after = ui.add(content, ui.card(Ui.TEAL_TINT, Ui.BAR), 8);
    after.addView(ui.label(tr("Talk Router writes")));
    ui.add(after, ui.text(exampleAfter(), 14, Ui.INK), 6);
  }

  /** Settings → Permissions: the same cards as in setup. */
  private void setupPage() {
    heading(tr("Settings"), tr("Permissions"), null);
    microphoneCard();
    overlayCard(tr("Floating panel · optional"));
    accessCard();
    dataCard();
    ui.add(
        content,
        ui.text(
            tr("Not needed: screen recording, notifications, contacts, location."),
            13,
            Ui.MUTED),
        14);
  }

  private void askMicrophone() {
    if (prefs.getBoolean("permissionsRequested", false)
        && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) appInfo();
    else {
      prefs.edit().putBoolean("permissionsRequested", true).apply();
      requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, 10);
    }
  }

  @Override
  public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
    super.onRequestPermissionsResult(code, permissions, results);
    if (page.equals("setup") || page.equals("allow1")) show(page);
  }

  // ---------------------------------------------------------------- bb

  /** Settings → bb: connection, chief of staff and notifications, each a page. */
  private void bbRows() {
    BbBridge bb = new BbBridge(this);
    ui.add(content, ui.label(tr("bb")), 22);
    LinearLayout card = ui.add(content, ui.card(Ui.BG, 0), 8);
    card.setPadding(0, 0, 0, 0);
    card.addView(ui.row(tr("Connection"), connectionLabel(bb), null, v -> open("bb")));
    card.addView(ui.divider());
    card.addView(
        ui.row(
            tr("Chief of staff"),
            bb.chiefId().isEmpty()
                ? tr("Not set")
                : (bb.chiefTitle().isEmpty() ? tr("Chief of staff") : bb.chiefTitle()) + " · " + bb.chiefId(),
            null,
            v -> open("chief")));
    card.addView(ui.divider());
    card.addView(
        ui.row(
            tr("Notifications"),
            "ntfy · " + bb.ntfyServer().replaceFirst("^https?://", ""),
            null,
            v -> open("ntfy")));
  }

  private String connectionLabel(BbBridge bb) {
    if (!bb.connected()) return tr("Not connected");
    return bb.mode().equals(BbBridge.CUSTOM)
        ? tr("Custom address · %1$s", Uri.parse(bb.url()).getAuthority())
        : tr("bb Connect · %1$s.getbb.app", bb.handle());
  }

  /** A line under a bb action: what happened, in green or as a warning. */
  private TextView bbResultLine() {
    TextView result = ui.add(content, ui.text("", 14, Ui.MUTED), 10);
    if (bbNotice.isEmpty()) result.setVisibility(View.GONE);
    else {
      result.setText(bbNotice);
      result.setTextColor(bbNoticeOk ? Ui.TEAL : Ui.WARN);
      bbNotice = "";
    }
    return result;
  }

  /** Runs a bb call off the main thread, then shows its outcome on this page. */
  private void bbAction(Button button, TextView result, String working, BbTask task) {
    button.setEnabled(false);
    result.setVisibility(View.VISIBLE);
    result.setTextColor(Ui.MUTED);
    result.setText(working);
    String shown = page;
    io.execute(
        () -> {
          String message;
          boolean ok;
          try {
            message = task.run();
            ok = true;
          } catch (Exception ex) {
            message =
                ex.getClass() == IOException.class && ex.getMessage() != null
                    ? ex.getMessage()
                    : DictationService.friendly(ex);
            ok = false;
          }
          String text = message;
          boolean success = ok;
          main.post(
              () -> {
                if (!page.equals(shown)) {
                  toast(text);
                  return;
                }
                bbNotice = text;
                bbNoticeOk = success;
                show(page);
              });
        });
  }

  private interface BbTask {
    String run() throws Exception;
  }

  /** "Later" on the optional bb setup pages: setup ends, and Talk Router is a dictation app. */
  private void laterButton() {
    ui.add(content, ui.button(tr("Later"), Ui.Kind.QUIET, v -> finishSetup()), 4);
  }

  /** Connect bb: bb Connect with a sign-in, or a custom MCP address. Setup page and Settings. */
  private void bbPage(boolean onboarding) {
    BbBridge bb = new BbBridge(this);
    if (bbModeShown.isEmpty()) bbModeShown = bb.mode();
    if (onboarding) {
      steps(false);
      heading(
          null,
          tr("Connect bb"),
          tr("Optional. bb (getbb.app) runs coding agents on your own computer. Connected, Talk Router hands a task you speak to your chief-of-staff thread there. Without bb, Talk Router is a dictation app."));
    } else
      heading(
          tr("Settings"),
          tr("bb"),
          tr("Talk Router reaches bb through bb's MCP server (plugin bb--mcp), which has to be on in bb."));
    boolean connected = bb.connected() && bb.mode().equals(bbModeShown);
    Ui.Flow modes = ui.add(content, ui.flow(), 16);
    modes.addView(
        ui.chip(
            tr("bb Connect"),
            bbModeShown.equals(BbBridge.CONNECT),
            v -> {
              bbModeShown = BbBridge.CONNECT;
              show(page);
            }));
    modes.addView(
        ui.chip(
            tr("Custom address"),
            bbModeShown.equals(BbBridge.CUSTOM),
            v -> {
              bbModeShown = BbBridge.CUSTOM;
              show(page);
            }));
    if (bbModeShown.equals(BbBridge.CONNECT)) connectCard(bb, connected);
    else customCard(bb, connected);
    if (bb.connected()) skillsCard();
    if (onboarding) {
      spacer();
      if (bb.connected())
        ui.add(content, ui.button(tr("Continue"), Ui.Kind.PRIMARY, v -> nextSetupPage()), 24);
      laterButton();
    }
  }

  /**
   * The chief's instructions live in two bb skills; this says whether bb has the current ones and
   * asks for the name the chief calls you.
   */
  private void skillsCard() {
    ui.add(content, ui.strong(tr("Your chief's skills"), 16), 24);
    ui.add(
        content,
        ui.text(
            tr("Your chief of staff follows two skills in bb, chief-of-staff and talk-router: how to work for you, and how to notify you and send text back. Talk Router installs and updates them in bb through a short thread."),
            13,
            Ui.MUTED),
        4);
    ui.add(content, ui.strong(tr("Your name"), 15), 14);
    EditText name =
        ui.input(prefs.getString(ChiefSkills.USER_NAME, ""), tr("Optional: what your chief calls you"), false);
    name.setFilters(new InputFilter[] {new InputFilter.LengthFilter(40)});
    ui.add(content, name, 6);
    autosave(
        name,
        t -> {
          prefs.edit().putString(ChiefSkills.USER_NAME, t.trim()).apply();
          main.removeCallbacks(skillsAfterName);
          main.postDelayed(skillsAfterName, 1500);
        });
    skillsLine = ui.add(content, ui.text("", 14, Ui.MUTED), 12);
    ui.add(content, ui.button(tr("Check the skills in bb"), Ui.Kind.QUIET, v -> ChiefSkills.sync(this, true)), 4);
    ChiefSkills.sync(this, false);
    renderSkills();
  }

  private void renderSkills() {
    if (skillsLine == null) return;
    switch (ChiefSkills.state) {
      case CHECKING -> {
        skillsLine.setTextColor(Ui.MUTED);
        skillsLine.setText(tr("Checking the chief's skills in bb…"));
      }
      case INSTALLING -> {
        skillsLine.setTextColor(Ui.MUTED);
        skillsLine.setText(tr("Installing the chief's skills in bb…"));
      }
      case INSTALLED -> {
        skillsLine.setTextColor(Ui.TEAL);
        skillsLine.setText(tr("✓  Installed in bb"));
      }
      case FAILED -> {
        skillsLine.setTextColor(Ui.WARN);
        skillsLine.setText(tr("Couldn't install the chief's skills: %1$s", ChiefSkills.problem));
      }
      default -> skillsLine.setText("");
    }
  }

  private void connectCard(BbBridge bb, boolean connected) {
    LinearLayout card = ui.add(content, ui.card(Ui.BG, Ui.LINE), 14);
    if (connected) {
      card.addView(ui.strong(tr("✓  Signed in to %1$s.getbb.app", bb.handle()), 16));
      TextView result = bbResultLine();
      Button[] test = {null};
      test[0] =
          ui.add(
              content,
              ui.button(
                  tr("Test connection"),
                  Ui.Kind.OUTLINE,
                  v ->
                      bbAction(
                          test[0],
                          result,
                          tr("Asking bb…"),
                          () -> {
                            bb.test();
                            return tr("✓  bb answers.");
                          })),
              10);
      Button out =
          ui.button(
              tr("Sign out"),
              Ui.Kind.QUIET,
              v ->
                  bbAction(
                      test[0],
                      result,
                      tr("Signing out…"),
                      () -> {
                        bb.disconnect();
                        return tr("Signed out of bb.");
                      }));
      out.setTextColor(Ui.WARN);
      ui.add(content, out, 4);
      return;
    }
    card.addView(ui.strong(tr("Your bb Connect handle"), 16));
    ui.add(
        card,
        ui.text(
            tr("The name in your bb address: <handle>.getbb.app. Your browser opens bb's account page; approve Talk Router there."),
            14,
            Ui.MUTED),
        4);
    LinearLayout row = ui.horizontal();
    row.setGravity(Gravity.CENTER_VERTICAL);
    EditText handle = ui.input(bb.handle(), tr("handle"), false);
    handle.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
    row.addView(handle, new LinearLayout.LayoutParams(0, -2, 1));
    TextView suffix = ui.text(".getbb.app", 16, Ui.MUTED);
    suffix.setPadding(ui.dp(8), 0, 0, 0);
    row.addView(suffix);
    ui.add(card, row, 12);
    TextView result = bbResultLine();
    Button[] signIn = {null};
    signIn[0] =
        ui.add(
            content,
            ui.button(
                tr("Sign in with bb"),
                Ui.Kind.PRIMARY,
                v -> {
                  String h = BbOAuth.handle(handle.getText().toString());
                  if (h.isEmpty()) {
                    toast(tr("Enter your bb Connect handle"));
                    return;
                  }
                  signIn[0].setEnabled(false);
                  result.setVisibility(View.VISIBLE);
                  result.setTextColor(Ui.MUTED);
                  result.setText(tr("Finding your bb…"));
                  io.execute(
                      () -> {
                        String url = null, failed = null;
                        try {
                          url = BbOAuth.begin(this, h);
                        } catch (Exception ex) {
                          failed =
                              ex.getClass() == IOException.class && ex.getMessage() != null
                                  ? ex.getMessage()
                                  : DictationService.friendly(ex);
                        }
                        String address = url, message = failed;
                        main.post(
                            () -> {
                              signIn[0].setEnabled(true);
                              if (message != null) {
                                result.setTextColor(Ui.WARN);
                                result.setText(message);
                              } else {
                                result.setText(tr("Approve Talk Router in your browser, then come back."));
                                openUrl(address);
                              }
                            });
                      });
                }),
            12);
  }

  private void customCard(BbBridge bb, boolean connected) {
    LinearLayout card = ui.add(content, ui.card(Ui.BG, Ui.LINE), 14);
    card.addView(ui.strong(tr("MCP address"), 16));
    ui.add(
        card,
        ui.text(
            tr("bb's local address is http://127.0.0.1:38886/api/v1/plugins/bb--mcp/http/mcp. Reach it from the phone through your tailnet (Tailscale Serve, HTTPS) or adb reverse."),
            13,
            Ui.MUTED),
        4);
    EditText url =
        ui.input(bb.mode().equals(BbBridge.CUSTOM) ? bb.url() : "", "https://…/mcp", false);
    url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
    ui.add(card, url, 10);
    ui.add(card, ui.strong(tr("Token (optional)"), 15), 14);
    EditText token = ui.input(bb.customToken(), tr("Sent as a bearer token"), false);
    android.graphics.Typeface face = token.getTypeface();
    token.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
    token.setTypeface(face);
    ui.add(card, token, 8);
    ui.add(card, ui.strong(tr("bb web address for links"), 15), 14);
    ui.add(
        card,
        ui.text(tr("Where thread links open, usually https://<handle>.getbb.app."), 13, Ui.MUTED),
        2);
    EditText links =
        ui.input(
            bb.mode().equals(BbBridge.CUSTOM) ? bb.linkBase() : bb.handle().isEmpty() ? "" : BbOAuth.origin(bb.handle()),
            "https://<handle>.getbb.app",
            false);
    links.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
    ui.add(card, links, 8);
    if (connected) ui.add(card, ui.text(tr("✓  Saved"), 14, Ui.TEAL), 10);
    TextView result = bbResultLine();
    Button[] save = {null};
    save[0] =
        ui.add(
            content,
            ui.button(
                tr("Save and test"),
                connected ? Ui.Kind.OUTLINE : Ui.Kind.PRIMARY,
                v -> {
                  String address = url.getText().toString().trim();
                  String base = links.getText().toString().trim();
                  if (!address.matches("(?i)https?://\\S+")) {
                    toast(tr("Enter the MCP address, starting with https:// or http://"));
                    return;
                  }
                  if (!base.isEmpty() && !base.matches("(?i)https?://\\S+")) {
                    toast(tr("The web address starts with https://"));
                    return;
                  }
                  bbAction(
                      save[0],
                      result,
                      tr("Asking bb…"),
                      () -> {
                        bb.useCustom(address, token.getText().toString(), base);
                        bb.test();
                        // Connected, or new links: the skills get checked and, if needed, updated.
                        ChiefSkills.sync(this, true);
                        return tr("✓  bb answers.");
                      });
                }),
            12);
    if (connected) {
      Button forget =
          ui.button(
              tr("Forget this address"),
              Ui.Kind.QUIET,
              v -> {
                bb.disconnect();
                show(page);
              });
      forget.setTextColor(Ui.WARN);
      ui.add(content, forget, 4);
    }
  }

  /** The chief of staff: create one in bb, use an existing thread, or open it. */
  private void chiefPage(boolean onboarding) {
    BbBridge bb = new BbBridge(this);
    if (onboarding) steps(false);
    heading(
        onboarding ? null : tr("Settings"),
        tr("Chief of staff"),
        tr("One bb thread gets every task you hand over by voice. It does small things itself, starts child threads for bigger work, and notifies you through ntfy when it's done or needs you."));
    if (!bb.connected()) {
      ui.add(content, ui.text(tr("Connect bb first."), 15, Ui.WARN), 16);
      ui.add(
          content,
          ui.button(tr("Connect bb"), Ui.Kind.OUTLINE, v -> open(onboarding ? "bb1" : "bb")),
          10);
      if (onboarding) {
        spacer();
        laterButton();
      }
      return;
    }
    String id = bb.chiefId();
    if (!id.isEmpty()) {
      LinearLayout card = ui.add(content, ui.card(Ui.TEAL_TINT, Ui.BAR), 16);
      card.addView(ui.strong(bb.chiefTitle().isEmpty() ? tr("Chief of staff") : bb.chiefTitle(), 16));
      ui.add(card, ui.mono(id, 12, Ui.MUTED), 4);
      String link = bb.threadLink(id);
      if (!link.isEmpty())
        ui.add(card, ui.button(tr("Open in bb ↗"), Ui.Kind.QUIET, v -> openBb(link)), 4);
      if (!changingChief)
        ui.add(
            content,
            ui.button(
                tr("Change"),
                Ui.Kind.OUTLINE,
                v -> {
                  changingChief = true;
                  show(page);
                }),
            10);
    }
    if (!id.isEmpty()) chiefNameField();
    TextView result = bbResultLine();
    if (id.isEmpty() || changingChief) {
      Button[] create = {null};
      create[0] =
          ui.add(
              content,
              ui.button(
                  tr("Create chief of staff"),
                  id.isEmpty() ? Ui.Kind.PRIMARY : Ui.Kind.OUTLINE,
                  v ->
                      bbAction(
                          create[0],
                          result,
                          tr("Installing the skills and creating the thread in bb…"),
                          () -> {
                            BbBridge.ThreadInfo t = bb.createChief();
                            changingChief = false;
                            return tr("✓  Created %1$s (%2$s).", t.title(), t.id());
                          })),
              14);
      ui.add(
          content,
          ui.text(
              tr("Installs the chief's skills in bb if needed, then creates a thread called “Chief of staff” in Personal (no project) that uses them."),
              13,
              Ui.MUTED),
          4);
      ui.add(content, ui.strong(tr("Or use a thread you have"), 15), 20);
      EditText existing = ui.input("", tr("Thread link or id (thr_…)"), false);
      existing.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
      ui.add(content, existing, 8);
      Button[] use = {null};
      use[0] =
          ui.add(
              content,
              ui.button(
                  tr("Use this thread"),
                  Ui.Kind.OUTLINE,
                  v -> {
                    String input = existing.getText().toString();
                    bbAction(
                        use[0],
                        result,
                        tr("Checking the thread and the skills in bb…"),
                        () -> {
                          BbBridge.ThreadInfo t = bb.useExistingChief(input);
                          changingChief = false;
                          return tr("✓  Using %1$s (%2$s). It was told to use the skills.", t.title().isEmpty() ? t.id() : t.title(), t.id());
                        });
                  }),
              8);
    }
    if (onboarding) {
      spacer();
      if (!id.isEmpty())
        ui.add(content, ui.button(tr("Continue"), Ui.Kind.PRIMARY, v -> nextSetupPage()), 24);
      laterButton();
    }
  }

  /** The name that sends a dictation to the chief when you say it. */
  private void chiefNameField() {
    ui.add(content, ui.strong(tr("Your chief's name"), 15), 22);
    ui.add(
        content,
        ui.text(
            tr("Say it to send a dictation to your chief: \"Ada, …\", \"this is for Ada\". Just mentioning the name in a message doesn't send it. Without a name, tap 🧑‍💼."),
            13,
            Ui.MUTED),
        4);
    EditText name = ui.input(profile.chiefName(), tr("For example: Ada"), false);
    name.setFilters(new InputFilter[] {new InputFilter.LengthFilter(40)});
    ui.add(content, name, 8);
    autosave(name, t -> profile.setChiefName(t));
  }

  /** ntfy: where the chief's notifications go, with a link to subscribe and a test. */
  private void ntfyPage(boolean onboarding) {
    BbBridge bb = new BbBridge(this);
    if (onboarding) steps(false);
    heading(
        onboarding ? null : tr("Settings"),
        tr("Notifications"),
        tr("Your chief of staff notifies you through ntfy; tapping a notification opens its thread in bb. Talk Router itself sends none. Install the ntfy app and subscribe to your topic."));
    String topic;
    try {
      topic = bb.ntfyTopic();
    } catch (Exception ex) {
      topic = "";
    }
    LinearLayout card = ui.add(content, ui.card(Ui.BG, Ui.LINE), 16);
    card.addView(ui.label(tr("Your topic")));
    TextView shown = ui.mono(topic, 14, Ui.INK);
    shown.setTextIsSelectable(true);
    ui.add(card, shown, 6);
    ui.add(card, ui.mono(bb.ntfyServer(), 12, Ui.MUTED), 4);
    ui.add(
        card,
        ui.text(
            tr("Anyone who knows the topic can read these notifications, so it's long and random. Keep it to yourself."),
            13,
            Ui.MUTED),
        8);
    String link = BbBridge.ntfyLink(bb.ntfyServer(), topic);
    ui.add(content, ui.button(tr("Open in ntfy"), Ui.Kind.PRIMARY, v -> openNtfy(link)), 14);
    TextView result = bbResultLine();
    Button[] test = {null};
    test[0] =
        ui.add(
            content,
            ui.button(
                tr("Send test"),
                Ui.Kind.OUTLINE,
                v ->
                    bbAction(
                        test[0],
                        result,
                        tr("Sending…"),
                        () -> {
                          bb.ntfyTest();
                          return tr("✓  Sent. It should show up in the ntfy app.");
                        })),
            10);
    ui.add(content, ui.strong(tr("ntfy server"), 15), 22);
    ui.add(content, ui.text(tr("ntfy.sh, or your own ntfy server."), 13, Ui.MUTED), 2);
    EditText server = ui.input(bb.ntfyServer(), BbBridge.NTFY_DEFAULT, false);
    server.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
    ui.add(content, server, 8);
    Button[] save = {null};
    save[0] =
        ui.add(
            content,
            ui.button(
                tr("Save server"),
                Ui.Kind.QUIET,
                v -> {
                  String value = server.getText().toString().trim();
                  if (!value.isEmpty() && !value.matches("(?i)https?://\\S+")) {
                    toast(tr("The server address starts with https://"));
                    return;
                  }
                  bbAction(
                      save[0],
                      result,
                      tr("Saving…"),
                      () -> {
                        bb.setNtfyServer(value);
                        // The talk-router skill holds the server: it's updated in bb.
                        ChiefSkills.sync(this, false);
                        return tr("✓  Saved.");
                      });
                }),
            4);
    if (onboarding) {
      spacer();
      ui.add(content, ui.button(tr("Done"), Ui.Kind.PRIMARY, v -> finishSetup()), 24);
    }
  }

  /** Subscribes in the ntfy app; without it, where to get it. */
  private void openNtfy(String link) {
    try {
      startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(link)));
    } catch (ActivityNotFoundException ex) {
      new AlertDialog.Builder(this)
          .setTitle(tr("Get the ntfy app"))
          .setMessage(tr("ntfy is free and open source. Install it, then tap Open in ntfy again."))
          .setPositiveButton(
              "Google Play",
              (d, w) -> openUrl("https://play.google.com/store/apps/details?id=io.heckel.ntfy"))
          .setNeutralButton("F-Droid", (d, w) -> openUrl("https://f-droid.org/packages/io.heckel.ntfy/"))
          .setNegativeButton(tr("Cancel"), null)
          .show();
    }
  }

  // ---------------------------------------------------------------- home

  private void homePage() {
    LinearLayout top = ui.horizontal();
    top.addView(ui.title("Talk Router", 26), new LinearLayout.LayoutParams(0, -2, 1));
    top.addView(ui.button(tr("Settings"), Ui.Kind.QUIET, v -> open("settings")));
    content.addView(top);
    if (!OpenRouter.hasKey(this)) {
      LinearLayout warn = ui.add(content, ui.card(0xfffde3d8, 0), 12);
      warn.addView(ui.strong(tr("Add your OpenRouter key to start dictating"), 15));
      warn.setOnClickListener(v -> open("key"));
    } else if (!microphone()) {
      LinearLayout warn = ui.add(content, ui.card(0xfffde3d8, 0), 12);
      warn.addView(ui.strong(tr("Finish setup to start dictating"), 15));
      warn.setOnClickListener(v -> open("setup"));
    }
    record =
        ui.add(content, ui.button(tr("Start dictating"), Ui.Kind.PRIMARY, v -> toggleRecording()), 16);
    record.setMinHeight(ui.dp(62));
    status = ui.add(content, ui.text("", 13, Ui.MUTED), 6);
    status.setGravity(Gravity.CENTER);
    recovery = ui.add(content, ui.vertical(), 0);
    ui.add(
        recovery,
        ui.button(
            tr("Retry saved recording"),
            Ui.Kind.OUTLINE,
            v -> {
              if (ready()) send(DictationService.RETRY);
            }),
        8);
    ui.add(
        recovery,
        ui.button(
            tr("Discard saved audio"),
            Ui.Kind.QUIET,
            v ->
                new AlertDialog.Builder(this)
                    .setTitle(tr("Discard the saved audio?"))
                    .setMessage(tr("Partial text and reported costs stay in your notes."))
                    .setPositiveButton(tr("Discard"), (d, w) -> send(DictationService.DISCARD))
                    .setNegativeButton(tr("Keep"), null)
                    .show()),
        0);
    LinearLayout attach = ui.horizontal();
    int attached = new Attachments(this).pending().size();
    attach.addView(
        ui.text(
            attached == 0
                ? tr("Talking about something? Add it, like an offer you reply to.")
                : plural(attached, "%1$d attachment for this dictation", "%1$d attachments for this dictation"),
            13,
            Ui.MUTED),
        new LinearLayout.LayoutParams(0, -2, 1));
    attach.addView(
        ui.button(
            tr("📎 Add"),
            Ui.Kind.QUIET,
            v -> startActivity(new Intent(this, AttachActivity.class))));
    ui.add(content, attach, 2);
    if (!prefs.getBoolean("tileAdded", false)) {
      LinearLayout tile = ui.horizontal();
      tile.addView(
          ui.text(tr("Start from any app: add Talk Router to Quick Settings."), 13, Ui.MUTED),
          new LinearLayout.LayoutParams(0, -2, 1));
      tile.addView(ui.button(tr("Add tile"), Ui.Kind.QUIET, v -> addTile()));
      ui.add(content, tile, 2);
    }
    monthBox = ui.add(content, ui.card(Ui.PANEL, 0), 14);
    monthBox.setPadding(ui.dp(18), ui.dp(18), ui.dp(18), ui.dp(18));
    monthBox.setOnClickListener(v -> open("usage"));
    monthBox.addView(ui.text(tr("%1$s so far", monthName(0)), 14, Ui.MUTED));
    LinearLayout models = ui.add(content, ui.card(Ui.BG, Ui.LINE), 8);
    models.setPadding(0, 0, 0, 0);
    models.addView(
        ui.row(
            tr("Speech model"),
            modelName(profile.fixedModel()) + " · " + perMinute(profile.fixedModel()),
            null,
            v -> open("models")));
    models.addView(ui.divider());
    models.addView(ui.row(tr("Refine"), refineLabel(), null, v -> open("refine")));
    LinearLayout recentHead = ui.horizontal();
    recentHead.addView(ui.label(tr("Recent")), new LinearLayout.LayoutParams(0, -2, 1));
    recentHead.addView(ui.button(tr("All notes"), Ui.Kind.QUIET, v -> open("notes")));
    ui.add(content, recentHead, 14);
    recentBox = ui.add(content, ui.vertical(), 0);
    loadNotes();
  }

  private void toggleRecording() {
    if (DictationService.busy()) {
      if ("recording".equals(prefs.getString("state", ""))) send(DictationService.STOP);
    } else if (ready()) send(DictationService.RECORD);
  }

  private void loadNotes() {
    if (loading) return;
    loading = true;
    io.execute(
        () -> {
          List<HistoryEntry> all;
          try (HistoryStore h = new HistoryStore(this)) {
            all = h.all();
          } catch (Exception ex) {
            all = new ArrayList<>();
          }
          List<HistoryEntry> entries = all;
          List<NoteList.Note> grouped = NoteList.group(entries);
          main.post(
              () -> {
                loading = false;
                notes = grouped;
                if (monthBox != null) renderMonth(entries);
                if (recentBox != null) {
                  recentBox.removeAllViews();
                  if (grouped.isEmpty())
                    ui.add(
                        recentBox, ui.text(tr("Your first note will appear here."), 15, Ui.MUTED), 10);
                  for (int i = 0; i < Math.min(3, grouped.size()); i++) recentRow(grouped.get(i));
                }
                if (notesBox != null) renderNotes();
              });
        });
  }

  private void recentRow(NoteList.Note n) {
    LinearLayout row = ui.horizontal();
    row.setPadding(0, ui.dp(12), 0, ui.dp(12));
    TextView t = ui.text(preview(n), 15, Ui.INK);
    t.setSingleLine(true);
    t.setEllipsize(TextUtils.TruncateAt.END);
    row.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
    TextView cost = ui.mono(cost(n.cost(), n.incompleteCost()), 13, Ui.MUTED);
    cost.setPadding(ui.dp(12), 0, 0, 0);
    row.addView(cost);
    row.setOnClickListener(v -> openNote(n.id()));
    recentBox.addView(row);
    recentBox.addView(ui.divider());
  }

  private void renderMonth(List<HistoryEntry> entries) {
    Calendar start = monthStart(0);
    long from = start.getTimeInMillis();
    BigDecimal spent = BigDecimal.ZERO;
    double seconds = 0;
    double[] days = new double[14];
    long today = dayStart(System.currentTimeMillis());
    for (HistoryEntry e : entries) {
      if (e.created() >= from) {
        spent = spent.add(e.cost());
        if (e.recordingId().equals(e.id())) seconds += e.duration();
      }
      int ago = (int) ((today - dayStart(e.created())) / 86_400_000L);
      if (ago >= 0 && ago < 14) days[13 - ago] += e.cost().doubleValue();
    }
    monthBox.removeAllViews();
    LinearLayout head = ui.horizontal();
    head.addView(
        ui.text(tr("%1$s so far", monthName(0)), 14, Ui.MUTED), new LinearLayout.LayoutParams(0, -2, 1));
    head.addView(ui.text(duration(seconds), 13, Ui.MUTED));
    monthBox.addView(head);
    ui.add(monthBox, ui.title(amount(spent), 44), 8);
    LinearLayout bars = ui.horizontal();
    bars.setGravity(Gravity.BOTTOM);
    double max = Arrays.stream(days).max().orElse(0);
    for (int i = 0; i < days.length; i++) {
      View bar = new View(this);
      bar.setBackground(ui.shape(i == days.length - 1 ? Ui.TEAL : Ui.BAR, 0, 3));
      int h = max <= 0 ? ui.dp(2) : Math.max(ui.dp(2), (int) (ui.dp(40) * days[i] / max));
      LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(0, h, 1);
      l.rightMargin = ui.dp(4);
      bars.addView(bar, l);
    }
    LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, ui.dp(40));
    bl.topMargin = ui.dp(12);
    monthBox.addView(bars, bl);
    ui.add(monthBox, ui.text(tr("Days without dictation cost nothing."), 13, Ui.MUTED), 10);
  }

  // ---------------------------------------------------------------- notes

  private void notesPage() {
    heading(tr("Home"), tr("Notes"), null);
    EditText search = ui.input("", tr("Search your notes"), false);
    search.setBackground(ui.shape(Ui.PANEL, 0, 14));
    search.addTextChangedListener(after(s -> renderNotes()));
    search.setTag("search");
    ui.add(content, search, 14);
    notesBox = ui.add(content, ui.vertical(), 6);
    loadNotes();
  }

  private void renderNotes() {
    if (notesBox == null) return;
    View search = content.findViewWithTag("search");
    String query = search instanceof EditText e ? e.getText().toString() : "";
    notesBox.removeAllViews();
    List<NoteList.Note> shown = NoteList.search(notes, query);
    if (shown.isEmpty())
      ui.add(
          notesBox,
          ui.text(query.isBlank() ? tr("No notes yet.") : tr("Nothing matches."), 15, Ui.MUTED),
          14);
    String lastDay = "";
    for (NoteList.Note n : shown) {
      String day = dayLabel(n.created());
      if (!day.equals(lastDay)) {
        ui.add(notesBox, ui.label(day), 18);
        lastDay = day;
      }
      LinearLayout row = ui.vertical();
      row.setPadding(0, ui.dp(10), 0, ui.dp(12));
      TextView t = ui.text(preview(n), 15, Ui.INK);
      t.setMaxLines(2);
      t.setEllipsize(TextUtils.TruncateAt.END);
      row.addView(t);
      LinearLayout meta = ui.horizontal();
      meta.addView(
          ui.mono(
              DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(n.created()))
                  + " · "
                  + AudioFiles.elapsed((long) (n.shown().duration() * 1000))
                  + " · "
                  + cost(n.cost(), n.incompleteCost()),
              12,
              Ui.MUTED));
      String badge = badge(n);
      if (badge != null) {
        TextView b = ui.text(badge, 12, Ui.TEAL);
        b.setTypeface(Ui.MEDIUM);
        b.setBackground(ui.shape(Ui.TEAL_SOFT, 0, 6));
        b.setPadding(ui.dp(6), ui.dp(2), ui.dp(6), ui.dp(2));
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-2, -2);
        l.leftMargin = ui.dp(8);
        meta.addView(b, l);
      }
      ui.add(row, meta, 4);
      row.setOnClickListener(v -> openNote(n.id()));
      notesBox.addView(row);
      notesBox.addView(ui.divider());
    }
  }

  private String badge(NoteList.Note n) {
    String s = n.shown().json.optString("status");
    if (!s.equals("complete"))
      return switch (s) {
        case "pending" -> tr("in progress");
        case "no speech" -> tr("no speech");
        default -> tr("unfinished");
      };
    if (chiefNote(n.shown()))
      return DictationService.sentToChief(n.shown()) ? tr("chief of staff") : tr("not sent");
    if (n.versions().size() > 1) return tr("redone");
    return "original".equals(n.shown().json.optString("version")) ? tr("as said") : null;
  }

  /** The note was sent to the chief of staff, or sending it failed. */
  private static boolean chiefNote(HistoryEntry e) {
    return e.json.has("chiefThread") || e.json.has("chiefFailed") || "chief".equals(e.json.optString("route"));
  }

  private void openNote(String id) {
    noteId = id;
    showOriginal = false;
    open("note");
  }

  // ---------------------------------------------------------------- one note

  private NoteList.Note currentNote() throws Exception {
    try (HistoryStore h = new HistoryStore(this)) {
      List<NoteList.Note> group = NoteList.group(h.versions(noteId));
      return group.isEmpty() ? null : group.get(0);
    }
  }

  private void notePage() {
    NoteList.Note n;
    try {
      n = currentNote();
    } catch (Exception ex) {
      n = null;
    }
    if (n == null) {
      heading(tr("Back"), tr("Note not found"), null);
      return;
    }
    HistoryEntry e = n.shown();
    content.addView(ui.back(tr("Back"), v -> goBack()));
    ui.add(
        content,
        ui.text(
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                    .format(new Date(n.created()))
                + " · "
                + tr("%1$s recorded", AudioFiles.elapsed((long) (e.duration() * 1000))),
            13,
            Ui.MUTED),
        4);
    boolean redoing = redoing();
    String state = e.json.optString("status");
    if (!state.equals("complete") && !redoing) {
      LinearLayout warn = ui.add(content, ui.card(0xfffde3d8, 0), 12);
      warn.addView(
          ui.text(
              switch (state) {
                case "pending" -> tr("Still transcribing.");
                case "no speech" -> tr("No speech was recognized in this recording.");
                default ->
                    tr(
                        "This recording didn't finish (%1$s). Retry it from Home.",
                        switch (state) {
                          case "needs attention" -> tr("needs attention");
                          case "audio discarded" -> tr("audio discarded");
                          case "imported" -> tr("imported");
                          default -> state;
                        });
              },
              14,
              Ui.WARN));
    }
    boolean chief = chiefNote(e);
    boolean sent = chief && DictationService.sentToChief(e);
    if (chief) chiefBanner(e, sent);
    if (e.json.has("chiefReply")) chiefReply(e);
    if (e.json.has("processingFailed")) {
      ui.add(
          content,
          ui.text(
              tr(
                  "Couldn't refine this note: %1$s Below is what you said.",
                  e.json.optString("processingFailed")),
              14,
              Ui.WARN),
          12);
      ui.add(content, ui.button(tr("Refine again"), Ui.Kind.OUTLINE, v -> processAgain()), 8);
    }
    boolean twoVersions =
        e.json.has("processed")
            && !e.json.optString("processed").equals(e.json.optString("original"));
    boolean original =
        twoVersions && (showOriginal || "original".equals(e.json.optString("version")));
    String shown = original ? e.json.optString("original", e.raw()) : e.text();
    if (twoVersions) {
      Ui.Flow versions = ui.add(content, ui.flow(), 14);
      versions.addView(ui.chip(tr("Refined"), !original, v -> switchVersion(false)));
      versions.addView(ui.chip(tr("What you said"), original, v -> switchVersion(true)));
    } else if (sent) ui.add(content, ui.strong(tr("What you said"), 15), 18);
    editor = ui.input(shown, tr("No text yet"), true);
    editor.setTextSize(18);
    editor.setLineSpacing(0, 1.3f);
    editor.setMinLines(6);
    editor.setBackground(ui.shape(Ui.PANEL, 0, 16));
    editor.setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(14));
    editorBase = shown;
    ui.add(content, editor, 14);
    ui.add(
        content,
        ui.button(
            tr("Copy"),
            Ui.Kind.PRIMARY,
            v -> {
              saveEdits();
              DictationService.copy(this, editor.getText().toString());
              toast(tr("Copied"));
            }),
        14);
    java.util.List<Attachments.Item> files = new Attachments(this).of(noteId);
    if (!files.isEmpty()) {
      StringBuilder names = new StringBuilder();
      boolean unsent = false;
      for (Attachments.Item f : files) {
        names.append(names.length() == 0 ? "" : ", ")
            .append(f.kind().equals("text") ? tr("pasted text") : f.name());
        unsent |= f.kind().equals("image") || f.kind().equals("pdf");
      }
      ui.add(content, ui.text("📎 " + names, 13, Ui.MUTED), 10);
      if (chief && unsent)
        ui.add(
            content,
            ui.text(tr("Images and PDFs aren't sent to the chief of staff yet; pasted text is."), 13, Ui.MUTED),
            2);
    }
    ui.add(
        content,
        ui.text(
            !n.incompleteCost()
                ? tr("This note cost %1$s.", money(n.cost(), 4))
                : n.cost().signum() > 0
                    ? tr("This note cost %1$s so far.", money(n.cost(), 4))
                    : tr("OpenRouter reported no cost for this note yet."),
            13,
            Ui.MUTED),
        8);
    if (n.anthropicCost().signum() > 0)
      ui.add(
          content,
          ui.text(tr("Refine: Anthropic credits ≈%1$s", exact(n.anthropicCost())), 13, Ui.MUTED),
          2);
    String warning = e.json.optString("anthropicWarning");
    if (!warning.isEmpty())
      ui.add(
          content,
          ui.text(tr("Refined through OpenRouter instead of Anthropic: %1$s", warning), 13, Ui.WARN),
          4);
    noteStatus = ui.add(content, ui.text("", 14, Ui.TEAL), 8);
    LinearLayout links = ui.horizontal();
    noteDetails(n, e, redoing, links);
    Button delete = ui.button(tr("Delete note"), Ui.Kind.QUIET, v -> deleteNote());
    delete.setTextColor(Ui.WARN);
    links.addView(delete);
    ui.add(content, links, 8);
  }

  /** What the chief of staff sent back for pasting, with Copy. */
  private void chiefReply(HistoryEntry e) {
    LinearLayout card = ui.add(content, ui.card(Ui.TEAL_TINT, Ui.BAR), 14);
    card.addView(ui.label(tr("From your chief of staff")));
    TextView text = ui.text(e.json.optString("chiefReply"), 16, Ui.INK);
    text.setTextIsSelectable(true);
    text.setLineSpacing(0, 1.25f);
    ui.add(card, text, 6);
    ui.add(
        card,
        ui.button(
            tr("Copy"),
            Ui.Kind.QUIET,
            v -> {
              DictationService.copy(this, e.json.optString("chiefReply"));
              toast(tr("Copied"));
            }),
        4);
  }

  /**
   * A chief-of-staff note: "Sent to chief of staff ↗" opens the thread in the bb web app; when
   * sending failed, why, and Send again.
   */
  private void chiefBanner(HistoryEntry e, boolean sent) {
    if (sent) {
      String link = e.json.optString("chiefLink");
      ui.add(
          content,
          ui.button(
              tr("Sent to chief of staff ↗"),
              Ui.Kind.PRIMARY,
              v -> {
                if (link.isEmpty()) toast(tr("Set the bb web address for links in Settings → bb."));
                else openBb(link);
              }),
          16);
      ui.add(
          content,
          ui.text(
              tr("Your chief of staff works on it in bb and notifies you through ntfy when it's done or needs you. The button opens the thread."),
              13,
              Ui.MUTED),
          6);
      return;
    }
    ui.add(
        content,
        ui.text(
            tr("Not sent to your chief of staff: %1$s Below is what you said.", e.json.optString("chiefFailed")),
            14,
            Ui.WARN),
        12);
    ui.add(content, ui.button(tr("Send again"), Ui.Kind.OUTLINE, v -> sendAgain()), 8);
  }

  /** Sends a note to the chief of staff once more, after sending failed. */
  private void sendAgain() {
    if (applying) return;
    applying = true;
    toast(tr("Sending to your chief of staff…"));
    io.execute(
        () -> {
          String failed = null;
          try {
            NoteList.Note n = currentNote();
            if (n == null) throw new IOException(tr("This note was deleted."));
            failed = DictationService.sendNote(this, n.shown().id());
            prefs.edit().putLong("historyRevision", System.nanoTime()).apply();
          } catch (Exception ex) {
            failed = DictationService.friendly(ex);
          } finally {
            String message = failed;
            main.post(
                () -> {
                  applying = false;
                  toast(message == null ? tr("Sent to chief of staff") : message);
                  if (page.equals("note")) show("note");
                });
          }
        });
  }

  /** Redo with a smarter model, the models used, and the saved audio. */
  private void noteDetails(NoteList.Note n, HistoryEntry e, boolean redoing, LinearLayout links) {
    boolean audio;
    try {
      audio = new AudioArchive(this).get(noteId) != null;
    } catch (Exception ex) {
      audio = false;
    }
    JSONObject smarter = smarterModel(n);
    Button redo =
        ui.add(
            content,
            ui.button(tr("Redo with a smarter model"), Ui.Kind.OUTLINE, v -> redo(smarter)),
            12);
    String redoNote =
        !audio
            ? tr("Audio for this note wasn't kept, so it can't be redone.")
            : smarter == null
                ? tr("This note already used the most accurate model.")
                : modelName(smarter.optString("id"))
                    + " · "
                    + estimate(smarter, e.duration());
    if (!audio || smarter == null || redoing) {
      redo.setEnabled(false);
      redo.setAlpha(0.5f);
    }
    ui.add(content, ui.text(redoNote, 13, Ui.MUTED), 4);
    if (redoing) {
      noteStatus.setText(tr("Redoing with a smarter model… You can leave this screen."));
      editor.setEnabled(false);
    }
    long ms = e.json.optLong("processingMs");
    ui.add(
        content,
        ui.mono(
            routeLabel(e)
                + " · "
                + e.model()
                + (e.json.has("textModel") ? " + " + e.json.optString("textModel") : "")
                + (e.json.has("template") ? " · " + e.json.optString("template") : "")
                + (ms > 0 ? String.format(Locale.ROOT, " · %.1f s", ms / 1000.0) : "")
                + (n.versions().size() > 1
                    ? " · " + plural(n.versions().size(), "%1$d version", "%1$d versions")
                    : ""),
            11,
            Ui.MUTED),
        18);
    if (audio)
      links.addView(
          ui.button(
              tr("Delete audio"),
              Ui.Kind.QUIET,
              v -> {
                try {
                  new AudioArchive(this).delete(noteId);
                  show("note");
                } catch (Exception ex) {
                  toast(tr("Could not delete audio"));
                }
              }));
  }

  /** Every note's steps, newest first, from what the notes on this device kept. */
  private void activityPage() {
    heading(
        tr("Settings"),
        tr("Activity"),
        tr("Every note's steps: which model did what, how long it took and what it cost. Kept on this device with your notes; times include the connection."));
    List<HistoryEntry> entries;
    try (HistoryStore h = new HistoryStore(this)) {
      entries = h.list(100, 0);
    } catch (Exception ex) {
      entries = new ArrayList<>();
    }
    if (entries.isEmpty()) ui.add(content, ui.text(tr("No notes yet."), 15, Ui.MUTED), 16);
    for (HistoryEntry e : entries) {
      LinearLayout card = ui.add(content, ui.card(Ui.BG, Ui.LINE), 10);
      LinearLayout head = ui.horizontal();
      boolean routed =
          e.json.has("route") || e.json.has("routeFailed") || e.json.has("textModel") || chiefNote(e);
      head.addView(
          ui.text(
              DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                      .format(new Date(e.created()))
                  + (routed ? " · " + routeLabel(e) : ""),
              13,
              Ui.MUTED),
          new LinearLayout.LayoutParams(0, -2, 1));
      head.addView(
          ui.mono(
              e.cost().signum() == 0 && e.incompleteCost() ? "–" : exact(e.cost()), 12, Ui.INK));
      card.addView(head);
      String text = e.text().replaceAll("\\s+", " ").trim();
      TextView preview = ui.text(text.isEmpty() ? tr("No text yet") : text, 15, Ui.INK);
      preview.setMaxLines(2);
      preview.setEllipsize(TextUtils.TruncateAt.END);
      ui.add(card, preview, 4);
      org.json.JSONArray steps = e.json.optJSONArray("steps");
      if (steps == null || steps.length() == 0)
        // Notes from before the activity log: only the models are known.
        ui.add(
            card,
            ui.mono(
                e.model() + (e.json.has("textModel") ? " + " + e.json.optString("textModel") : ""),
                11,
                Ui.MUTED),
            6);
      else
        for (int i = 0; i < steps.length(); i++) {
          JSONObject step = steps.optJSONObject(i);
          if (step != null) ui.add(card, stepRow(step), 6);
        }
      card.setOnClickListener(v -> openNote(e.recordingId()));
    }
  }

  /** The request check of versions 2.0 and 2.1, in older notes' activity. */
  static final String LEGACY_CHECK = "deepseek/deepseek-v4.1-flash", LEGACY_CHECK_NAME = "DeepSeek V4.1 Flash";

  /** "Jev · 0.4 s · $0.00002 → refine 0.97", "Refine · Claude Sonnet 5.5 · 2.1 s · $0.0003". */
  private View stepRow(JSONObject step) {
    String kind = step.optString("kind"), model = step.optString("model");
    String detail = step.optString("detail");
    String label =
        switch (kind) {
          case "speech" -> tr("Transcription");
          case "jev" -> "Jev";
          case "check" -> tr("Request check");
          // "text", "simple" and "smart": notes from before Refine.
          case "refine", "text", "simple", "smart" -> tr("Refine");
          case DictationService.CHIEF_STEP -> "bb";
          default -> kind;
        };
    String name =
        switch (kind) {
          case "speech" -> modelName(model);
          case "jev" -> "";
          case "check" -> LEGACY_CHECK_NAME;
          case DictationService.CHIEF_STEP -> tr("Chief of staff");
          default -> textModelName(model);
        };
    // A step's detail is an error, except the old check's answer; Jev's answer is kept apart.
    String answer =
        kind.equals("check") ? checkDetail(detail) : step.has("answer") ? step.optString("answer") : null;
    boolean failed = !detail.isEmpty() && !kind.equals("check");
    String line =
        label
            + (name.isEmpty() ? "" : " · " + name)
            + " · "
            + String.format(Locale.getDefault(), "%.1f s", step.optLong("ms") / 1000.0)
            + (step.has("cost")
                ? " · " + (step.optBoolean("approx") ? "≈" : "") + exact(new BigDecimal(step.optString("cost")))
                : "")
            + (step.optBoolean("approx") ? " " + tr("(Anthropic credits)") : "")
            + (failed ? " · " + detail : answer != null && !answer.isEmpty() ? " → " + answer : "")
            + (step.has("fallback") ? " · " + tr("via OpenRouter: %1$s", step.optString("fallback")) : "");
    TextView t = ui.text(line, 13, failed ? Ui.WARN : Ui.BODY);
    t.setContentDescription(line + " (" + model + ")");
    return t;
  }

  /** The old check's answer in words ("Text · Apartment application"); null for an error. */
  private static String checkDetail(String detail) {
    int cut = detail.indexOf(" · ");
    String rest = cut < 0 ? "" : detail.substring(cut);
    return switch (cut < 0 ? detail : detail.substring(0, cut)) {
      case "transcript" -> tr("Transcript only") + rest;
      case "text", "simple", "smart" -> tr("Refine") + rest;
      case "chief" -> tr("Chief of staff");
      default -> null;
    };
  }

  /** What happened to this note: refined, sent to the chief, or the transcript as said. */
  private static String routeLabel(HistoryEntry e) {
    if (chiefNote(e))
      return DictationService.sentToChief(e) ? tr("Sent to chief of staff") : tr("Not sent to chief of staff");
    if (e.json.has("processed")) return tr("Refined");
    if (e.json.has("routeFailed")) return tr("Request check failed, kept as said");
    return tr("Transcript only");
  }

  private boolean redoing() {
    return DictationService.busy() && noteId.equals(prefs.getString("recordingId", ""));
  }

  /** Saves typed changes to the version you are looking at. */
  private void saveEdits() {
    if (editor == null || applying) return;
    String now = editor.getText().toString().trim();
    if (now.equals(editorBase.trim())) return;
    try (HistoryStore h = new HistoryStore(this)) {
      NoteList.Note n = currentNote();
      if (n == null) return;
      HistoryEntry e = n.shown();
      // Edits belong to the version you are looking at; "raw" stays what the speech model wrote.
      if (showOriginal) e.json.put("original", now);
      else {
        e.json.put("text", now);
        e.json.put("processed".equals(e.json.optString("version")) ? "processed" : "original", now);
      }
      h.save(e);
      editorBase = now;
      prefs.edit().putLong("historyRevision", System.nanoTime()).apply();
      seenRevision = prefs.getLong("historyRevision", 0);
    } catch (Exception ex) {
      toast(tr("Could not save your changes"));
    }
  }

  /** Shows the tidied-up text or what you said; Copy copies whichever is shown. */
  private void switchVersion(boolean original) {
    saveEdits();
    showOriginal = original;
    show("note");
  }

  /** Runs Refine once more on a note where it failed. */
  private void processAgain() {
    if (applying) return;
    try {
      DictationService.requireKey(this);
    } catch (IOException ex) {
      toast(ex.getMessage());
      open("key");
      return;
    }
    saveEdits(); // Unsaved typing would otherwise overwrite the new result.
    applying = true;
    toast(tr("Refining your text…"));
    io.execute(
        () -> {
          String failed = null;
          try {
            NoteList.Note n = currentNote();
            if (n == null) throw new IOException(tr("This note was deleted."));
            HistoryEntry e = n.shown();
            failed = DictationService.refineNote(this, e.id(), profile, e.json.optString("template"));
            prefs.edit().putLong("historyRevision", System.nanoTime()).apply();
          } catch (Exception ex) {
            failed = DictationService.friendly(ex);
          } finally {
            String message = failed;
            main.post(
                () -> {
                  applying = false;
                  toast(message != null ? message : tr("Refined"));
                  if (page.equals("note")) show("note");
                });
          }
        });
  }

  private JSONObject smarterModel(NoteList.Note n) {
    if (catalog == null) return null;
    Set<String> tried = new HashSet<>();
    for (HistoryEntry v : n.versions()) tried.add(v.model());
    return Feedback.alternative(
        catalog.models,
        catalog.find(n.shown().model()),
        Feedback.ACCURACY,
        tried);
  }

  private void redo(JSONObject model) {
    if (model == null) return;
    saveEdits();
    if (DictationService.busy() || DictationService.audio(this).exists()) {
      toast(tr("Finish or discard the pending recording first"));
      return;
    }
    String session = UUID.randomUUID().toString();
    try {
      startForegroundService(
          new Intent(this, DictationService.class)
              .setAction(DictationService.COMPARE)
              .putExtra("recordingId", noteId)
              .putExtra("model", model.optString("id"))
              .putExtra("sessionId", session));
      redoSession = session;
      main.postDelayed(
          () -> {
            if (page.equals("note")) show("note");
          },
          300);
    } catch (Exception ex) {
      toast(tr("Could not start. Your note is safe."));
    }
  }

  private void deleteNote() {
    if (noteId.equals(prefs.getString("recordingId", "")) && DictationService.busy()) {
      toast(tr("Wait until this note finishes"));
      return;
    }
    new AlertDialog.Builder(this)
        .setTitle(tr("Delete this note?"))
        .setMessage(tr("Its text, versions, costs and audio are removed. This can't be undone."))
        .setPositiveButton(
            tr("Delete"),
            (d, w) -> {
              try (HistoryStore h = new HistoryStore(this)) {
                for (HistoryEntry v : h.versions(noteId)) h.delete(v.id());
                new AudioArchive(this).delete(noteId);
                new Attachments(this).deleteNote(noteId);
                vault.remove("transcript");
              } catch (Exception ex) {
                toast(tr("Could not delete everything"));
              }
              editor = null;
              prefs.edit().putLong("historyRevision", System.nanoTime()).apply();
              goBack();
            })
        .setNegativeButton(tr("Keep"), null)
        .show();
  }

  // ---------------------------------------------------------------- usage

  private void usagePage() {
    content.addView(ui.back(tr("Home"), v -> goBack()));
    LinearLayout head = ui.horizontal();
    head.addView(ui.title(monthName(usageMonth), 30), new LinearLayout.LayoutParams(0, -2, 1));
    Button earlier =
        ui.button(
            tr("‹ Earlier"),
            Ui.Kind.QUIET,
            v -> {
              usageMonth++;
              show("usage");
            });
    earlier.setContentDescription(tr("Previous month"));
    head.addView(earlier);
    if (usageMonth > 0) {
      Button later =
          ui.button(
              tr("Later ›"),
              Ui.Kind.QUIET,
              v -> {
                usageMonth--;
                show("usage");
              });
      later.setContentDescription(tr("Next month"));
      head.addView(later);
    }
    ui.add(content, head, 4);
    LinearLayout box = ui.add(content, ui.vertical(), 8);
    int month = usageMonth;
    io.execute(
        () -> {
          List<HistoryEntry> all;
          try (HistoryStore h = new HistoryStore(this)) {
            all = h.all();
          } catch (Exception ex) {
            all = new ArrayList<>();
          }
          long from = monthStart(month).getTimeInMillis(),
              to = monthStart(month - 1).getTimeInMillis();
          BigDecimal spent = BigDecimal.ZERO, credits = BigDecimal.ZERO;
          double seconds = 0;
          int count = 0;
          boolean incomplete = false;
          Map<String, double[]> byModel = new TreeMap<>();
          for (HistoryEntry e : all) {
            if (e.created() < from || e.created() >= to) continue;
            spent = spent.add(e.cost());
            credits = credits.add(e.anthropicCost());
            incomplete |= e.incompleteCost();
            if (e.recordingId().equals(e.id())) {
              seconds += e.duration();
              count++;
            }
            double[] m = byModel.computeIfAbsent(e.model(), k -> new double[2]);
            m[0] += e.duration();
            m[1] += e.cost().subtract(e.textCost()).subtract(e.routeCost()).subtract(e.jevCost()).doubleValue();
            if (e.textCost().signum() > 0) {
              double[] t = byModel.computeIfAbsent(e.json.optString("textModel"), k -> new double[2]);
              t[1] += e.textCost().doubleValue();
            }
            if (e.routeCost().signum() > 0)
              byModel.computeIfAbsent(LEGACY_CHECK, k -> new double[2])[1] +=
                  e.routeCost().doubleValue();
            if (e.jevCost().signum() > 0)
              byModel.computeIfAbsent(Jev.MODEL, k -> new double[2])[1] += e.jevCost().doubleValue();
          }
          BigDecimal total = spent;
          double talked = seconds;
          int notesCount = count;
          boolean partial = incomplete;
          BigDecimal anthropic = credits;
          main.post(() -> renderUsage(box, total, talked, notesCount, partial, byModel, anthropic));
        });
  }

  private void renderUsage(
      LinearLayout box,
      BigDecimal spent,
      double seconds,
      int count,
      boolean partial,
      Map<String, double[]> byModel,
      BigDecimal anthropic) {
    if (!page.equals("usage")) return;
    LinearLayout tiles = ui.horizontal();
    String[][] values = {
      {tr("Spent"), amount(spent)}, {tr("Talked"), duration(seconds)}, {tr("Notes"), String.valueOf(count)}
    };
    for (int i = 0; i < values.length; i++) {
      LinearLayout t = ui.card(Ui.PANEL, 0);
      t.addView(ui.text(values[i][0], 12, Ui.MUTED));
      t.addView(ui.title(values[i][1], 20));
      ui.addWeighted(tiles, t, i == 0 ? 0 : 8);
    }
    ui.add(box, tiles, 12);
    LinearLayout models = ui.add(box, ui.card(Ui.BG, Ui.LINE), 14);
    models.addView(ui.strong(tr("By model"), 15));
    double max = byModel.values().stream().mapToDouble(m -> m[1]).max().orElse(0);
    if (byModel.isEmpty()) ui.add(models, ui.text(tr("Nothing this month."), 14, Ui.MUTED), 8);
    for (Map.Entry<String, double[]> m : byModel.entrySet()) {
      LinearLayout row = ui.horizontal();
      row.addView(
          ui.text(modelName(m.getKey()), 13, Ui.INK), new LinearLayout.LayoutParams(0, -2, 1));
      row.addView(
          ui.text(
              (m.getValue()[0] > 0 ? duration(m.getValue()[0]) + " · " : "")
                  + amount(BigDecimal.valueOf(m.getValue()[1])),
              12,
              Ui.MUTED));
      ui.add(models, row, 12);
      LinearLayout track = ui.horizontal();
      track.setBackground(ui.shape(Ui.DIVIDER, 0, 3));
      View fill = new View(this);
      fill.setBackground(ui.shape(Ui.TEAL, 0, 3));
      float share = max <= 0 ? 0 : (float) (m.getValue()[1] / max);
      track.addView(fill, new LinearLayout.LayoutParams(0, ui.dp(6), Math.max(0.02f, share)));
      track.addView(
          new View(this), new LinearLayout.LayoutParams(0, ui.dp(6), Math.max(0.0001f, 1 - share)));
      ui.add(models, track, 6);
    }
    ui.add(
        box,
        ui.text(
            partial
                ? tr(
                    "What OpenRouter charged for each call. Some calls reported no cost, so the"
                        + " real total is a little higher.")
                : tr("What OpenRouter charged for each call."),
            13,
            Ui.MUTED),
        14);
    // Anthropic credits are already paid for: shown apart, never in the total.
    if (anthropic.signum() > 0)
      ui.add(
          box,
          ui.text(tr("Anthropic credits used this month ≈%1$s (Refine with your Anthropic key, at list price; not in the total)", exact(anthropic)), 13, Ui.MUTED),
          10);
  }

  // ---------------------------------------------------------------- settings

  private void settingsPage() {
    heading(tr("Home"), tr("Settings"), null);
    ui.add(content, ui.label(tr("OpenRouter")), 22);
    LinearLayout openRouter = ui.add(content, ui.card(Ui.BG, 0), 8);
    openRouter.setPadding(0, 0, 0, 0);
    openRouter.addView(
        ui.row(
            tr("OpenRouter key"),
            OpenRouter.hasKey(this) ? tr("Saved, encrypted") : tr("Not set"),
            null,
            v -> open("key")));
    openRouter.addView(ui.divider());
    openRouter.addView(ui.row(tr("Usage"), tr("What each month cost"), null, v -> open("usage")));
    openRouter.addView(ui.divider());
    openRouter.addView(
        ui.row(
            tr("Activity"),
            tr("Which model did what, how long it took, what it cost"),
            null,
            v -> open("activity")));
    bbRows();
    ui.add(content, ui.label(tr("Dictation")), 22);
    LinearLayout dictation = ui.add(content, ui.card(Ui.BG, 0), 8);
    dictation.setPadding(0, 0, 0, 0);
    dictation.addView(
        ui.row(tr("Speech model"), modelName(profile.fixedModel()), null, v -> open("models")));
    dictation.addView(ui.divider());
    dictation.addView(ui.row(tr("Refine"), refineLabel(), null, v -> open("refine")));
    dictation.addView(ui.divider());
    int templates = new Templates(this).all().size();
    dictation.addView(
        ui.row(
            tr("Templates"),
            templates == 0
                ? tr("Texts you reuse, adapted by voice")
                : plural(templates, "%1$d template", "%1$d templates"),
            null,
            v -> open("templates")));
    dictation.addView(ui.divider());
    dictation.addView(
        ui.row(
            tr("Words"),
            plural(profile.words().size(), "%1$d word", "%1$d words"),
            null,
            v -> open("words")));
    dictation.addView(ui.divider());
    List<String> topics = profile.topics();
    dictation.addView(
        ui.row(
            tr("About you"),
            topics.isEmpty()
                ? tr("Not set")
                : String.join(", ", topics.stream().map(I18n::tr).toList()),
            null,
            v -> open("about")));
    ui.add(content, ui.label(tr("Using Talk Router")), 22);
    LinearLayout using = ui.add(content, ui.card(Ui.BG, 0), 8);
    using.setPadding(0, 0, 0, 0);
    using.addView(ui.row(tr("Start and insert"), startLabel(), null, v -> open("start")));
    using.addView(ui.divider());
    using.addView(ui.row(tr("Floating panel"), bubbleLabel(), null, v -> bubbleOptions()));
    using.addView(ui.divider());
    using.addView(
        ui.row(
            tr("Permissions"),
            microphone() ? tr("Microphone allowed") : tr("Microphone not allowed"),
            null,
            v -> open("setup")));
    using.addView(ui.divider());
    using.addView(keepAudioRow());
    using.addView(ui.divider());
    using.addView(ui.row(tr("Delete saved audio"), null, null, v -> deleteAudio()));
    ui.add(content, ui.label(tr("App")), 22);
    LinearLayout app = ui.add(content, ui.card(Ui.BG, 0), 8);
    app.setPadding(0, 0, 0, 0);
    app.addView(
        ui.row(
            tr("Language"),
            getResources().getConfiguration().getLocales().get(0).getDisplayLanguage(),
            null,
            v -> {
              // Android keeps the per-app language; this opens its picker for Talk Router.
              try {
                startActivity(
                    new Intent(Settings.ACTION_APP_LOCALE_SETTINGS)
                        .setData(Uri.fromParts("package", getPackageName(), null)));
              } catch (ActivityNotFoundException ex) {
                toast(tr("Change the language in Android settings → Apps → Talk Router."));
              }
            }));
    app.addView(ui.divider());
    app.addView(ui.row(tr("Legal & support"), null, null, v -> open("legal")));
    app.addView(ui.divider());
    TextView wipe = ui.text(tr("Delete all my data"), 16, Ui.WARN);
    wipe.setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(16));
    wipe.setOnClickListener(v -> deleteAllData());
    app.addView(wipe);
    TextView version = ui.text("Talk Router " + BuildConfig.VERSION_NAME, 12, Ui.MUTED);
    version.setGravity(Gravity.CENTER);
    ui.add(content, version, 18);
  }

  private View keepAudioRow() {
    Switch keep = ui.toggle(prefs.getBoolean("retainAudio", true));
    keep.setOnCheckedChangeListener(
        (b, c) -> {
          prefs.edit().putBoolean("retainAudio", c).commit();
          try {
            new AudioArchive(this).prune();
          } catch (Exception ignored) {
            /* Pruning runs again on next launch. */
          }
        });
    return ui.row(
        tr("Keep audio for 7 days"), tr("Needed to redo a note with a smarter model"), keep, null);
  }

  private void deleteAudio() {
    if (DictationService.busy()) {
      toast(tr("Finish recording or upload first"));
      return;
    }
    new AlertDialog.Builder(this)
        .setTitle(tr("Delete all saved audio?"))
        .setMessage(tr("Text and costs stay. Notes can no longer be redone."))
        .setPositiveButton(
            tr("Delete"),
            (d, w) -> {
              try {
                new AudioArchive(this).deleteAll();
                toast(tr("Saved audio deleted"));
              } catch (Exception ex) {
                toast(tr("Could not delete all audio"));
              }
            })
        .setNegativeButton(tr("Keep"), null)
        .show();
  }

  private void modelsPage() {
    heading(tr("Back"), tr("Speech model"), tr("Talk Router uses this model for every note."));
    Ui.Flow sorts = ui.add(content, ui.flow(), 14);
    String[][] options = {
      {"recommended", tr("Best overall")},
      {"accuracy", tr("Most accurate")},
      {"cost", tr("Cheapest")},
      {"speed", tr("Fastest")}
    };
    for (String[] o : options)
      sorts.addView(
          ui.chip(
              o[1],
              modelsSort.equals(o[0]),
              v -> {
                modelsSort = o[0];
                show("models");
              }));
    EditText search = ui.input(modelsQuery, tr("Search models"), false);
    search.setBackground(ui.shape(Ui.PANEL, 0, 14));
    search.addTextChangedListener(
        after(
            s -> {
              modelsQuery = s;
              renderModels();
            }));
    ui.add(content, search, 12);
    modelList = ui.add(content, ui.vertical(), 8);
    renderModels();
    ui.add(
        content,
        ui.text(
            tr(
                "Word and speed dots are editorial estimates, not measurements of your voice."
                    + " Prices per minute from %1$s.",
                catalog == null ? "—" : catalog.priceDate),
            12,
            Ui.MUTED),
        14);
  }

  private void renderModels() {
    if (modelList == null || catalog == null) return;
    modelList.removeAllViews();
    String selected = profile.fixedModel();
    List<JSONObject> list = ModelRanking.filter(catalog.models, modelsQuery, modelsSort);
    if (list.isEmpty()) ui.add(modelList, ui.text(tr("No models match."), 14, Ui.MUTED), 10);
    for (JSONObject m : list) {
      boolean on = m.optString("id").equals(selected);
      LinearLayout row = ui.card(on ? Ui.TEAL_TINT : Ui.BG, on ? Ui.TEAL : Ui.LINE);
      LinearLayout top = ui.horizontal();
      top.addView(ui.strong(ModelRanking.name(m), 16), new LinearLayout.LayoutParams(0, -2, 1));
      top.addView(ui.mono(perMinute(m.optString("id")), 12, Ui.INK));
      row.addView(top);
      ui.add(
          row,
          ui.mono(
              shortId(m.optString("id")).split("/")[0]
                  + " · "
                  + ModelRanking.languages(m)
                  + "   "
                  + tr(
                      "words %1$s  speed %2$s",
                      ModelRanking.dots(ModelRanking.accuracy(m)),
                      ModelRanking.dots(ModelRanking.speed(m))),
              11,
              Ui.MUTED),
          4);
      row.setOnClickListener(
          v -> {
            if (DictationService.busy()) {
              toast(tr("Finish recording or upload first"));
              return;
            }
            prefs.edit().putString("model", m.optString("id")).apply();
            toast(tr("Talk Router now always uses %1$s", ModelRanking.name(m)));
            goBack();
          });
      ui.add(modelList, row, 8);
    }
  }

  private void legalPage() {
    heading(tr("Settings"), tr("Legal & support"), null);
    LinearLayout docs = ui.add(content, ui.card(Ui.BG, Ui.LINE), 18);
    docs.setPadding(0, 0, 0, 0);
    String[][] rows = {
      {"privacy", tr("Privacy policy")},
      {"impressum", tr("Imprint")},
      {"licenses", tr("Open-source notices")}
    };
    for (int i = 0; i < rows.length; i++) {
      String[] r = rows[i];
      if (i > 0) docs.addView(ui.divider());
      docs.addView(ui.row(r[1], null, null, v -> openDoc(r[0])));
    }
    LinearLayout help = ui.add(content, ui.card(Ui.BG, Ui.LINE), 12);
    help.setPadding(0, 0, 0, 0);
    help.addView(ui.row(tr("Send feedback"), SUPPORT_EMAIL, null, v -> feedback()));
    TextView version = ui.text("Talk Router " + BuildConfig.VERSION_NAME, 12, Ui.MUTED);
    version.setGravity(Gravity.CENTER);
    ui.add(content, version, 18);
  }

  private void openDoc(String name) {
    doc = name;
    open("doc");
  }

  private void docPage() {
    int id =
        switch (doc) {
          case "impressum" -> R.raw.impressum;
          case "licenses" -> R.raw.licenses;
          default -> R.raw.privacy;
        };
    String text;
    try (java.io.InputStream in = getResources().openRawResource(id)) {
      text = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    } catch (Exception ex) {
      text = "";
    }
    int firstBreak = text.indexOf('\n');
    heading(tr("Back"), firstBreak > 0 ? text.substring(0, firstBreak).trim() : "", null);
    TextView body = ui.text(text.substring(Math.max(0, firstBreak)).trim(), 15, Ui.BODY);
    body.setTextIsSelectable(true);
    body.setLineSpacing(0, 1.35f);
    ui.add(content, body, 14);
  }

  /** Opens the mail app; the user sees and sends the message themselves. */
  private void feedback() {
    String body =
        "\n\n—\nTalk Router "
            + BuildConfig.VERSION_NAME
            + " · Android "
            + Build.VERSION.RELEASE
            + " · "
            + Build.MODEL;
    Intent mail =
        new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
            .putExtra(Intent.EXTRA_EMAIL, new String[] {SUPPORT_EMAIL})
            .putExtra(Intent.EXTRA_SUBJECT, tr("Talk Router feedback"))
            .putExtra(Intent.EXTRA_TEXT, body);
    try {
      startActivity(mail);
    } catch (Exception ex) {
      toast(tr("No email app found. Write to %1$s", SUPPORT_EMAIL));
    }
  }

  private void deleteAllData() {
    if (DictationService.busy()) {
      toast(tr("Finish recording or upload first"));
      return;
    }
    new AlertDialog.Builder(this)
        .setTitle(tr("Delete all your data?"))
        .setMessage(
            tr("This deletes your notes, audio, templates, words, About you, your OpenRouter key, your bb sign-in and settings on this phone. It can't be undone."))
        .setPositiveButton(
            tr("Delete everything"),
            (d, w) -> {
              try {
                new AudioArchive(this).deleteAll();
                DictationService.audio(this).delete();
                deleteDatabase("history.db");
                vault.remove(OpenRouter.KEY);
                vault.remove("templates");
                new Attachments(this).deleteAll();
                vault.remove("transcript");
                vault.remove("progress");
                BbBridge.forgetAll(this);
              } catch (Exception ignored) {
                /* Continue with what can be removed. */
              }
              prefs.edit().clear().commit();
              stack.clear();
              toast(tr("All your data was deleted"));
              show("welcome");
            })
        .setNegativeButton(tr("Keep"), null)
        .show();
  }

  /** Settings → Refine: what starts by itself, the models, and Refine's instructions. */
  private void refinePage() {
    heading(
        tr("Back"),
        tr("Refine"),
        tr("Your words come first, always. Then ✨ Refine turns them into the text you need: it works out what it's for (a message, an email, a note), follows what you asked for and fills in your templates. Jev, a fast decision model, starts it by itself when you asked for a text, and sends a task to your chief of staff only when you clearly address it."));
    LinearLayout card = ui.add(content, ui.card(Ui.BG, Ui.LINE), 18);
    card.setPadding(0, 0, 0, 0);
    refineRows(card);
    example();
    ui.add(content, ui.strong(tr("Instructions for Refine"), 15), 22);
    ui.add(content, ui.text(tr("About you, your words and the template you name are added automatically."), 13, Ui.MUTED), 2);
    EditText prompt = ui.input(profile.refinePrompt(), "", true);
    prompt.setFilters(new InputFilter[] {new InputFilter.LengthFilter(4000)});
    prompt.setMinLines(8);
    prompt.setTextSize(14);
    ui.add(content, prompt, 8);
    autosave(
        prompt,
        t ->
            prefs
                .edit()
                .putString("processingPrompt", t.trim().equals(TextModel.DEFAULT_PROMPT) ? "" : t)
                .apply());
    ui.add(
        content,
        ui.button(
            tr("Reset to default"),
            Ui.Kind.QUIET,
            v -> {
              prefs.edit().remove("processingPrompt").apply();
              show("refine");
            }),
        4);
  }

  /** Templates: texts you reuse, which a dictation can ask for and adapt. */
  private void templatesPage() {
    heading(
        tr("Settings"),
        tr("Templates"),
        tr("Keep texts you write again and again, like an application for an apartment. Then dictate which one you want and what's different this time."));
    LinearLayout example = ui.add(content, ui.card(Ui.PANEL, 0), 16);
    example.addView(ui.label(tr("You say")));
    ui.add(
        example,
        ui.text(
            tr("“Use my apartment application for the two-room flat on Hauptstraße 5, the viewing is on Saturday, and mention that I work from home.”"),
            14,
            Ui.BODY),
        6);
    ui.add(
        example,
        ui.text(
            tr("Talk Router writes your application with these details, ready to paste."), 14, Ui.MUTED),
        6);
    if (!profile.autoRefine())
      ui.add(
          content,
          ui.text(
              tr("Refine automatically is off: a template is used when you tap ✨ Refine."),
              14,
              Ui.MUTED),
          14);
    List<Templates.Template> all = new Templates(this).all();
    if (all.isEmpty())
      ui.add(content, ui.text(tr("No templates yet."), 15, Ui.MUTED), 18);
    else {
      LinearLayout list = ui.add(content, ui.card(Ui.BG, Ui.LINE), 18);
      list.setPadding(0, 0, 0, 0);
      for (int i = 0; i < all.size(); i++) {
        Templates.Template t = all.get(i);
        if (i > 0) list.addView(ui.divider());
        String preview = t.text().replace('\n', ' ');
        list.addView(
            ui.row(
                t.name().isBlank() ? tr("Untitled") : t.name(),
                preview.length() > 80 ? preview.substring(0, 80) + "…" : preview,
                null,
                v -> {
                  templateId = t.id();
                  open("template");
                }));
      }
    }
    ui.add(
        content,
        ui.button(
            tr("New template"),
            Ui.Kind.PRIMARY,
            v -> {
              templateId = "";
              open("template");
            }),
        14);
    ui.add(
        content,
        ui.text(
            tr("Your templates stay on this device. Only the one you ask for is sent to the AI, together with your dictation."),
            13,
            Ui.MUTED),
        12);
  }

  private void templatePage() {
    Templates store = new Templates(this);
    Templates.Template t = templateId.isEmpty() ? null : store.get(templateId);
    heading(tr("Back"), t == null ? tr("New template") : tr("Edit template"), null);
    ui.add(content, ui.strong(tr("Name"), 15), 18);
    EditText name =
        ui.input(t == null ? "" : t.name(), tr("For example: Apartment application"), false);
    ui.add(content, name, 8);
    ui.add(content, ui.strong(tr("Text"), 15), 18);
    ui.add(
        content,
        ui.text(tr("Paste or type the text as you'd send it."), 13, Ui.MUTED),
        2);
    EditText text = ui.input(t == null ? "" : t.text(), tr("Your template"), true);
    text.setMinLines(10);
    text.setTextSize(15);
    text.setFilters(new InputFilter[] {new InputFilter.LengthFilter(Templates.MAX_TEXT)});
    ui.add(content, text, 8);
    ui.add(
        content,
        ui.button(
            tr("Save"),
            Ui.Kind.PRIMARY,
            v -> {
              if (name.getText().toString().isBlank() || text.getText().toString().isBlank()) {
                toast(tr("Give the template a name and a text"));
                return;
              }
              try {
                templateId =
                    store.save(
                        t == null ? null : t.id(),
                        name.getText().toString(),
                        text.getText().toString());
                toast(tr("Template saved"));
                goBack();
              } catch (Exception ex) {
                toast(tr("Could not save the template"));
              }
            }),
        16);
    if (t != null) {
      Button delete =
          ui.button(
              tr("Delete template"),
              Ui.Kind.QUIET,
              v ->
                  new AlertDialog.Builder(this)
                      .setTitle(tr("Delete this template?"))
                      .setPositiveButton(
                          tr("Delete"),
                          (d, w) -> {
                            try {
                              store.delete(t.id());
                            } catch (Exception ex) {
                              toast(tr("Could not delete the template"));
                            }
                            goBack();
                          })
                      .setNegativeButton(tr("Keep"), null)
                      .show());
      delete.setTextColor(Ui.WARN);
      ui.add(content, delete, 8);
    }
  }

  /** The two switches and the two fixed models, for the Refine page. */
  private void refineRows(LinearLayout card) {
    Switch refine = ui.toggle(profile.autoRefine());
    refine.setOnCheckedChangeListener((b, c) -> prefs.edit().putBoolean(Profile.AUTO_REFINE, c).apply());
    card.addView(
        ui.row(
            tr("Refine automatically"),
            tr("When you ask for a text, name a template, or the dictation needs tidying"),
            refine,
            null));
    card.addView(ui.divider());
    Switch chief = ui.toggle(profile.autoChief());
    chief.setOnCheckedChangeListener((b, c) -> prefs.edit().putBoolean(Profile.AUTO_CHIEF, c).apply());
    card.addView(
        ui.row(
            tr("Send to chief when addressed"),
            tr("When you speak to your chief by name (Settings → Chief of staff); otherwise tap 🧑‍💼"),
            chief,
            null));
    card.addView(ui.divider());
    card.addView(ui.row(tr("Decides"), "Jev · " + Jev.MODEL, null, null));
    card.addView(ui.divider());
    boolean direct = Anthropic.hasKey(this);
    card.addView(
        ui.row(
            tr("Refines"),
            TextModel.NAME + " · " + (direct ? tr("Anthropic, your key") : TextModel.MODEL),
            null,
            null));
    card.addView(ui.divider());
    card.addView(
        ui.row(
            tr("Anthropic key"),
            direct ? tr("Saved, encrypted · Refine uses your Anthropic credits") : tr("Optional · Refine on your Anthropic credits"),
            null,
            v -> open("anthropic")));
  }

  private String refineLabel() {
    return profile.autoRefine() ? tr("Automatic · %1$s", TextModel.NAME) : tr("When you tap ✨ Refine");
  }

  private String textModelName(String id) {
    if (LEGACY_CHECK.equals(id)) return tr("Request check · %1$s", LEGACY_CHECK_NAME);
    if (Jev.MODEL.equals(id)) return "Jev";
    if (TextModel.MODEL.equals(id)) return TextModel.NAME;
    if (Anthropic.model(TextModel.MODEL).equals(id)) return TextModel.NAME + " · Anthropic";
    return id;
  }

  private String bubbleLabel() {
    return "never".equals(prefs.getString("bubbleMode", "recording")) ? tr("Never") : tr("While you dictate");
  }

  private void bubbleOptions() {
    String[] labels = {tr("While you dictate"), tr("Never")};
    String[] values = {"recording", "never"};
    int current = Arrays.asList(values).indexOf(prefs.getString("bubbleMode", "recording"));
    new AlertDialog.Builder(this)
        .setTitle(tr("Floating panel"))
        .setSingleChoiceItems(
            labels,
            Math.max(0, current),
            (d, which) -> {
              prefs.edit().putString("bubbleMode", values[which]).apply();
              if (!Settings.canDrawOverlays(this) && !values[which].equals("never")) {
                startActivity(
                    new Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
              } else if (DictationService.instance != null || values[which].equals("always"))
                send(DictationService.CONFIGURE);
              d.dismiss();
              show(page);
            })
        .setNegativeButton(tr("Cancel"), null)
        .show();
  }

  private void addTile() {
    try {
      getSystemService(StatusBarManager.class)
          .requestAddTileService(
              new ComponentName(this, DictationTile.class),
              tr("Talk Router"),
              android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_drift),
              getMainExecutor(),
              result -> {
                if (result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED
                    || result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED) {
                  prefs.edit().putBoolean("tileAdded", true).apply();
                  toast(tr("Talk Router is in Quick Settings, at the end. Swipe down, then left to find it."));
                  if (List.of("start1", "home", "start").contains(page)) show(page);
                } else toast(tr("You can also add Talk Router using the Quick Settings edit button."));
              });
    } catch (RuntimeException ex) {
      toast(tr("Swipe down, edit Quick Settings, then add Talk Router."));
    }
  }

  // ---------------------------------------------------------------- live state

  private void renderStatus() {
    renderSkills();
    boolean busy = DictationService.busy(), active = DictationService.instance != null;
    String state = active ? prefs.getString("state", "") : "";
    boolean pending = !busy && DictationService.audio(this).exists();
    if (record != null) {
      boolean recording = "recording".equals(state);
      String line =
          active && busy
              ? prefs.getString("detail", "")
              : pending
                  ? (DictationService.ERROR.equals(prefs.getString("state", ""))
                      ? tr("%1$s The recording is saved.", prefs.getString("detail", ""))
                      : tr("A recording was saved but not transcribed."))
                  : DictationService.EMPTY.equals(prefs.getString("state", ""))
                      ? tr("No speech recognized. Ready to try again.")
                      : "";
      String key = recording + "|" + busy + "|" + pending + "|" + line;
      if (!key.equals(record.getTag())) {
        record.setTag(key);
        record.setText(
            recording
                ? tr("Stop")
                : busy ? tr("Transcribing…") : page.equals("try1") ? tr("Start") : tr("Start dictating"));
        record.setBackground(ui.shape(recording ? Ui.REC : Ui.TEAL, 0, 16));
        record.setEnabled(recording || (!busy && !pending));
        record.setAlpha(record.isEnabled() ? 1f : 0.6f);
        if (recovery != null) recovery.setVisibility(pending ? View.VISIBLE : View.GONE);
        status.setText(line);
        status.setVisibility(line.isEmpty() ? View.GONE : View.VISIBLE);
      }
    }
    long revision = prefs.getLong("historyRevision", 0);
    if (revision != seenRevision) {
      seenRevision = revision;
      if (page.equals("home") || page.equals("notes")) loadNotes();
      else if (page.equals("note") && !applying) {
        boolean dirty =
            editor != null && !editor.getText().toString().trim().equals(editorBase.trim());
        if (!dirty) {
          if (!redoSession.isEmpty() && !DictationService.busy()) {
            redoSession = "";
            toast(tr("Redone with a smarter model"));
          }
          show("note");
        }
      }
    }
    if (page.equals("note")
        && noteStatus != null
        && !redoing()
        && noteStatus.getText().length() > 0
        && !applying) show("note");
  }

  @Override
  protected void onResume() {
    super.onResume();
    if ("always".equals(prefs.getString("bubbleMode", "recording"))
        && Settings.canDrawOverlays(this)
        && DictationService.instance == null
        && setupComplete()) send(DictationService.CONFIGURE);
    if (List.of("setup", "settings", "allow1", "start1", "insert1", "start").contains(page)) show(page);
    main.post(refresh);
    // After an app update, or a change elsewhere, bb gets the current skills.
    ChiefSkills.sync(this, false);
  }

  @Override
  protected void onPause() {
    saveEdits();
    main.removeCallbacks(refresh);
    super.onPause();
  }

  @Override
  protected void onDestroy() {
    main.removeCallbacksAndMessages(null);
    io.shutdownNow();
    super.onDestroy();
  }

  // ---------------------------------------------------------------- helpers

  private boolean microphone() {
    return checkSelfPermission(Manifest.permission.RECORD_AUDIO)
        == PackageManager.PERMISSION_GRANTED;
  }

  private boolean setupComplete() {
    return microphone() && OpenRouter.hasKey(this);
  }

  private boolean ready() {
    if (!OpenRouter.hasKey(this)) {
      open("key");
      toast(tr("Add your OpenRouter key to start dictating"));
      return false;
    }
    if (!microphone()) {
      open("setup");
      toast(tr("Finish setup to start dictating"));
      return false;
    }
    return true;
  }

  private String modelName(String id) {
    JSONObject m = catalog == null ? null : catalog.find(id);
    return m == null ? textModelName(shortId(id)) : ModelRanking.name(m);
  }

  private static String shortId(String id) {
    return id == null ? "" : id;
  }

  private String perMinute(String id) {
    JSONObject m = catalog == null ? null : catalog.find(id);
    double p = m == null ? Double.NaN : ModelRanking.minuteCost(m);
    return Double.isFinite(p) ? tr("%1$s/min", money(BigDecimal.valueOf(p), 4)) : "";
  }

  private String estimate(JSONObject m, double seconds) {
    double p = ModelRanking.minuteCost(m);
    return Double.isFinite(p)
        ? tr("about %1$s", money(BigDecimal.valueOf(p * seconds / 60), 4))
        : tr("price shown afterwards");
  }

  /** Spending: cents for a dollar and more, otherwise four decimals so small notes don't read $0.00. */
  static String amount(BigDecimal value) {
    return money(value, value.abs().compareTo(BigDecimal.ONE) >= 0 || value.signum() == 0 ? 2 : 4);
  }

  /** A note's cost; "–" when OpenRouter reported none, never a misleading $0.00. */
  private static String cost(BigDecimal value, boolean incomplete) {
    return value.signum() == 0 && incomplete ? "–" : money(value, 4);
  }

  /** Charges to the last cent fraction that isn't zero, up to six decimals ("$0.000015"). */
  static String exact(BigDecimal value) {
    int decimals = Math.max(4, Math.min(6, value.stripTrailingZeros().scale()));
    return money(value, decimals);
  }

  /** US dollars, written the way the current language writes amounts ("$7.14", "7,14 $"). */
  static String money(BigDecimal value, int decimals) {
    java.text.NumberFormat f = java.text.NumberFormat.getCurrencyInstance(Locale.getDefault());
    f.setCurrency(java.util.Currency.getInstance("USD"));
    f.setMinimumFractionDigits(decimals);
    f.setMaximumFractionDigits(decimals);
    f.setRoundingMode(java.math.RoundingMode.HALF_UP);
    return f.format(value);
  }

  private static String duration(double seconds) {
    if (seconds > 0 && seconds < 60) return tr("%1$d s", Math.round(seconds));
    long minutes = Math.round(seconds / 60);
    return minutes >= 60
        ? tr("%1$d h %2$d min", minutes / 60, minutes % 60)
        : tr("%1$d min", minutes);
  }

  private static String preview(NoteList.Note n) {
    String t = n.shown().text().replace('\n', ' ').trim();
    return t.isEmpty() ? tr("No text yet") : t;
  }

  private static Calendar monthStart(int monthsAgo) {
    Calendar c = Calendar.getInstance();
    c.set(Calendar.DAY_OF_MONTH, 1);
    c.set(Calendar.HOUR_OF_DAY, 0);
    c.set(Calendar.MINUTE, 0);
    c.set(Calendar.SECOND, 0);
    c.set(Calendar.MILLISECOND, 0);
    c.add(Calendar.MONTH, -monthsAgo);
    return c;
  }

  private static String monthName(int monthsAgo) {
    Calendar c = monthStart(monthsAgo);
    String name = c.getDisplayName(Calendar.MONTH, Calendar.LONG, Locale.getDefault());
    return monthsAgo == 0 ? name : name + " " + c.get(Calendar.YEAR);
  }

  private static long dayStart(long millis) {
    Calendar c = Calendar.getInstance();
    c.setTimeInMillis(millis);
    c.set(Calendar.HOUR_OF_DAY, 0);
    c.set(Calendar.MINUTE, 0);
    c.set(Calendar.SECOND, 0);
    c.set(Calendar.MILLISECOND, 0);
    return c.getTimeInMillis();
  }

  private static String dayLabel(long millis) {
    long days = (dayStart(System.currentTimeMillis()) - dayStart(millis)) / 86_400_000L;
    if (days == 0) return tr("Today");
    if (days == 1) return tr("Yesterday");
    return DateFormat.getDateInstance(DateFormat.MEDIUM).format(new Date(millis));
  }

  private interface OnText {
    void changed(String s);
  }

  /**
   * Text settings save as you type; a "✓ Saved" line under the field (already added to its page)
   * says so after each change.
   */
  private void autosave(EditText field, OnText save) {
    LinearLayout parent = (LinearLayout) field.getParent();
    TextView saved = ui.text(tr("✓ Saved"), 13, Ui.TEAL);
    saved.setVisibility(View.INVISIBLE);
    LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-2, -2);
    l.topMargin = Math.round(4 * getResources().getDisplayMetrics().density);
    parent.addView(saved, parent.indexOfChild(field) + 1, l);
    field.addTextChangedListener(
        after(
            t -> {
              save.changed(t);
              saved.setVisibility(View.VISIBLE);
            }));
  }

  private static TextWatcher after(OnText listener) {
    return new TextWatcher() {
      public void beforeTextChanged(CharSequence s, int a, int b, int c) {}

      public void onTextChanged(CharSequence s, int a, int b, int c) {}

      public void afterTextChanged(Editable e) {
        listener.changed(e.toString());
      }
    };
  }

  private void appInfo() {
    startActivity(
        new Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:" + getPackageName())));
  }

  private void openBb(String link) {
    try {
      startActivity(BbBridge.view(link));
    } catch (Exception ex) {
      toast(tr("No browser available"));
    }
  }

  private void openUrl(String url) {
    try {
      startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
    } catch (Exception ex) {
      toast(tr("No browser available"));
    }
  }

  private void send(String action) {
    try {
      DictationService.send(this, action);
      main.postDelayed(this::renderStatus, 200);
    } catch (Exception ex) {
      toast(tr("Could not start. Check permissions and try again."));
    }
  }

  private void toast(String s) {
    Toast.makeText(this, s, Toast.LENGTH_LONG).show();
  }
}
