package dev.reef.util;

import java.util.Iterator;

/**
 * Wraps a single-use iterator as an Iterable. From Pufferfish (GPL-3.0).
 */
public record IterableWrapper<T>(Iterator<T> iterator) implements Iterable<T> {
}
