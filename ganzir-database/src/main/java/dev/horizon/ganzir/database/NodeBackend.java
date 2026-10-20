package dev.horizon.ganzir.database;

import dev.horizon.aquery.aip158.Page;
import dev.horizon.aquery.aip158.PageRequest;
import dev.horizon.basepoint.Filter;
import dev.horizon.basepoint.LockMode;
import dev.horizon.cinder.Backend;
import dev.horizon.cinder.Database;
import dev.horizon.cinder.DuplicateKeyException;
import dev.horizon.ganzir.Metadata;
import dev.horizon.ganzir.Node;
import dev.horizon.ganzir.State;
import dev.horizon.ganzir.database.schema.v1.Tables;
import dev.horizon.pyrite.common.AbortedException;
import dev.horizon.pyrite.common.AlreadyExistsException;
import dev.horizon.pyrite.common.InvalidArgumentException;
import dev.horizon.pyrite.common.NotFoundException;
import dev.horizon.pyrite.common.Revision;
import dev.horizon.pyrite.common.Violation;
import io.vertx.core.Future;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

record NodeBackend(Database database, ModelBackend models, LienBackend liens, FinalizerBackend finalizers, Clock clock)
    implements Backend<Tables.Node, String> {

  private static final MetadataMapper METADATA = new MetadataMapper();
  private static final NodeFilter FILTER = new NodeFilter();
  private static final String CHANGEABLE = "name,parent_id,scope,path,depth,display_name,labels,label_index,"
      + "annotations,state,revision,update_time,delete_time,purge_time";
  private static final int PURGED_AT_ONCE = 100;

  @Override
  public Class<Tables.Node> type() {
    return Tables.Node.class;
  }

  Future<Node> node(String id) {
    return first(Filter.eq("id", id)).map(found -> found.orElseThrow(() -> new NotFoundException("node", id)));
  }

  Future<Optional<Node>> first(Filter where) {
    return find(where).include("liens").include("finalizers").first()
        .map(found -> found.<Node> map(row -> new DatabaseNode(this, row)));
  }

  /**
   * Locks a node for the transaction. Its liens and finalizers are read after the lock, so that they include every
   * one that a transaction which held the lock before committed.
   */
  Future<LockedNode> lock(String id) {
    return find(Filter.eq("id", id)).lock(LockMode.UPDATE).first()
        .map(found -> found.orElseThrow(() -> new NotFoundException("node", id)))
        .compose(row -> liens.find(Filter.eq("node_id", id)).list()
            .compose(placed -> finalizers.find(Filter.eq("node_id", id)).list()
                .map(added -> new LockedNode(row.toBuilder().addAllLiens(placed).addAllFinalizers(added)))));
  }

  /**
   * Locks a node for the transaction, if it still has the revision that the caller expects.
   */
  Future<LockedNode> lock(String id, Revision expected) {
    return lock(id).map(node -> {
      node.expect(expected);
      return node;
    });
  }

  Future<Page<Node>> page(Filter base, PageRequest request) {
    if (request.orderBy() != null && !request.orderBy().isBlank()) {
      return Future.failedFuture(new InvalidArgumentException("Pages of nodes are ordered by name only",
          List.of(new Violation("orderBy", "empty"))));
    }
    Filter where;
    try {
      where = FILTER.of(request.filter()).map(narrowing -> Filter.and(base, narrowing)).orElse(base);
    } catch (InvalidArgumentException invalid) {
      return Future.failedFuture(invalid);
    }
    return find(where).include("liens").include("finalizers")
        .page(new PageRequest("", "name", request.pageSize(), request.pageToken()))
        .map(page -> new Page<>(page.items().stream().<Node> map(row -> new DatabaseNode(this, row)).toList(),
            page.nextPageToken(), page.totalSize()));
  }

  Future<List<Node>> ancestors(DatabaseNode node) {
    List<String> path = node.path().ids();
    List<String> above = path.subList(0, path.size() - 1);
    if (above.isEmpty()) {
      return Future.succeededFuture(List.of());
    }
    return find(Filter.in("id", above)).include("liens").include("finalizers").list()
        .map(rows -> rows.stream()
            .sorted(Comparator.comparingInt(Tables.Node::getDepth).reversed())
            .<Node> map(row -> new DatabaseNode(this, row))
            .toList());
  }

  /**
   * Reads the nodes below a node, with only the fields that a move changes.
   */
  Future<List<Tables.Node>> subtree(LockedNode node) {
    return find(Filter.and(Filter.startsWith("path", node.path().value()), Filter.ne("id", node.id())))
        .mask("id,type,path,depth")
        .list();
  }

  Future<Boolean> hasActiveChildren(LockedNode node) {
    return find(Filter.and(Filter.eq("parent_id", node.id()), Filter.ne("state", State.DELETED.name()))).exists();
  }

  /**
   * Starts the row of an active node, which is a root until a parent adopts it.
   */
  Tables.Node.Builder newNode(String type, String name, Metadata metadata) {
    String id = UUID.randomUUID().toString().replace("-", "");
    String now = clock.instant().toString();
    return METADATA.write(Tables.Node.newBuilder()
        .setId(id)
        .setType(type)
        .setName(name)
        .setPath(NodePath.TOP.child(id).value())
        .setState(State.ACTIVE.name())
        .setRevision(1)
        .setCreateTime(now)
        .setUpdateTime(now), metadata);
  }

  Future<Node> insert(Tables.Node.Builder row) {
    return insert(row.build())
        .recover(failure -> Future.failedFuture(failure instanceof DuplicateKeyException
            ? new AlreadyExistsException("node", row.getName())
            : failure))
        .map(inserted -> new DatabaseNode(this, inserted));
  }

  Future<Node> save(LockedNode node) {
    Revision locked = node.revision();
    Tables.Node saved = node.row().clone()
        .setRevision(locked.value() + 1)
        .setUpdateTime(clock.instant().toString())
        .build();
    return update()
        .where(Filter.and(Filter.eq("id", saved.getId()), Filter.eq("revision", locked.value())))
        .set(saved, CHANGEABLE)
        .execute()
        .recover(failure -> Future.failedFuture(failure instanceof DuplicateKeyException
            ? new AlreadyExistsException("node", saved.getName())
            : failure))
        .compose(updated -> updated == 1
            ? Future.succeededFuture(new DatabaseNode(this, saved))
            : existsById(saved.getId()).compose(exists -> Future.failedFuture(exists
                ? new AbortedException("node", saved.getId(), locked)
                : new NotFoundException("node", saved.getId()))));
  }

  Future<Void> savePaths(List<Tables.Node> rows) {
    return Future.all(rows.stream()
        .map(row -> update().where(Filter.eq("id", row.getId())).set(row, "path,depth").execute())
        .toList())
        .mapEmpty();
  }

  Future<Long> purge() {
    return purged(0L);
  }

  private Future<Long> purged(long count) {
    return find(Filter.and(Filter.eq("state", State.DELETED.name()), Filter.lte("purge_time", clock.instant())))
        .mask("id")
        .limit(PURGED_AT_ONCE)
        .list()
        .compose(candidates -> {
          Future<Long> purged = Future.succeededFuture(0L);
          for (Tables.Node candidate : candidates) {
            purged = purged.compose(sum -> purged(candidate.getId()).map(one -> sum + one));
          }
          return purged;
        })
        .compose(purged -> purged == 0 ? Future.succeededFuture(count) : purged(count + purged));
  }

  private Future<Long> purged(String id) {
    return database.transaction(transaction -> find(Filter.eq("parent_id", id)).exists()
        .compose(children -> children
            ? Future.succeededFuture(0L)
            : liens.delete().where(Filter.eq("node_id", id)).execute()
                .compose(deleted -> finalizers.delete().where(Filter.eq("node_id", id)).execute())
                .compose(deleted -> delete()
                    .where(Filter.and(Filter.eq("id", id), Filter.eq("state", State.DELETED.name())))
                    .execute())));
  }
}
