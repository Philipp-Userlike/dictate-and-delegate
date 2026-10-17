package app.driftdictation;

import static org.junit.Assert.*;

import java.util.*;
import org.json.*;
import org.junit.Test;

public class ModelRankingTest {
  JSONObject model(String id, int accuracy, int speed, String unit, String price) throws Exception {
    return new JSONObject()
        .put("id", id)
        .put("name", id)
        .put("accuracyTier", accuracy)
        .put("speedTier", speed)
        .put("supportsDe", true)
        .put("supportsEn", true)
        .put("unit", unit)
        .put("pricing", new JSONObject().put("prompt", price));
  }

  @Test
  public void costsUseComparableUnitsAndDoNotGuessTokenCosts() throws Exception {
    JSONObject second = model("seconds", 4, 4, "second", ".0001");
    JSONObject hour = model("hours", 4, 4, "hour", ".36");
    assertEquals(ModelRanking.minuteCost(second), ModelRanking.minuteCost(hour), .0000001);
    assertEquals(4, ModelRanking.cost(second));
    JSONObject tokens = model("tokens", 5, 0, "token", ".000001");
    assertTrue(Double.isNaN(ModelRanking.minuteCost(tokens)));
    assertEquals(0, ModelRanking.cost(tokens));
    assertEquals(0, ModelRanking.cost(model("bad", 3, 3, "second", "-1")));
  }

  @Test
  public void listsEveryModelWhateverItsLanguagesAndSearchesByName() throws Exception {
    JSONObject both = model("both", 4, 4, "hour", ".1");
    JSONObject unknown =
        model("unknown", 4, 4, "hour", ".1").put("supportsDe", false).put("supportsEn", false);
    assertEquals(2, ModelRanking.filter(List.of(both, unknown), "", "name").size());
    assertEquals(1, ModelRanking.filter(List.of(both, unknown), "unk", "name").size());
    assertEquals("—", ModelRanking.languages(unknown));
    assertEquals("🌐 60 languages", ModelRanking.languages(both.put("languages", "🌐 60 languages")));
  }

  @Test
  public void sortsUnverifiedCostsAndSpeedLast() throws Exception {
    var low = model("low", 3, 5, "second", ".00001");
    var high = model("high", 5, 2, "second", ".0001");
    var unknown = model("unknown", 0, 0, "token", "0");
    var values = List.of(unknown, high, low);
    assertEquals("low", ModelRanking.filter(values, "", "cost").get(0).getString("id"));
    assertEquals("unknown", ModelRanking.filter(values, "", "cost").get(2).getString("id"));
    assertEquals(
        "high", ModelRanking.filter(values, "", "accuracy").get(0).getString("id"));
    assertEquals("low", ModelRanking.filter(values, "", "speed").get(0).getString("id"));
  }

  @Test
  public void unratedModelsShowNoDots() {
    assertEquals("—", ModelRanking.dots(0));
  }
}
