package app.driftdictation;

/** Separate test APK: an ordinary destination text field, never packaged in the deliverable. */
public final class TestPadActivity extends android.app.Activity {
  @Override
  public void onCreate(android.os.Bundle state) {
    super.onCreate(state);
    android.widget.LinearLayout root = new android.widget.LinearLayout(this);
    root.setOrientation(1);
    root.setPadding(48, 100, 48, 50);
    android.widget.TextView title = new android.widget.TextView(this);
    title.setText("Notes test field");
    root.addView(title);
    android.widget.EditText input = new android.widget.EditText(this);
    input.setHint("Paste your transcription");
    input.setMinLines(4);
    root.addView(input);
    android.widget.Button paste = new android.widget.Button(this);
    paste.setText("Paste");
    paste.setAllCaps(false);
    paste.setOnClickListener(
        v -> {
          input.requestFocus();
          input.onTextContextMenuItem(android.R.id.paste);
        });
    root.addView(paste);
    setContentView(root);
  }
}
