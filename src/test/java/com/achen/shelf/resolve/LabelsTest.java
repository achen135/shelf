package com.achen.shelf.resolve;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LabelsTest {

  @TempDir Path dir;

  private Path fixture(String name) throws IOException {
    Path target = dir.resolve(name);
    Files.copy(
        Path.of("src/test/resources/fixtures/labels").resolve(name),
        target,
        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    return target;
  }

  @Test
  void readsHeaderCommentsAndRows() throws IOException {
    List<Labels.Label> labels = Labels.read(fixture("good.tsv"));

    assertThat(labels).hasSize(7);
    Labels.Label first = labels.get(0);
    assertThat(first.offerUrl()).endsWith("?variant=42069797568601");
    assertThat(first.brand()).isEqualTo("Keychron");
    assertThat(first.model()).isEqualTo("Q6 HE");
    assertThat(first.match()).isTrue();
    assertThat(first.note()).isEqualTo("whole match");
    assertThat(labels.get(3).note()).isEmpty(); // a row with no note column
  }

  @Test
  void rejectsAMatchThatIsNotTrueOrFalse() throws IOException {
    Path file = fixture("bad-match.tsv");
    assertThatThrownBy(() -> Labels.read(file))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("bad-match.tsv:2")
        .hasMessageContaining("`match` must be true or false");
  }

  @Test
  void rejectsADuplicatePair() throws IOException {
    Path file = fixture("duplicate.tsv");
    assertThatThrownBy(() -> Labels.read(file))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("duplicate.tsv:3")
        .hasMessageContaining("duplicate label");
  }

  @Test
  void rejectsAShortRow() throws IOException {
    Path file = dir.resolve("short.tsv");
    Files.writeString(file, "http://x\tt\tKeychron\n", StandardCharsets.UTF_8);
    assertThatThrownBy(() -> Labels.read(file))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("expected at least 5");
  }

  @Test
  void theCommittedKeyboardLabelsParse() {
    // The real label set is data the eval depends on; a malformed line would be a broken claim.
    List<Labels.Label> labels = Labels.read(Path.of("data/labels/keyboards-resolution.tsv"));
    assertThat(labels).hasSizeGreaterThanOrEqualTo(200);
    assertThat(labels).allSatisfy(l -> assertThat(l.offerUrl()).startsWith("https://"));
    assertThat(labels.stream().filter(Labels.Label::match).count()).isGreaterThan(50);
  }
}
