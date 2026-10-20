package dev.horizon.ganzir.database;

import dev.horizon.aquery.aip158.Page;
import dev.horizon.aquery.aip158.PageRequest;
import dev.horizon.basepoint.Filter;
import dev.horizon.ganzir.Finalizer;
import dev.horizon.ganzir.Lien;
import dev.horizon.ganzir.Metadata;
import dev.horizon.ganzir.Node;
import dev.horizon.ganzir.NodeType;
import dev.horizon.ganzir.Reason;
import dev.horizon.ganzir.Scope;
import dev.horizon.ganzir.State;
import dev.horizon.ganzir.database.schema.v1.Tables;
import dev.horizon.pyrite.common.AbortedException;
import dev.horizon.pyrite.common.FailedPreconditionException;
import dev.horizon.pyrite.common.InvalidArgumentException;
import dev.horizon.pyrite.common.NotFoundException;
import dev.horizon.pyrite.common.Revision;
import dev.horizon.pyrite.common.Violation;
import io.vertx.core.Future;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

final class DatabaseNode implements Node {

  private static final MetadataMapper METADATA = new MetadataMapper();
  private static final Pattern FINALIZER = Pattern.compile("[A-Za-z0-9._~/-]{1,255}");
  private static final int ORIGIN = 255;
  private static final int REASON = 1024;

  private final NodeBackend nodes;
  private final Tables.Node row;

  DatabaseNode(NodeBackend nodes, Tables.Node row) {
    this.nodes = nodes;
    this.row = row;
  }

  @Override
  public String id() {
    return row.getId();
  }

  @Override
  public String type() {
    return row.getType();
  }

  @Override
  public String name() {
    return row.getName();
  }

  @Override
  public Optional<String> parent() {
    return row.hasParentId() ? Optional.of(row.getParentId()) : Optional.empty();
  }

  @Override
  public Metadata metadata() {
    return METADATA.read(row);
  }

  @Override
  public State state() {
    return State.valueOf(row.getState());
  }

  @Override
  public Revision revision() {
    return new Revision(row.getRevision());
  }

  @Override
  public Instant createTime() {
    return Instant.parse(row.getCreateTime());
  }

  @Override
  public Instant updateTime() {
    return Instant.parse(row.getUpdateTime());
  }

  @Override
  public Optional<Instant> deleteTime() {
    return row.hasDeleteTime() ? Optional.of(Instant.parse(row.getDeleteTime())) : Optional.empty();
  }

  @Override
  public List<Lien> liens() {
    return row.getLiensList().stream().<Lien> map(lien -> new DatabaseLien(nodes, lien)).toList();
  }

  @Override
  public List<Finalizer> finalizers() {
    return row.getFinalizersList().stream().<Finalizer> map(finalizer -> new DatabaseFinalizer(nodes, finalizer))
        .toList();
  }

  @Override
  public Future<Node> create(String type, String name) {
    return create(type, name, Metadata.NONE);
  }

  @Override
  public Future<Node> create(String type, String name, Metadata metadata) {
    return nodes.database().transaction(transaction -> Future.all(nodes.models().schema(), nodes.lock(id()))
        .compose(read -> {
          Schema schema = read.resultAt(0);
          DatabaseNode parent = read.resultAt(1);
          return nodes.insert(parent.admit(schema, type, name), name, metadata, parent.path());
        }));
  }

  @Override
  public Future<Node> child(String type, String name) {
    return nodes.first(Filter.and(Filter.eq("parent_id", id()), Filter.eq("type", type), Filter.eq("name", name)))
        .map(found -> found.orElseThrow(() -> new NotFoundException("node", name() + "/" + type + "/" + name)));
  }

  @Override
  public Future<Page<Node>> children(PageRequest request) {
    return nodes.page(Filter.eq("parent_id", id()), request);
  }

  @Override
  public Future<Page<Node>> descendants(PageRequest request) {
    return nodes.page(Filter.and(Filter.startsWith("path", row.getPath()), Filter.ne("id", id())), request);
  }

  @Override
  public Future<List<Node>> ancestors() {
    return nodes.ancestors(this);
  }

  @Override
  public Future<Node> update(Metadata metadata, Revision expected) {
    return nodes.database().transaction(transaction -> nodes.lock(id())
        .compose(node -> nodes.save(node.updated(expected, metadata), expected)));
  }

  @Override
  public Future<Node> rename(String name, Revision expected) {
    return nodes.database().transaction(transaction -> Future.all(nodes.models().schema(), nodes.lock(id()))
        .compose(read -> {
          Schema schema = read.resultAt(0);
          DatabaseNode node = read.resultAt(1);
          return nodes.save(node.renamed(expected, schema, name), expected);
        }));
  }

  @Override
  public Future<Node> move(Node parent, Revision expected) {
    return nodes.database().transaction(transaction -> Future.all(nodes.models().schema(), nodes.lock(id()),
        nodes.lock(parent.id())).compose(read -> {
          Schema schema = read.resultAt(0);
          DatabaseNode node = read.resultAt(1);
          Tables.Node moved = node.moved(expected, read.resultAt(2), schema);
          return nodes.subtree(node)
              .compose(below -> nodes.savePaths(below.stream()
                  .map(descendant -> descendant.shifted(schema, node, moved))
                  .toList()))
              .compose(saved -> nodes.save(moved, expected));
        }));
  }

  @Override
  public Future<Node> delete(Revision expected) {
    return nodes.database().transaction(transaction -> Future.all(nodes.models().schema(), nodes.lock(id()))
        .compose(read -> {
          Schema schema = read.resultAt(0);
          DatabaseNode node = read.resultAt(1);
          return nodes.hasActiveChildren(node)
              .compose(children -> nodes.save(node.deleted(expected, children, schema.retention(node.type()),
                  nodes.clock().instant()), expected));
        }));
  }

  @Override
  public Future<Node> restore(Revision expected) {
    return nodes.database().transaction(transaction -> nodes.lock(id()).compose(node -> {
      Future<Boolean> parentActive = node.parent().isPresent()
          ? nodes.lock(node.parent().get()).map(parent -> parent.state() == State.ACTIVE)
          : Future.succeededFuture(true);
      return parentActive.compose(active -> nodes.save(node.restored(expected, active), expected));
    }));
  }

  @Override
  public Future<Lien> lien(String origin, String reason) {
    if (origin.isEmpty() || origin.length() > ORIGIN || reason.length() > REASON) {
      return Future.failedFuture(new InvalidArgumentException("The lien is not valid", List.of(
          new Violation("origin", "1 to " + ORIGIN + " characters"),
          new Violation("reason", "at most " + REASON + " characters"))));
    }
    return nodes.database().transaction(transaction -> nodes.lock(id()).compose(node -> {
      node.requireActive();
      return nodes.liens().insert(Tables.Lien.newBuilder()
          .setNodeId(id())
          .setId(UUID.randomUUID().toString().replace("-", ""))
          .setOrigin(origin)
          .setReason(reason)
          .setCreateTime(nodes.clock().instant().toString())
          .build())
          .compose(placed -> nodes.save(node.touched(), node.revision()).map(saved -> new DatabaseLien(nodes, placed)));
    }));
  }

  @Override
  public Future<Finalizer> finalizer(String name) {
    if (!FINALIZER.matcher(name).matches()) {
      return Future.failedFuture(new InvalidArgumentException("The finalizer is not valid", List.of(new Violation(
          "name", "1 to 255 letters, digits, '.', '_', '~', '/' and '-'"))));
    }
    return nodes.database().transaction(transaction -> nodes.lock(id()).compose(node -> {
      node.requireActive();
      Optional<Tables.Finalizer> existing = node.row.getFinalizersList().stream()
          .filter(finalizer -> finalizer.getName().equals(name))
          .findFirst();
      if (existing.isPresent()) {
        return Future.succeededFuture(new DatabaseFinalizer(nodes, existing.get()));
      }
      return nodes.finalizers().insert(Tables.Finalizer.newBuilder().setNodeId(id()).setName(name).build())
          .compose(added -> nodes.save(node.touched(), node.revision())
              .map(saved -> new DatabaseFinalizer(nodes, added)));
    }));
  }

  NodePath path() {
    return new NodePath(row.getPath());
  }

  NodeType admit(Schema schema, String type, String name) {
    requireActive();
    return schema.child(type, name, row.getType(), row.getDepth() + 1);
  }

  Tables.Node updated(Revision expected, Metadata metadata) {
    expect(expected);
    return METADATA.write(row.toBuilder(), metadata).build();
  }

  Tables.Node renamed(Revision expected, Schema schema, String name) {
    expect(expected);
    schema.checkName(name);
    return row.toBuilder().setName(name).build();
  }

  Tables.Node moved(Revision expected, DatabaseNode target, Schema schema) {
    expect(expected);
    requireActive();
    target.requireActive();
    if (path().contains(target.path())) {
      throw new FailedPreconditionException(Reason.RULE.name(), "The node " + id() + " cannot be moved under itself "
          + "or a node below it");
    }
    NodeType declared = schema.child(row.getType(), row.getName(), target.row.getType(), target.row.getDepth() + 1);
    return row.toBuilder()
        .setParentId(target.id())
        .setScope(declared.scope() == Scope.PARENT ? target.id() : "")
        .setPath(target.path().child(id()).value())
        .setDepth(target.row.getDepth() + 1)
        .build();
  }

  Tables.Node shifted(Schema schema, DatabaseNode from, Tables.Node to) {
    int depth = row.getDepth() + to.getDepth() - from.row.getDepth();
    schema.checkDepth(row.getType(), depth);
    return row.toBuilder()
        .setPath(path().moved(from.path(), new NodePath(to.getPath())).value())
        .setDepth(depth)
        .build();
  }

  Tables.Node deleted(Revision expected, boolean children, Duration retention, Instant now) {
    expect(expected);
    if (state() != State.ACTIVE) {
      throw new FailedPreconditionException(Reason.STATE.name(), "The node " + id() + " is already deleted");
    }
    if (children) {
      throw new FailedPreconditionException(Reason.CHILDREN.name(), "The node " + id() + " has children that are "
          + "not deleted");
    }
    if (row.getLiensCount() > 0) {
      throw new FailedPreconditionException(Reason.LIEN.name(), "The node " + id() + " has liens; remove them first");
    }
    return row.toBuilder()
        .setState(row.getFinalizersCount() > 0 ? State.DELETING.name() : State.DELETED.name())
        .setDeleteTime(now.toString())
        .setPurgeTime(now.plus(retention).toString())
        .build();
  }

  Tables.Node restored(Revision expected, boolean parentActive) {
    expect(expected);
    if (state() == State.ACTIVE) {
      throw new FailedPreconditionException(Reason.STATE.name(), "The node " + id() + " is active");
    }
    if (!parentActive) {
      throw new FailedPreconditionException(Reason.STATE.name(), "The parent of the node " + id() + " is not active; "
          + "restore it first");
    }
    return row.toBuilder().setState(State.ACTIVE.name()).clearDeleteTime().clearPurgeTime().build();
  }

  Tables.Node finalized(boolean remaining) {
    return state() == State.DELETING && !remaining ? row.toBuilder().setState(State.DELETED.name()).build() : row;
  }

  Tables.Node touched() {
    return row;
  }

  void requireActive() {
    if (state() != State.ACTIVE) {
      throw new FailedPreconditionException(Reason.STATE.name(), "The node " + id() + " is " + row.getState()
          + ", and it must be active");
    }
  }

  private void expect(Revision expected) {
    if (row.getRevision() != expected.value()) {
      throw new AbortedException("node", id(), expected);
    }
  }
}
