package dev.horizon.ganzir.database;

import dev.horizon.cinder.Backend;
import dev.horizon.cinder.Database;
import dev.horizon.cinder.DuplicateKeyException;
import dev.horizon.ganzir.Model;
import dev.horizon.ganzir.database.schema.v1.Tables;
import dev.horizon.pyrite.common.AbortedException;
import dev.horizon.pyrite.common.Revision;
import io.vertx.core.Future;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

record ModelBackend(Database database) implements Backend<Tables.Model, Long> {

  private static final ModelMapper MAPPER = new ModelMapper();

  @Override
  public Class<Tables.Model> type() {
    return Tables.Model.class;
  }

  Future<Void> declare(Schema schema, Instant now) {
    return findAll().orderBy("version desc").first().compose(latest -> {
      long version = latest.map(row -> row.getVersion() + 1).orElse(1L);
      return insert(MAPPER.write(Tables.Model.newBuilder()
          .setVersion(version)
          .setCreateTime(now.toString()), schema.model()).build())
          .recover(failure -> Future.failedFuture(failure instanceof DuplicateKeyException
              ? new AbortedException("model", "latest", new Revision(version - 1))
              : failure));
    }).mapEmpty();
  }

  Future<Optional<Model>> latest() {
    return findAll().orderBy("version desc").first().map(latest -> latest.map(MAPPER::read));
  }

  Future<Schema> schema() {
    return latest().map(model -> new Schema(model.orElse(new Model(List.of()))));
  }
}
