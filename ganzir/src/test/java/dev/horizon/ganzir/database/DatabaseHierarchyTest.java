package dev.horizon.ganzir.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.horizon.aquery.aip158.Page;
import dev.horizon.aquery.aip158.PageRequest;
import dev.horizon.cinder.Database;
import dev.horizon.cinder.protobuf.ProtobufExtension;
import dev.horizon.cinder.sqlite.SqliteDriver;
import dev.horizon.ganzir.Children;
import dev.horizon.ganzir.Depth;
import dev.horizon.ganzir.Hierarchy;
import dev.horizon.ganzir.Hold;
import dev.horizon.ganzir.Metadata;
import dev.horizon.ganzir.Model;
import dev.horizon.ganzir.Node;
import dev.horizon.ganzir.NodeType;
import dev.horizon.ganzir.Parents;
import dev.horizon.ganzir.Root;
import dev.horizon.ganzir.Scope;
import dev.horizon.ganzir.State;
import dev.horizon.pyrite.common.AbortedException;
import dev.horizon.pyrite.common.AlreadyExistsException;
import dev.horizon.pyrite.common.FailedPreconditionException;
import dev.horizon.pyrite.common.InvalidArgumentException;
import dev.horizon.pyrite.common.NotFoundException;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.jdbcclient.JDBCConnectOptions;
import io.vertx.jdbcclient.JDBCPool;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DatabaseHierarchyTest {

  private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
  private static final Duration RETENTION = Duration.ofDays(30);
  private static final Model GOOGLE = new Model(List.of(
      new NodeType("organization", Scope.GLOBAL, RETENTION, List.of(new Root())),
      new NodeType("folder", Scope.PARENT, RETENTION, List.of(new Parents("organization", "folder"), new Depth(3))),
      new NodeType("project", Scope.GLOBAL, RETENTION,
          List.of(new Parents("organization", "folder"), new Children()))));
  private static final PageRequest ALL = new PageRequest("", "", 50, "");

  private Vertx vertx;
  private Pool pool;
  private MovingClock clock;
  private Hierarchy hierarchy;

  @BeforeEach
  void start() throws Exception {
    vertx = Vertx.vertx();
    Path file = Files.createTempFile("ganzir", ".db");
    file.toFile().deleteOnExit();
    String url = "jdbc:sqlite:" + file;
    Flyway.configure()
        .dataSource(url, null, null)
        .locations("classpath:ganzir/migration/**/sqlite")
        .table("ganzir_schema_history")
        .load()
        .migrate();
    pool = JDBCPool.pool(vertx, new JDBCConnectOptions().setJdbcUrl(url), new PoolOptions().setMaxSize(1));
    clock = new MovingClock(START);
    hierarchy = new DatabaseHierarchy(Database.builder(pool, new SqliteDriver())
        .extension(new ProtobufExtension())
        .build(), clock);
    hierarchy.declare(GOOGLE).await(10, TimeUnit.SECONDS);
  }

  @AfterEach
  void stop() throws Exception {
    vertx.close().await(10, TimeUnit.SECONDS);
  }

  @Test
  void theDeclaredModelIsReadBack() throws Exception {
    assertThat(hierarchy.model().await(10, TimeUnit.SECONDS)).isEqualTo(GOOGLE);
  }

  @Test
  void aModelThatNamesAnUndeclaredTypeIsRefused() {
    Model broken = new Model(List.of(
        new NodeType("folder", Scope.PARENT, RETENTION, List.of(new Parents("organization")))));

    assertThatThrownBy(() -> hierarchy.declare(broken).await(10, TimeUnit.SECONDS))
        .isInstanceOf(InvalidArgumentException.class);
  }

  @Test
  void ancestorsAreTheNodesAboveNearestFirst() throws Exception {
    Node example = hierarchy.create("organization", "example.com").await(10, TimeUnit.SECONDS);
    Node finance = example.create("folder", "finance").await(10, TimeUnit.SECONDS);
    Node ledger = finance.create("project", "ledger-prod").await(10, TimeUnit.SECONDS);

    assertThat(ledger.ancestors().await(10, TimeUnit.SECONDS)).extracting(Node::name)
        .containsExactly("finance", "example.com");
    assertThat(example.ancestors().await(10, TimeUnit.SECONDS)).isEmpty();
    assertThat(ledger.parent()).contains(finance.id());
  }

  @Test
  void theRulesOfTheModelAreChecked() throws Exception {
    Node example = hierarchy.create("organization", "example.com").await(10, TimeUnit.SECONDS);
    Node ledger = example.create("project", "ledger-prod").await(10, TimeUnit.SECONDS);

    assertRefused(() -> hierarchy.create("folder", "loose").await(10, TimeUnit.SECONDS), "RULE");
    assertRefused(() -> ledger.create("folder", "inside").await(10, TimeUnit.SECONDS), "RULE");
    Node level = example;
    for (int depth = 1; depth <= 3; depth++) {
      level = level.create("folder", "level-" + depth).await(10, TimeUnit.SECONDS);
    }
    Node deepest = level;
    assertRefused(() -> deepest.create("folder", "too-deep").await(10, TimeUnit.SECONDS), "RULE");
    assertThatThrownBy(() -> example.create("bucket", "logs").await(10, TimeUnit.SECONDS))
        .isInstanceOf(InvalidArgumentException.class);
  }

  @Test
  void namesAreUniqueWithinTheScopeOfTheirType() throws Exception {
    Node example = hierarchy.create("organization", "example.com").await(10, TimeUnit.SECONDS);
    Node finance = example.create("folder", "finance").await(10, TimeUnit.SECONDS);
    Node legal = example.create("folder", "legal").await(10, TimeUnit.SECONDS);
    finance.create("project", "ledger-prod").await(10, TimeUnit.SECONDS);

    assertThatThrownBy(() -> legal.create("project", "ledger-prod").await(10, TimeUnit.SECONDS))
        .isInstanceOf(AlreadyExistsException.class);
    assertThat(finance.create("folder", "shared").await(10, TimeUnit.SECONDS).name()).isEqualTo("shared");
    assertThat(legal.create("folder", "shared").await(10, TimeUnit.SECONDS).name()).isEqualTo("shared");
    assertThatThrownBy(() -> legal.create("folder", "shared").await(10, TimeUnit.SECONDS))
        .isInstanceOf(AlreadyExistsException.class);
    assertThat(hierarchy.node("project", "ledger-prod").await(10, TimeUnit.SECONDS).parent()).contains(finance.id());
    assertThatThrownBy(() -> hierarchy.node("folder", "shared").await(10, TimeUnit.SECONDS))
        .isInstanceOf(InvalidArgumentException.class);
  }

  @Test
  void aMoveCarriesTheWholeSubtree() throws Exception {
    Node example = hierarchy.create("organization", "example.com").await(10, TimeUnit.SECONDS);
    Node finance = example.create("folder", "finance").await(10, TimeUnit.SECONDS);
    Node archive = example.create("folder", "archive").await(10, TimeUnit.SECONDS);
    Node ledger = finance.create("project", "ledger-prod").await(10, TimeUnit.SECONDS);

    Node moved = finance.move(archive, finance.revision()).await(10, TimeUnit.SECONDS);

    assertThat(moved.parent()).contains(archive.id());
    assertThat(hierarchy.node(ledger.id()).await(10, TimeUnit.SECONDS).ancestors().await(10, TimeUnit.SECONDS))
        .extracting(Node::name)
        .containsExactly("finance", "archive", "example.com");
    assertThat(archive.descendants(ALL).await(10, TimeUnit.SECONDS).items()).extracting(Node::name)
        .containsExactlyInAnyOrder("finance", "ledger-prod");
  }

  @Test
  void aNodeCannotMoveUnderItselfOrBreakARule() throws Exception {
    Node example = hierarchy.create("organization", "example.com").await(10, TimeUnit.SECONDS);
    Node finance = example.create("folder", "finance").await(10, TimeUnit.SECONDS);
    Node payroll = finance.create("folder", "payroll").await(10, TimeUnit.SECONDS);
    Node ledger = example.create("project", "ledger-prod").await(10, TimeUnit.SECONDS);

    assertRefused(() -> finance.move(payroll, finance.revision()).await(10, TimeUnit.SECONDS), "RULE");
    assertRefused(() -> finance.move(ledger, finance.revision()).await(10, TimeUnit.SECONDS), "RULE");
  }

  @Test
  void aChangeAppliesOnlyAtTheRevisionItWasReadAt() throws Exception {
    Node example = hierarchy.create("organization", "example.com").await(10, TimeUnit.SECONDS);
    Metadata billing = new Metadata("Example", Map.of("team", "billing"), Map.of("note", "the main one"));

    Node updated = example.update(billing, example.revision()).await(10, TimeUnit.SECONDS);

    assertThat(updated.labels()).containsEntry("team", "billing");
    assertThat(updated.annotations()).containsEntry("note", "the main one");
    assertThat(updated.revision().value()).isEqualTo(example.revision().value() + 1);
    assertThatThrownBy(() -> example.rename("example.org", example.revision()).await(10, TimeUnit.SECONDS))
        .isInstanceOf(AbortedException.class);
  }

  @Test
  void childrenAreFilteredByTypeStateAndLabels() throws Exception {
    Node example = hierarchy.create("organization", "example.com").await(10, TimeUnit.SECONDS);
    example.create("project", "ledger-prod", new Metadata("", Map.of("team", "billing", "env", "prod"), Map.of()))
        .await(10, TimeUnit.SECONDS);
    example.create("project", "ledger-dev", new Metadata("", Map.of("team", "billing", "env", "dev"), Map.of()))
        .await(10, TimeUnit.SECONDS);
    example.create("folder", "legal", new Metadata("", Map.of("team", "legal"), Map.of())).await(10, TimeUnit.SECONDS);

    assertThat(names(example.children(filtered("labels.team = \"billing\"")))).containsExactly("ledger-dev",
        "ledger-prod");
    assertThat(names(example.children(filtered("labels.team = billing AND labels.env = prod"))))
        .containsExactly("ledger-prod");
    assertThat(names(example.children(filtered("type = folder OR labels.env = dev")))).containsExactly("ledger-dev",
        "legal");
    assertThat(names(example.children(filtered("labels.env:*")))).containsExactly("ledger-dev", "ledger-prod");
    assertThat(names(example.children(filtered("NOT labels.team = billing")))).containsExactly("legal");
    assertThatThrownBy(() -> example.children(filtered("create_time > 0")).await(10, TimeUnit.SECONDS))
        .isInstanceOf(InvalidArgumentException.class);
  }

  @Test
  void aNodeWithChildrenOrHoldsIsNotDeleted() throws Exception {
    Node example = hierarchy.create("organization", "example.com").await(10, TimeUnit.SECONDS);
    Node ledger = example.create("project", "ledger-prod").await(10, TimeUnit.SECONDS);
    Hold billing = ledger.hold("billing", "An active subscription is billed to this project.")
        .await(10, TimeUnit.SECONDS);

    assertRefused(() -> example.delete(example.revision()).await(10, TimeUnit.SECONDS), "CHILDREN");
    Node held = hierarchy.node(ledger.id()).await(10, TimeUnit.SECONDS);
    assertThat(held.holds()).extracting(Hold::origin).containsExactly("billing");
    assertRefused(() -> held.delete(held.revision()).await(10, TimeUnit.SECONDS), "HELD");

    billing.release().await(10, TimeUnit.SECONDS);
    Node released = hierarchy.node(ledger.id()).await(10, TimeUnit.SECONDS);
    assertThat(released.delete(released.revision()).await(10, TimeUnit.SECONDS).state()).isEqualTo(State.DELETED);
    Node parent = hierarchy.node(example.id()).await(10, TimeUnit.SECONDS);
    assertThat(parent.delete(parent.revision()).await(10, TimeUnit.SECONDS).state()).isEqualTo(State.DELETED);
  }

  @Test
  void aDeletedNodeIsRestoredUntilItsRetentionEndsAndThenPurged() throws Exception {
    Node example = hierarchy.create("organization", "example.com").await(10, TimeUnit.SECONDS);
    Node ledger = example.create("project", "ledger-prod").await(10, TimeUnit.SECONDS);

    Node deleted = ledger.delete(ledger.revision()).await(10, TimeUnit.SECONDS);
    assertThat(deleted.deleteTime()).contains(START);
    Node restored = deleted.restore(deleted.revision()).await(10, TimeUnit.SECONDS);
    assertThat(restored.state()).isEqualTo(State.ACTIVE);
    assertThat(restored.deleteTime()).isEmpty();

    Node again = restored.delete(restored.revision()).await(10, TimeUnit.SECONDS);
    clock.move(RETENTION.minusSeconds(1));
    assertThat(hierarchy.purge().await(10, TimeUnit.SECONDS)).isZero();
    clock.move(Duration.ofSeconds(1));
    assertThat(hierarchy.purge().await(10, TimeUnit.SECONDS)).isEqualTo(1);
    assertThatThrownBy(() -> hierarchy.node(again.id()).await(10, TimeUnit.SECONDS))
        .isInstanceOf(NotFoundException.class);
    assertThat(example.create("project", "ledger-prod").await(10, TimeUnit.SECONDS).state()).isEqualTo(State.ACTIVE);
  }

  @Test
  void aNodeWithFinalizersIsDeletingUntilTheLastIsRemoved() throws Exception {
    Node example = hierarchy.create("organization", "example.com").await(10, TimeUnit.SECONDS);
    Node ledger = example.create("project", "ledger-prod").await(10, TimeUnit.SECONDS);
    ledger.addFinalizer("storage.example.com/buckets").await(10, TimeUnit.SECONDS);
    Node finalized = ledger.addFinalizer("keys.example.com/keyring").await(10, TimeUnit.SECONDS);

    Node deleting = finalized.delete(finalized.revision()).await(10, TimeUnit.SECONDS);
    assertThat(deleting.state()).isEqualTo(State.DELETING);
    assertThat(deleting.finalizers()).containsExactlyInAnyOrder("storage.example.com/buckets",
        "keys.example.com/keyring");

    assertThat(deleting.removeFinalizer("storage.example.com/buckets").await(10, TimeUnit.SECONDS).state())
        .isEqualTo(State.DELETING);
    assertThat(deleting.removeFinalizer("keys.example.com/keyring").await(10, TimeUnit.SECONDS).state())
        .isEqualTo(State.DELETED);
  }

  @Test
  void rootsAreListedInPages() throws Exception {
    for (String name : List.of("c.example", "a.example", "b.example")) {
      hierarchy.create("organization", name).await(10, TimeUnit.SECONDS);
    }

    Page<Node> first = hierarchy.roots(new PageRequest("", "", 2, "")).await(10, TimeUnit.SECONDS);
    Page<Node> second = hierarchy.roots(new PageRequest("", "", 2, first.nextPageToken())).await(10,
        TimeUnit.SECONDS);

    assertThat(first.items()).extracting(Node::name).containsExactly("a.example", "b.example");
    assertThat(second.items()).extracting(Node::name).containsExactly("c.example");
  }

  @Test
  void aDatabaseWithoutTheProtobufExtensionIsRefused() {
    Database plain = Database.builder(pool, new SqliteDriver()).build();

    assertThatThrownBy(() -> new DatabaseHierarchy(plain))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ProtobufExtension");
  }

  @Test
  void aChildTypeThatTheParentDoesNotAllowIsRefused() throws Exception {
    hierarchy.declare(new Model(List.of(
        new NodeType("organization", Scope.GLOBAL, RETENTION, List.of(new Root(), new Children("folder"))),
        new NodeType("folder", Scope.PARENT, RETENTION, List.of(new Parents("organization"))),
        new NodeType("project", Scope.GLOBAL, RETENTION, List.of(new Parents("organization", "folder"))))))
        .await(10, TimeUnit.SECONDS);
    Node example = hierarchy.create("organization", "example.com").await(10, TimeUnit.SECONDS);

    assertRefused(() -> example.create("project", "ledger-prod").await(10, TimeUnit.SECONDS), "RULE");
    assertThat(example.create("folder", "finance").await(10, TimeUnit.SECONDS).type()).isEqualTo("folder");
  }

  private PageRequest filtered(String filter) {
    return new PageRequest(filter, "", 50, "");
  }

  private List<String> names(Future<Page<Node>> page) throws Exception {
    return page.await(10, TimeUnit.SECONDS).items().stream().map(Node::name).toList();
  }

  private void assertRefused(ThrowingCallable call, String reason) {
    assertThatThrownBy(call).isInstanceOfSatisfying(FailedPreconditionException.class,
        refused -> assertThat(refused.reason()).isEqualTo(reason));
  }
}
