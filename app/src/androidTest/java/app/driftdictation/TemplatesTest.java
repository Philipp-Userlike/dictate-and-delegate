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
  public void savedTemplatesAreEncryptedAndListedForTheTextModel() throws Exception {
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
    String stored =
        InstrumentationRegistry.getInstrumentation()
            .getTargetContext()
            .getSharedPreferences("vault", 0)
            .getString("templates", "");
    assertFalse(stored.contains("Dear landlord"));
    templates.save(id, "Apartment", "Changed");
    assertEquals("Changed", templates.get(id).text());
    templates.delete(id);
    assertNull(templates.get(id));
  }
}
