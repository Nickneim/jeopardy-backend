package com.nickneim.jeopardy.session.answer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A stored answer, parsed for matching.
 *
 * <p>"(N of) A | B | C" is a list of different answers: a correct response
 * names at least {@code required} of the options, and nothing that isn't one.
 * Any other answer is a single option. "[A | B]" anywhere is one thing worded
 * differently ("a linen [closet | cupboard]", "(2 of) coroner | [coronet |
 * coronel]"): any wording counts, and they all count as the same option. An
 * option with a slash outside parentheses ("bear/bare") can also be given
 * either way. Words in parentheses are optional,
 * one by one, but an option always needs its words outside them:
 * "(Abraham) Lincoln" accepts "Lincoln" and "Abraham Lincoln", not "Abraham".
 *
 * @param question whether the answer is itself a question ("Who's the Boss?"),
 *                 which counts as the question form when said bare
 */
record ExpectedAnswer(int required, List<Option> options, boolean question) {

    private static final Pattern LIST = Pattern.compile("^\\s*\\((\\d+) of\\)\\s*(.*)$", Pattern.DOTALL);
    // Longer responses are never right, and the cost of matching grows with length.
    private static final int MAX_RESPONSE_TOKENS = 60;

    static ExpectedAnswer parse(String answer) {
        int required = 1;
        List<String> texts = List.of(answer);

        Matcher list = LIST.matcher(answer);
        if (list.matches()) {
            required = Math.max(1, Integer.parseInt(list.group(1)));
            texts = splitOutsideBrackets(list.group(2));
        }

        List<Option> options = texts.stream()
                .map(Option::parse)
                .filter(option -> !option.variants().isEmpty())
                .toList();

        return new ExpectedAnswer(
                Math.min(required, Math.max(1, options.size())),
                options,
                texts.stream()
                        .flatMap(text -> expandBrackets(text).stream())
                        .anyMatch(QuestionForm::isQuestion)
        );
    }

    /** Splits a list at "|", except inside "[...]": "coroner | [coronet | coronel]". */
    private static List<String> splitOutsideBrackets(String text) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth = Math.max(0, depth - 1);
            } else if (c == '|' && depth == 0) {
                parts.add(text.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(text.substring(start));
        return parts;
    }

    // An option with more ways to be worded than this keeps only the first ones.
    private static final int MAX_WORDINGS = 64;

    /**
     * Every way to word a text with "[A | B]" groups, which mean the same
     * thing said differently: "a linen [closet | cupboard]" is "a linen
     * closet" or "a linen cupboard". Groups don't nest; an unclosed "[" is
     * just text.
     */
    static List<String> expandBrackets(String text) {
        int open = text.indexOf('[');
        int close = open < 0 ? -1 : text.indexOf(']', open);
        if (open < 0 || close < 0) {
            return List.of(text);
        }
        String before = text.substring(0, open);
        List<String> rest = expandBrackets(text.substring(close + 1));
        List<String> wordings = new ArrayList<>();
        for (String choice : text.substring(open + 1, close).split("\\|")) {
            for (String after : rest) {
                if (wordings.size() < MAX_WORDINGS) {
                    wordings.add(before + choice.strip() + after);
                }
            }
        }
        return wordings;
    }

    /** True if every option is a number on its own, so only an exact number will do. */
    boolean expectsNumber() {
        return !options.isEmpty() && options.stream()
                .flatMap(option -> option.variants().stream())
                .allMatch(Variant::isNumber);
    }

    /** The first wording of every option, to show the answer. */
    List<String> firstWordings() {
        return options.stream().map(Option::firstWording).toList();
    }

    /** The first wording of every option the response didn't name. */
    List<String> firstWordingsExcept(List<Named> named) {
        List<String> wordings = new ArrayList<>();
        for (int i = 0; i < options.size(); i++) {
            int option = i;
            if (named.stream().noneMatch(n -> n.option() == option)) {
                wordings.add(options.get(i).firstWording());
            }
        }
        return wordings;
    }

    /**
     * The options the response names, in the order it names them, each with
     * the wording it used; null unless it names at least {@code required}
     * different options and nothing else. The response is cut into pieces
     * that each match an option; separators (commas, slashes, "and", "or")
     * between pieces are skipped, and pieces may also follow each other with
     * no separator.
     */
    List<Named> match(List<String> response) {
        if (options.isEmpty() || response.isEmpty() || response.size() > MAX_RESPONSE_TOKENS) {
            return null;
        }

        int maxPieceLength = options.stream()
                .flatMap(option -> option.variants().stream())
                .mapToInt(variant -> variant.words().size())
                .max()
                .orElse(0) * 2 + 2;

        // reachable.get(i): the sets of options (as bit masks) that the first i
        // tokens can be cut into, each with the last step of one way to get there.
        List<Map<Long, Step>> reachable = new ArrayList<>();
        for (int i = 0; i <= response.size(); i++) {
            reachable.add(new LinkedHashMap<>());
        }
        reachable.getFirst().put(0L, null);

        for (int start = 0; start < response.size(); start++) {
            Map<Long, Step> masks = reachable.get(start);
            if (masks.isEmpty()) {
                continue;
            }
            if (AnswerText.isSeparator(response.get(start))) {
                for (long mask : masks.keySet()) {
                    reachable.get(start + 1).putIfAbsent(mask, new Step(start, mask, null));
                }
            }
            int last = Math.min(response.size(), start + maxPieceLength);
            for (int end = start + 1; end <= last; end++) {
                List<Named> matched = matchingOptions(response.subList(start, end));
                for (long mask : masks.keySet()) {
                    for (Named named : matched) {
                        // A list longer than 64 options shares the last bit; none comes close.
                        long bit = 1L << Math.min(named.option(), 63);
                        reachable.get(end).putIfAbsent(mask | bit, new Step(start, mask, named));
                    }
                }
            }
        }

        Long best = reachable.getLast().keySet().stream()
                .filter(mask -> Long.bitCount(mask) >= required)
                .max(Comparator.comparingInt(Long::bitCount))
                .orElse(null);
        if (best == null) {
            return null;
        }

        List<Named> said = new ArrayList<>();
        long mask = best;
        for (int end = response.size(); end > 0; ) {
            Step step = reachable.get(end).get(mask);
            if (step.named() != null) {
                said.add(step.named());
            }
            mask = step.previous();
            end = step.start();
        }
        Collections.reverse(said);
        // An option named twice is reported once.
        List<Named> named = new ArrayList<>();
        for (Named n : said) {
            if (named.stream().noneMatch(other -> other.option() == n.option())) {
                named.add(n);
            }
        }
        return named;
    }

    /** The options a piece of a response matches, each with the wording it matches. */
    private List<Named> matchingOptions(List<String> piece) {
        // "and" and "or" can start or end an option ("or" is an answer), commas can't.
        if (piece.getFirst().equals(AnswerText.SEPARATOR) || piece.getLast().equals(AnswerText.SEPARATOR)) {
            return List.of();
        }
        List<String> words = piece.stream().filter(token -> !token.equals(AnswerText.SEPARATOR)).toList();
        List<Named> matched = new ArrayList<>();
        for (int i = 0; i < options.size(); i++) {
            String wording = options.get(i).matchingWording(words);
            if (wording != null) {
                matched.add(new Named(i, wording));
            }
        }
        return matched;
    }

    /** An option a response names ({@code option} is its index), and the wording it matched. */
    record Named(int option, String wording) {}

    /** How {@link #match} reached a set of options: from {@code previous} at token {@code start}, naming {@code named} (null for a separator). */
    private record Step(int start, long previous, Named named) {}

    /** One option of an answer, with its wordings ("a linen [closet | cupboard]" and "bear/bare" have two). */
    record Option(List<Variant> variants) {

        static Option parse(String text) {
            return new Option(expandBrackets(text).stream()
                    .flatMap(wording -> splitAtSlashes(wording).stream())
                    .map(Variant::parse)
                    .filter(variant -> !variant.words().isEmpty())
                    .toList());
        }

        String firstWording() {
            return variants.getFirst().wording();
        }

        /**
         * The wording the words say, or null. A typo can be close to more
         * than one ("coronel" is one letter from "coronet"), so a wording
         * that has every word as said comes first.
         */
        String matchingWording(List<String> words) {
            List<Variant> matching = variants.stream().filter(variant -> variant.isMatchedBy(words)).toList();
            if (matching.isEmpty()) {
                return null;
            }
            return matching.stream()
                    .filter(variant -> variant.hasEvery(words))
                    .findFirst()
                    .orElse(matching.getFirst())
                    .wording();
        }

        /** Splits at slashes outside parentheses, except in a fraction like "8/12". */
        private static List<String> splitAtSlashes(String text) {
            List<String> parts = new ArrayList<>();
            int depth = 0;
            int start = 0;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth = Math.max(0, depth - 1);
                } else if (c == '/' && depth == 0 && !isBetweenDigits(text, i)) {
                    parts.add(text.substring(start, i));
                    start = i + 1;
                }
            }
            parts.add(text.substring(start));
            return parts;
        }

        private static boolean isBetweenDigits(String text, int i) {
            return i > 0 && i < text.length() - 1
                    && Character.isDigit(text.charAt(i - 1)) && Character.isDigit(text.charAt(i + 1));
        }
    }

    /**
     * One way to say an option. Each word is required ({@code group} -1) or
     * belongs to a parenthesized group; {@code parents} gives each group's
     * enclosing group (-1 at the top level). {@code wording} is the text it
     * was parsed from, to show to players.
     */
    record Variant(List<Word> words, List<Integer> parents, String wording) {

        // Each group can be left in or out when the response is compared as
        // one string; past this many, only all-in and all-out are tried.
        private static final int MAX_GROUP_COMBINATIONS_BITS = 6;

        static Variant parse(String text) {
            List<Word> words = new ArrayList<>();
            List<Integer> parents = new ArrayList<>();
            Deque<Integer> open = new ArrayDeque<>();
            StringBuilder segment = new StringBuilder();

            for (char c : text.toCharArray()) {
                if (c != '(' && c != ')') {
                    segment.append(c);
                    continue;
                }
                addWords(words, segment, open.isEmpty() ? -1 : open.peek());
                segment.setLength(0);
                if (c == '(') {
                    parents.add(open.isEmpty() ? -1 : open.peek());
                    open.push(parents.size() - 1);
                } else if (!open.isEmpty()) {
                    open.pop();
                }
            }
            // An unclosed group runs to the end.
            addWords(words, segment, open.isEmpty() ? -1 : open.peek());

            if (words.isEmpty()) {
                // Nothing but articles or symbols ("A", "@").
                for (String token : AnswerText.wholeTokens(text.replaceAll("[()]", " "))) {
                    words.add(new Word(token, -1));
                }
            }
            // An answer that is all parentheses ("(smiling and waving)") needs all of it.
            if (words.stream().allMatch(Word::isOptional)) {
                words = words.stream().map(word -> new Word(word.text(), -1)).toList();
            }
            return new Variant(List.copyOf(words), List.copyOf(parents), text.strip().replaceAll("\\s+", " "));
        }

        private static void addWords(List<Word> words, CharSequence segment, int group) {
            for (String token : AnswerText.tokens(segment.toString())) {
                if (!token.equals(AnswerText.SEPARATOR)) {
                    words.add(new Word(token, group));
                }
            }
        }

        /** True if each of the given words is one of this wording's words, spelled the same. */
        boolean hasEvery(List<String> given) {
            return given.stream().allMatch(token -> words.stream().anyMatch(word -> word.text().equals(token)));
        }

        boolean isNumber() {
            List<Word> required = words.stream().filter(word -> !word.isOptional()).toList();
            return required.size() == 1 && AnswerText.isNumber(required.getFirst().text());
        }

        /**
         * True if the response words say this option: word by word in any
         * order, or as one string (so "O Rourke" matches "O'Rourke" and
         * "duck bill platypus" matches "(duck-bill(ed)) platypus").
         */
        boolean isMatchedBy(List<String> given) {
            return isMatchedWordByWord(given) || isMatchedAsOneString(given);
        }

        /**
         * Every response word is a different word of the option, every
         * required word is said, and nothing else is.
         */
        private boolean isMatchedWordByWord(List<String> given) {
            long requiredCount = words.stream().filter(word -> !word.isOptional()).count();
            if (given.size() > words.size() || given.size() < requiredCount) {
                return false;
            }

            List<List<Integer>> candidates = new ArrayList<>();
            for (String word : given) {
                List<Integer> close = new ArrayList<>();
                for (int i = 0; i < words.size(); i++) {
                    if (AnswerText.isClose(word, words.get(i).text())) {
                        close.add(i);
                    }
                }
                if (close.isEmpty()) {
                    return false;
                }
                candidates.add(close);
            }
            return assign(candidates, 0, new boolean[words.size()]);
        }

        private boolean assign(List<List<Integer>> candidates, int index, boolean[] used) {
            if (index == candidates.size()) {
                for (int i = 0; i < words.size(); i++) {
                    if (!used[i] && !words.get(i).isOptional()) {
                        return false;
                    }
                }
                return true;
            }
            for (int i : candidates.get(index)) {
                if (!used[i]) {
                    used[i] = true;
                    if (assign(candidates, index + 1, used)) {
                        return true;
                    }
                    used[i] = false;
                }
            }
            return false;
        }

        /**
         * The response, joined without spaces, is within the typo allowance of
         * the option joined the same way, with some of its parenthesized groups
         * left out. Numbers must still be the same, in the same order. The
         * allowance is that of the option's shortest word, so a short word
         * can't go missing unnoticed ("Botha" isn't "P.W. Botha").
         */
        private boolean isMatchedAsOneString(List<String> given) {
            String joined = String.join("", given);
            List<String> givenExact = given.stream().filter(AnswerText::isExact).toList();

            int groups = parents.size();
            List<Long> selections = new ArrayList<>();
            if (groups <= MAX_GROUP_COMBINATIONS_BITS) {
                for (long selection = 0; selection < (1L << groups); selection++) {
                    selections.add(selection);
                }
            } else {
                selections.add(0L);
                selections.add((1L << Math.min(groups, 63)) - 1);
            }

            for (long selection : selections) {
                List<String> chosen = words.stream()
                        .filter(word -> isIncluded(word.group(), selection))
                        .map(Word::text)
                        .toList();
                if (chosen.isEmpty() || !givenExact.equals(chosen.stream().filter(AnswerText::isExact).toList())) {
                    continue;
                }
                String expected = String.join("", chosen);
                int allowed = AnswerText.allowedEdits(chosen.stream().mapToInt(String::length).min().orElse(0));
                if (AnswerText.distance(joined, expected, allowed) <= allowed) {
                    return true;
                }
            }
            return false;
        }

        /** A word is in if it's required or its group and every enclosing group are selected. */
        private boolean isIncluded(int group, long selection) {
            for (int g = group; g >= 0; g = parents.get(g)) {
                if (g >= 63 || (selection & (1L << g)) == 0) {
                    return false;
                }
            }
            return true;
        }
    }

    record Word(String text, int group) {

        boolean isOptional() {
            return group >= 0;
        }
    }
}
