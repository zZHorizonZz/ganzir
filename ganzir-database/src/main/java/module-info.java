module dev.horizon.ganzir.database {
  requires transitive dev.horizon.ganzir.api;
  requires transitive dev.horizon.cinder;
  requires transitive dev.horizon.cinder.protobuf;
  requires dev.horizon.aquery.ebnf;
  requires dev.horizon.leadseal.protobuf;
  requires com.google.protobuf;

  exports dev.horizon.ganzir.database;

  opens dev.horizon.ganzir.database.schema.v1 to com.google.protobuf, dev.horizon.cinder.protobuf;
}
