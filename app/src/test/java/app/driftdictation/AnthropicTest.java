package app.driftdictation;

import static org.junit.Assert.*;

import java.math.BigDecimal;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class AnthropicTest {
  @Test
  public void theModelIdComesFromTheOpenRouterOne() {
    assertEquals("claude-sonnet-5-5", Anthropic.model(TextModel.MODEL));
  }

  @Test
  public void theRequestHasThinkingOffAtLowEffort() throws Exception {
    JSONObject b = Anthropic.body("claude-sonnet-5-5", "Refine this.", "hello", 4096);
    assertEquals("claude-sonnet-5-5", b.getString("model"));
    assertEquals(4096, b.getInt("max_tokens"));
    assertEquals("Refine this.", b.getString("system"));
    // between_tools takes no other field.
    assertEquals(1, b.getJSONObject("thinking").length());
    assertEquals("between_tools", b.getJSONObject("thinking").getString("type"));
    assertEquals("low", b.getJSONObject("output_config").getString("effort"));
    JSONObject m = b.getJSONArray("messages").getJSONObject(0);
    assertEquals("user", m.getString("role"));
    assertEquals("hello", m.getString("content"));
  }

  @Test
  public void attachmentsBecomeAnthropicBlocksMediaFirst() throws Exception {
    JSONArray openRouter =
        new JSONArray()
            .put(new JSONObject().put("type", "text").put("text", "reply to this"))
            .put(
                new JSONObject()
                    .put("type", "image_url")
                    .put("image_url", new JSONObject().put("url", "data:image/png;base64,iVBOR")))
            .put(
                new JSONObject()
                    .put("type", "file")
                    .put(
                        "file",
                        new JSONObject().put("filename", "offer.pdf").put("file_data", "data:application/pdf;base64,JVBER")))
            .put(new JSONObject().put("type", "text").put("text", "<pasted>x</pasted>"));
    JSONArray blocks = Anthropic.parts(openRouter);
    assertEquals(4, blocks.length());
    JSONObject image = blocks.getJSONObject(0);
    assertEquals("image", image.getString("type"));
    assertEquals("base64", image.getJSONObject("source").getString("type"));
    assertEquals("image/png", image.getJSONObject("source").getString("media_type"));
    assertEquals("iVBOR", image.getJSONObject("source").getString("data"));
    JSONObject pdf = blocks.getJSONObject(1);
    assertEquals("document", pdf.getString("type"));
    assertEquals("application/pdf", pdf.getJSONObject("source").getString("media_type"));
    assertEquals("JVBER", pdf.getJSONObject("source").getString("data"));
    assertEquals("reply to this", blocks.getJSONObject(2).getString("text"));
    assertEquals("<pasted>x</pasted>", blocks.getJSONObject(3).getString("text"));
    // Through body(): the parts are the user's content.
    JSONObject b = Anthropic.body("m", "s", openRouter, 10);
    assertEquals(4, b.getJSONArray("messages").getJSONObject(0).getJSONArray("content").length());
    assertNull(Anthropic.dataUrl("https://example.com/x.png"));
  }

  @Test
  public void theAnswerIsItsTextBlocksAndCostsListPrice() throws Exception {
    JSONObject r =
        new JSONObject(
            "{\"content\": [{\"type\": \"thinking\", \"thinking\": \"\"}, {\"type\": \"text\", \"text\": \"Hi \"},"
                + " {\"type\": \"text\", \"text\": \"there.\"}], \"stop_reason\": \"end_turn\","
                + " \"usage\": {\"input_tokens\": 1000, \"output_tokens\": 100}}");
    OpenRouter.Result result = Anthropic.parse(r);
    assertEquals("Hi there.", result.text());
    // 1,000 × $2/M + 100 × $10/M = $0.003
    assertEquals(0, new BigDecimal("0.003").compareTo(result.cost()));
    assertNull(Anthropic.cost(new JSONObject()));
  }

  @Test
  public void aRefusalOrNothingFails() throws Exception {
    try {
      Anthropic.parse(new JSONObject("{\"content\": [], \"stop_reason\": \"refusal\"}"));
      fail();
    } catch (Anthropic.Failure f) {
      assertEquals(Anthropic.Problem.OTHER, f.problem);
    }
    try {
      Anthropic.parse(new JSONObject("{\"content\": [{\"type\": \"thinking\", \"thinking\": \"\"}]}"));
      fail();
    } catch (Anthropic.Failure f) {
      assertEquals(Anthropic.Problem.OTHER, f.problem);
    }
  }

  @Test
  public void errorsSayWhatWentWrong() {
    assertEquals(Anthropic.Problem.KEY, Anthropic.failure(401, "invalid x-api-key").problem);
    assertEquals(Anthropic.Problem.KEY, Anthropic.failure(403, "").problem);
    assertEquals(
        Anthropic.Problem.CREDITS,
        Anthropic.failure(400, "Your credit balance is too low to access the Anthropic API.").problem);
    assertEquals(Anthropic.Problem.BUSY, Anthropic.failure(429, "").problem);
    assertEquals(Anthropic.Problem.BUSY, Anthropic.failure(529, "Overloaded").problem);
    assertEquals(Anthropic.Problem.OTHER, Anthropic.failure(500, "").problem);
    Anthropic.Failure bad = Anthropic.failure(400, "thinking: unknown type");
    assertEquals(Anthropic.Problem.OTHER, bad.problem);
    assertTrue(bad.getMessage(), bad.getMessage().contains("thinking: unknown type"));
  }
}
