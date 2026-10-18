package app.driftdictation;

import static org.junit.Assert.*;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class TemplatesTest {
  Templates templates;

  @Before
  public void setup() {
    templates = new Templates(InstrumentationRegistry.getInstrumentation().getTargetContext());
    for (Templates.Template t : templates.all()) {
      try {
        templates.delete(t.id());
      } catch (Exception ignored) {
        /* Next test fails loudly. */
      }
    }
  }

  @Test
  public void savedTemplatesAreKeptPlainAndListedForTheTextModel() throws Exception {
    assertEquals("", templates.prompt(""));
    String id = templates.save(null, "Apartment application", "Dear landlord, I'd like to rent …");
    templates.save(null, "Empty", "  ");
    assertEquals(2, templates.all().size());
    assertEquals("Apartment application", templates.get(id).name());
    assertEquals(java.util.List.of("Apartment application"), templates.names());
    String prompt = templates.prompt("apartment application");
    assertTrue(prompt.contains(Templates.INSTRUCTIONS));
    assertTrue(prompt.contains("<template name=\"Apartment application\">\nDear landlord"));
    assertEquals("", templates.prompt("Empty"));
    assertEquals("", templates.prompt("Something else"));
    var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    // Not a secret: plain in app-private storage, nothing left in the encrypted vault.
    assertTrue(context.getSharedPreferences("data", 0).getString("templates", "").contains("Dear landlord"));
    assertFalse(context.getSharedPreferences("vault", 0).contains("templates"));
    templates.save(id, "Apartment", "Changed");
    assertEquals("Changed", templates.get(id).text());
    templates.delete(id);
    assertNull(templates.get(id));
  }

  @Test
  public void templatesSealedByEarlierVersionsMovePlain() throws Exception {
    var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    String saved =
        new org.json.JSONArray()
            .put(new org.json.JSONObject().put("id", "old").put("name", "Old").put("text", "Kept"))
            .toString();
    context.getSharedPreferences("data", 0).edit().remove("templates").commit();
    context
        .getSharedPreferences("vault", 0)
        .edit()
        .putString("templates", new Vault(context).seal(saved))
        .commit();
    assertEquals("Kept", templates.get("old").text());
    assertEquals(saved, context.getSharedPreferences("data", 0).getString("templates", ""));
    assertFalse(context.getSharedPreferences("vault", 0).contains("templates"));
    templates.delete("old");
  }
}
