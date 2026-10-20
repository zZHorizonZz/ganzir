package dev.horizon.ganzir.database;

import dev.horizon.ganzir.Metadata;
import dev.horizon.ganzir.NodeData;
import dev.horizon.ganzir.State;
import dev.horizon.ganzir.database.schema.v1.Tables;
import dev.horizon.pyrite.common.Revision;
import java.time.Instant;
import java.util.Optional;

abstract class AbstractNode implements NodeData {

  private static final MetadataMapper METADATA = new MetadataMapper();

  abstract Tables.NodeOrBuilder row();

  @Override
  public String id() {
    return row().getId();
  }

  @Override
  public String type() {
    return row().getType();
  }

  @Override
  public String name() {
    return row().getName();
  }

  @Override
  public Optional<String> parent() {
    return row().hasParentId() ? Optional.of(row().getParentId()) : Optional.empty();
  }

  @Override
  public Metadata metadata() {
    return METADATA.read(row());
  }

  @Override
  public State state() {
    return State.valueOf(row().getState());
  }

  @Override
  public Revision revision() {
    return new Revision(row().getRevision());
  }

  @Override
  public Instant createTime() {
    return Instant.parse(row().getCreateTime());
  }

  @Override
  public Instant updateTime() {
    return Instant.parse(row().getUpdateTime());
  }

  @Override
  public Optional<Instant> deleteTime() {
    return row().hasDeleteTime() ? Optional.of(Instant.parse(row().getDeleteTime())) : Optional.empty();
  }

  NodePath path() {
    return new NodePath(row().getPath());
  }
}
