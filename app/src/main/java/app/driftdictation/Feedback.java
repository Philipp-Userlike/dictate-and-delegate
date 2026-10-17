package app.driftdictation;

import java.util.*;
import java.util.function.Predicate;
import org.json.JSONObject;

/** "Redo with a smarter model": picks the next engine to try from editorial tiers and prices. */
final class Feedback {
  static final String ACCURACY = "accuracy", SPEED = "speed", COST = "cost";
  static final String[] ISSUES = {ACCURACY, SPEED, COST};
  static final String[] LABELS = {"Wrong words", "Too slow", "Too expensive"};

  static String adjective(String issue) {
    return switch (issue) {
      case SPEED -> "faster";
      case COST -> "cheaper";
      default -> "more accurate";
    };
  }

  /** Returns null when no assessed engine improves on the complaint. */
  static JSONObject alternative(
      List<JSONObject> models,
      JSONObject current,
      String issue,
      Set<String> tried) {
    int accuracy = current == null ? 0 : ModelRanking.accuracy(current);
    int speed = current == null ? 0 : ModelRanking.speed(current);
    double price = current == null ? Double.NaN : ModelRanking.minuteCost(current);
    // Speed and cost fixes may not trade away much accuracy.
    int floor = Math.max(3, accuracy - 1);
    Predicate<JSONObject> improves =
        switch (issue) {
          case SPEED -> m -> ModelRanking.speed(m) > speed && ModelRanking.accuracy(m) >= floor;
          case COST ->
              m -> {
                double p = ModelRanking.minuteCost(m);
                return Double.isFinite(p)
                    && (!Double.isFinite(price) || p < price)
                    && ModelRanking.accuracy(m) >= floor;
              };
          default -> m -> ModelRanking.accuracy(m) > Math.max(0, accuracy);
        };
    String sort = issue.equals(ACCURACY) ? "accuracy" : issue;
    return models.stream()
        .filter(m -> ModelRanking.accuracy(m) > 0)
        .filter(m -> !tried.contains(m.optString("id")))
        .filter(improves)
        .min(ModelRanking.order(sort))
        .orElse(null);
  }
}
