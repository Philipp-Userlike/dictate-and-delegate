package app.driftdictation;

import static app.driftdictation.I18n.tr;

import android.content.Context;
import java.io.InputStream;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/**
 * The speech models Dictate & Delegate offers, with prices and editorial notes, bundled with the app
 * (assets/models.json): only models whose word hints work (Profile.OFFERED). No network call.
 */
final class ModelCatalog {
  final List<JSONObject> models = new ArrayList<>();
  String priceDate;

  ModelCatalog(Context context) throws Exception {
    JSONObject root;
    try (InputStream in = context.getAssets().open("models.json")) {
      root = new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
    }
    priceDate = root.getString("verified");
    JSONArray data = root.getJSONArray("models");
    for (int i = 0; i < data.length(); i++) {
      JSONObject row = data.getJSONObject(i);
      if (Profile.OFFERED.contains(row.optString("id"))) models.add(row);
    }
    models.sort(Comparator.comparing(m -> m.optString("name")));
  }

  JSONObject find(String id) {
    return models.stream().filter(m -> m.optString("id").equals(id)).findFirst().orElse(null);
  }

  String name(String id) {
    JSONObject m = find(id);
    return m == null ? id : m.optString("name");
  }

  static String number(BigDecimal value) {
    return value.setScale(6, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
  }

  static String price(JSONObject model) {
    try {
      JSONObject p = model.getJSONObject("pricing");
      BigDecimal input = new BigDecimal(p.getString("prompt"));
      return switch (model.optString("unit")) {
        case "second" -> tr("From $%1$s / min", number(input.multiply(BigDecimal.valueOf(60))));
        case "hour" ->
            tr(
                "From $%1$s / min",
                number(input.divide(BigDecimal.valueOf(60), 9, RoundingMode.HALF_UP)));
        case "token" ->
            tr(
                "$%1$s in / $%2$s out per 1M tokens",
                number(input.multiply(BigDecimal.valueOf(1000000))),
                number(
                    new BigDecimal(p.getString("completion"))
                        .multiply(BigDecimal.valueOf(1000000))));
        default -> tr("Price unit unverified · see source");
      };
    } catch (Exception ex) {
      return tr("Price unavailable · see source");
    }
  }
}
