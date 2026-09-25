package com.chatbot.ai.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Selects a short, verbatim evidence window from a retrieved vector chunk. */
public final class CitationEvidenceSelector {
    private static final int MAX_EXCERPT_CHARS = 220;
    private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s+(.+?)\\s*#*\\s*$");
    private static final Pattern WORDS = Pattern.compile("\\p{IsHan}+|[\\p{L}\\p{N}]{2,}");
    private static final Set<String> COMMON = Set.of("用户", "请问", "可以", "什么", "怎么", "这个", "那个", "需要",
            "是否", "情况", "问题", "进行", "提供", "根据", "处理", "我们", "已经", "可能", "以及", "资料", "来源");

    private CitationEvidenceSelector() { }

    public static Set<Integer> citedSourceNumbers(String answer, int candidateCount) {
        Set<Integer> numbers = new LinkedHashSet<>();
        if (answer == null) return numbers;
        Matcher references = Pattern.compile("\\[资料\\s*(\\d+)\\s*]").matcher(answer);
        while (references.find()) {
            try {
                int number = Integer.parseInt(references.group(1));
                if (number >= 1 && number <= candidateCount) numbers.add(number);
            } catch (NumberFormatException ignored) {
                // Ignore malformed model-generated source numbers.
            }
        }
        return numbers;
    }

    public static Evidence select(String fullText, String question, String answer, int sourceNumber) {
        String text = fullText == null ? "" : fullText.replace("\r\n", "\n").trim();
        if (text.isEmpty()) return new Evidence("", null);

        Set<String> questionTerms = terms(question);
        Set<String> answerTerms = terms(claimNearCitation(answer, sourceNumber));
        List<Candidate> candidates = candidates(text);
        if (questionTerms.isEmpty() && answerTerms.isEmpty()) {
            Candidate first = candidates.isEmpty() ? null : candidates.get(0);
            return first == null ? new Evidence(limit(text, questionTerms, answerTerms), null)
                    : new Evidence(limit(first.text(), questionTerms, answerTerms), first.sectionTitle());
        }
        Candidate best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (Candidate candidate : candidates) {
            double score = score(candidate.text(), questionTerms, answerTerms);
            if (score > bestScore) {
                best = candidate;
                bestScore = score;
            }
        }
        if (best == null || bestScore <= 0) return new Evidence("", null);
        return new Evidence(limit(best.text(), questionTerms, answerTerms), best.sectionTitle());
    }

    private static List<Candidate> candidates(String text) {
        List<Candidate> result = new ArrayList<>();
        String heading = null;
        for (String rawLine : text.split("\n")) {
            String line = rawLine.trim();
            if (line.isEmpty() || "---".equals(line)) continue;
            Matcher title = HEADING.matcher(line);
            if (title.matches()) {
                heading = title.group(1).trim();
                continue;
            }
            int start = 0;
            for (int index = 0; index < line.length(); index++) {
                if ("。！？；!?;".indexOf(line.charAt(index)) < 0) continue;
                addCandidate(result, line.substring(start, index + 1), heading);
                start = index + 1;
            }
            if (start < line.length()) addCandidate(result, line.substring(start), heading);
        }
        return result;
    }

    private static void addCandidate(List<Candidate> result, String text, String heading) {
        String candidate = text.trim();
        if (!candidate.isEmpty()) result.add(new Candidate(candidate, heading));
    }

    private static Set<String> terms(String input) {
        Set<String> result = new LinkedHashSet<>();
        if (input == null) return result;
        Matcher words = WORDS.matcher(input.toLowerCase(Locale.ROOT));
        while (words.find() && result.size() < 200) {
            String word = words.group();
            if (word.codePoints().allMatch(codePoint ->
                    Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN)) {
                for (int size = 4; size >= 2; size--) {
                    for (int offset = 0; offset + size <= word.length() && result.size() < 200; offset++) {
                        String term = word.substring(offset, offset + size);
                        if (!COMMON.contains(term)) result.add(term);
                    }
                }
            } else if (word.length() >= 2) {
                result.add(word);
            }
        }
        return result;
    }

    private static String claimNearCitation(String answer, int sourceNumber) {
        if (answer == null || answer.isBlank()) return "";
        Pattern reference = Pattern.compile("\\[资料\\s*" + sourceNumber + "\\s*]");
        Matcher match = reference.matcher(answer);
        if (match.find()) {
            int end = match.start();
            while (end > 0 && (Character.isWhitespace(answer.charAt(end - 1))
                    || "。！？；!?;".indexOf(answer.charAt(end - 1)) >= 0)) end--;
            int start = Math.max(0, end - 180);
            for (char boundary : new char[] { '。', '！', '？', '\n', ']' }) {
                int previous = answer.lastIndexOf(boundary, end - 1);
                if (previous >= start) start = previous + 1;
            }
            return answer.substring(start, end);
        }
        return answer.substring(0, Math.min(240, answer.length()));
    }

    private static double score(String text, Set<String> questionTerms, Set<String> answerTerms) {
        String normalized = text.toLowerCase(Locale.ROOT);
        double score = 0;
        for (String term : questionTerms) {
            if (normalized.contains(term)) score += term.length() >= 4 ? 4 : term.length() == 3 ? 2 : 1;
        }
        for (String term : answerTerms) {
            if (normalized.contains(term)) score += term.length() >= 4 ? 5 : term.length() == 3 ? 3 : 1;
        }
        if (text.length() < 18) score -= 8;
        else if (text.length() < 35) score -= 3;
        return score / (1 + Math.max(0, text.length() - 120) / 180.0);
    }

    private static String limit(String text, Set<String> questionTerms, Set<String> answerTerms) {
        if (text.length() <= MAX_EXCERPT_CHARS) return text;
        int bestStart = 0;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int start = 0; start < text.length(); start += 40) {
            int end = Math.min(text.length(), start + MAX_EXCERPT_CHARS - 2);
            double score = score(text.substring(start, end), questionTerms, answerTerms);
            if (score > bestScore) {
                bestStart = start;
                bestScore = score;
            }
            if (end == text.length()) break;
        }
        int end = Math.min(text.length(), bestStart + MAX_EXCERPT_CHARS - 2);
        return (bestStart > 0 ? "…" : "") + text.substring(bestStart, end).trim()
                + (end < text.length() ? "…" : "");
    }

    private record Candidate(String text, String sectionTitle) { }

    public record Evidence(String excerpt, String sectionTitle) { }
}
