package dev.horizon.ganzir.database;

import dev.horizon.aquery.aip158.Page;
import dev.horizon.aquery.aip158.PageRequest;
import dev.horizon.basepoint.Filter;
import dev.horizon.basepoint.LockMode;
import dev.horizon.cinder.Assignments;
import dev.horizon.cinder.Database;
import dev.horizon.cinder.DuplicateKeyException;
import dev.horizon.cinder.Query;
import dev.horizon.ganzir.Children;
import dev.horizon.ganzir.Depth;
import dev.horizon.ganzir.Hierarchy;
import dev.horizon.ganzir.Metadata;
import dev.horizon.ganzir.Model;
import dev.horizon.ganzir.Node;
import dev.horizon.ganzir.NodeType;
import dev.horizon.ganzir.Parents;
import dev.horizon.ganzir.Reason;
import dev.horizon.ganzir.Root;
import dev.horizon.ganzir.Rule;
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
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

public final class DatabaseHierarchy implements Hierarchy {

  static final MetadataMapper METADATA_MAPPER = new MetadataMapper();
  static final Metadata NONE = new Metadata("", Map.of(), Map.of());
  private static final ModelMapper MODEL_MAPPER = new ModelMapper();
  private static final Model EMPTY = new Model(List.of());
  private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._~-]{1,255}");
  private static final Pattern TYPE = Pattern.compile("[a-z][a-z0-9_]{0,62}");
  private static final Pattern LABEL_KEY = Pattern.compile("[a-z][a-z0-9_-]{0,62}");
  private static final Pattern LABEL_VALUE = Pattern.compile("[a-z0-9_-]{0,63}");
  private static final int DISPLAY_NAME = 255;
  private static final int LABELS = 64;
  private static final int ANNOTATIONS = 256 * 1024;
  private static final int PURGED_AT_ONCE = 100;

  final Database database;
  final Clock clock;
  final NodeBackend nodes;
  final HoldBackend holds;
  final FinalizerBackend finalizers;
  private final ModelBackend models;
  private final NodeFilter filter = new NodeFilter();

  public DatabaseHierarchy(Database database) {
    this(database, Clock.systemUTC());
  }

  public DatabaseHierarchy(Database database, Clock clock) {
    try {
      database.model(Tables.Node.class);
    } catch (IllegalArgumentException unknown) {
      throw new IllegalArgumentException("The database cannot store Ganzir's tables, which are protobuf messages; build "
          + "it with .extension(new ProtobufExtension())", unknown);
    }
    this.database = database;
    this.clock = Clock.tick(clock, Duration.ofMillis(1));
    this.nodes = new NodeBackend(database);
    this.holds = new HoldBackend(database);
    this.finalizers = new FinalizerBackend(database);
    this.models = new ModelBackend(database);
  }

  @Override
  public Future<Void> declare(Model model) {
    List<Violation> violations = violations(model);
    if (!violations.isEmpty()) {
      return Future.failedFuture(new InvalidArgumentException("The model is not valid", violations));
    }
    return models.findAll().orderBy("version desc").first().compose(latest -> {
      long version = latest.map(row -> row.getVersion() + 1).orElse(1L);
      return models.insert(MODEL_MAPPER.write(Tables.Model.newBuilder()
          .setVersion(version)
          .setCreateTime(clock.instant().toString()), model).build())
          .recover(failure -> Future.failedFuture(failure instanceof DuplicateKeyException
              ? new AbortedException("model", "latest", new Revision(version - 1))
              : failure));
    }).mapEmpty();
  }

  @Override
  public Future<Model> model() {
    return models.findAll().orderBy("version desc").first()
        .map(latest -> MODEL_MAPPER.read(latest.orElseThrow(() -> new NotFoundException("model", "latest"))));
  }

  @Override
  public Future<Node> create(String type, String name) {
    return create(type, name, NONE);
  }

  @Override
  public Future<Node> create(String type, String name, Metadata metadata) {
    return inserted(type, name, metadata, Optional.empty());
  }

  @Override
  public Future<Node> node(String id) {
    return read(Filter.eq("id", id)).first()
        .map(found -> node(found.orElseThrow(() -> new NotFoundException("node", id))));
  }

  @Override
  public Future<Node> node(String type, String name) {
    return current().compose(model -> {
      NodeType declared = model.type(type).orElseThrow(() -> new InvalidArgumentException(
          "The model declares no type " + type, List.of(new Violation("type", "a type that the model declares"))));
      if (declared.scope() != Scope.GLOBAL) {
        throw new InvalidArgumentException("The names of the type " + type + " are unique among siblings only; ask "
            + "the parent for its child", List.of(new Violation("type", "a type whose names are unique globally")));
      }
      return read(Filter.and(Filter.eq("type", type), Filter.eq("scope", ""), Filter.eq("name", name))).first()
          .map(found -> node(found.orElseThrow(() -> new NotFoundException("node", type + "/" + name))));
    });
  }

  @Override
  public Future<Page<Node>> roots(PageRequest request) {
    return page(Filter.isNull("parent_id"), request);
  }

  @Override
  public Future<Long> purge() {
    return purged(0L);
  }

  Future<Node> inserted(String type, String name, Metadata metadata, Optional<String> parentId) {
    List<Violation> violations = violations(name, metadata);
    if (!violations.isEmpty()) {
      return Future.failedFuture(new InvalidArgumentException("The node is not valid", violations));
    }
    String id = UUID.randomUUID().toString().replace("-", "");
    String now = clock.instant().toString();
    return database.transaction(transaction -> current().compose(model -> parent(parentId).compose(parent -> {
      int depth = parent.map(row -> row.getDepth() + 1).orElse(0);
      NodeType declared = new Placement(model, type, parent, depth).check();
      Tables.Node.Builder row = METADATA_MAPPER.write(Tables.Node.newBuilder()
          .setId(id)
          .setType(type)
          .setName(name)
          .setScope(declared.scope() == Scope.PARENT ? parent.map(Tables.Node::getId).orElse("") : "")
          .setPath(parent.map(found -> new NodePath(found.getPath())).orElse(NodePath.TOP).child(id).value())
          .setDepth(depth)
          .setState(State.ACTIVE.name())
          .setRevision(1)
          .setCreateTime(now)
          .setUpdateTime(now), metadata);
      parent.ifPresent(found -> row.setParentId(found.getId()));
      return nodes.insert(row.build());
    })))
        .recover(failure -> Future.failedFuture(failure instanceof DuplicateKeyException
            ? new AlreadyExistsException("node", name)
            : failure))
        .compose(inserted -> node(id));
  }

  Future<Model> current() {
    return models.findAll().orderBy("version desc").first()
        .map(latest -> latest.map(MODEL_MAPPER::read).orElse(EMPTY));
  }

  Query<Tables.Node> read(Filter where) {
    return nodes.find(where).include("holds").include("finalizers");
  }

  Future<Tables.Node> locked(String id) {
    return nodes.find(Filter.eq("id", id)).lock(LockMode.UPDATE).first()
        .map(found -> found.orElseThrow(() -> new NotFoundException("node", id)));
  }

  Future<Page<Node>> page(Filter base, PageRequest request) {
    if (request.orderBy() != null && !request.orderBy().isBlank()) {
      return Future.failedFuture(new InvalidArgumentException("Pages of nodes are ordered by name only",
          List.of(new Violation("orderBy", "empty"))));
    }
    Filter where;
    try {
      where = filter.of(request.filter()).map(narrowing -> Filter.and(base, narrowing)).orElse(base);
    } catch (InvalidArgumentException invalid) {
      return Future.failedFuture(invalid);
    }
    return read(where).page(new PageRequest("", "name", request.pageSize(), request.pageToken()))
        .map(page -> new Page<>(page.items().stream().map(this::node).toList(), page.nextPageToken(),
            page.totalSize()));
  }

  Future<Void> touch(String id) {
    return nodes.update()
        .where(Filter.eq("id", id))
        .set(Assignments.none().increment("revision", 1).set("update_time", clock.instant()))
        .execute()
        .mapEmpty();
  }

  Node node(Tables.Node row) {
    return new DatabaseNode(this, row);
  }

  List<Violation> violations(String name, Metadata metadata) {
    List<Violation> violations = new ArrayList<>();
    if (!NAME.matcher(name).matches()) {
      violations.add(new Violation("name", "1 to 255 letters, digits, '.', '_', '~' and '-'"));
    }
    violations.addAll(violations(metadata));
    return violations;
  }

  List<Violation> violations(Metadata metadata) {
    List<Violation> violations = new ArrayList<>();
    if (metadata.displayName().length() > DISPLAY_NAME) {
      violations.add(new Violation("metadata.displayName", "at most " + DISPLAY_NAME + " characters"));
    }
    if (metadata.labels().size() > LABELS) {
      violations.add(new Violation("metadata.labels", "at most " + LABELS + " labels"));
    }
    metadata.labels().forEach((key, value) -> {
      if (!LABEL_KEY.matcher(key).matches() || !LABEL_VALUE.matcher(value).matches()) {
        violations.add(new Violation("metadata.labels", "keys of 1 to 63 lowercase letters, digits, '_' and '-' that "
            + "start with a letter, and values of up to 63 of them; " + key + " is not"));
      }
    });
    int size = metadata.annotations().entrySet().stream()
        .mapToInt(annotation -> annotation.getKey().length() + annotation.getValue().length())
        .sum();
    if (size > ANNOTATIONS || metadata.annotations().containsKey("")) {
      violations.add(new Violation("metadata.annotations", "keys that are not empty, and at most " + ANNOTATIONS
          + " characters of keys and values together"));
    }
    return violations;
  }

  private List<Violation> violations(Model model) {
    List<Violation> violations = new ArrayList<>();
    if (model.types().isEmpty()) {
      violations.add(new Violation("types", "at least one type"));
    }
    Set<String> names = new HashSet<>();
    for (NodeType type : model.types()) {
      if (!TYPE.matcher(type.name()).matches()) {
        violations.add(new Violation("types", "names of 1 to 63 lowercase letters, digits and '_' that start with a "
            + "letter; " + type.name() + " is not"));
      }
      if (!names.add(type.name())) {
        violations.add(new Violation("types", "each type once; " + type.name() + " is declared twice"));
      }
    }
    for (NodeType type : model.types()) {
      String field = "types." + type.name();
      if (type.retention().isNegative()) {
        violations.add(new Violation(field + ".retention", "zero or a positive duration"));
      }
      Set<Class<?>> kinds = new HashSet<>();
      boolean placeable = false;
      for (Rule rule : type.rules()) {
        if (!kinds.add(rule.getClass())) {
          violations.add(new Violation(field + ".rules", "each kind of rule once; " + rule.getClass().getSimpleName()
              + " is there twice"));
        }
        switch (rule) {
          case Root root -> placeable = true;
          case Parents parents -> {
            placeable = placeable || !parents.types().isEmpty();
            parents.types().stream().filter(parent -> !names.contains(parent)).forEach(parent -> violations.add(
                new Violation(field + ".rules", "parents that the model declares; " + parent + " is not")));
          }
          case Children children -> children.types().stream().filter(child -> !names.contains(child))
              .forEach(child -> violations.add(new Violation(field + ".rules", "children that the model declares; "
                  + child + " is not")));
          case Depth depth -> {
            if (depth.levels() < 0 || depth.levels() > Placement.MAX_DEPTH) {
              violations.add(new Violation(field + ".rules", "a depth from 0 to " + Placement.MAX_DEPTH));
            }
          }
        }
      }
      if (!placeable) {
        violations.add(new Violation(field + ".rules", "Root, or Parents with at least one type"));
      }
    }
    return violations;
  }

  private Future<Optional<Tables.Node>> parent(Optional<String> id) {
    if (id.isEmpty()) {
      return Future.succeededFuture(Optional.empty());
    }
    return locked(id.get()).map(parent -> {
      if (!parent.getState().equals(State.ACTIVE.name())) {
        throw new FailedPreconditionException(Reason.STATE.name(), "The node " + parent.getId() + " is "
            + parent.getState() + ", and a node can be created only under an active node");
      }
      return Optional.of(parent);
    });
  }

  private Future<Long> purged(long count) {
    return nodes.find(Filter.and(Filter.eq("state", State.DELETED.name()), Filter.lte("purge_time", clock.instant())))
        .limit(PURGED_AT_ONCE)
        .list()
        .compose(candidates -> {
          Future<Long> purged = Future.succeededFuture(0L);
          for (Tables.Node candidate : candidates) {
            purged = purged.compose(sum -> purged(candidate).map(one -> sum + one));
          }
          return purged;
        })
        .compose(purged -> purged == 0 ? Future.succeededFuture(count) : purged(count + purged));
  }

  private Future<Long> purged(Tables.Node candidate) {
    String id = candidate.getId();
    return database.transaction(transaction -> nodes.find(Filter.eq("parent_id", id)).exists()
        .compose(children -> children
            ? Future.succeededFuture(0L)
            : holds.delete().where(Filter.eq("node_id", id)).execute()
                .compose(deleted -> finalizers.delete().where(Filter.eq("node_id", id)).execute())
                .compose(deleted -> nodes.delete()
                    .where(Filter.and(Filter.eq("id", id), Filter.eq("state", State.DELETED.name())))
                    .execute())));
  }
}
