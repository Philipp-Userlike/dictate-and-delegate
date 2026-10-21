package app.driftdictation;

/**
 * Separate test APK: a web editor like the bb app's (a contenteditable textbox whose placeholder
 * is its aria-label and a CSS ::before), where Chrome reports the placeholder as the field's text.
 */
public final class WebPadActivity extends android.app.Activity {
  static final String PLACEHOLDER = "Ask for a follow-up. @ to mention files, folders, sections, or threads";

  @Override
  public void onCreate(android.os.Bundle state) {
    super.onCreate(state);
    android.webkit.WebView web = new android.webkit.WebView(this);
    web.getSettings().setJavaScriptEnabled(true);
    web.loadDataWithBaseURL(
        "https://pad.test/",
        "<html><head><meta name=viewport content='width=device-width'><style>"
            + "#e{min-height:120px;border:1px solid #888;padding:8px;font-size:18px}"
            + "#e p.empty::before{content:attr(data-placeholder);color:#999;pointer-events:none}"
            + "</style></head><body><div id=e contenteditable=true role=textbox aria-label='" + PLACEHOLDER + "' data-placeholder='" + PLACEHOLDER + "'>"
            + "<p class=empty data-placeholder='" + PLACEHOLDER + "'><br></p></div>"
            + "<script>var e=document.getElementById('e');e.addEventListener('input',function(){var p=e.querySelector('p');"
            + "if(p)p.className=e.innerText.trim()?'':'empty';});setTimeout(function(){e.focus();},300);</script>"
            + "</body></html>",
        "text/html",
        "utf-8",
        null);
    setContentView(web);
    web.requestFocus();
  }
}
