package dev.horizon.ganzir.database;

import dev.horizon.aquery.InvalidQueryException;
import dev.horizon.aquery.aip160.Filter.And;
import dev.horizon.aquery.aip160.Filter.Condition;
import dev.horizon.aquery.aip160.Filter.Not;
import dev.horizon.aquery.aip160.Filter.Or;
import dev.horizon.aquery.aip160.Filter.Restriction;
import dev.horizon.aquery.aip160.Filter.StringLiteral;
import dev.horizon.aquery.aip160.Filter.Text;
import dev.horizon.aquery.aip160.Filter.Value;
import dev.horizon.aquery.aip160.Operator;
import dev.horizon.aquery.ebnf.EbnfFilterParser;
import dev.horizon.basepoint.Filter;
import dev.horizon.pyrite.common.InvalidArgumentException;
import dev.horizon.pyrite.common.Violation;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class NodeFilter {

  private static final Map<String, String> COLUMNS = Map.of("type", "type", "state", "state", "name", "name",
      "display_name", "display_name", "displayName", "display_name");
  private static final String LABELS = "labels";
  private static final String LABEL_INDEX = "label_index";
  private static final String ANY = "*";

  private final EbnfFilterParser parser = new EbnfFilterParser();
  private final LabelIndex labels = new LabelIndex();

  Optional<Filter> of(String text) {
    if (text == null || text.isBlank()) {
      return Optional.empty();
    }
    try {
      return Optional.of(condition(parser.parse(text).condition()));
    } catch (InvalidQueryException invalid) {
      throw unsupported(invalid.getMessage());
    }
  }

  private Filter condition(Condition condition) {
    return switch (condition) {
      case And and -> Filter.and(and.operands().stream().map(this::condition).toArray(Filter[]::new));
      case Or or -> Filter.or(or.operands().stream().map(this::condition).toArray(Filter[]::new));
      case Not not -> Filter.not(condition(not.operand()));
      case Restriction restriction -> restriction(restriction);
      default -> throw unsupported("A filter can only compare type, state, name, display_name and labels");
    };
  }

  private Filter restriction(Restriction restriction) {
    List<String> segments = restriction.fieldPath().segments();
    Operator operator = restriction.operator();
    String value = text(restriction.value());
    if (segments.size() == 1 && COLUMNS.containsKey(segments.getFirst())) {
      String column = COLUMNS.get(segments.getFirst());
      return switch (operator) {
        case EQUALS -> Filter.eq(column, value);
        case NOT_EQUALS -> Filter.ne(column, value);
        default -> throw unsupported("The field " + segments.getFirst() + " can only be compared with = and !=");
      };
    }
    if (segments.size() == 1 && segments.getFirst().equals(LABELS) && operator == Operator.HAS) {
      return Filter.like(LABEL_INDEX, labels.pattern(value));
    }
    if (segments.size() == 2 && segments.getFirst().equals(LABELS)) {
      String key = segments.get(1);
      return switch (operator) {
        case EQUALS -> Filter.like(LABEL_INDEX, labels.pattern(key, value));
        case NOT_EQUALS -> Filter.not(Filter.like(LABEL_INDEX, labels.pattern(key, value)));
        case HAS -> {
          if (!value.equals(ANY)) {
            throw unsupported("A label can only be tested for presence with labels." + key + ":*");
          }
          yield Filter.like(LABEL_INDEX, labels.pattern(key));
        }
        default -> throw unsupported("A label can only be compared with =, != and :*");
      };
    }
    throw unsupported("A filter can only compare type, state, name, display_name and labels, not "
        + String.join(".", segments));
  }

  private String text(Value value) {
    return switch (value) {
      case StringLiteral literal when literal.wildcards() && literal.value().contains(ANY) ->
        throw unsupported("A filter cannot use wildcards");
      case StringLiteral literal -> literal.value();
      case Text text -> text.text();
      default -> throw unsupported("A filter can only compare with text");
    };
  }

  private InvalidArgumentException unsupported(String detail) {
    return new InvalidArgumentException("The filter is not valid: " + detail,
        List.of(new Violation("filter", detail)));
  }
}
