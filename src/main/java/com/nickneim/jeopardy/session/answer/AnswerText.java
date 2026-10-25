package com.nickneim.jeopardy.session.answer;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Turns answer and response text into the words the matcher compares, and
 * decides when two words are close enough.
 *
 * <p>Both sides are normalized the same way: accents and case are dropped,
 * "&amp;" is "and", periods, apostrophes, quotes and hyphens join what's
 * around them ("U.S." is "us", "O'Rourke" is "orourke", "A-line" is "aline"),
 * and the articles a, an and the are left out (except an "a" that names the
 * letter: "Vitamin A"). Numbers keep their sign,
 * decimal point and fraction slash ("-4", "4.0", "2/3"); "1,001" is "1001",
 * and "$" and "%" (or the word "percent" after a number) are dropped. Commas,
 * semicolons and other slashes become {@link #SEPARATOR} tokens, where a
 * response may list several items.
 */
final class AnswerText {

    /** A comma, semicolon or slash: a place where a response can list several items. */
    static final String SEPARATOR = ",";

    private static final Set<String> ARTICLES = Set.of("a", "an", "the");
    // Words that change a number's meaning, so they must match exactly like
    // digits do: "200 million" isn't "200 billion", although they're 1 edit apart.
    private static final Set<String> MAGNITUDES = Set.of("hundred", "thousand", "million", "billion", "trillion");

    // Placeholders that protect number punctuation from the cleanup below.
    private static final char DECIMAL_POINT = '\uE000';
    private static final char FRACTION_SLASH = '\uE001';
    private static final char MINUS = '\uE002';

    private static final Pattern MARKS = Pattern.compile("\\p{M}");
    private static final Pattern THOUSANDS_COMMA = Pattern.compile("(?<=\\d),(?=\\d{3}(?!\\d))");
    private static final Pattern HYPHEN_BETWEEN_DIGITS = Pattern.compile("(?<=\\d)-(?=\\d)");
    private static final Pattern POINT_BETWEEN_DIGITS = Pattern.compile("(?<=\\d)\\.(?=\\d)");
    private static final Pattern SLASH_BETWEEN_DIGITS = Pattern.compile("(?<=\\d)/(?=\\d)");
    private static final Pattern MINUS_SIGN = Pattern.compile("(?<![\\p{L}\\p{N}'])-(?=\\d)");
    private static final Pattern JOINERS = Pattern.compile("[.'\"`-]");
    private static final Pattern SEPARATORS = Pattern.compile("[,;/]");
    private static final Pattern OTHER = Pattern.compile(
            "[^\\p{L}\\p{N}," + DECIMAL_POINT + FRACTION_SLASH + MINUS + "]+"
    );

    private static final Pattern SYMBOL_PUNCTUATION = Pattern.compile(
            "^[\\s\"'‘’“”?!.]+|[\\s\"'‘’“”?!.]+$"
    );

    private static final Pattern DECADE = Pattern.compile("(\\d\\d)?\\d0s");
    // A Roman numeral long enough to allow typos ("VIII", "XVII"); shorter
    // words allow none anyway. Real words aren't valid numerals ("civil", "mild").
    private static final Pattern ROMAN_NUMERAL = Pattern.compile(
            "(?=[ivxlcdm]{4})m{0,4}(cm|cd|d?c{0,3})(xc|xl|l?x{0,3})(ix|iv|v?i{0,3})"
    );
    private static final Pattern NUMBER = Pattern.compile("-?\\d+(?:[./]\\d+)?(?:st|nd|rd|th)?");

    private AnswerText() {
    }

    /** The normalized words of a text, with {@link #SEPARATOR} tokens where it lists items. */
    static List<String> tokens(String text) {
        return tokens(text, true);
    }

    /**
     * Like {@link #tokens(String)}, for a piece of a longer text; {@code endsText}
     * says whether anything follows it, which decides if a last "a" is a letter.
     */
    static List<String> tokens(String text, boolean endsText) {
        String s = Normalizer.normalize(text, Normalizer.Form.NFKC).replace('\u2044', '/');
        s = MARKS.matcher(Normalizer.normalize(s, Normalizer.Form.NFD)).replaceAll("");
        s = s.toLowerCase(Locale.ROOT)
                .replace('\u2018', '\'')
                .replace('\u2019', '\'')
                .replace('\u201C', '"')
                .replace('\u201D', '"')
                .replace("&", " and ")
                .replace("$", "")
                .replace("%", " ");
        s = THOUSANDS_COMMA.matcher(s).replaceAll("");
        s = HYPHEN_BETWEEN_DIGITS.matcher(s).replaceAll(" ");
        s = POINT_BETWEEN_DIGITS.matcher(s).replaceAll(String.valueOf(DECIMAL_POINT));
        s = SLASH_BETWEEN_DIGITS.matcher(s).replaceAll(String.valueOf(FRACTION_SLASH));
        s = MINUS_SIGN.matcher(s).replaceAll(String.valueOf(MINUS));
        s = JOINERS.matcher(s).replaceAll("");
        s = SEPARATORS.matcher(s).replaceAll(" " + SEPARATOR + " ");
        s = OTHER.matcher(s).replaceAll(" ");
        s = s.replace(DECIMAL_POINT, '.').replace(FRACTION_SLASH, '/').replace(MINUS, '-');

        List<String> tokens = new ArrayList<>();
        String[] words = s.trim().split("\\s+");
        for (int i = 0; i < words.length; i++) {
            String token = words[i];
            if (token.isEmpty() || ARTICLES.contains(token) && !isLetter(words, i, endsText)) {
                continue;
            }
            if (!token.equals("percent") || tokens.isEmpty() || !hasDigit(tokens.getLast())) {
                tokens.add(token);
            }
        }

        return tokens;
    }

    /**
     * True for an "a" that names the letter: after a word and at the end or
     * before a separator ("Vitamin A", "type A, type B"). An article always
     * comes before what it goes with.
     */
    private static boolean isLetter(String[] words, int i, boolean endsText) {
        return words[i].equals("a")
                && i > 0 && !isSeparator(words[i - 1])
                && (i == words.length - 1 ? endsText : words[i + 1].equals(SEPARATOR));
    }

    /**
     * Like {@link #tokens}, for a whole answer or response. An answer can be
     * nothing but an article ("A", "The The") or a symbol ("@"); then that's
     * what has to be said.
     */
    static List<String> wholeTokens(String text) {
        List<String> tokens = tokens(text);
        if (!tokens.isEmpty() || text.isBlank()) {
            return tokens;
        }
        List<String> articles = List.of(text.toLowerCase(Locale.ROOT).replaceAll("[^a-z]+", " ").trim().split(" "));
        if (!articles.getFirst().isEmpty() && ARTICLES.containsAll(articles)) {
            return articles;
        }
        // "What is @?" says "@": quotes and sentence punctuation around a symbol aren't part of it.
        String symbol = SYMBOL_PUNCTUATION.matcher(text).replaceAll("");
        return List.of((symbol.isEmpty() ? text.strip() : symbol).replaceAll("\\s+", " ").toLowerCase(Locale.ROOT));
    }

    /** True for a token a response may use between the items of a list. */
    static boolean isSeparator(String token) {
        return token.equals(SEPARATOR) || token.equals("and") || token.equals("or");
    }

    /**
     * True for a word that must be matched exactly: a number, a word like
     * "million", or a Roman numeral ("Henry VII" isn't "Henry VIII").
     */
    static boolean isExact(String token) {
        return hasDigit(token) || MAGNITUDES.contains(token) || ROMAN_NUMERAL.matcher(token).matches();
    }

    /** True for a number on its own: "1970", "-4", "4.0", "2/3", "50th". */
    static boolean isNumber(String token) {
        return NUMBER.matcher(token).matches() && !isDecade(token);
    }

    /**
     * True if a response word {@code given} is close enough to an answer word
     * {@code expected}. Numbers and words like "million" must be equal, and
     * decades must name the same one; other words allow a few typos, by the
     * length of the expected word ({@link #allowedEdits(int)}).
     */
    static boolean isClose(String given, String expected) {
        if (isDecade(expected) || isDecade(given)) {
            return isSameDecade(given, expected);
        }
        if (isExact(expected) || isExact(given)) {
            return given.equals(expected);
        }
        int allowed = allowedEdits(expected.length());
        return distance(given, expected, allowed) <= allowed;
    }

    /** Typos allowed in a word of this length: none up to 3 letters, 1 up to 6, then 2. */
    static int allowedEdits(int length) {
        if (length <= 3) {
            return 0;
        }
        return length <= 6 ? 1 : 2;
    }

    private static boolean isDecade(String token) {
        return DECADE.matcher(token).matches();
    }

    /**
     * "1980s" accepts "1980s" and "80s"; "60s" (stored without a century)
     * accepts "60s" and "1960s". Decades are never typos of each other.
     */
    private static boolean isSameDecade(String given, String expected) {
        if (!isDecade(given) || !isDecade(expected)) {
            return false;
        }
        if (given.equals(expected)) {
            return true;
        }
        if (expected.length() == 5) {
            return given.length() == 3 && expected.endsWith(given);
        }
        return given.equals("19" + expected);
    }

    private static boolean hasDigit(String token) {
        return token.chars().anyMatch(Character::isDigit);
    }

    /**
     * The edit distance between two strings, counting a swap of two adjacent
     * letters as one edit. Stops early and returns {@code limit + 1} once the
     * distance is known to be larger than {@code limit}.
     */
    static int distance(String a, String b, int limit) {
        if (Math.abs(a.length() - b.length()) > limit) {
            return limit + 1;
        }
        int[][] d = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) {
            d[i][0] = i;
        }
        for (int j = 0; j <= b.length(); j++) {
            d[0][j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            int rowMin = Integer.MAX_VALUE;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) {
                    d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1);
                }
                rowMin = Math.min(rowMin, d[i][j]);
            }
            if (rowMin > limit) {
                return limit + 1;
            }
        }
        return d[a.length()][b.length()];
    }
}
