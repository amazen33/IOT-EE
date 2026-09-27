package com.iotee.platform.identity.architecture.fixtures;

import com.openai.FakeOpenAiClient;
import org.springframework.ai.FakeChatClient;

/**
 * Deliberately violates the "no vendor SDK outside adapters" rule for the
 * AI entries of the denylist: Spring AI ({@code org.springframework.ai}) and
 * a model-provider SDK ({@code com.openai}). Test-fixture only.
 */
public class ViolatingAiSdkUsageClass {
    public void useAiSdksDirectly() {
        new FakeChatClient().call("synthetic prompt");
        new FakeOpenAiClient().complete();
    }
}
