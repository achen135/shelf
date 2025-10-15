package com.achen.shelf.crawl;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Prices become integer cents, or nothing at all. */
class MoneyTest {

  @ParameterizedTest
  @CsvSource({"'229.99', 22999", "'80.00', 8000", "'69.5', 6950", "'0.00', 0", "'1299', 129900"})
  void parsesDecimalAmounts(String raw, int cents) {
    assertThat(Money.toCents(raw)).contains(cents);
  }

  @Test
  void doesNotLoseACentToBinaryFloatingPoint() {
    // 129.10 as a double is 129.09999...; going through BigDecimal keeps it 12910.
    assertThat(Money.toCents("129.10")).contains(12910);
  }

  @ParameterizedTest
  @CsvSource({
    "'From $129.00', 12900",
    "'$1,299.00', 129900",
    "'  $69.50  ', 6950",
    "'Sale price $99', 9900"
  })
  void pullsAmountsOutOfCardText(String text, int cents) {
    assertThat(Money.fromText(text)).contains(cents);
  }

  @Test
  void rejectsWhatItCannotRead() {
    assertThat(Money.toCents(null)).isEmpty();
    assertThat(Money.toCents("")).isEmpty();
    assertThat(Money.toCents("free")).isEmpty();
    assertThat(Money.toCents("-5.00")).isEmpty();
    assertThat(Money.fromText("Sold out")).isEmpty();
  }
}
