package kiwi.ingenuity.netbeans.plugin.aicoder.ai;

/**
 * Type-wide background-service lifecycle. Instances are owned by {@link AiTypeRegistry}, which starts one only when a
 * session of that type is created and stops only those it started during plugin shutdown.
 */
public interface AiTypeLifecycle {

    AiTypeLifecycle NO_OP = new AiTypeLifecycle() {
    };

    default void start() {
    }

    default void stop() {
    }
}
