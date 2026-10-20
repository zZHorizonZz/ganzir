module dev.horizon.ganzir.api {
  requires transitive dev.horizon.pyrite.common;
  requires transitive dev.horizon.aquery.api;
  requires transitive io.vertx.core;

  exports dev.horizon.ganzir;
}
