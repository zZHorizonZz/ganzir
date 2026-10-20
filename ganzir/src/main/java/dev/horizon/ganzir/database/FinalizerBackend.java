package dev.horizon.ganzir.database;

import dev.horizon.cinder.Backend;
import dev.horizon.cinder.Database;
import dev.horizon.ganzir.database.schema.v1.Tables;

record FinalizerBackend(Database database) implements Backend<Tables.Finalizer, FinalizerBackend.Id> {

  @Override
  public Class<Tables.Finalizer> type() {
    return Tables.Finalizer.class;
  }

  record Id(String nodeId, String name) {
  }
}
