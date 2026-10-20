package dev.horizon.ganzir.database;

import dev.horizon.cinder.Backend;
import dev.horizon.cinder.Database;
import dev.horizon.ganzir.database.schema.v1.Tables;

record NodeBackend(Database database) implements Backend<Tables.Node, String> {

  @Override
  public Class<Tables.Node> type() {
    return Tables.Node.class;
  }
}
