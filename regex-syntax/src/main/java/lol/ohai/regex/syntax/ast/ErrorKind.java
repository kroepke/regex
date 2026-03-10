package lol.ohai.regex.syntax.ast;

/**
 * The type of an error that occurred while building an AST.
 */
public sealed interface ErrorKind {
    record CaptureLimitExceeded() implements ErrorKind {}
    record ClassEscapeInvalid() implements ErrorKind {}
    record ClassRangeInvalid() implements ErrorKind {}
    record ClassRangeLiteral() implements ErrorKind {}
    record ClassUnclosed() implements ErrorKind {}
    record DecimalEmpty() implements ErrorKind {}
    record DecimalInvalid() implements ErrorKind {}
    record EscapeHexEmpty() implements ErrorKind {}
    record EscapeHexInvalid() implements ErrorKind {}
    record EscapeHexInvalidDigit() implements ErrorKind {}
    record EscapeUnexpectedEof() implements ErrorKind {}
    record EscapeUnrecognized() implements ErrorKind {}
    record FlagDanglingNegation() implements ErrorKind {}
    record FlagDuplicate(Span original) implements ErrorKind {}
    record FlagRepeatedNegation(Span original) implements ErrorKind {}
    record FlagUnexpectedEof() implements ErrorKind {}
    record FlagUnrecognized() implements ErrorKind {}
    record GroupNameDuplicate(Span original) implements ErrorKind {}
    record GroupNameEmpty() implements ErrorKind {}
    record GroupNameInvalid() implements ErrorKind {}
    record GroupNameUnexpectedEof() implements ErrorKind {}
    record GroupUnclosed() implements ErrorKind {}
    record GroupUnopened() implements ErrorKind {}
    record NestLimitExceeded(int limit) implements ErrorKind {}
    record RepetitionCountInvalid() implements ErrorKind {}
    record RepetitionCountDecimalEmpty() implements ErrorKind {}
    record RepetitionCountUnclosed() implements ErrorKind {}
    record RepetitionMissing() implements ErrorKind {}
    record SpecialWordBoundaryUnclosed() implements ErrorKind {}
    record SpecialWordBoundaryUnrecognized() implements ErrorKind {}
    record SpecialWordOrRepetitionUnexpectedEof() implements ErrorKind {}
    record UnicodeClassInvalid() implements ErrorKind {}
    record UnsupportedBackreference() implements ErrorKind {}
    record UnsupportedLookAround() implements ErrorKind {}
}
