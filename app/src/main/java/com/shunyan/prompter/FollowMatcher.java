package com.shunyan.prompter;

import android.icu.text.Transliterator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

final class FollowMatcher {
    private record Unit(int codePoint, int end) {}
    private final Unit[] manuscript;
    private final Map<Integer, String> pinyin = new HashMap<>();
    private Transliterator transliterator;
    private int next;
    private int utteranceStart;
    private String previous = "";

    FollowMatcher(String body, int cursor) {
        manuscript = units(body);
        seek(cursor);
    }

    void seek(int cursor) {
        int low = 0, high = manuscript.length;
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (manuscript[middle].end() <= cursor) low = middle + 1;
            else high = middle;
        }
        next = low;
        sentenceEnd();
    }

    void sentenceEnd() {
        utteranceStart = next;
        previous = "";
    }

    int match(String partial, boolean allowPinyin) {
        if (partial.equals(previous) || next == manuscript.length) return -1;
        previous = partial;
        Unit[] spoken = units(partial);
        if (spoken.length == 0) return -1;

        int first = Math.max(utteranceStart, next - 9);
        int last = Math.min(manuscript.length, next + (spoken.length < 3 ? 4 : 30));
        int queryStart = Math.max(0, spoken.length - 9);
        int count = last - first;
        int[] scores = new int[count + 1];
        int[] hits = new int[count + 1];
        for (int i = queryStart; i < spoken.length; i++) {
            int[] rowScores = new int[count + 1];
            int[] rowHits = new int[count + 1];
            for (int j = 1; j <= count; j++) {
                int weight = similarity(spoken[i].codePoint(), manuscript[first + j - 1].codePoint(), allowPinyin);
                int diagonal = scores[j - 1] + weight;
                int skipManuscript = rowScores[j - 1] - 2;
                int skipSpeech = scores[j] - 2;
                if (diagonal > 0 && diagonal >= Math.max(skipManuscript, skipSpeech)) {
                    rowScores[j] = diagonal;
                    rowHits[j] = hits[j - 1] + (weight > 0 ? 1 : 0);
                } else if (skipManuscript >= skipSpeech && skipManuscript > 0) {
                    rowScores[j] = skipManuscript;
                    rowHits[j] = rowHits[j - 1];
                } else if (skipSpeech > 0) {
                    rowScores[j] = skipSpeech;
                    rowHits[j] = hits[j];
                }
            }
            scores = rowScores;
            hits = rowHits;
        }

        int best = -1;
        int bestScore = 0;
        int lastSpoken = spoken[spoken.length - 1].codePoint();
        for (int j = 1; j <= count; j++) {
            int index = first + j - 1;
            if (similarity(lastSpoken, manuscript[index].codePoint(), allowPinyin) <= 0) continue;
            int distance = Math.max(0, index - next);
            int minimumHits = distance > 5 ? 3 : distance > 1 ? 2 : 1;
            int score = scores[j] * 4 - distance;
            if (hits[j] >= minimumHits && score > bestScore) {
                bestScore = score;
                best = index;
            }
        }
        if (best < next) return -1;
        next = best + 1;
        return manuscript[best].end();
    }

    static int[] readingOffsets(String body) {
        Unit[] units = units(body);
        int[] offsets = new int[units.length];
        for (int i = 0; i < units.length; i++) offsets[i] = units[i].end();
        return offsets;
    }

    private int similarity(int heard, int written, boolean allowPinyin) {
        if (heard == written) return 3;
        if (allowPinyin && Character.UnicodeScript.of(heard) == Character.UnicodeScript.HAN
                && Character.UnicodeScript.of(written) == Character.UnicodeScript.HAN
                && romanize(heard).equals(romanize(written))) return 2;
        return -2;
    }

    private String romanize(int codePoint) {
        if (transliterator == null) {
            transliterator = Transliterator.getInstance("Han-Latin; NFD; [:Nonspacing Mark:] Remove; NFC; Lower()");
        }
        return pinyin.computeIfAbsent(codePoint,
                key -> transliterator.transliterate(new String(Character.toChars(key))));
    }

    private static Unit[] units(String value) {
        ArrayList<Unit> result = new ArrayList<>();
        for (int i = 0; i < value.length();) {
            int codePoint = value.codePointAt(i);
            i += Character.charCount(codePoint);
            if (Character.isLetterOrDigit(codePoint)) {
                result.add(new Unit(Character.toLowerCase(codePoint), i));
            }
        }
        return result.toArray(new Unit[0]);
    }
}
