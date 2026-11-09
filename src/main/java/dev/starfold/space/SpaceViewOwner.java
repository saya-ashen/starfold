package dev.starfold.space;

import java.lang.ref.WeakReference;

/** Entity equality uses reused entity ids; a weak-key View must also check object ownership. */
record SpaceViewOwner<T>(WeakReference<T> reference) {
    SpaceViewOwner(T owner) {
        this(new WeakReference<>(owner));
    }

    boolean owns(T candidate) {
        return reference.get() == candidate;
    }
}
