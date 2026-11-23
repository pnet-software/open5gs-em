package gr.pnet.em.support;

import java.util.List;
import java.util.function.Function;

/** One page of a collection, and how many items the whole collection has. */
public record Slice<T>(List<T> items, long total) {

    public <R> Slice<R> map(Function<T, R> mapper) {
        return new Slice<>(items.stream().map(mapper).toList(), total);
    }
}
