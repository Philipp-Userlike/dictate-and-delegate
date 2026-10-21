package app.driftdictation;

/**
 * Separate test APK: a web field with a placeholder, as web apps make them, where Chromium may
 * report the placeholder as the empty field's text. Extra "kind": textarea (placeholder
 * attribute), aria (contenteditable with aria-placeholder) or css (contenteditable whose
 * placeholder is a CSS ::before, like the bb app's editor); extra "text": what's in it already (spaces as _).
 */
public final class WebPadActivity extends android.app.Activity {
  static final String PLACEHOLDER = "Ask for a follow-up. @ to mention files, folders, sections, or threads";

  @Override
  public void onCreate(android.os.Bundle state) {
    super.onCreate(state);
    String kind = String.valueOf(getIntent().getStringExtra("kind"));
    String text = getIntent().getStringExtra("text");
    text = text == null ? "" : text.replace('_', ' ');
    String field =
        switch (kind) {
          case "textarea" -> "<textarea id=e placeholder='" + PLACEHOLDER + "'>" + text + "</textarea>";
          case "aria" -> "<div id=e contenteditable=true role=textbox aria-placeholder='" + PLACEHOLDER + "'>" + text + "</div>";
          default ->
              "<div id=e contenteditable=true role=textbox><p class='" + (text.isEmpty() ? "empty" : "") + "' data-placeholder='"
                  + PLACEHOLDER + "'>" + (text.isEmpty() ? "<br>" : text) + "</p></div>";
        };
    android.webkit.WebView web = new android.webkit.WebView(this);
    web.getSettings().setJavaScriptEnabled(true);
    web.loadDataWithBaseURL(
        "https://pad.test/",
        "<html><head><meta name=viewport content='width=device-width'><style>"
            + "#e{display:block;width:90%;min-height:120px;border:1px solid #888;padding:8px;font-size:18px}"
            + "#e p.empty::before{content:attr(data-placeholder);color:#999;pointer-events:none}"
            + "</style></head><body>" + field
            + "<script>var e=document.getElementById('e');e.addEventListener('input',function(){var p=e.querySelector('p');"
            + "if(p)p.className=e.innerText.trim()?'':'empty';});"
            + "setTimeout(function(){e.focus();var r=document.createRange();r.selectNodeContents(e);r.collapse(false);"
            + "if(e.setSelectionRange){e.setSelectionRange(e.value.length,e.value.length);}else{var s=getSelection();s.removeAllRanges();s.addRange(r);}},300);</script>"
            + "</body></html>",
        "text/html",
        "utf-8",
        null);
    setContentView(web);
    web.requestFocus();
  }
}
