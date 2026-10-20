package dev.horizon.ganzir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.horizon.pyrite.common.InvalidArgumentException;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MetadataTest {

  @Test
  void validMetadataIsKept() {
    Metadata metadata = new Metadata("Finance", Map.of("team", "billing", "cost-center", ""), Map.of("note", "x"));

    assertThat(metadata.labels()).containsEntry("team", "billing").containsEntry("cost-center", "");
  }

  @Test
  void aLabelKeyMustStartWithALowercaseLetter() {
    assertRefused(Map.of("Team", "billing"));
    assertRefused(Map.of("1team", "billing"));
    assertRefused(Map.of("", "billing"));
  }

  @Test
  void aLabelValueHasOnlyLowercaseLettersDigitsUnderscoresAndHyphens() {
    assertRefused(Map.of("team", "Billing"));
    assertRefused(Map.of("team", "a".repeat(64)));
  }

  @Test
  void aNodeHasAtMost64Labels() {
    Map<String, String> labels = new HashMap<>();
    for (int index = 0; index < 65; index++) {
      labels.put("label-" + index, "");
    }

    assertRefused(labels);
  }

  @Test
  void aDisplayNameHasAtMost255Characters() {
    assertThatThrownBy(() -> new Metadata("x".repeat(256), Map.of(), Map.of()))
        .isInstanceOf(InvalidArgumentException.class);
  }

  @Test
  void annotationsHaveKeysAndAtMost256KibibytesTogether() {
    assertThatThrownBy(() -> new Metadata("", Map.of(), Map.of("", "x"))).isInstanceOf(InvalidArgumentException.class);
    assertThatThrownBy(() -> new Metadata("", Map.of(), Map.of("note", "x".repeat(256 * 1024))))
        .isInstanceOf(InvalidArgumentException.class);
  }

  private void assertRefused(Map<String, String> labels) {
    assertThatThrownBy(() -> new Metadata("", labels, Map.of())).isInstanceOf(InvalidArgumentException.class);
  }
}
