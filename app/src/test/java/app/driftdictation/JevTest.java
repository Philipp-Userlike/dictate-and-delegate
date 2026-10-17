package app.driftdictation;

import static org.junit.Assert.*;

import java.math.BigDecimal;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;

public class JevTest {
  final List<String> templates = List.of("Apartment application", "Weekly update");

  @Test
  public void theRequestAsksOnlyWhatMatters() throws Exception {
    JSONObject all = Jev.body("chief of staff, book a table", templates, true, true);
    assertEquals(Jev.MODEL, all.getString("model"));
    assertEquals("chief of staff, book a table", all.getJSONObject("state").getString("dictation"));
    JSONObject q = all.getJSONObject("questions");
    assertEquals("noul", q.getJSONObject("refine").getString("type"));
    assertEquals(Jev.REFINE, q.getJSONObject("refine").getString("instructions"));
    JSONObject yesNo = q.getJSONObject("chief").getJSONObject("criteria");
    assertEquals(Jev.CHIEF_YES, yesNo.getString("true"));
    assertEquals(Jev.CHIEF_NO, yesNo.getString("false"));
    // The template is a choice over "none" and the user's template names.
    JSONObject options = q.getJSONObject("template").getJSONObject("criteria");
    assertEquals("choice", q.getJSONObject("template").getString("type"));
    assertEquals(3, options.length());
    assertEquals(Jev.TEMPLATE_NONE, options.getString("none"));
    assertTrue(options.getString("Weekly update").contains("\"Weekly update\""));

    // Auto-refine off, no chief: only the template; no templates either: nothing.
    JSONObject some = Jev.body("x", templates, false, false).getJSONObject("questions");
    assertEquals(1, some.length());
    assertTrue(some.has("template"));
    assertEquals(0, Jev.body("x", List.of(), false, false).getJSONObject("questions").length());
    assertFalse(Jev.body("x", List.of(), true, false).getJSONObject("questions").has("template"));
  }

  @Test
  public void answersAreReadAndUnknownsIgnored() throws Exception {
    JSONObject r =
        new JSONObject(
            "{\"answers\": {\"refine\": {\"type\": \"noul\", \"noul\": 0.82},"
                + " \"template\": {\"type\": \"choice\", \"choice\": \"Weekly update\", \"confidence\": 0.9},"
                + " \"chief\": {\"type\": \"noul\", \"noul\": 0.03}}, \"usage\": {\"cost\": 1.9404e-05}}");
    Jev.Answers a = Jev.parse(r, templates);
    assertEquals(0.82, a.refine(), 1e-9);
    assertEquals(0.03, a.chief(), 1e-9);
    assertEquals("Weekly update", a.template());
    assertEquals(0, new BigDecimal("0.000019404").compareTo(a.cost()));
    assertEquals("refine 0.82 · chief 0.03 · Weekly update", Jev.detail(a));

    // "none", a name the user doesn't have, a missing answer, nonsense: nothing.
    Jev.Answers b =
        Jev.parse(
            new JSONObject(
                "{\"answers\": {\"template\": {\"choice\": \"Invoice\"}, \"refine\": {\"noul\": \"high\"}}}"),
            templates);
    assertNull(b.refine());
    assertNull(b.chief());
    assertEquals("", b.template());
    assertNull(b.cost());
    assertEquals("", Jev.detail(b));
    assertEquals(1.0, Jev.parse(new JSONObject("{\"answers\": {\"chief\": {\"noul\": 1.7}}}"), templates).chief(), 0);
  }

  Jev.Answers a(Double refine, String template, Double chief) {
    return new Jev.Answers(refine, template, chief, null);
  }

  @Test
  public void refineStartsByItselfAboveItsThresholdOrForATemplate() {
    assertTrue(Jev.decide(a(Jev.REFINE_AT, "", 0.0), true, true, true).refine());
    assertFalse(Jev.decide(a(Jev.REFINE_AT - 0.01, "", 0.0), true, true, true).refine());
    Jev.Decision t = Jev.decide(a(0.1, "Weekly update", 0.0), true, true, true);
    assertTrue(t.refine());
    assertEquals("Weekly update", t.template());
    // Switched off: nothing starts, but a tapped Refine still uses the template.
    Jev.Decision off = Jev.decide(a(0.99, "Weekly update", 0.0), false, true, true);
    assertFalse(off.refine());
    assertEquals("Weekly update", off.template());
  }

  @Test
  public void theChiefGetsItOnlyWhenClearlyAddressed() {
    assertTrue(Jev.decide(a(0.0, "", 0.9), true, true, true).chief());
    assertTrue(Jev.decide(a(0.0, "", 0.97), true, true, true).chief());
    // Below the threshold nothing is sent; between 0.5 and 0.9 🧑‍💼 is highlighted.
    Jev.Decision unsure = Jev.decide(a(0.0, "", 0.89), true, true, true);
    assertFalse(unsure.chief());
    assertTrue(unsure.hint());
    Jev.Decision no = Jev.decide(a(0.0, "", 0.49), true, true, true);
    assertFalse(no.chief());
    assertFalse(no.hint());
    // Switched off, or no chief set up: never sent; the hint only when there is a chief.
    Jev.Decision off = Jev.decide(a(0.0, "", 0.99), true, false, true);
    assertFalse(off.chief());
    assertTrue(off.hint());
    Jev.Decision none = Jev.decide(a(0.0, "", 0.99), true, true, false);
    assertFalse(none.chief());
    assertFalse(none.hint());
  }

  @Test
  public void withoutAnAnswerNothingHappensByItself() {
    Jev.Decision d = Jev.decide(null, true, true, true);
    assertFalse(d.refine());
    assertFalse(d.chief());
    assertFalse(d.hint());
    assertEquals("", d.template());
  }
}
