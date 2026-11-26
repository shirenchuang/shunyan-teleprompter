package com.shunyan.prompter;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import android.text.StaticLayout;
import android.text.TextPaint;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class ReplayInstrumentation extends Instrumentation {
    private static final String MANUSCRIPT = "今天我们测试悬浮提词器。请跟着文字慢慢朗读。";

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            FollowMatcher strict = new FollowMatcher("悬浮提词器", 0);
            FollowMatcher pinyin = new FollowMatcher("悬浮提词器", 0);
            require(strict.match("悬浮提瓷", false) < 0, "Strict mode accepted a homophone");
            require(pinyin.match("悬浮提瓷", true) == 4, "Homophone mode missed the corresponding character");
            require(pinyin.match("悬浮提瓷", true) < 0, "Repeated partial incorrectly advanced the cursor");
            verifyReadingLayout();
            result.putString("layout", "homophone matching, character hit, column width and rotation passed");
            String[] assets = getContext().getAssets().list("");
            String replay = Arrays.asList(assets).contains("replay.wav")
                    ? verifyReplay() : "skipped: optional replay.wav not supplied";
            result.putString("stream", replay);
            finish(Activity.RESULT_OK, result);
        } catch (Exception | AssertionError | LinkageError error) {
            result.putString("stream", String.valueOf(error));
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private String verifyReplay() throws Exception {
        FollowMatcher matcher = new FollowMatcher(MANUSCRIPT, 0);
        int[] cursor = {0};
        int[] changes = {0};
        int[] sentences = {0};
        SpeechEngine.Listener listener = new SpeechEngine.Listener() {
            @Override public void onReady() {}
            @Override public void onText(String text) {
                changes[0]++;
                int next = matcher.match(text, true);
                if (next > cursor[0]) cursor[0] = next;
            }
            @Override public void onSentenceEnd() { sentences[0]++; matcher.sentenceEnd(); }
            @Override public void onError() { throw new AssertionError("Decoder error"); }
        };
        try (SpeechEngine.Decoder decoder = new SpeechEngine.Decoder(getTargetContext().getAssets())) {
            ByteBuffer pcm = loadSample();
            while (pcm.hasRemaining()) {
                float[] chunk = new float[Math.min(1600, pcm.remaining() / 2)];
                for (int i = 0; i < chunk.length; i++) chunk[i] = pcm.getShort() / 32768f;
                decoder.accept(chunk, listener);
            }
            for (int i = 0; i < 22; i++) decoder.accept(new float[1600], listener);
            require(changes[0] >= 8, "Expected multiple streamed partials");
            require(cursor[0] == MANUSCRIPT.length() - 1, "Missed final word: " + cursor[0]);
            require(sentences[0] > 0, "Expected a streaming endpoint");
        }
        return "partials=" + changes[0] + ", cursor=" + cursor[0] + ", endpoints=" + sentences[0];
    }

    private static void verifyReadingLayout() {
        String text = "保持视线集中让口播自然";
        TextPaint paint = new TextPaint();
        paint.setTextSize(48);
        int inset = ScriptLayout.columnInset(480, 20, 5, paint.measureText("字"));
        require(inset > 20, "Column did not narrow");
        require(ScriptLayout.columnInset(480, 20, 0, paint.measureText("字")) == 20, "Auto width changed");
        float advance = paint.measureText("字");
        float dragged = ScriptLayout.columnsForInset(480, 20, 4, 71, advance);
        require(Math.abs(ScriptLayout.columnInset(480, 20, dragged, advance) - 71) <= 1,
                "A dragged width did not follow the finger");
        require(ScriptLayout.columnsForInset(480, 20, 4, 0, advance) == 0, "Outward drag missed auto width");
        require(ScriptLayout.columnsForInset(480, 20, 4, 480, advance) == 4, "Inward drag exceeded minimum width");
        StaticLayout layout = StaticLayout.Builder.obtain(text, 0, text.length(), paint, 480 - 2 * inset).build();
        require(layout.getLineEnd(0) == 5 && layout.getLineCount() > 1, "Five-column wrap failed");
        int target = layout.getLineStart(1) + 1;
        float x = layout.getPrimaryHorizontal(target) * .25f + layout.getPrimaryHorizontal(target + 1) * .75f;
        float y = layout.getLineTop(1) + 10;
        require(ScriptLayout.characterAt(layout, text, x, y) == target, "Tap on a wrapped glyph missed");
        require(ScriptLayout.characterAt(layout, text, -1, y) == -1, "Outside tap moved the cursor");
        require(RotatingOverlay.autoRotation(1, 3) == 0, "Already rotated display was double-rotated");
        require(RotatingOverlay.autoRotation(1, 0) == 3, "Locked portrait orientation was not compensated");
        require(RotatingOverlay.stableSensorTurns(49, 0) == 0, "Sensor jitter crossed the rotation boundary");
        require(RotatingOverlay.stableSensorTurns(70, 0) == 1, "Landscape direction was not recognized");
    }

    private ByteBuffer loadSample() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (InputStream stream = getContext().getAssets().open("replay.wav")) {
            byte[] chunk = new byte[4096];
            int size;
            while ((size = stream.read(chunk)) >= 0) bytes.write(chunk, 0, size);
        }
        ByteBuffer wav = ByteBuffer.wrap(bytes.toByteArray()).order(ByteOrder.LITTLE_ENDIAN);
        wav.position(12);
        byte[] kind = new byte[4];
        while (wav.remaining() >= 8) {
            wav.get(kind);
            int size = wav.getInt();
            if ("data".equals(new String(kind, StandardCharsets.US_ASCII))) {
                wav.limit(wav.position() + size);
                return wav;
            }
            wav.position(wav.position() + size + (size & 1));
        }
        throw new IllegalArgumentException("PCM data not found");
    }

    private static void require(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
}
