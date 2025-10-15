package com.achen.shelf.crawl;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parsing of prices into integer minor units. */
public final class Money {

  // Matches the first money-looking number in a string: "From $1,299.00" -> 1,299.00
  private static final Pattern AMOUNT =
      Pattern.compile("(\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.(\\d{1,2}))?");

  private Money() {}

  /**
   * Parses a decimal amount string ("129.00") into cents.
   *
   * <p>Returns empty rather than throwing: a retailer publishing a price we cannot read is a parse
   * miss to be logged and skipped, not a reason to abandon the run. Values are read through
   * BigDecimal so that 129.10 is 12910 cents and not 12909.
   */
  public static Optional<Integer> toCents(String amount) {
    if (amount == null || amount.isBlank()) {
      return Optional.empty();
    }
    try {
      BigDecimal value = new BigDecimal(amount.strip());
      if (value.signum() < 0) {
        return Optional.empty();
      }
      return Optional.of(
          value.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).intValueExact());
    } catch (NumberFormatException | ArithmeticException e) {
      return Optional.empty();
    }
  }

  /** Pulls the first money amount out of free text such as {@code "From $129.00"}. */
  public static Optional<Integer> fromText(String text) {
    if (text == null) {
      return Optional.empty();
    }
    Matcher m = AMOUNT.matcher(text);
    if (!m.find()) {
      return Optional.empty();
    }
    String whole = m.group(1).replace(",", "");
    String fraction = m.group(2);
    return toCents(fraction == null ? whole : whole + "." + fraction);
  }
}
