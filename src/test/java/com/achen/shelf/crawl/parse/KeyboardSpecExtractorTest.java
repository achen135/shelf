package com.achen.shelf.crawl.parse;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Spec extraction, including the cases where the right answer is to say nothing.
 *
 * <p>A missing spec costs a filter match later; a wrong one corrupts the catalog and every deal
 * signal computed from it. These tests pin that asymmetry down.
 */
class KeyboardSpecExtractorTest {

  private final SpecExtractor extractor = new KeyboardSpecExtractor();

  @Test
  void readsKeychronsStructuredTags() {
    Map<String, Object> spec =
        extractor.extract(
            "Keychron Q6 HE QMK Wireless Custom Keyboard",
            List.of(
                "100% Layout",
                "CaseMaterial:All-metal",
                "MountStyle:Gasket Mount",
                "KeycapsType:Double-shot PBT",
                "SwitchMount:Hot-swappable"),
            null);

    assertThat(spec)
        .containsEntry("layout_size", "full")
        .containsEntry("case_material", "aluminum")
        .containsEntry("mount_type", "gasket")
        .containsEntry("keycap_material", "pbt")
        .containsEntry("hot_swap", Boolean.TRUE);
  }

  @Test
  void readsAMultiBrandRetailersLowercaseTags() {
    Map<String, Object> spec =
        extractor.extract(
            "HHKB Hybrid Type-S Topre Silent 45g Keyboard",
            List.of("topre", "plate mount", "plastic frame", "compact", "60"),
            null);

    assertThat(spec)
        .containsEntry("switch_type", "topre")
        .containsEntry("mount_type", "plate")
        .containsEntry("case_material", "plastic")
        .containsEntry("layout_size", "60");
  }

  @Test
  void prefersTheMoreSpecificLayout() {
    // "96" and "100" both appear in the vocabulary; a 96% board is an 1800-compact, not full size.
    assertThat(extractor.extract("Keychron Q5 96% Keyboard", List.of(), null))
        .containsEntry("layout_size", "1800");
  }

  @Test
  void readsKeyCounts() {
    assertThat(extractor.extract("KBDfans Kit — 61 key layout", List.of(), null))
        .containsEntry("key_count", 61L);
  }

  @Test
  void staysSilentOnMultiModeConnectivity() {
    // Real Keychron tags: the board is wireless AND carries a "Wired Keyboard" tag. Recording
    // it as wired would be worse than recording nothing.
    Map<String, Object> spec =
        extractor.extract(
            "Keychron Q6 HE QMK Wireless Custom Keyboard",
            List.of("Connectivity:wireless", "Wired Keyboard"),
            null);

    assertThat(spec).doesNotContainKey("connectivity");
  }

  @Test
  void claimsConnectivityWhenTheRadioIsNamed() {
    assertThat(extractor.extract("Some Board Bluetooth Keyboard", List.of(), null))
        .containsEntry("connectivity", "bluetooth");
    assertThat(extractor.extract("Some Board Tri-Mode Keyboard", List.of(), null))
        .containsEntry("connectivity", "tri-mode");
  }

  @Test
  void ignoresTheMarketingDescription() {
    // A description saying "unlike clicky switches, ours are smooth" must not set switch_type.
    Map<String, Object> spec =
        extractor.extract(
            "Generic Board", List.of(), "Unlike clicky switches, these are gasket mounted bliss.");

    assertThat(spec).isEmpty();
  }

  @Test
  void claimsNothingWhenAListingSaysNothing() {
    assertThat(extractor.extract("Mystery Keyboard", List.of(), null)).isEmpty();
  }
}
