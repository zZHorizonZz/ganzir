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
import dev.horizon.ganzir.NodeType;
import dev.horizon.ganzir.Scope;
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

  Future<DatabaseNode> lock(String id) {
    return find(Filter.eq("id", id)).lock(LockMode.UPDATE).first()
        .map(found -> found.orElseThrow(() -> new NotFoundException("node", id)))
        .compose(row -> liens.find(Filter.eq("node_id", id)).list()
            .compose(placed -> finalizers.find(Filter.eq("node_id", id)).list()
                .map(added -> new DatabaseNode(this, row.toBuilder()
                    .addAllLiens(placed)
                    .addAllFinalizers(added)
                    .build()))));
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

  Future<List<DatabaseNode>> subtree(DatabaseNode node) {
    return find(Filter.and(Filter.startsWith("path", node.path().value()), Filter.ne("id", node.id()))).list()
        .map(rows -> rows.stream().map(row -> new DatabaseNode(this, row)).toList());
  }

  Future<Boolean> hasActiveChildren(DatabaseNode node) {
    return find(Filter.and(Filter.eq("parent_id", node.id()), Filter.ne("state", State.DELETED.name()))).exists();
  }

  Future<Node> insert(NodeType type, String name, Metadata metadata, NodePath parent) {
    String id = UUID.randomUUID().toString().replace("-", "");
    String now = clock.instant().toString();
    List<String> above = parent.ids();
    Tables.Node.Builder row = METADATA.write(Tables.Node.newBuilder()
        .setId(id)
        .setType(type.name())
        .setName(name)
        .setScope(type.scope() == Scope.PARENT && !above.isEmpty() ? above.getLast() : "")
        .setPath(parent.child(id).value())
        .setDepth(above.size())
        .setState(State.ACTIVE.name())
        .setRevision(1)
        .setCreateTime(now)
        .setUpdateTime(now), metadata);
    if (!above.isEmpty()) {
      row.setParentId(above.getLast());
    }
    return insert(row.build())
        .recover(failure -> Future.failedFuture(failure instanceof DuplicateKeyException
            ? new AlreadyExistsException("node", name)
            : failure))
        .compose(inserted -> node(id));
  }

  Future<Node> save(Tables.Node row, Revision expected) {
    Tables.Node changed = row.toBuilder()
        .setRevision(expected.value() + 1)
        .setUpdateTime(clock.instant().toString())
        .build();
    return update()
        .where(Filter.and(Filter.eq("id", row.getId()), Filter.eq("revision", expected.value())))
        .set(changed, CHANGEABLE)
        .execute()
        .recover(failure -> Future.failedFuture(failure instanceof DuplicateKeyException
            ? new AlreadyExistsException("node", row.getName())
            : failure))
        .compose(updated -> updated == 1
            ? node(row.getId())
            : existsById(row.getId()).compose(exists -> Future.failedFuture(exists
                ? new AbortedException("node", row.getId(), expected)
                : new NotFoundException("node", row.getId()))));
  }

  Future<Void> savePaths(List<Tables.Node> rows) {
    Future<Long> saved = Future.succeededFuture(0L);
    for (Tables.Node row : rows) {
      saved = saved.compose(count -> update().where(Filter.eq("id", row.getId())).set(row, "path,depth").execute());
    }
    return saved.mapEmpty();
  }

  Future<Long> purge() {
    return purged(0L);
  }

  private Future<Long> purged(long count) {
    return find(Filter.and(Filter.eq("state", State.DELETED.name()), Filter.lte("purge_time", clock.instant())))
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
