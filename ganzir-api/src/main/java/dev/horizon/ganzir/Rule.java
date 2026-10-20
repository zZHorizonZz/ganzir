package dev.horizon.ganzir;

/**
 * A check that every create, move and rename of a node of a type must pass. Ganzir runs the rules before it writes.
 */
public sealed interface Rule permits Root, Parents, Children, Depth {
}
