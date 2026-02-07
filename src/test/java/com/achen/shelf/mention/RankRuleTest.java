package com.achen.shelf.mention;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class RankRuleTest {

  @Test
  void listMarkersBeforeTheNameAreRanks() {
    String description =
        "My top 5 for 2026:\n1. Keychron Q1 Pro\n2) Glorious GMMK Pro\n#3 KBDfans Athena 75\nnumber 4: Wooting 60HE V2";
    assertThat(RankRule.rank(description, List.of("q1", "pro"))).contains(1);
    assertThat(RankRule.rank(description, List.of("gmmk", "pro"))).contains(2);
    assertThat(RankRule.rank(description, List.of("athena", "75"))).contains(3);
    assertThat(RankRule.rank(description, List.of("60he", "v2"))).contains(4);
  }

  @Test
  void aBareNumberIsAQuantityNotARank() {
    assertThat(
            RankRule.rank(
                "I bought 2 Epomaker P65 on Amazon for $45 apiece", List.of("epomaker", "p65")))
        .isEmpty();
    assertThat(RankRule.rank("5. Keychron K2 Ultra 4. Epomaker TH80", List.of("th80"))).isEmpty();
    assertThat(RankRule.rank("top 5 keyboards: the Q1 Pro", List.of("q1", "pro"))).isEmpty();
  }

  @Test
  void theNameMustBeWholeAfterTheMarker() {
    assertThat(RankRule.rank("#1 Q15 Max", List.of("q1"))).isEmpty();
  }
}
