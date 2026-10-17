package app.driftdictation;

import static org.junit.Assert.*;

import java.util.List;
import org.junit.Test;

public class RouterTest {
  final List<String> names = List.of("Apartment application");

  @Test
  public void readsTheAnswerAndFallsBackToTheTranscript() {
    assertEquals("text", Router.parse("{\"route\": \"text\", \"template\": \"\"}", names, null).kind());
    assertEquals(
        "text",
        Router.parse("```json\n{\"route\":\"text\",\"template\":\"\"}\n```", names, null).kind());
    assertEquals("transcript", Router.parse("I think it's text", names, null).kind());
    assertEquals("transcript", Router.parse("{\"route\": \"boss\"}", names, null).kind());
    assertEquals("transcript", Router.parse(null, names, null).kind());
  }

  @Test
  public void theRoutesOfEarlierVersionsMeanText() {
    assertEquals("text", Router.parse("{\"route\": \"simple\"}", names, null).kind());
    assertEquals("text", Router.parse("{\"route\": \"smart\"}", names, null).kind());
  }

  @Test
  public void aTemplateIsOnlyOneTheUserHasAndMeansText() {
    Router.Route r =
        Router.parse("{\"route\": \"transcript\", \"template\": \"apartment application\"}", names, null);
    assertEquals("text", r.kind());
    assertEquals("Apartment application", r.template());
    assertEquals("", Router.parse("{\"route\": \"text\", \"template\": \"Invoice\"}", names, null).template());
  }
}
