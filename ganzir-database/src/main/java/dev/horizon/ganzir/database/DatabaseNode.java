package dev.horizon.ganzir.database;

import dev.horizon.aquery.aip158.Page;
import dev.horizon.aquery.aip158.PageRequest;
import dev.horizon.basepoint.Filter;
import dev.horizon.ganzir.Finalizer;
import dev.horizon.ganzir.Lien;
import dev.horizon.ganzir.Node;
import dev.horizon.ganzir.database.schema.v1.Tables;
import dev.horizon.pyrite.common.InvalidArgumentException;
import dev.horizon.pyrite.common.Metadata;
import dev.horizon.pyrite.common.NotFoundException;
import dev.horizon.pyrite.common.Revision;
import dev.horizon.pyrite.common.Violation;
import io.vertx.core.Future;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

final class DatabaseNode extends AbstractNode implements Node {

  private static final Pattern FINALIZER = Pattern.compile("[A-Za-z0-9._~/-]{1,255}");
  private static final int ORIGIN = 255;
  private static final int REASON = 1024;

  private final NodeBackend backend;
  private final Tables.Node row;

  DatabaseNode(NodeBackend backend, Tables.Node row) {
    this.backend = backend;
    this.row = row;
  }

  @Override
  Tables.Node row() {
    return row;
  }

  @Override
  public List<Lien> liens() {
    return row.getLiensList().stream().<Lien> map(lien -> new DatabaseLien(backend, lien)).toList();
  }

  @Override
  public List<Finalizer> finalizers() {
    return row.getFinalizersList().stream().<Finalizer> map(finalizer -> new DatabaseFinalizer(backend, finalizer)).toList();
  }

  @Override
  public Future<Node> create(String type, String name) {
    return create(type, name, Metadata.NONE);
  }

  @Override
  public Future<Node> create(String type, String name, Metadata metadata) {
    return backend.database().transaction(_ -> Future.all(backend.models().schema(), backend.lock(id()))
        .compose(read -> {
          Schema schema = read.resultAt(0);
          LockedNode parent = read.resultAt(1);
          return backend.insert(parent.adopt(schema, backend.newNode(type, name, metadata)));
        }));
  }

  @Override
  public Future<Node> child(String type, String name) {
    return backend.first(Filter.and(Filter.eq("parent_id", id()), Filter.eq("type", type), Filter.eq("name", name)))
        .map(found -> found.orElseThrow(() -> new NotFoundException("node", name() + "/" + type + "/" + name)));
  }

  @Override
  public Future<Page<Node>> children(PageRequest request) {
    return backend.page(Filter.eq("parent_id", id()), request);
  }

  @Override
  public Future<Page<Node>> descendants(PageRequest request) {
    return backend.page(Filter.and(Filter.startsWith("path", row.getPath()), Filter.ne("id", id())), request);
  }

  @Override
  public Future<List<Node>> ancestors() {
    return backend.ancestors(this);
  }

  @Override
  public Future<Node> update(Metadata metadata, Revision expected) {
    return backend.database().transaction(_ -> backend.lock(id(), expected).compose(node -> {
      node.update(metadata);
      return backend.save(node);
    }));
  }

  @Override
  public Future<Node> rename(String name, Revision expected) {
    return backend.database().transaction(_ -> Future.all(backend.models().schema(), backend.lock(id(), expected))
        .compose(read -> {
          Schema schema = read.resultAt(0);
          LockedNode node = read.resultAt(1);
          node.rename(schema, name);
          return backend.save(node);
        }));
  }

  @Override
  public Future<Node> move(Node parent, Revision expected) {
    return backend.database().transaction(_ -> Future.all(backend.models().schema(), backend.lock(id(), expected),
        backend.lock(parent.id())).compose(read -> {
          Schema schema = read.resultAt(0);
          LockedNode node = read.resultAt(1);
          LockedNode target = read.resultAt(2);
          return backend.subtree(node)
              .compose(below -> backend.savePaths(node.move(schema, target, below)))
              .compose(moved -> backend.save(node));
        }));
  }

  @Override
  public Future<Node> delete(Revision expected) {
    return backend.database()
        .transaction(_ -> Future.all(backend.models().schema(), backend.lock(id(), expected)).compose(read -> {
          Schema schema = read.resultAt(0);
          LockedNode node = read.resultAt(1);
          return backend.hasActiveChildren(node).compose(children -> {
            node.delete(schema, children, backend.clock().instant());
            return backend.save(node);
          });
        }));
  }

  @Override
  public Future<Node> restore(Revision expected) {
    return backend.database().transaction(_ -> backend.lock(id(), expected).compose(node -> {
      if (node.parent().isEmpty()) {
        node.restore();
        return backend.save(node);
      }
      return backend.lock(node.parent().get()).compose(parent -> {
        node.restore(parent);
        return backend.save(node);
      });
    }));
  }

  @Override
  public Future<Lien> lien(String origin, String reason) {
    if (origin.isEmpty() || origin.length() > ORIGIN || reason.length() > REASON) {
      return Future.failedFuture(new InvalidArgumentException("The lien is not valid", List.of(
          new Violation("origin", "1 to " + ORIGIN + " characters"),
          new Violation("reason", "at most " + REASON + " characters"))));
    }
    return backend.database().transaction(_ -> backend.lock(id()).compose(node -> {
      Tables.Lien lien = Tables.Lien.newBuilder()
          .setNodeId(id())
          .setId(UUID.randomUUID().toString().replace("-", ""))
          .setOrigin(origin)
          .setReason(reason)
          .setCreateTime(backend.clock().instant().toString())
          .build();
      node.addLien(lien);
      return backend.liens().insert(lien).compose(placed -> backend.save(node).map(_ -> new DatabaseLien(backend, placed)));
    }));
  }

  @Override
  public Future<Finalizer> finalizer(String name) {
    if (!FINALIZER.matcher(name).matches()) {
      return Future.failedFuture(new InvalidArgumentException("The finalizer is not valid", List.of(new Violation(
          "name", "1 to 255 letters, digits, '.', '_', '~', '/' and '-'"))));
    }
    return backend.database().transaction(transaction -> backend.lock(id()).compose(node -> {
      Tables.Finalizer finalizer = Tables.Finalizer.newBuilder().setNodeId(id()).setName(name).build();
      if (!node.addFinalizer(finalizer)) {
        return Future.succeededFuture(new DatabaseFinalizer(backend, finalizer));
      }
      return backend.finalizers().insert(finalizer)
          .compose(added -> backend.save(node).map(saved -> new DatabaseFinalizer(backend, added)));
    }));
  }
}
