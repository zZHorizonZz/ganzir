package dev.horizon.ganzir.database;

import dev.horizon.cinder.Backend;
import dev.horizon.cinder.Database;
import dev.horizon.ganzir.database.schema.v1.Tables;

record LienBackend(Database database) implements Backend<Tables.Lien, LienBackend.Id> {

  @Override
  public Class<Tables.Lien> type() {
    return Tables.Lien.class;
  }

  record Id(String nodeId, String id) {
  }
}
