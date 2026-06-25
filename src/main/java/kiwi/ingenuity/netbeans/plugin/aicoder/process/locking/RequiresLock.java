package kiwi.ingenuity.netbeans.plugin.aicoder.process.locking;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares that a tool requires a lock before execution. Framework
 * automatically acquires/releases the lock.
 *
 * Usage:
 *
 * @RequiresLock(LockType.GIT_LOCK) public String handle(ToolRequestArguments
 * args) { ... }
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresLock {

    LockTypeEnum value();
}
