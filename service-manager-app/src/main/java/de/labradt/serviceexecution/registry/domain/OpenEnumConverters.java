package de.labradt.serviceexecution.registry.domain;

import de.labradt.serviceexecution.core.api.enums.TechnologyAdapter;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Stores the open enum TechnologyAdapter by name, as @Enumerated(STRING) did. */
public final class OpenEnumConverters {

    private OpenEnumConverters() {
    }

    @Converter(autoApply = true)
    public static class AdapterConverter implements AttributeConverter<TechnologyAdapter, String> {
        @Override
        public String convertToDatabaseColumn(TechnologyAdapter value) {
            return value == null ? null : value.name();
        }

        @Override
        public TechnologyAdapter convertToEntityAttribute(String value) {
            return value == null ? null : TechnologyAdapter.valueOf(value);
        }
    }
}
