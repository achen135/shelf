package com.achen.shelf.mention;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The two hand-labeled files of M9, as committed under {@code data/labels/}.
 *
 * <p>{@code <category>-mention-resolution.tsv}: {@code source source_id excerpt brand model match
 * note} — a raw mention keyed by the platform's own id (stable across databases) and a product by
 * the seed's brand and model, like {@code resolve/Labels}. {@code <category>-mention-
 * sentiment.tsv}: {@code source source_id phrase sentiment note} — keyed by the phrase as written
 * rather than a product, so the sample is not limited to what the catalog carries.
 */
public final class MentionLabels {

  /** One labeled (raw mention, product) pair. */
  public record MatchLabel(
      String source,
      String sourceId,
      String excerpt,
      String brand,
      String model,
      boolean match,
      String note) {}

  /** One labeled (raw mention, phrase) sentiment. */
  public record SentimentLabel(
      String source,
      String sourceId,
      String phrase,
      SentimentRule.Sentiment sentiment,
      String note) {}

  private MentionLabels() {}

  public static List<MatchLabel> readMatches(Path file) {
    List<MatchLabel> out = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    int n = 0;
    for (String[] cols : rows(file, "source", 6)) {
      n++;
      boolean match =
          switch (cols[5].strip()) {
            case "true" -> true;
            case "false" -> false;
            default ->
                throw new IllegalArgumentException(
                    file + ": `match` must be true or false, got '" + cols[5] + "'");
          };
      String key = cols[0].strip() + " " + cols[1].strip() + " " + cols[3].strip() + " " + cols[4];
      if (!seen.add(key.strip())) {
        throw new IllegalArgumentException(file + ": duplicate label for " + key);
      }
      out.add(
          new MatchLabel(
              cols[0].strip(),
              cols[1].strip(),
              cols[2].strip(),
              cols[3].strip(),
              cols[4].strip(),
              match,
              cols.length > 6 ? cols[6].strip() : ""));
    }
    if (n == 0) {
      throw new IllegalArgumentException(file + ": no labels");
    }
    return out;
  }

  public static List<SentimentLabel> readSentiments(Path file) {
    List<SentimentLabel> out = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (String[] cols : rows(file, "source", 4)) {
      SentimentRule.Sentiment s;
      try {
        s = SentimentRule.Sentiment.valueOf(cols[3].strip().toUpperCase(java.util.Locale.ROOT));
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException(
            file + ": `sentiment` must be positive, negative or neutral, got '" + cols[3] + "'");
      }
      String key = cols[0].strip() + " " + cols[1].strip() + " " + cols[2].strip();
      if (!seen.add(key)) {
        throw new IllegalArgumentException(file + ": duplicate label for " + key);
      }
      out.add(
          new SentimentLabel(
              cols[0].strip(),
              cols[1].strip(),
              cols[2].strip(),
              s,
              cols.length > 4 ? cols[4].strip() : ""));
    }
    return out;
  }

  private static List<String[]> rows(Path file, String headerFirstColumn, int minColumns) {
    List<String> lines;
    try {
      lines = Files.readAllLines(file, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("could not read labels " + file, e);
    }
    List<String[]> out = new ArrayList<>();
    for (int i = 0; i < lines.size(); i++) {
      String line = lines.get(i);
      if (line.isBlank() || line.startsWith("#")) {
        continue;
      }
      String[] cols = line.split("\t", -1);
      if (cols[0].equals(headerFirstColumn)) {
        continue;
      }
      if (cols.length < minColumns) {
        throw new IllegalArgumentException(
            file
                + ":"
                + (i + 1)
                + ": expected at least "
                + minColumns
                + " columns, got "
                + cols.length);
      }
      for (int c = 0; c < minColumns; c++) {
        if (c != 2 && cols[c].isBlank() && minColumns == 6 && c < 5) {
          throw new IllegalArgumentException(
              file + ":" + (i + 1) + ": column " + (c + 1) + " is blank");
        }
      }
      out.add(cols);
    }
    return out;
  }
}
