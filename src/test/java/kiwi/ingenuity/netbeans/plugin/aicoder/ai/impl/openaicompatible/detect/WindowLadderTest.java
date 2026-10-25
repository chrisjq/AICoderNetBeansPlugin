package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class WindowLadderTest {

    @Test
    @Timeout(5)
    void overrideWinsAndConfirms() {
        MutableSources sources = new MutableSources();
        sources.ps = 4096;

        WindowReading reading = WindowLadder.resolve(20000, sources, null, 8192, 0.9);

        assertEquals(20000, reading.tokens());
        assertEquals(WindowReading.Source.OVERRIDE, reading.source());
        assertTrue(reading.confirmed());
    }

    @Test
    @Timeout(5)
    void zeroOverrideIsAutoAndFallsThrough() {
        MutableSources sources = new MutableSources();
        sources.ps = 4096;

        WindowReading reading = WindowLadder.resolve(0, sources, null, 8192, 0.9);

        assertEquals(WindowReading.Source.OLLAMA_PS, reading.source());
        assertEquals(4096, reading.tokens());
    }

    @Test
    @Timeout(5)
    void ollamaPsBeatsNumCtx() {
        MutableSources sources = new MutableSources();
        sources.ps = 8192;
        sources.numCtx = 4096;

        WindowReading reading = WindowLadder.resolve(null, sources, null, 8192, 0.9);

        assertEquals(8192, reading.tokens());
        assertEquals(WindowReading.Source.OLLAMA_PS, reading.source());
        assertTrue(reading.confirmed());
    }

    @Test
    @Timeout(5)
    void ollamaNumCtxUsedWhenNotLoaded() {
        MutableSources sources = new MutableSources();
        sources.numCtx = 4096;

        WindowReading reading = WindowLadder.resolve(null, sources, null, 8192, 0.9);

        assertEquals(4096, reading.tokens());
        assertEquals(WindowReading.Source.OLLAMA_NUM_CTX, reading.source());
        assertTrue(reading.confirmed());
    }

    @Test
    @Timeout(5)
    void lmStudioLoadedUsed() {
        MutableSources sources = new MutableSources();
        sources.kind = "LMSTUDIO";
        sources.lmLoaded = 32768;

        WindowReading reading = WindowLadder.resolve(null, sources, null, 8192, 0.9);

        assertEquals(32768, reading.tokens());
        assertEquals(WindowReading.Source.LMSTUDIO_LOADED, reading.source());
        assertTrue(reading.confirmed());
    }

    @Test
    @Timeout(5)
    void llamaCppNCtxUsed() {
        MutableSources sources = new MutableSources();
        sources.kind = "LLAMACPP";
        sources.llamaN = 2048;

        WindowReading reading = WindowLadder.resolve(null, sources, null, 8192, 0.9);

        assertEquals(2048, reading.tokens());
        assertEquals(WindowReading.Source.LLAMACPP_N_CTX, reading.source());
        assertTrue(reading.confirmed());
    }

    @Test
    @Timeout(5)
    void vllmMaxModelLenUsed() {
        MutableSources sources = new MutableSources();
        sources.kind = "VLLM";
        sources.vllm = 16384;

        WindowReading reading = WindowLadder.resolve(null, sources, null, 8192, 0.9);

        assertEquals(16384, reading.tokens());
        assertEquals(WindowReading.Source.VLLM_MAX_MODEL_LEN, reading.source());
        assertTrue(reading.confirmed());
    }

    @Test
    @Timeout(5)
    void learnedErrorBeatsV1ModelsField() {
        MutableSources sources = new MutableSources();
        sources.kind = "GENERIC";
        sources.v1 = 8192;

        WindowReading reading = WindowLadder.resolve(null, sources, 4096, 8192, 0.9);

        assertEquals(4096, reading.tokens());
        assertEquals(WindowReading.Source.SERVER_ERROR, reading.source());
        assertTrue(reading.confirmed());
    }

    @Test
    @Timeout(5)
    void realOllamaReadingBeatsLearnedError() {
        MutableSources sources = new MutableSources();
        sources.ps = 16384;

        WindowReading reading = WindowLadder.resolve(null, sources, 4096, 8192, 0.9);

        assertEquals(16384, reading.tokens());
        assertEquals(WindowReading.Source.OLLAMA_PS, reading.source());
    }

    @Test
    @Timeout(5)
    void v1ModelsFieldUsedWhenNothingElse() {
        MutableSources sources = new MutableSources();
        sources.kind = "GENERIC";
        sources.v1 = 8192;

        WindowReading reading = WindowLadder.resolve(null, sources, null, 8192, 0.9);

        assertEquals(8192, reading.tokens());
        assertEquals(WindowReading.Source.V1_MODELS, reading.source());
        assertTrue(reading.confirmed());
    }

    @Test
    @Timeout(5)
    void upperBoundIsScaledAndUnconfirmed() {
        MutableSources sources = new MutableSources();
        sources.ollamaTrain = 10000;

        WindowReading reading = WindowLadder.resolve(null, sources, null, 8192, 0.9);

        assertEquals(9000, reading.tokens());
        assertEquals(WindowReading.Source.UPPER_BOUND, reading.source());
        assertFalse(reading.confirmed());
    }

    @Test
    @Timeout(5)
    void lmStudioUpperBoundUsedWhenNoLoaded() {
        MutableSources sources = new MutableSources();
        sources.kind = "LMSTUDIO";
        sources.lmMax = 100000;

        WindowReading reading = WindowLadder.resolve(null, sources, null, 8192, 0.5);

        assertEquals(50000, reading.tokens());
        assertEquals(WindowReading.Source.UPPER_BOUND, reading.source());
        assertFalse(reading.confirmed());
    }

    @Test
    @Timeout(5)
    void assumedWhenNothingKnown() {
        MutableSources sources = new MutableSources();
        sources.kind = "GENERIC";

        WindowReading reading = WindowLadder.resolve(null, sources, null, 8192, 0.9);

        assertEquals(8192, reading.tokens());
        assertEquals(WindowReading.Source.ASSUMED, reading.source());
        assertFalse(reading.confirmed());
    }

    @Test
    @Timeout(5)
    void nullSourcesFallBackToAssumed() {
        WindowReading reading = WindowLadder.resolve(null, null, null, 4096, 0.9);

        assertEquals(4096, reading.tokens());
        assertEquals(WindowReading.Source.ASSUMED, reading.source());
        assertFalse(reading.confirmed());
    }

    @Test
    @Timeout(5)
    void hasAnySourceFalseForGenericWithoutWindowField() {
        MutableSources sources = new MutableSources();
        sources.kind = "GENERIC";
        sources.v1HasField = false;

        assertFalse(WindowLadder.hasAnySource(sources));
    }

    @Test
    @Timeout(5)
    void hasAnySourceTrueForGenericWithWindowField() {
        MutableSources sources = new MutableSources();
        sources.kind = "GENERIC";
        sources.v1HasField = true;

        assertTrue(WindowLadder.hasAnySource(sources));
    }

    @Test
    @Timeout(5)
    void hasAnySourceTrueForKnownKinds() {
        MutableSources ollama = new MutableSources();
        ollama.kind = "OLLAMA";
        assertTrue(WindowLadder.hasAnySource(ollama));

        MutableSources llamaCpp = new MutableSources();
        llamaCpp.kind = "LLAMACPP";
        assertTrue(WindowLadder.hasAnySource(llamaCpp));
    }

    @Test
    @Timeout(5)
    void hasAnySourceFalseForNull() {
        assertFalse(WindowLadder.hasAnySource(null));
    }

    @Test
    @Timeout(5)
    void smallerReadingOnlyReplacesOnSecondConsecutiveReading() {
        WindowLadder ladder = new WindowLadder();

        assertEquals(8192, ladder.record("m", 8192));
        assertEquals(8192, ladder.record("m", 4096));
        assertEquals(4096, ladder.record("m", 4096));
        assertEquals(Integer.valueOf(4096), ladder.current());
    }

    @Test
    @Timeout(5)
    void largerReadingResetsPendingShrink() {
        WindowLadder ladder = new WindowLadder();

        assertEquals(8192, ladder.record("m", 8192));
        assertEquals(8192, ladder.record("m", 4096));
        assertEquals(8192, ladder.record("m", 8192));
        assertEquals(8192, ladder.record("m", 4096));
        assertEquals(4096, ladder.record("m", 4096));
    }

    @Test
    @Timeout(5)
    void modelChangeResetsRememberedReading() {
        WindowLadder ladder = new WindowLadder();

        assertEquals(8192, ladder.record("a", 8192));
        assertEquals(4096, ladder.record("b", 4096));
        assertEquals(Integer.valueOf(4096), ladder.current());
    }

    @Test
    @Timeout(5)
    void resetForgetsEverything() {
        WindowLadder ladder = new WindowLadder();
        ladder.record("m", 8192);

        ladder.reset();

        assertNull(ladder.current());
        assertEquals(4096, ladder.record("m", 4096));
    }

    private static final class MutableSources implements WindowSources {

        private String kind = "OLLAMA";
        private Integer ps;
        private Integer numCtx;
        private Integer lmLoaded;
        private Integer llamaN;
        private Integer vllm;
        private Integer v1;
        private Integer ollamaTrain;
        private Integer lmMax;
        private Integer llamaTrain;
        private boolean v1HasField;

        @Override
        public String kindName() {
            return kind;
        }

        @Override
        public Integer ollamaPsContextLength() {
            return ps;
        }

        @Override
        public Integer ollamaNumCtx() {
            return numCtx;
        }

        @Override
        public Integer lmStudioLoadedContextLength() {
            return lmLoaded;
        }

        @Override
        public Integer llamaCppNCtx() {
            return llamaN;
        }

        @Override
        public Integer vllmMaxModelLen() {
            return vllm;
        }

        @Override
        public Integer v1ModelsWindow() {
            return v1;
        }

        @Override
        public Integer ollamaTrainingContextLength() {
            return ollamaTrain;
        }

        @Override
        public Integer lmStudioMaxContextLength() {
            return lmMax;
        }

        @Override
        public Integer llamaCppTrainContextLength() {
            return llamaTrain;
        }

        @Override
        public boolean v1ModelsHasWindowField() {
            return v1HasField || v1 != null;
        }
    }
}
