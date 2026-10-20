package dev.horizon.ganzir.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.horizon.ganzir.Children;
import dev.horizon.ganzir.Depth;
import dev.horizon.ganzir.Model;
import dev.horizon.ganzir.NodeType;
import dev.horizon.ganzir.Parents;
import dev.horizon.ganzir.Root;
import dev.horizon.ganzir.Scope;
import dev.horizon.pyrite.common.FailedPreconditionException;
import dev.horizon.pyrite.common.InvalidArgumentException;
import java.time.Duration;
import java.util.List;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

class SchemaTest {

  private static final Duration RETENTION = Duration.ofDays(30);

  private final Schema google = new Schema(new Model(List.of(
      new NodeType("organization", Scope.GLOBAL, RETENTION, List.of(new Root(), new Children("folder", "project"))),
      new NodeType("folder", Scope.PARENT, RETENTION, List.of(new Parents("organization", "folder"), new Depth(3))),
      new NodeType("project", Scope.GLOBAL, Duration.ofDays(7), List.of(new Parents("organization", "folder"))))));

  @Test
  void aTypeWithoutRootOrParentsIsRefused() {
    assertThatThrownBy(() -> new Schema(new Model(List.of(
        new NodeType("folder", Scope.PARENT, RETENTION, List.of())))))
        .isInstanceOf(InvalidArgumentException.class);
  }

  @Test
  void aRuleThatNamesAnUndeclaredTypeIsRefused() {
    assertThatThrownBy(() -> new Schema(new Model(List.of(
        new NodeType("folder", Scope.PARENT, RETENTION, List.of(new Parents("organization")))))))
        .isInstanceOf(InvalidArgumentException.class);
  }

  @Test
  void aTypeDeclaredTwiceOrARuleGivenTwiceIsRefused() {
    NodeType organization = new NodeType("organization", Scope.GLOBAL, RETENTION, List.of(new Root()));

    assertThatThrownBy(() -> new Schema(new Model(List.of(organization, organization))))
        .isInstanceOf(InvalidArgumentException.class);
    assertThatThrownBy(() -> new Schema(new Model(List.of(
        new NodeType("organization", Scope.GLOBAL, RETENTION, List.of(new Root(), new Root()))))))
        .isInstanceOf(InvalidArgumentException.class);
  }

  @Test
  void aNegativeRetentionOrADepthBeyondTheLimitIsRefused() {
    assertThatThrownBy(() -> new Schema(new Model(List.of(
        new NodeType("organization", Scope.GLOBAL, Duration.ofDays(-1), List.of(new Root()))))))
        .isInstanceOf(InvalidArgumentException.class);
    assertThatThrownBy(() -> new Schema(new Model(List.of(
        new NodeType("organization", Scope.GLOBAL, RETENTION, List.of(new Root(), new Depth(25)))))))
        .isInstanceOf(InvalidArgumentException.class);
  }

  @Test
  void onlyARootTypeIsARoot() {
    assertThat(google.root("organization", "example.com").name()).isEqualTo("organization");
    assertRefused(() -> google.root("folder", "finance"));
  }

  @Test
  void aChildMustBeAllowedByItsParentsAndByItsParent() {
    assertThat(google.child("folder", "finance", "organization", 1).name()).isEqualTo("folder");
    assertRefused(() -> google.child("folder", "finance", "project", 2));
    assertRefused(() -> google.child("organization", "example.com", "folder", 2));

    Schema strict = new Schema(new Model(List.of(
        new NodeType("organization", Scope.GLOBAL, RETENTION, List.of(new Root(), new Children("folder"))),
        new NodeType("folder", Scope.PARENT, RETENTION, List.of(new Parents("organization"))),
        new NodeType("project", Scope.GLOBAL, RETENTION, List.of(new Parents("organization", "folder"))))));
    assertRefused(() -> strict.child("project", "ledger-prod", "organization", 1));
    assertThat(strict.child("project", "ledger-prod", "folder", 2).name()).isEqualTo("project");
  }

  @Test
  void aNodeCannotBeDeeperThanItsTypeAllows() {
    assertThat(google.child("folder", "level-3", "folder", 3).name()).isEqualTo("folder");
    assertRefused(() -> google.child("folder", "level-4", "folder", 4));
    assertRefused(() -> google.checkDepth("project", Schema.MAX_DEPTH + 1));
  }

  @Test
  void aNameHasOneTo255Characters() {
    assertThatThrownBy(() -> google.root("organization", "")).isInstanceOf(InvalidArgumentException.class);
    assertThatThrownBy(() -> google.root("organization", "x".repeat(256))).isInstanceOf(
        InvalidArgumentException.class);
  }

  @Test
  void anUndeclaredTypeIsRefusedAndHasNoRetention() {
    assertThatThrownBy(() -> google.root("bucket", "logs")).isInstanceOf(InvalidArgumentException.class);
    assertThat(google.retention("bucket")).isZero();
    assertThat(google.retention("project")).isEqualTo(Duration.ofDays(7));
  }

  @Test
  void onlyAGlobalTypeIsFoundByName() {
    assertThat(google.global("project").name()).isEqualTo("project");
    assertThatThrownBy(() -> google.global("folder")).isInstanceOf(InvalidArgumentException.class);
  }

  private void assertRefused(ThrowingCallable call) {
    assertThatThrownBy(call).isInstanceOfSatisfying(FailedPreconditionException.class,
        refused -> assertThat(refused.reason()).isEqualTo("RULE"));
  }
}
