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
    assertEquals("https://philipp258.github.io/dictate-and-delegate/oauth/callback.html", q.get("redirect_uri"));
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

  @Test
  public void tokensAreRefreshedWithTheClientIdTheyWereIssuedTo() throws Exception {
    // Saved before 3.1.0, when the app was Talk Router: no clientId field, so the old client ID.
    org.json.JSONObject legacy =
        new org.json.JSONObject().put("refresh", "r1").put("resource", "https://someone.getbb.app/mcp");
    assertEquals("https://philipp258.github.io/talk-router/oauth/client.json", BbOAuth.clientIdOf(legacy));
    java.util.Map<String, String> body = BbOAuth.refreshBody(legacy);
    assertEquals("refresh_token", body.get("grant_type"));
    assertEquals("r1", body.get("refresh_token"));
    assertEquals(BbOAuth.LEGACY_CLIENT_ID, body.get("client_id"));
    assertEquals("https://someone.getbb.app/mcp", body.get("resource"));
    // Saved by a new sign-in: the new client ID.
    org.json.JSONObject current = new org.json.JSONObject(legacy.toString()).put("clientId", BbOAuth.CLIENT_ID);
    assertEquals("https://philipp258.github.io/dictate-and-delegate/oauth/client.json", BbOAuth.refreshBody(current).get("client_id"));
    // The redirect sits next to the new client document.
    assertEquals("https://philipp258.github.io/dictate-and-delegate/oauth/callback.html", BbOAuth.REDIRECT);
  }
}
