package com.achen.shelf.crawl.parse;

import com.achen.shelf.crawl.Normalizer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads keyboard specs out of listing titles and tags.
 *
 * <p>The rule throughout is to claim a value only on an unambiguous signal and stay silent
 * otherwise. A missing spec costs a filter match in M6; a wrong one silently corrupts the catalog
 * and any deal signal computed from it, so every lookup below is a whole-token match against
 * vocabulary these retailers actually use (collected during the M0 survey), never a substring
 * guess.
 *
 * <p>Everything it returns is still validated against the category's spec_schema by the caller —
 * this class decides what a listing says, the schema decides what is allowed.
 */
public final class KeyboardSpecExtractor implements SpecExtractor {

  private static final Pattern KEY_COUNT =
      Pattern.compile("\\b(\\d{2,3})\\s*(?:key|keys|key layout)\\b");

  /** Layout vocabulary, checked longest-first so "1800" wins over "100". */
  private static final Map<String, String> LAYOUTS =
      Map.ofEntries(
          Map.entry("full size", "full"),
          Map.entry("fullsize", "full"),
          Map.entry("100", "full"),
          Map.entry("1800", "1800"),
          Map.entry("96", "1800"),
          Map.entry("tkl", "tkl"),
          Map.entry("tenkeyless", "tkl"),
          Map.entry("80", "tkl"),
          Map.entry("87", "tkl"),
          Map.entry("75", "75"),
          Map.entry("65", "65"),
          Map.entry("60", "60"),
          Map.entry("40", "40"),
          Map.entry("alice", "alice"),
          Map.entry("split", "split"));

  private static final Map<String, String> SWITCHES =
      Map.ofEntries(
          Map.entry("silent linear", "silent-linear"),
          Map.entry("silent tactile", "silent-tactile"),
          Map.entry("linear", "linear"),
          Map.entry("tactile", "tactile"),
          Map.entry("clicky", "clicky"),
          Map.entry("magnetic", "magnetic"),
          Map.entry("hall effect", "magnetic"),
          Map.entry("he", "magnetic"),
          Map.entry("tmr", "magnetic"),
          Map.entry("topre", "topre"),
          Map.entry("optical", "optical"));

  private static final Map<String, String> CASES =
      Map.ofEntries(
          Map.entry("aluminum", "aluminum"),
          Map.entry("aluminium", "aluminum"),
          Map.entry("all metal", "aluminum"),
          Map.entry("polycarbonate", "polycarbonate"),
          Map.entry("wood", "wood"),
          Map.entry("plastic", "plastic"));

  private static final Map<String, String> MOUNTS =
      Map.ofEntries(
          Map.entry("gasket", "gasket"),
          Map.entry("tray mount", "tray"),
          Map.entry("top mount", "top"),
          Map.entry("plate mount", "plate"));

  @Override
  public Map<String, Object> extract(String title, List<String> tags, String description) {
    // Tags and title only. The description is excluded on purpose: a marketing paragraph
    // mentioning "unlike clicky switches" would otherwise set switch_type=clicky.
    StringBuilder text = new StringBuilder(Normalizer.normalize(title));
    for (String tag : tags) {
      text.append(' ').append(Normalizer.normalize(tag));
    }
    String haystack = text.toString();

    Map<String, Object> spec = new LinkedHashMap<>();
    putIfFound(spec, "layout_size", haystack, LAYOUTS);
    putIfFound(spec, "switch_type", haystack, SWITCHES);
    putIfFound(spec, "case_material", haystack, CASES);
    putIfFound(spec, "mount_type", haystack, MOUNTS);

    if (containsAny(haystack, "hotswap", "hot swap", "hot swappable")) {
      spec.put("hot_swap", Boolean.TRUE);
    }
    connectivity(haystack).ifPresent(v -> spec.put("connectivity", v));
    keycapMaterial(haystack).ifPresent(v -> spec.put("keycap_material", v));

    Matcher keys = KEY_COUNT.matcher(haystack);
    if (keys.find()) {
      spec.put("key_count", Long.valueOf(keys.group(1)));
    }
    return spec;
  }

  /**
   * Connectivity, or nothing.
   *
   * <p>Multi-mode boards are tagged with every mode they support — a Keychron Q6 HE carries both
   * "Wired Keyboard" and "Connectivity:wireless" — so a bare "wired" hit next to a "wireless" one
   * says nothing about how the board is used. Unless the tags name a specific radio, or say
   * tri-mode outright, this claims nothing rather than recording a board as wired because that word
   * happened to appear.
   */
  private static java.util.Optional<String> connectivity(String haystack) {
    boolean bluetooth = containsAny(haystack, "bluetooth");
    boolean wireless24 = containsAny(haystack, "2 4ghz", "2 4 ghz", "24ghz");
    boolean wirelessMentioned = containsAny(haystack, "wireless");
    boolean wired = containsAny(haystack, "wired");

    if (containsAny(haystack, "tri mode", "trimode", "3 mode")
        || (bluetooth && wireless24 && wired)) {
      return java.util.Optional.of("tri-mode");
    }
    if (wireless24) {
      return java.util.Optional.of("2.4ghz");
    }
    if (bluetooth) {
      return java.util.Optional.of("bluetooth");
    }
    if (wired && !wirelessMentioned) {
      return java.util.Optional.of("wired");
    }
    return java.util.Optional.empty();
  }

  private static java.util.Optional<String> keycapMaterial(String haystack) {
    if (containsAny(haystack, "pbt")) {
      return java.util.Optional.of("pbt");
    }
    if (containsAny(haystack, "abs")) {
      return java.util.Optional.of("abs");
    }
    return java.util.Optional.empty();
  }

  private static void putIfFound(
      Map<String, Object> spec, String field, String haystack, Map<String, String> vocabulary) {
    String best = null;
    String bestKey = null;
    for (Map.Entry<String, String> entry : vocabulary.entrySet()) {
      if (Normalizer.containsModel(haystack, entry.getKey())
          && (bestKey == null || entry.getKey().length() > bestKey.length())) {
        bestKey = entry.getKey();
        best = entry.getValue();
      }
    }
    if (best != null) {
      spec.put(field, best);
    }
  }

  private static boolean containsAny(String haystack, String... needles) {
    for (String needle : needles) {
      if (Normalizer.containsModel(haystack, needle)) {
        return true;
      }
    }
    return false;
  }
}
