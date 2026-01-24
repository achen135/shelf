package com.achen.shelf.crawl.parse;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Monitor spec extraction, on titles and tags copied from the live storefronts on 2026-09-16.
 *
 * <p>Same asymmetry as the keyboard tests: a missing value costs a filter match, a wrong one
 * corrupts the catalog, so the cases where the right answer is silence are pinned as carefully as
 * the ones where a value is read.
 */
class MonitorSpecExtractorTest {

  private final SpecExtractor extractor = new MonitorSpecExtractor();

  @Test
  void readsADellTitleAndFocusCamerasStructuredTags() {
    Map<String, Object> spec =
        extractor.extract(
            "Dell UltraSharp U2725QE 27 Inch 4K UHD IPS Monitor with 120Hz and Thunderbolt 4",
            List.of(
                "Aspect Ratio: 16:9",
                "Connectivity: Thunderbolt 4",
                "Panel Type: IPS",
                "Refresh Rate: 120Hz",
                "Resolution: 4K UHD (3840×2160)",
                "Screen Shape: Flat",
                "Screen Size: 27"),
            null);

    assertThat(spec)
        .containsEntry("panel_type", "ips")
        .containsEntry("resolution", "4k")
        .containsEntry("aspect_ratio", "16:9")
        .containsEntry("refresh_hz", 120L)
        .containsEntry("size_in", 27.0)
        .containsEntry("curved", Boolean.FALSE)
        .containsEntry("usb_c", Boolean.TRUE)
        .doesNotContainKeys("hdr", "speakers");
  }

  @Test
  void readsPixiosTagsWhenTheTitleSaysLittle() {
    // Pixio titles are model names; every spec is a tag, including the bare-number size.
    Map<String, Object> spec =
        extractor.extract(
            "PXC278 Wave 27\" Curved Gaming Monitor — White",
            List.of("1440p", "180Hz", "1ms (GTG)", "27", "Curved", "Fast VA", "Wave Series"),
            null);

    assertThat(spec)
        .containsEntry("panel_type", "va")
        .containsEntry("resolution", "1440p")
        .containsEntry("refresh_hz", 180L)
        .containsEntry("size_in", 27.0)
        .containsEntry("curved", Boolean.TRUE);
  }

  @Test
  void readsTheSizeFromTheRawTitleNotTheNormalizedOne() {
    // Normalization turns 27" 4K into "27 4k"; the inch marker has to be read before it goes.
    assertThat(
            extractor.extract(
                "INNOCN | 27\" 4K 120Hz IPS HDR500 Monitor | CB27U1", List.of(), null))
        .containsEntry("size_in", 27.0)
        .containsEntry("resolution", "4k")
        .containsEntry("hdr", "hdr500")
        .containsEntry("refresh_hz", 120L);
    assertThat(extractor.extract("BenQ GW3290QT 31.5 Inch 2K QHD USB-C Monitor", List.of(), null))
        .containsEntry("size_in", 31.5)
        .containsEntry("resolution", "1440p")
        .containsEntry("usb_c", Boolean.TRUE);
    assertThat(
            extractor.extract(
                "Dell UltraSharp U3425WE 34-In. UW-QHD 21:9 Curved LED Monitor 120Hz",
                List.of(),
                null))
        .containsEntry("size_in", 34.0)
        .containsEntry("resolution", "ultrawide-1440p")
        .containsEntry("aspect_ratio", "21:9")
        .containsEntry("curved", Boolean.TRUE);
  }

  @Test
  void doesNotReadAModelNumberOrAnInzoneAsASize() {
    // "Sony 27 INZONE": "IN" is the start of a word, not an inch marker; "Dell UltraSharp 32 4K":
    // no marker at all, so no size rather than a guess.
    assertThat(extractor.extract("Sony 27 INZONE M10S OLED QHD Gaming Monitor", List.of(), null))
        .doesNotContainKey("size_in")
        .containsEntry("panel_type", "oled");
    assertThat(
            extractor.extract(
                "Dell UltraSharp 32 4K Thunderbolt Hub Monitor - U3225QE", List.of(), null))
        .doesNotContainKey("size_in");
  }

  @Test
  void tellsASuperUltrawideFromItsHalf() {
    assertThat(
            extractor.extract(
                "INNOCN | 49\" Dual QHD 240Hz Ultrawide Curved Gaming Monitor | 49C1S",
                List.of(),
                null))
        .containsEntry("resolution", "dual-1440p")
        .containsEntry("aspect_ratio", "32:9");
    assertThat(
            extractor.extract(
                "Samsung 57-Inch Odyssey Neo G9 G95NC Dual UHD Quantum Matrix Gaming Monitor",
                List.of(),
                null))
        .containsEntry("resolution", "dual-4k");
    assertThat(
            extractor.extract(
                "LG 52G930B-B 52-inch Ultragear evo G9 5K2K (5120x2160) Curved Gaming Monitor",
                List.of(),
                null))
        .containsEntry("resolution", "ultrawide-5k2k")
        .containsEntry("aspect_ratio", "21:9");
    assertThat(
            extractor.extract(
                "Dell UltraSharp U4025QW 40-Inch Curved Thunderbolt Hub Monitor with 5K Display",
                List.of("Aspect Ratio: 21:9"),
                null))
        .containsEntry("resolution", "ultrawide-5k2k");
  }

  @Test
  void staysSilentOnABareWqhd() {
    // LG's WQHD is 3440×1440, Samsung's is 2560×1440; with no ultrawide cue the word says nothing.
    assertThat(
            extractor.extract(
                "LG 45GS95QE Ultragear OLED Curved Gaming Monitor 45-Inch WQHD 800R 240Hz",
                List.of(),
                null))
        .doesNotContainKey("resolution")
        .containsEntry("curved", Boolean.TRUE)
        .containsEntry("refresh_hz", 240L);
    assertThat(extractor.extract("LG 34-Inch UltraWide WQHD 160Hz Gaming Monitor", List.of(), null))
        .containsEntry("resolution", "ultrawide-1440p");
  }

  @Test
  void prefersTheTitlesRefreshRateAndIgnoresRangeTags() {
    // INNOCN tags are ranges ("144-240Hz"); read literally they would report the top of the range.
    assertThat(
            extractor.extract(
                "INNOCN | 27\" 320Hz QHD Mini-LED Gaming Monitor | GA27T1M",
                List.of("280-500Hz", "23\"-27\""),
                null))
        .containsEntry("refresh_hz", 320L)
        .containsEntry("panel_type", "mini-led");
    assertThat(
            extractor.extract(
                "INNOCN | 27\" 4K IPS USB-C HDR400 Professional Monitor | 27C1U-D",
                List.of("60-120Hz"),
                null))
        .doesNotContainKey("refresh_hz");
  }

  @Test
  void takesTheHigherRateOfADualModePanel() {
    Map<String, Object> spec =
        extractor.extract(
            "Pixio PX27U Prime Neo 27\" Dual Mode Gaming Monitor",
            List.of(
                "1080p", "160Hz", "2160p", "27", "320Hz", "4K", "Dual Mode", "FAST IPS", "Flat"),
            null);

    assertThat(spec)
        .containsEntry("refresh_hz", 320L)
        .containsEntry("resolution", "4k")
        .containsEntry("panel_type", "ips")
        .containsEntry("curved", Boolean.FALSE);
  }

  @Test
  void readsHdrTiersButNotABareHdr() {
    assertThat(
            extractor.extract(
                "LG 34-Inch UltraWide FHD VESA Display HDR 400 AMD FreeSync IPS Monitor with USB Type-C",
                List.of(),
                null))
        .containsEntry("hdr", "hdr400")
        .containsEntry("resolution", "ultrawide-1080p")
        .containsEntry("usb_c", Boolean.TRUE);
    assertThat(
            extractor.extract(
                "ASUS ProArt PA32UCDM 4K-HDR QD-OLED 31.5-inch Professional Monitor",
                List.of(),
                null))
        .doesNotContainKey("hdr")
        .containsEntry("panel_type", "qd-oled")
        .containsEntry("size_in", 31.5);
  }

  @Test
  void breaksEqualLengthVocabularyTiesByPrecedenceNotMapOrder() {
    // The keyboard extractor's known gap: "alice" vs "split" fall to map iteration order. Here
    // equal-length phrases are ordered, so a super-ultrawide described as "32:9 (2 x 16:9)"
    // is 32:9 and a listing naming both a VA and a TN spelling is whichever comes first.
    assertThat(
            extractor.extract(
                "Samsung 49\" 32:9 (2 x 16:9) Curved Gaming Monitor", List.of(), null))
        .containsEntry("aspect_ratio", "32:9");
    assertThat(extractor.extract("Sceptre 24\" 16:9 Monitor", List.of("Aspect Ratio: 16:9"), null))
        .containsEntry("aspect_ratio", "16:9");
    assertThat(extractor.extract("Monitor with HDR 400 and HDR 600 modes", List.of(), null))
        .containsEntry("hdr", "hdr600");
  }

  @Test
  void staysSilentWhenCurvedAndFlatBothAppear() {
    assertThat(extractor.extract("Pixio 27\" Gaming Monitor", List.of("Curved", "Flat"), null))
        .doesNotContainKey("curved");
  }

  @Test
  void readsSpeakersAndUsbCOnlyWhenStated() {
    assertThat(
            extractor.extract(
                "Samsung 34 inch ViewFinity S65TC Curved Ultra-WQHD Monitor with Built-in Speaker",
                List.of(),
                null))
        .containsEntry("speakers", Boolean.TRUE)
        .containsEntry("resolution", "ultrawide-1440p")
        .doesNotContainKey("usb_c");
  }

  @Test
  void claimsNothingFromATitleWithoutSpecs() {
    assertThat(extractor.extract("Tempest GP27U", List.of(), null)).isEmpty();
    assertThat(extractor.extract("Spectrum Black 32 — Matte without Hub", List.of(), null))
        .isEmpty();
  }

  @Test
  void ignoresTheDescription() {
    // A description that argues against OLED must not set the panel to OLED.
    assertThat(
            extractor.extract(
                "KOORUI G2711P 27-inch IPS FHD 200Hz Gaming Monitor",
                List.of(),
                "Unlike OLED panels, this VA-free IPS panel…"))
        .containsEntry("panel_type", "ips");
  }
}
