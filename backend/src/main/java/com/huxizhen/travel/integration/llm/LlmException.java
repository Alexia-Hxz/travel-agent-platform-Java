package com.huxizhen.travel.integration.llm;

/** 模型调用失败。调用方按"能否降级"决定是忽略还是转成 5xx/422。 */
public class LlmException extends RuntimeException {

    public LlmException(String message) {
        super(message);
    }

    public LlmException(String message, Throwable cause) {
        super(message, cause);
    }
}
