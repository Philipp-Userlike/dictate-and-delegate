package app.driftdictation;

import android.content.Context;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Texts the user keeps for reuse, like an apartment application. A dictation can ask for one and
 * say what to change ("use my apartment application for the flat on Hauptstraße, viewing on
 * Saturday"): the request check (Router) sees only the names, and only the template asked for
 * goes to the text model. Kept plain in app-private storage, like notes.
 */
final class Templates {
  record Template(String id, String name, String text) {}

  /** Each template's text, well inside the server's 64,000 characters with the prompt. */
  static final int MAX_TEXT = 8000;

  static final String INSTRUCTIONS =
      "The dictation asks for the user's template below. Reply with that template adapted as the"
          + " dictation says: keep its wording and structure, change what the dictation asks to"
          + " change or what must differ in this case (names, addresses, dates, amounts), and fill"
          + " in the details it gives. Change nothing else, and keep the template's language"
          + " unless the dictation asks otherwise.";

  private final Vault vault;

  Templates(Context context) {
    vault = new Vault(context);
  }

  List<Template> all() {
    List<Template> out = new ArrayList<>();
    try {
      String saved = vault.get("templates");
      JSONArray array = new JSONArray(saved.isEmpty() ? "[]" : saved);
      for (int i = 0; i < array.length(); i++) {
        JSONObject t = array.getJSONObject(i);
        out.add(new Template(t.getString("id"), t.optString("name"), t.optString("text")));
      }
    } catch (Exception ignored) {
      /* Unreadable: no templates rather than a broken note. */
    }
    return out;
  }

  Template get(String id) {
    for (Template t : all()) if (t.id().equals(id)) return t;
    return null;
  }

  /** Adds a template (id null) or replaces the one with this id; returns its id. */
  String save(String id, String name, String text) throws Exception {
    List<Template> list = all();
    String key = id == null ? UUID.randomUUID().toString() : id;
    Template t =
        new Template(
            key,
            name.trim(),
            text.trim().length() > MAX_TEXT ? text.trim().substring(0, MAX_TEXT) : text.trim());
    int i = indexOf(list, key);
    if (i >= 0) list.set(i, t);
    else list.add(t);
    write(list);
    return key;
  }

  void delete(String id) throws Exception {
    List<Template> list = all();
    int i = indexOf(list, id);
    if (i >= 0) list.remove(i);
    write(list);
  }

  /** Names of the templates with text, for the request check. */
  List<String> names() {
    List<String> out = new ArrayList<>();
    for (Template t : all()) if (!t.name().isBlank() && !t.text().isBlank()) out.add(t.name());
    return out;
  }

  /** The text model's instructions for the template asked for; empty when none was. */
  String prompt(String name) {
    if (name == null || name.isBlank()) return "";
    for (Template t : all())
      if (t.name().equalsIgnoreCase(name) && !t.text().isBlank())
        return "\n\n"
            + INSTRUCTIONS
            + "\n\n<template name=\""
            + t.name().replace("\"", "'")
            + "\">\n"
            + t.text()
            + "\n</template>";
    return "";
  }

  private static int indexOf(List<Template> list, String id) {
    for (int i = 0; i < list.size(); i++) if (list.get(i).id().equals(id)) return i;
    return -1;
  }

  private void write(List<Template> list) throws Exception {
    JSONArray array = new JSONArray();
    for (Template t : list)
      array.put(new JSONObject().put("id", t.id()).put("name", t.name()).put("text", t.text()));
    vault.put("templates", array.toString());
  }
}
