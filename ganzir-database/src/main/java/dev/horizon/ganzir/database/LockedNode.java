package dev.horizon.ganzir.database;

import dev.horizon.ganzir.NodeType;
import dev.horizon.ganzir.Reason;
import dev.horizon.ganzir.Scope;
import dev.horizon.ganzir.State;
import dev.horizon.ganzir.database.schema.v1.Tables;
import dev.horizon.pyrite.common.AbortedException;
import dev.horizon.pyrite.common.FailedPreconditionException;
import dev.horizon.pyrite.common.Metadata;
import dev.horizon.pyrite.common.Revision;
import java.time.Instant;
import java.util.List;

final class LockedNode extends AbstractNode {

  private static final MetadataMapper METADATA = new MetadataMapper();

  private final Tables.Node.Builder row;

  LockedNode(Tables.Node.Builder row) {
    this.row = row;
  }

  @Override
  Tables.Node.Builder row() {
    return row;
  }

  void expect(Revision expected) {
    if (row.getRevision() != expected.value()) {
      throw new AbortedException("node", id(), expected);
    }
  }

  Tables.Node.Builder adopt(Schema schema, Tables.Node.Builder child) {
    requireActive();
    NodeType declared = schema.child(child.getType(), child.getName(), row.getType(), row.getDepth() + 1);
    return child.setParentId(id())
        .setScope(declared.scope() == Scope.PARENT ? id() : "")
        .setPath(path().child(child.getId()).value())
        .setDepth(row.getDepth() + 1);
  }

  void update(Metadata metadata) {
    METADATA.write(row, metadata);
  }

  void rename(Schema schema, String name) {
    schema.checkName(name);
    row.setName(name);
  }

  List<Tables.Node> move(Schema schema, LockedNode parent, List<Tables.Node> below) {
    requireActive();
    parent.requireActive();
    NodePath from = path();
    if (from.contains(parent.path())) {
      throw new FailedPreconditionException(Reason.RULE.name(),
          "The node " + id() + " cannot be moved under itself or a node below it");
    }
    int shift = parent.row.getDepth() + 1 - row.getDepth();
    parent.adopt(schema, row);
    NodePath to = path();
    return below.stream().map(descendant -> {
      int depth = descendant.getDepth() + shift;
      schema.checkDepth(descendant.getType(), depth);
      return descendant.toBuilder()
          .setPath(new NodePath(descendant.getPath()).moved(from, to).value())
          .setDepth(depth)
          .build();
    }).toList();
  }

  void delete(Schema schema, boolean activeChildren, Instant now) {
    if (state() != State.ACTIVE) {
      throw new FailedPreconditionException(Reason.STATE.name(), "The node " + id() + " is already deleted");
    }
    if (activeChildren) {
      throw new FailedPreconditionException(Reason.CHILDREN.name(), "The node " + id() + " has children that are "
          + "not deleted");
    }
    if (row.getLiensCount() > 0) {
      throw new FailedPreconditionException(Reason.LIEN.name(), "The node " + id() + " has liens; remove them first");
    }
    row.setState(row.getFinalizersCount() > 0 ? State.DELETING.name() : State.DELETED.name())
        .setDeleteTime(now.toString())
        .setPurgeTime(now.plus(schema.retention(row.getType())).toString());
  }

  void restore() {
    if (state() == State.ACTIVE) {
      throw new FailedPreconditionException(Reason.STATE.name(), "The node " + id() + " is active");
    }
    row.setState(State.ACTIVE.name()).clearDeleteTime().clearPurgeTime();
  }

  void restore(LockedNode parent) {
    if (parent.state() != State.ACTIVE) {
      throw new FailedPreconditionException(Reason.STATE.name(), "The parent of the node " + id() + " is not active; "
          + "restore it first");
    }
    restore();
  }

  void addLien(Tables.Lien lien) {
    requireActive();
    row.addLiens(lien);
  }

  boolean removeLien(String id) {
    for (int index = 0; index < row.getLiensCount(); index++) {
      if (row.getLiens(index).getId().equals(id)) {
        row.removeLiens(index);
        return true;
      }
    }
    return false;
  }

  boolean addFinalizer(Tables.Finalizer finalizer) {
    requireActive();
    if (row.getFinalizersList().stream().anyMatch(added -> added.getName().equals(finalizer.getName()))) {
      return false;
    }
    row.addFinalizers(finalizer);
    return true;
  }

  boolean removeFinalizer(String name) {
    for (int index = 0; index < row.getFinalizersCount(); index++) {
      if (row.getFinalizers(index).getName().equals(name)) {
        row.removeFinalizers(index);
        if (state() == State.DELETING && row.getFinalizersCount() == 0) {
          row.setState(State.DELETED.name());
        }
        return true;
      }
    }
    return false;
  }

  private void requireActive() {
    if (state() != State.ACTIVE) {
      throw new FailedPreconditionException(Reason.STATE.name(),
          "The node " + id() + " is " + row.getState() + ", and it must be active");
    }
  }
}
