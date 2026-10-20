package dev.horizon.ganzir.database;

import dev.horizon.cinder.Backend;
import dev.horizon.cinder.Database;
import dev.horizon.ganzir.database.schema.v1.Tables;

record ModelBackend(Database database) implements Backend<Tables.Model, Long> {

  @Override
  public Class<Tables.Model> type() {
    return Tables.Model.class;
  }
}
