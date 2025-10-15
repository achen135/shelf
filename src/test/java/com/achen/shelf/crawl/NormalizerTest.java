package com.achen.shelf.crawl;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The normalization rules the catalog's identity depends on. */
class NormalizerTest {

  @ParameterizedTest
  @CsvSource({
    "'Keychron Q6 HE', 'keychron q6 he'",
    "'KEYCHRON  Q6-HE', 'keychron q6 he'",
    "'Keychron Q6/HE', 'keychron q6 he'",
    "'  Keychron   Q6   HE  ', 'keychron q6 he'",
    "'GMMK 3 HE', 'gmmk 3 he'",
    "'60%', '60'",
    "'+84 Classic TKL', '84 classic tkl'",
    "'Ducky One 2 Mini Pro', 'ducky one 2 mini pro'",
  })
  void normalizesToACanonicalForm(String raw, String expected) {
    assertThat(Normalizer.normalize(raw)).isEqualTo(expected);
  }

  @Test
  void stripsDiacritics() {
    assertThat(Normalizer.normalize("Vörtex Pöker")).isEqualTo("vortex poker");
  }

  @Test
  void treatsNullAndBlankAsEmpty() {
    assertThat(Normalizer.normalize(null)).isEmpty();
    assertThat(Normalizer.normalize("   ")).isEmpty();
  }

  @Test
  void doesNotStemOrDropWords() {
    // "pro" and "professional" are different products; collapsing them would merge two SKUs.
    assertThat(Normalizer.normalize("HHKB Professional"))
        .isNotEqualTo(Normalizer.normalize("HHKB Pro"));
  }

  @Test
  void matchesModelsOnWholeTokens() {
    String title = Normalizer.normalize("Keychron Q6 HE QMK Wireless Custom Keyboard");

    assertThat(Normalizer.containsModel(title, Normalizer.normalize("Q6 HE"))).isTrue();
    assertThat(Normalizer.containsModel(title, Normalizer.normalize("Q6"))).isTrue();
    assertThat(Normalizer.containsModel(title, Normalizer.normalize("Keychron Q6 HE"))).isTrue();
  }

  @Test
  void doesNotMatchAModelInsideALongerToken() {
    // The bug this rule exists to prevent: a Q65 listing is not a Q6.
    String title = Normalizer.normalize("Keychron Q65 Max Keyboard");

    assertThat(Normalizer.containsModel(title, Normalizer.normalize("Q6"))).isFalse();
    assertThat(Normalizer.containsModel(title, Normalizer.normalize("Q65"))).isTrue();
  }

  @Test
  void emptyInputsNeverMatch() {
    assertThat(Normalizer.containsModel("", "q6")).isFalse();
    assertThat(Normalizer.containsModel("keychron q6", "")).isFalse();
  }
}
