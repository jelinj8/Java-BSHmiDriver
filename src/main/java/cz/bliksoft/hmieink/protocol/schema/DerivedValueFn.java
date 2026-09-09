package cz.bliksoft.hmieink.protocol.schema;

import java.util.Map;

/**
 * Computes a {@link FieldSpec#derived} field's value at encode time from the other field values
 * already supplied (by name - {@link PayloadCodec#encode} evaluates fields in declared order, so
 * only earlier fields are guaranteed present).
 */
public interface DerivedValueFn {
	Object compute(Map<String, Object> fields);
}
