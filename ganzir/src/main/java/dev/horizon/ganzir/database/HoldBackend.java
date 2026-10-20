package dev.horizon.ganzir.database;

import dev.horizon.cinder.Backend;
import dev.horizon.cinder.Database;
import dev.horizon.ganzir.database.schema.v1.Tables;

record HoldBackend(Database database) implements Backend<Tables.Hold, HoldBackend.Id> {

  @Override
  public Class<Tables.Hold> type() {
    return Tables.Hold.class;
  }

  record Id(String nodeId, String id) {
  }
}
