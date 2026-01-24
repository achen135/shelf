package com.achen.shelf.crawl.parse;

import com.achen.shelf.crawl.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads monitor specs out of listing titles and tags — the second {@link SpecExtractor}, written
 * for M7 against the vocabulary six live storefronts actually use.
 *
 * <p>Same rule as the keyboard extractor: claim a value only on an unambiguous signal, stay silent
 * otherwise, and read the title and tags but never the description. The category's own ambiguities
 * shape the rules below — "WQHD" is 3440×1440 at LG and 2560×1440 at Samsung, so it is only read
 * with an ultrawide cue beside it; "Dual Mode" is a refresh feature while "Dual QHD" is a
 * resolution; INNOCN's tags are ranges ("144-240Hz") that would read as the top of the range; and
 * after normalization strips punctuation, {@code 27" 4K} is {@code 27 4k}, which is why the size is
 * read from the raw title with its inch marker still attached.
 *
 * <p>Everything returned is validated against {@code categories/monitors.yaml} by the caller.
 */
public final class MonitorSpecExtractor implements SpecExtractor {

  /** A size with its inch marker still attached: {@code 27"}, {@code 31.5-inch}, {@code 34-In.}. */
  private static final Pattern SIZE_RAW =
      Pattern.compile(
          "(?<![\\d.])(\\d{2}(?:\\.\\d)?)\\s*(?:[\"″”]|-?\\s?[Ii]nch(?:es)?\\b|-?\\s?[Ii]n\\b)");

  /** A size-only tag: Pixio's "27", KOORUI's "23.8 Inch", Focus Camera's "Screen Size: 27". */
  private static final Pattern SIZE_TAG =
      Pattern.compile("^(?:screen size:? )?(\\d{2}(?:\\.\\d)?)(?: ?(?:inch|in))?$");

  /** A refresh rate, on normalized text. */
  private static final Pattern REFRESH = Pattern.compile("(?<![\\d.])(\\d{2,3}) ?hz\\b");

  /**
   * A refresh-rate tag on its own ("180hz", "240hz refresh rate", "refresh rate 120hz"), on
   * normalized text; a range tag ("144 240hz") does not match.
   */
  private static final Pattern REFRESH_TAG =
      Pattern.compile("^(?:refresh rate )?(\\d{2,3}) ?hz(?: refresh(?: rate)?)?$");

  /** A curvature radius ("1500R", "800R"), which only a curved panel states. */
  private static final Pattern CURVATURE = Pattern.compile("\\b\\d{3,4}r\\b");

  private static final double MIN_SIZE = 10;
  private static final double MAX_SIZE = 100;

  /**
   * Panel vocabulary. The longest matching phrase wins ("qd oled" over "oled", "ips black" over
   * "ips"); between phrases of equal length the earlier entry wins, so the order below is a
   * precedence — the keyboard extractor's equal-length ties fall to map iteration order, which is
   * the known gap this list is written not to repeat.
   */
  private static final List<Map.Entry<String, String>> PANELS =
      List.of(
          Map.entry("qd oled", "qd-oled"),
          Map.entry("qdoled", "qd-oled"),
          Map.entry("oled", "oled"),
          Map.entry("mini led", "mini-led"),
          Map.entry("miniled", "mini-led"),
          Map.entry("ips black", "ips"),
          Map.entry("nano ips", "ips"),
          Map.entry("fast ips", "ips"),
          Map.entry("fast va", "va"),
          Map.entry("ips", "ips"),
          Map.entry("va", "va"),
          Map.entry("tn", "tn"));

  /** HDR tiers; the higher tier first among equal-length spellings. */
  private static final List<Map.Entry<String, String>> HDR =
      List.of(
          Map.entry("displayhdr 1000", "hdr1000"),
          Map.entry("displayhdr 600", "hdr600"),
          Map.entry("displayhdr 400", "hdr400"),
          Map.entry("true black 500", "true-black-500"),
          Map.entry("true black 400", "true-black-400"),
          Map.entry("hdr1400", "hdr1400"),
          Map.entry("hdr 1400", "hdr1400"),
          Map.entry("hdr1000", "hdr1000"),
          Map.entry("hdr 1000", "hdr1000"),
          Map.entry("hdr600", "hdr600"),
          Map.entry("hdr 600", "hdr600"),
          Map.entry("hdr500", "hdr500"),
          Map.entry("hdr 500", "hdr500"),
          Map.entry("hdr400", "hdr400"),
          Map.entry("hdr 400", "hdr400"),
          Map.entry("hdr10", "hdr10"),
          Map.entry("hdr 10", "hdr10"));

  /** Aspect ratios; the wider one first, so a "32:9 (2 × 16:9)" listing reads as 32:9. */
  private static final List<Map.Entry<String, String>> ASPECT =
      List.of(
          Map.entry("16 10", "16:10"),
          Map.entry("32 9", "32:9"),
          Map.entry("21 9", "21:9"),
          Map.entry("16 9", "16:9"));

  @Override
  public Map<String, Object> extract(String title, List<String> tags, String description) {
    String titleNorm = Normalizer.normalize(title);
    List<String> tagNorms = new ArrayList<>();
    StringBuilder text = new StringBuilder(titleNorm);
    for (String tag : tags) {
      String norm = Normalizer.normalize(tag);
      tagNorms.add(norm);
      text.append(' ').append(norm);
    }
    String haystack = text.toString();

    Map<String, Object> spec = new LinkedHashMap<>();
    longestMatch(haystack, PANELS).ifPresent(v -> spec.put("panel_type", v));
    resolution(haystack).ifPresent(v -> spec.put("resolution", v));
    aspectRatio(haystack, spec.get("resolution")).ifPresent(v -> spec.put("aspect_ratio", v));
    refresh(titleNorm, tagNorms).ifPresent(v -> spec.put("refresh_hz", v));
    size(title, tags).ifPresent(v -> spec.put("size_in", v));
    curved(haystack).ifPresent(v -> spec.put("curved", v));
    longestMatch(haystack, HDR).ifPresent(v -> spec.put("hdr", v));
    if (containsAny(haystack, "usb c", "type c", "thunderbolt")) {
      spec.put("usb_c", Boolean.TRUE);
    }
    if (containsAny(haystack, "speaker", "speakers", "built in speaker", "built in speakers")) {
      spec.put("speakers", Boolean.TRUE);
    }
    return spec;
  }

  /**
   * The native resolution, or nothing.
   *
   * <p>Pixel dimensions and the unambiguous marketing names are read in the order that keeps a
   * super-ultrawide from being mistaken for its half: "Dual QHD" before "QHD", "5K2K" before "5K",
   * "4K" only when no dual or ultrawide cue claims it. A bare "WQHD" is left alone (see the class
   * comment); a bare "5K" or "6K" with a 21:9 or ultrawide cue is the 21:9 variant.
   */
  private static Optional<String> resolution(String h) {
    boolean ultrawide = containsAny(h, "ultrawide", "ultra wide", "21 9", "uwqhd", "uw qhd");
    if (containsAny(h, "dual uhd", "dual 4k", "duhd", "7680 x 2160", "7680x2160")) {
      return Optional.of("dual-4k");
    }
    if (containsAny(
        h,
        "dual qhd",
        "dqhd",
        "dual 1440p",
        "5120 x 1440",
        "5120x1440",
        "dual wqhd",
        "dual quad hd")) {
      return Optional.of("dual-1440p");
    }
    if (containsAny(h, "5k2k", "5120 x 2160", "5120x2160", "wuhd")) {
      return Optional.of("ultrawide-5k2k");
    }
    if (containsAny(h, "6k")) {
      return Optional.of("6k");
    }
    if (containsAny(h, "5k", "5120 x 2880", "5120x2880")) {
      return Optional.of(ultrawide ? "ultrawide-5k2k" : "5k");
    }
    if (containsAny(
        h,
        "3440 x 1440",
        "3440x1440",
        "uwqhd",
        "uw qhd",
        "ultra wqhd",
        "ultrawide qhd",
        "ultrawide quad hd",
        "ultrawide wqhd",
        "ultra wide qhd",
        "uw5k")) {
      return Optional.of("ultrawide-1440p");
    }
    if (containsAny(
        h,
        "2560 x 1080",
        "2560x1080",
        "uwfhd",
        "uw fhd",
        "ultrawide fhd",
        "ultrawide full hd",
        "ultra wide fhd")) {
      return Optional.of("ultrawide-1080p");
    }
    boolean qhd = containsAny(h, "qhd", "1440p", "2560 x 1440", "2560x1440", "2k", "quad hd");
    boolean fhd = containsAny(h, "fhd", "1080p", "full hd", "1920 x 1080", "1920x1080");
    if (ultrawide) {
      // "34-Inch UltraWide FHD" / "Ultrawide 21:9 QHD 144Hz": the cue tells the width, and a
      // WQHD beside it is the 3440-wide one.
      if ((qhd || containsAny(h, "wqhd")) && !fhd) {
        return Optional.of("ultrawide-1440p");
      }
      if (fhd && !qhd) {
        return Optional.of("ultrawide-1080p");
      }
    }
    if (containsAny(h, "4k", "uhd", "2160p", "3840 x 2160", "3840x2160", "ultra hd")) {
      return Optional.of("4k");
    }
    if (qhd) {
      return Optional.of("1440p");
    }
    if (fhd) {
      return Optional.of("1080p");
    }
    return Optional.empty();
  }

  /** The aspect ratio as stated, else as the resolution implies, else nothing. */
  private static Optional<String> aspectRatio(String h, Object resolution) {
    Optional<String> stated = longestMatch(h, ASPECT);
    if (stated.isPresent()) {
      return stated;
    }
    if (resolution instanceof String r) {
      if (r.startsWith("ultrawide-")) {
        return Optional.of("21:9");
      }
      if (r.startsWith("dual-")) {
        return Optional.of("32:9");
      }
    }
    if (containsAny(h, "ultrawide", "ultra wide")) {
      return Optional.of("21:9");
    }
    return Optional.empty();
  }

  /**
   * The highest refresh rate the title states; failing that, the highest a standalone tag states.
   *
   * <p>The title first because tags can be ranges ("144-240Hz", INNOCN) and the tag pattern only
   * accepts a lone rate. The maximum because dual-mode panels are tagged with both rates and the
   * higher is the one the listing sells on.
   */
  private static Optional<Long> refresh(String titleNorm, List<String> tagNorms) {
    long best = 0;
    Matcher m = REFRESH.matcher(titleNorm);
    while (m.find()) {
      best = Math.max(best, Long.parseLong(m.group(1)));
    }
    if (best == 0) {
      for (String tag : tagNorms) {
        Matcher t = REFRESH_TAG.matcher(tag);
        if (t.matches()) {
          best = Math.max(best, Long.parseLong(t.group(1)));
        }
      }
    }
    return best >= 30 && best <= 1000 ? Optional.of(best) : Optional.empty();
  }

  /** The panel size in inches from the raw title's inch marker, else from a size-only tag. */
  private static Optional<Double> size(String title, List<String> tags) {
    Matcher m = SIZE_RAW.matcher(title == null ? "" : title);
    while (m.find()) {
      Optional<Double> v = plausibleSize(m.group(1));
      if (v.isPresent()) {
        return v;
      }
    }
    for (String tag : tags) {
      Matcher t = SIZE_TAG.matcher(tag.strip().toLowerCase(Locale.ROOT));
      if (t.matches()) {
        Optional<Double> v = plausibleSize(t.group(1));
        if (v.isPresent()) {
          return v;
        }
      }
    }
    return Optional.empty();
  }

  private static Optional<Double> plausibleSize(String digits) {
    double v = Double.parseDouble(digits);
    return v >= MIN_SIZE && v <= MAX_SIZE ? Optional.of(v) : Optional.empty();
  }

  /** Curved when the listing says so or states a curvature radius; flat when it says flat. */
  private static Optional<Boolean> curved(String h) {
    boolean curved = containsAny(h, "curved") || CURVATURE.matcher(h).find();
    boolean flat = containsAny(h, "flat");
    if (curved && !flat) {
      return Optional.of(Boolean.TRUE);
    }
    if (flat && !curved) {
      return Optional.of(Boolean.FALSE);
    }
    return Optional.empty();
  }

  /** The value of the longest matching phrase; among equal lengths, the earliest in the list. */
  private static Optional<String> longestMatch(
      String haystack, List<Map.Entry<String, String>> vocabulary) {
    String bestKey = null;
    String best = null;
    for (Map.Entry<String, String> entry : vocabulary) {
      if (Normalizer.containsModel(haystack, entry.getKey())
          && (bestKey == null || entry.getKey().length() > bestKey.length())) {
        bestKey = entry.getKey();
        best = entry.getValue();
      }
    }
    return Optional.ofNullable(best);
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
