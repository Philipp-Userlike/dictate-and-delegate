package app.driftdictation;

import static org.junit.Assert.*;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

public class BbOAuthTest {
  @Test
  public void challengeFollowsRfc7636() {
    // RFC 7636, appendix B.
    assertEquals(
        "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
        BbOAuth.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"));
  }

  @Test
  public void verifiersAreLongRandomAndUrlSafe() {
    String a = BbOAuth.verifier(), b = BbOAuth.verifier();
    assertEquals(43, a.length());
    assertTrue(a.matches("[A-Za-z0-9_-]+"));
    assertNotEquals(a, b);
  }

  @Test
  public void authorizeUrlCarriesEverythingBbAsksFor() throws Exception {
    String url =
        BbOAuth.authorizeUrl(
            "https://getbb.app/oauth/authorize", BbOAuth.resource("someone"), "CHALLENGE", "st/ate");
    URI uri = new URI(url);
    assertEquals("getbb.app", uri.getHost());
    assertEquals("/oauth/authorize", uri.getPath());
    Map<String, String> q = new HashMap<>();
    for (String pair : uri.getRawQuery().split("&")) {
      String[] kv = pair.split("=", 2);
      q.put(kv[0], URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
    }
    assertEquals("code", q.get("response_type"));
    assertEquals(BbOAuth.CLIENT_ID, q.get("client_id"));
    assertEquals("talkrouter://oauth/callback", q.get("redirect_uri"));
    assertEquals("bb", q.get("scope"));
    assertEquals("CHALLENGE", q.get("code_challenge"));
    assertEquals("S256", q.get("code_challenge_method"));
    assertEquals("st/ate", q.get("state"));
    assertEquals("https://someone.getbb.app/mcp", q.get("resource"));
    assertEquals(8, q.size());
    assertFalse(url.contains("+"));
  }

  @Test
  public void handlesAreReadFromWhateverThePersonPastes() {
    assertEquals("someone", BbOAuth.handle("someone"));
    assertEquals("someone", BbOAuth.handle(" Someone.getbb.app "));
    assertEquals("someone", BbOAuth.handle("https://someone.getbb.app/threads/thr_abc"));
    assertEquals("my-bb2", BbOAuth.handle("my-bb2"));
    assertEquals("", BbOAuth.handle(""));
    assertEquals("", BbOAuth.handle("not a handle"));
    assertEquals("", BbOAuth.handle("-dash"));
  }
}
