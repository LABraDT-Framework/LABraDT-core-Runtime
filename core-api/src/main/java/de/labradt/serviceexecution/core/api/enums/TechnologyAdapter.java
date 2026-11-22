package de.labradt.serviceexecution.core.api.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.io.Serializable;
import java.util.Collection;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Technology adapter of a service: the runner inside its category (SCRIPT/LUA, CONTAINER/DOCKER, EXTERNAL/REST, ...).
 *
 * <p>The categories ({@link ExecutionCategory}) are fixed; the adapters (languages, runners) are open.
 * This class is an "open enum": the built-in adapters are constants as before ({@code TechnologyAdapter.LUA}), and a runtime
 * plugin defines a new one, e.g. a Python interpreter:
 * {@code public static final TechnologyAdapter PYTHON = TechnologyAdapter.define("PYTHON", ExecutionCategory.SCRIPT);}.
 * Instances are canonical, so {@code ==} and map keys work like with an enum.
 */
public final class TechnologyAdapter implements Serializable, Comparable<TechnologyAdapter> {

    private static final long serialVersionUID = 1L;
    private static final Pattern NAME = Pattern.compile("[A-Z][A-Z0-9_]{0,40}");
    private static final Map<String, TechnologyAdapter> VALUES = new ConcurrentHashMap<>();

    public static final TechnologyAdapter LUA = define("LUA", ExecutionCategory.SCRIPT);
    public static final TechnologyAdapter BASH = define("BASH", ExecutionCategory.SCRIPT);
    public static final TechnologyAdapter DOCKER = define("DOCKER", ExecutionCategory.CONTAINER);
    public static final TechnologyAdapter REST = define("REST", ExecutionCategory.EXTERNAL);

    private final String name;
    /** The category this adapter belongs to; null while only known by name (its plugin is not loaded). */
    public volatile ExecutionCategory executionCategory;

    private TechnologyAdapter(String name, ExecutionCategory executionCategory) {
        this.name = name;
        this.executionCategory = executionCategory;
    }

    /** Defines an adapter of a category (plugins call this in a static field); returns the canonical instance. */
    public static TechnologyAdapter define(String name, ExecutionCategory executionCategory) {
        String key = normalize(name);
        TechnologyAdapter adapter = VALUES.computeIfAbsent(key, k -> new TechnologyAdapter(k, executionCategory));
        if (executionCategory != null) {
            if (adapter.executionCategory == null) {
                adapter.executionCategory = executionCategory;
            } else if (adapter.executionCategory != executionCategory) {
                throw new IllegalStateException("Adapter " + key + " is already defined for category " + adapter.executionCategory);
            }
        }
        return adapter;
    }

    /** Looks an adapter up by name, like {@code Enum.valueOf}; unknown names are kept (their plugin may load later). */
    @JsonCreator
    public static TechnologyAdapter valueOf(String name) {
        return define(name, null);
    }

    public static Collection<TechnologyAdapter> values() {
        return Collections.unmodifiableCollection(VALUES.values());
    }

    @JsonValue
    public String name() {
        return name;
    }

    @Override
    public String toString() {
        return name;
    }

    @Override
    public int compareTo(TechnologyAdapter other) {
        return name.compareTo(other.name);
    }

    private Object readResolve() {
        return define(name, executionCategory);
    }

    private static String normalize(String name) {
        if (name == null) throw new IllegalArgumentException("Name must not be null");
        String key = name.trim().toUpperCase(Locale.ROOT);
        if (!NAME.matcher(key).matches()) throw new IllegalArgumentException("Invalid adapter name '" + name + "' (use A-Z, 0-9 and _)");
        return key;
    }
}
