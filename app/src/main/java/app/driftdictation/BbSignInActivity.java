package app.driftdictation;

import static app.driftdictation.I18n.tr;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.LinearLayout;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Where the browser comes back after signing in to bb Connect: talkrouter://oauth/callback. Swaps
 * the code for tokens (BbOAuth.complete), then returns to Settings → bb (or the setup page) with
 * the outcome.
 */
public final class BbSignInActivity extends Activity {
  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    I18n.init(this);
    Ui ui = new Ui(this);
    LinearLayout root = ui.vertical();
    root.setGravity(Gravity.CENTER);
    root.setPadding(ui.dp(28), ui.dp(28), ui.dp(28), ui.dp(28));
    root.setBackgroundColor(Ui.BG);
    root.addView(ui.title(tr("Signing in to bb…"), 24));
    setContentView(root);
    Uri data = getIntent() == null ? null : getIntent().getData();
    Map<String, String> query = new HashMap<>();
    if (data != null && data.isHierarchical())
      for (String name : data.getQueryParameterNames()) query.put(name, data.getQueryParameter(name));
    Handler main = new Handler(Looper.getMainLooper());
    Executors.newSingleThreadExecutor()
        .execute(
            () -> {
              String message;
              boolean ok = false;
              try {
                String handle = BbOAuth.complete(this, query);
                new BbBridge(this).useConnect(handle);
                message = tr("Signed in to %1$s.getbb.app", handle);
                ok = true;
              } catch (Exception ex) {
                message =
                    ex.getClass() == java.io.IOException.class && ex.getMessage() != null
                        ? ex.getMessage()
                        : tr("Sign-in failed: %1$s", String.valueOf(ex.getMessage()));
              }
              String result = message;
              boolean success = ok;
              main.post(
                  () -> {
                    startActivity(
                        new Intent(this, MainActivity.class)
                            .addFlags(
                                Intent.FLAG_ACTIVITY_NEW_TASK
                                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                                    | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            .putExtra("open", "bb")
                            .putExtra("bbResult", result)
                            .putExtra("bbOk", success));
                    finish();
                  });
            });
  }
}
