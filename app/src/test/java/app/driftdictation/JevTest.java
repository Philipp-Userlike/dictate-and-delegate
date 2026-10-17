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
    JSONObject all = Jev.body("Henry, book a table", templates, true, "Henry");
    assertEquals(Jev.MODEL, all.getString("model"));
    assertEquals("Henry, book a table", all.getJSONObject("state").getString("dictation"));
    JSONObject q = all.getJSONObject("questions");
    assertEquals("noul", q.getJSONObject("refine").getString("type"));
    assertEquals(Jev.REFINE, q.getJSONObject("refine").getString("instructions"));
    // The chief question carries the chief's name.
    assertTrue(q.getJSONObject("chief").getString("instructions").contains("is called Henry."));
    assertFalse(q.getJSONObject("chief").getString("instructions").contains("%1$s"));
    JSONObject yesNo = q.getJSONObject("chief").getJSONObject("criteria");
    assertTrue(yesNo.getString("true").startsWith("The speaker addresses Henry directly"));
    assertTrue(yesNo.getString("false").startsWith("Henry only comes up"));
    // The template is a choice over "none" and the user's template names.
    JSONObject options = q.getJSONObject("template").getJSONObject("criteria");
    assertEquals("choice", q.getJSONObject("template").getString("type"));
    assertEquals(3, options.length());
    assertEquals(Jev.TEMPLATE_NONE, options.getString("none"));
    assertTrue(options.getString("Weekly update").contains("\"Weekly update\""));

    // Auto-refine off, no name: only the template; no templates either: nothing.
    JSONObject some = Jev.body("x", templates, false, "").getJSONObject("questions");
    assertEquals(1, some.length());
    assertTrue(some.has("template"));
    assertEquals(0, Jev.body("x", List.of(), false, "").getJSONObject("questions").length());
    assertFalse(Jev.body("x", List.of(), true, "").getJSONObject("questions").has("template"));
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
    assertTrue(Jev.decide(a(Jev.REFINE_AT, "", 0.0), true, true, true, false).refine());
    assertFalse(Jev.decide(a(Jev.REFINE_AT - 0.01, "", 0.0), true, true, true, false).refine());
    Jev.Decision t = Jev.decide(a(0.1, "Weekly update", 0.0), true, true, true, false);
    assertTrue(t.refine());
    assertEquals("Weekly update", t.template());
    // Switched off: nothing starts, but a tapped Refine still uses the template.
    Jev.Decision off = Jev.decide(a(0.99, "Weekly update", 0.0), false, true, true, false);
    assertFalse(off.refine());
    assertEquals("Weekly update", off.template());
  }

  @Test
  public void theChiefGetsItOnlyWhenNamedAndJevIsSure() {
    double sure = Jev.CHIEF_AT, unsureAt = (Jev.HINT_AT + Jev.CHIEF_AT) / 2;
    Jev.Decision d = Jev.decide(a(0.95, "", sure), true, true, true, true);
    assertTrue(d.chief());
    // What goes to the chief isn't refined by itself.
    assertFalse(d.refine());
    assertFalse(d.hint());
    // Unsure: nothing sent, 🧑‍💼 highlighted, Refine as Jev says.
    Jev.Decision unsure = Jev.decide(a(0.95, "", unsureAt), true, true, true, true);
    assertFalse(unsure.chief());
    assertTrue(unsure.hint());
    assertTrue(unsure.refine());
    Jev.Decision no = Jev.decide(a(0.0, "", Jev.HINT_AT - 0.01), true, true, true, true);
    assertFalse(no.chief());
    assertFalse(no.hint());
    // The name didn't come up: never sent, no hint, whatever the answer.
    Jev.Decision unnamed = Jev.decide(a(0.0, "", 0.99), true, true, true, false);
    assertFalse(unnamed.chief());
    assertFalse(unnamed.hint());
    // Switched off: never sent, but highlighted; no chief set up: neither.
    Jev.Decision off = Jev.decide(a(0.0, "", 0.99), true, false, true, true);
    assertFalse(off.chief());
    assertTrue(off.hint());
    Jev.Decision none = Jev.decide(a(0.0, "", 0.99), true, true, false, true);
    assertFalse(none.chief());
    assertFalse(none.hint());
  }

  @Test
  public void withoutAnAnswerNothingHappensByItself() {
    Jev.Decision d = Jev.decide(null, true, true, true, false);
    assertFalse(d.refine());
    assertFalse(d.chief());
    assertFalse(d.hint());
    assertEquals("", d.template());
    // The name came up but Jev failed: not sent, only highlighted.
    Jev.Decision named = Jev.decide(null, true, true, true, true);
    assertFalse(named.chief());
    assertTrue(named.hint());
    assertFalse(Jev.decide(a(0.9, "", null), true, true, true, true).chief());
  }

  @Test
  public void theNameIsFoundAsAWholeWordWithItsMishearings() {
    String[] yes = {
      "Okay, so I want to talk to Henry about the invoices",
      "this time it should go to Henry as well",
      "Henry, book the train",
      "hey henry find the cheapest flight",
      "this is for Henri",
      "Henrie should look at it",
      "that's Henry's job",
      "Das soll an Henrys Liste",
      "HENRY, remind me tomorrow",
    };
    for (String said : yes) assertTrue(said, Jev.mentions(said, "Henry"));
    String[] no = {"Henryk called", "tidy this up into an email for Jana", "the chief of staff", ""};
    for (String said : no) assertFalse(said, Jev.mentions(said, "Henry"));
    // No name, no chief.
    assertFalse(Jev.mentions("Henry, book the train", ""));
    assertFalse(Jev.mentions("Henry, book the train", "  "));
    // Names with spaces, and names that end in -i or -ie.
    assertTrue(Jev.mentions("send it to the chief-of-staff", "chief of staff"));
    assertTrue(Jev.mentions("Jenny, look", "Jenni"));
    assertTrue(Jev.mentions("Josy, look", "Josie"));
    assertTrue(Jev.mentions("Zoë, schau mal", "zoë"));
    assertFalse(Jev.mentions("Zoëlle", "Zoë"));
  }
}
