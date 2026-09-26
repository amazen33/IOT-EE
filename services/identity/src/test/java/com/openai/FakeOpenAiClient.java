package com.openai;

/**
 * Stands in for a real {@code com.openai.*} class -- test-fixture only,
 * declared under the REAL package name because ArchUnit matches on the
 * package name string. Model-provider SDKs may be used only inside an
 * {@code adapter.out.ai} package.
 */
public class FakeOpenAiClient {
    public void complete() {
        // no-op stub
    }
}
