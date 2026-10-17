package app.driftdictation;

import java.util.*;
import org.json.JSONObject;

/** Explicit editorial tiers + price bands; never represents a measured accuracy percentage. */
final class ModelRanking {
  static int accuracy(JSONObject m) {
    return tier(m, "accuracyTier");
  }

  static int speed(JSONObject m) {
    return tier(m, "speedTier");
  }

  private static int tier(JSONObject m, String key) {
    return Math.max(0, Math.min(5, m.optInt(key)));
  }

  static double minuteCost(JSONObject m) {
    try {
      double input = Double.parseDouble(m.getJSONObject("pricing").getString("prompt"));
      if (!Double.isFinite(input) || input < 0) return Double.NaN;
      return switch (m.optString("unit")) {
        case "second" -> input * 60;
        case "hour" -> input / 60;
        default ->
            Double.NaN; // Tokens cannot be honestly converted to minutes without workload data.
      };
    } catch (Exception e) {
      return Double.NaN;
    }
  }

  static int cost(JSONObject m) {
    double price = minuteCost(m);
    if (!Double.isFinite(price)) return 0;
    return price <= .0005 ? 1 : price <= .002 ? 2 : price <= .004 ? 3 : price <= .008 ? 4 : 5;
  }

  /** Language coverage as the catalog states it, e.g. "🌐 60 languages"; Dictate & Delegate never filters by it. */
  static String languages(JSONObject m) {
    String value = m.optString("languages").trim();
    return value.isEmpty() ? "—" : value;
  }

  static String dots(int value) {
    return value == 0 ? "—" : "●".repeat(value) + "○".repeat(5 - value);
  }

  static String name(JSONObject m) {
    String n = m.optString("name", m.optString("id"));
    int colon = n.indexOf(": ");
    return colon < 0 ? n : n.substring(colon + 2);
  }

  static double score(JSONObject m) {
    // Missing components earn no points. New/unassessed models do not outrank assessed models.
    return accuracy(m) * .65 + (cost(m) == 0 ? 0 : 6 - cost(m)) * .20 + speed(m) * .15;
  }

  static Comparator<JSONObject> order(String sort) {
    Comparator<JSONObject> tie =
        Comparator.comparing(ModelRanking::name).thenComparing(m -> m.optString("id"));
    Comparator<JSONObject> primary =
        switch (sort) {
          case "accuracy" ->
              Comparator.comparingInt(ModelRanking::accuracy)
                  .reversed()
                  .thenComparingDouble(
                      m ->
                          Double.isFinite(minuteCost(m))
                              ? minuteCost(m)
                              : Double.POSITIVE_INFINITY);
          case "cost" ->
              Comparator.comparingDouble(
                  m -> Double.isFinite(minuteCost(m)) ? minuteCost(m) : Double.POSITIVE_INFINITY);
          case "speed" ->
              Comparator.comparingInt(ModelRanking::speed)
                  .reversed()
                  .thenComparing(Comparator.comparingInt(ModelRanking::accuracy).reversed());
          case "name" -> tie;
          default -> Comparator.comparingDouble(ModelRanking::score).reversed();
        };
    return primary.thenComparing(tie);
  }

  static List<JSONObject> filter(List<JSONObject> models, String query, String sort) {
    String q = query.toLowerCase(Locale.ROOT).trim();
    List<JSONObject> result = new ArrayList<>();
    for (JSONObject m : models)
      if ((m.optString("name") + " " + m.optString("id")).toLowerCase(Locale.ROOT).contains(q))
        result.add(m);
    result.sort(order(sort));
    return result;
  }

  private static void pick(
      List<JSONObject> models,
      Map<String, String> picks,
      String role,
      Comparator<JSONObject> order,
      java.util.function.Predicate<JSONObject> eligible) {
    models.stream()
        .filter(eligible)
        .filter(m -> !picks.containsKey(m.optString("id")))
        .min(order)
        .ifPresent(m -> picks.put(m.optString("id"), role));
  }
}
