package dev.horizon.ganzir.database;

import dev.horizon.aquery.aip158.Page;
import dev.horizon.aquery.aip158.PageRequest;
import dev.horizon.basepoint.Filter;
import dev.horizon.cinder.DuplicateKeyException;
import dev.horizon.ganzir.Hold;
import dev.horizon.ganzir.Metadata;
import dev.horizon.ganzir.Node;
import dev.horizon.ganzir.NodeType;
import dev.horizon.ganzir.Reason;
import dev.horizon.ganzir.Scope;
import dev.horizon.ganzir.State;
import dev.horizon.ganzir.database.schema.v1.Tables;
import dev.horizon.pyrite.common.AbortedException;
import dev.horizon.pyrite.common.AlreadyExistsException;
import dev.horizon.pyrite.common.FailedPreconditionException;
import dev.horizon.pyrite.common.InvalidArgumentException;
import dev.horizon.pyrite.common.NotFoundException;
import dev.horizon.pyrite.common.Revision;
import dev.horizon.pyrite.common.Violation;
import io.vertx.core.Future;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

final class DatabaseNode implements Node {

  private static final String METADATA = "display_name,labels,label_index,annotations";
  private static final String DELETION = "state,delete_time,purge_time";
  private static final Pattern FINALIZER = Pattern.compile("[A-Za-z0-9._~/-]{1,255}");
  private static final int ORIGIN = 255;
  private static final int REASON = 1024;

  private final DatabaseHierarchy hierarchy;
  private final Tables.Node row;

  DatabaseNode(DatabaseHierarchy hierarchy, Tables.Node row) {
    this.hierarchy = hierarchy;
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
  public String displayName() {
    return row.getDisplayName();
  }

  @Override
  public Map<String, String> labels() {
    return row.getLabelsMap();
  }

  @Override
  public Map<String, String> annotations() {
    return row.getAnnotationsMap();
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
  public List<Hold> holds() {
    return row.getHoldsList().stream().<Hold> map(hold -> new DatabaseHold(hierarchy, hold)).toList();
  }

  @Override
  public List<String> finalizers() {
    return row.getFinalizersList().stream().map(Tables.Finalizer::getName).toList();
  }

  @Override
  public Future<Node> create(String type, String name) {
    return create(type, name, DatabaseHierarchy.NONE);
  }

  @Override
  public Future<Node> create(String type, String name, Metadata metadata) {
    return hierarchy.inserted(type, name, metadata, Optional.of(row.getId()));
  }

  @Override
  public Future<Node> child(String type, String name) {
    return hierarchy.read(Filter.and(Filter.eq("parent_id", row.getId()), Filter.eq("type", type),
        Filter.eq("name", name))).first()
        .map(found -> hierarchy.node(found.orElseThrow(() -> new NotFoundException("node",
            row.getName() + "/" + type + "/" + name))));
  }

  @Override
  public Future<Page<Node>> children(PageRequest request) {
    return hierarchy.page(Filter.eq("parent_id", row.getId()), request);
  }

  @Override
  public Future<Page<Node>> descendants(PageRequest request) {
    return hierarchy.page(Filter.and(Filter.startsWith("path", row.getPath()), Filter.ne("id", row.getId())),
        request);
  }

  @Override
  public Future<List<Node>> ancestors() {
    List<String> path = new NodePath(row.getPath()).ids();
    List<String> ancestors = path.subList(0, path.size() - 1);
    if (ancestors.isEmpty()) {
      return Future.succeededFuture(List.of());
    }
    return hierarchy.read(Filter.in("id", ancestors)).list()
        .map(rows -> rows.stream()
            .sorted(Comparator.comparingInt(Tables.Node::getDepth).reversed())
            .map(hierarchy::node)
            .toList());
  }

  @Override
  public Future<Node> update(Metadata metadata, Revision expected) {
    List<Violation> violations = hierarchy.violations(metadata);
    if (!violations.isEmpty()) {
      return Future.failedFuture(new InvalidArgumentException("The metadata is not valid", violations));
    }
    return changed(DatabaseHierarchy.METADATA_MAPPER.write(row.toBuilder(), metadata), METADATA, expected);
  }

  @Override
  public Future<Node> rename(String name, Revision expected) {
    List<Violation> violations = hierarchy.violations(name, DatabaseHierarchy.NONE);
    if (!violations.isEmpty()) {
      return Future.failedFuture(new InvalidArgumentException("The name is not valid", violations));
    }
    return changed(row.toBuilder().setName(name), "name", expected);
  }

  @Override
  public Future<Node> move(Node parent, Revision expected) {
    return hierarchy.database.transaction(transaction -> hierarchy.locked(row.getId())
        .compose(current -> hierarchy.locked(parent.id()).compose(target -> {
          active(current(current, expected));
          active(target);
          NodePath from = new NodePath(current.getPath());
          NodePath to = new NodePath(target.getPath()).child(current.getId());
          if (from.contains(new NodePath(target.getPath()))) {
            throw new FailedPreconditionException(Reason.RULE.name(), "The node " + current.getId()
                + " cannot be moved under itself or a node below it");
          }
          int shift = target.getDepth() + 1 - current.getDepth();
          return hierarchy.current().compose(model -> {
            NodeType declared = new Placement(model, current.getType(), Optional.of(target), target.getDepth() + 1)
                .check();
            return hierarchy.nodes.find(Filter.and(Filter.startsWith("path", from.value()),
                Filter.ne("id", current.getId()))).list().compose(descendants -> {
                  Future<Long> moved = Future.succeededFuture(0L);
                  for (Tables.Node descendant : descendants) {
                    new Placement(model, descendant.getType(), Optional.empty(), descendant.getDepth() + shift)
                        .checkDepth();
                    moved = moved.compose(count -> hierarchy.nodes.update()
                        .where(Filter.eq("id", descendant.getId()))
                        .set(descendant.toBuilder()
                            .setPath(new NodePath(descendant.getPath()).moved(from, to).value())
                            .setDepth(descendant.getDepth() + shift)
                            .build(), "path,depth")
                        .execute());
                  }
                  return moved.compose(count -> changed(current.toBuilder()
                      .setParentId(target.getId())
                      .setScope(declared.scope() == Scope.PARENT ? target.getId() : "")
                      .setPath(to.value())
                      .setDepth(target.getDepth() + 1), "parent_id,scope,path,depth", expected));
                });
          });
        })));
  }

  @Override
  public Future<Node> delete(Revision expected) {
    return hierarchy.database.transaction(transaction -> hierarchy.locked(row.getId()).compose(current -> {
      if (!current(current, expected).getState().equals(State.ACTIVE.name())) {
        throw state("The node " + current.getId() + " is already deleted");
      }
      return hierarchy.nodes.find(Filter.and(Filter.eq("parent_id", current.getId()),
          Filter.ne("state", State.DELETED.name()))).exists()
          .compose(children -> {
            if (children) {
              throw new FailedPreconditionException(Reason.CHILDREN.name(), "The node " + current.getId()
                  + " has children that are not deleted");
            }
            return hierarchy.holds.find(Filter.eq("node_id", current.getId())).exists();
          })
          .compose(held -> {
            if (held) {
              throw new FailedPreconditionException(Reason.HELD.name(), "The node " + current.getId()
                  + " has holds; release them first");
            }
            return hierarchy.finalizers.find(Filter.eq("node_id", current.getId())).exists();
          })
          .compose(finalized -> hierarchy.current().compose(model -> {
            Instant now = hierarchy.clock.instant();
            Duration retention = model.type(current.getType()).map(NodeType::retention).orElse(Duration.ZERO);
            return changed(current.toBuilder()
                .setState(finalized ? State.DELETING.name() : State.DELETED.name())
                .setDeleteTime(now.toString())
                .setPurgeTime(now.plus(retention).toString()), DELETION, expected);
          }));
    }));
  }

  @Override
  public Future<Node> restore(Revision expected) {
    return hierarchy.database.transaction(transaction -> hierarchy.locked(row.getId()).compose(current -> {
      if (current(current, expected).getState().equals(State.ACTIVE.name())) {
        throw state("The node " + current.getId() + " is active");
      }
      Future<Void> parent = current.hasParentId()
          ? hierarchy.locked(current.getParentId()).map(found -> {
            if (!found.getState().equals(State.ACTIVE.name())) {
              throw state("The parent of the node " + current.getId() + " is deleted; restore it first");
            }
            return null;
          })
          : Future.succeededFuture();
      return parent.compose(ignored -> changed(current.toBuilder()
          .setState(State.ACTIVE.name())
          .clearDeleteTime()
          .clearPurgeTime(), DELETION, expected));
    }));
  }

  @Override
  public Future<Hold> hold(String origin, String reason) {
    if (origin.isEmpty() || origin.length() > ORIGIN || reason.length() > REASON) {
      return Future.failedFuture(new InvalidArgumentException("The hold is not valid", List.of(
          new Violation("origin", "1 to " + ORIGIN + " characters"),
          new Violation("reason", "at most " + REASON + " characters"))));
    }
    Tables.Hold hold = Tables.Hold.newBuilder()
        .setNodeId(row.getId())
        .setId(UUID.randomUUID().toString().replace("-", ""))
        .setOrigin(origin)
        .setReason(reason)
        .setCreateTime(hierarchy.clock.instant().toString())
        .build();
    return hierarchy.database.transaction(transaction -> hierarchy.locked(row.getId())
        .compose(current -> {
          active(current);
          return hierarchy.holds.insert(hold);
        })
        .compose(inserted -> hierarchy.touch(row.getId()).map(touched -> new DatabaseHold(hierarchy, inserted))));
  }

  @Override
  public Future<Node> addFinalizer(String finalizer) {
    if (!FINALIZER.matcher(finalizer).matches()) {
      return Future.failedFuture(new InvalidArgumentException("The finalizer is not valid", List.of(new Violation(
          "finalizer", "1 to 255 letters, digits, '.', '_', '~', '/' and '-'"))));
    }
    Filter mine = Filter.and(Filter.eq("node_id", row.getId()), Filter.eq("name", finalizer));
    return hierarchy.database.transaction(transaction -> hierarchy.locked(row.getId())
        .compose(current -> hierarchy.finalizers.find(mine).exists().map(present -> {
          active(current);
          return present;
        }))
        .compose(present -> present
            ? Future.succeededFuture()
            : hierarchy.finalizers.insert(Tables.Finalizer.newBuilder().setNodeId(row.getId()).setName(finalizer)
                .build()).compose(inserted -> hierarchy.touch(row.getId()))))
        .compose(changed -> hierarchy.node(row.getId()));
  }

  @Override
  public Future<Node> removeFinalizer(String finalizer) {
    Filter mine = Filter.and(Filter.eq("node_id", row.getId()), Filter.eq("name", finalizer));
    return hierarchy.database.transaction(transaction -> hierarchy.locked(row.getId())
        .compose(current -> hierarchy.finalizers.delete().where(mine).execute().compose(removed -> {
          if (removed == 0) {
            return Future.succeededFuture();
          }
          return hierarchy.finalizers.find(Filter.eq("node_id", row.getId())).exists().compose(remaining -> {
            if (!remaining && current.getState().equals(State.DELETING.name())) {
              return changed(current.toBuilder().setState(State.DELETED.name()), "state",
                  new Revision(current.getRevision())).mapEmpty();
            }
            return hierarchy.touch(row.getId());
          });
        })))
        .compose(changed -> hierarchy.node(row.getId()));
  }

  private Future<Node> changed(Tables.Node.Builder change, String mask, Revision expected) {
    Tables.Node changedRow = change.setRevision(expected.value() + 1)
        .setUpdateTime(hierarchy.clock.instant().toString())
        .build();
    return hierarchy.nodes.update()
        .where(Filter.and(Filter.eq("id", row.getId()), Filter.eq("revision", expected.value())))
        .set(changedRow, mask + ",revision,update_time")
        .execute()
        .recover(failure -> Future.failedFuture(failure instanceof DuplicateKeyException
            ? new AlreadyExistsException("node", changedRow.getName())
            : failure))
        .compose(updated -> updated == 1
            ? hierarchy.node(row.getId())
            : hierarchy.nodes.existsById(row.getId()).compose(exists -> Future.failedFuture(exists
                ? new AbortedException("node", row.getId(), expected)
                : new NotFoundException("node", row.getId()))));
  }

  private Tables.Node current(Tables.Node current, Revision expected) {
    if (current.getRevision() != expected.value()) {
      throw new AbortedException("node", current.getId(), expected);
    }
    return current;
  }

  private Tables.Node active(Tables.Node current) {
    if (!current.getState().equals(State.ACTIVE.name())) {
      throw state("The node " + current.getId() + " is " + current.getState() + ", and it must be active");
    }
    return current;
  }

  private FailedPreconditionException state(String detail) {
    return new FailedPreconditionException(Reason.STATE.name(), detail);
  }
}
