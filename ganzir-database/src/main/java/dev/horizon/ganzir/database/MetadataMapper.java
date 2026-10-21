package dev.horizon.ganzir.database;

import dev.horizon.cinder.protobuf.ProtobufMapper;
import dev.horizon.ganzir.database.schema.v1.Tables;
import dev.horizon.pyrite.common.Metadata;

final class MetadataMapper implements ProtobufMapper<Metadata, Tables.NodeOrBuilder, Tables.Node.Builder> {

  private final LabelIndex labels = new LabelIndex();

  @Override
  public Metadata read(Tables.NodeOrBuilder row) {
    return new Metadata(row.getDisplayName(), row.getLabelsMap(), row.getAnnotationsMap());
  }

  @Override
  public Tables.Node.Builder write(Tables.Node.Builder row, Metadata metadata) {
    return row.setDisplayName(metadata.displayName())
        .clearLabels()
        .putAllLabels(metadata.labels())
        .setLabelIndex(labels.of(metadata.labels()))
        .clearAnnotations()
        .putAllAnnotations(metadata.annotations());
  }
}
