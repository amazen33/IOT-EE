package org.springframework.ai;

/**
 * Stands in for a real {@code org.springframework.ai.*} class -- test-fixture
 * only, declared under the REAL package name because ArchUnit matches on the
 * package name string (same convention as {@code FakeKafkaProducerClass}).
 * Spring AI may be used only inside an {@code adapter.out.ai} package
 * (proposed ADR XXXX-proposed-ai-integration-port-and-controls).
 */
public class FakeChatClient {
    public String call(String prompt) {
        return "";
    }
}
