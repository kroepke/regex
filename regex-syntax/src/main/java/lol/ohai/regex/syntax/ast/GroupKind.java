package lol.ohai.regex.syntax.ast;

/**
 * The kind of a group.
 */
public sealed interface GroupKind {
    /** {@code (a)} */
    record CaptureIndex(int index) implements GroupKind {}

    /** {@code (?P<name>a)} or {@code (?<name>a)} */
    record CaptureName(boolean startsWithP, String name, int index) implements GroupKind {}

    /** {@code (?:a)} and {@code (?i:a)} */
    record NonCapturing(FlagItems flags) implements GroupKind {}
}
