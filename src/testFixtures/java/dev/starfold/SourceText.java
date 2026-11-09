package dev.starfold;

/**
 * A main source as tests read it: every search ignores whitespace on both sides, so an assertion about what the code
 * says does not depend on how the code is laid out. Positions count characters of the text without whitespace.
 */
public final class SourceText implements CharSequence {
    private final String text;

    private SourceText(String squeezed) {
        text = squeezed;
    }

    public static SourceText of(String source) {
        return new SourceText(squeeze(source));
    }

    private static String squeeze(String s) {
        return s.replaceAll("\\s+", "");
    }

    public boolean contains(String part) {
        return text.contains(squeeze(part));
    }

    public int indexOf(String part) {
        return text.indexOf(squeeze(part));
    }

    public int indexOf(String part, int from) {
        return text.indexOf(squeeze(part), from);
    }

    public int lastIndexOf(String part) {
        return text.lastIndexOf(squeeze(part));
    }

    public SourceText substring(int from) {
        return new SourceText(text.substring(from));
    }

    public SourceText substring(int from, int to) {
        return new SourceText(text.substring(from, to));
    }

    public SourceText plus(SourceText more) {
        return new SourceText(text + more.text);
    }

    public String[] split(String regex, int limit) {
        return text.split(regex, limit);
    }

    @Override
    public char charAt(int index) {
        return text.charAt(index);
    }

    @Override
    public SourceText subSequence(int from, int to) {
        return substring(from, to);
    }

    @Override
    public int length() {
        return text.length();
    }

    @Override
    public String toString() {
        return text;
    }
}
