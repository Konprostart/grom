// SPDX-License-Identifier: Apache-2.0
package javax.management;

/**
 * Minimal Android stand-in for the desktop-only class Apache MINA SSHD tests
 * against while unwrapping an exception (see engine/lint.xml).
 *
 * The SSH transport never constructs this type. `ExceptionUtils.peelException`
 * only runs `instanceof`, so an `instanceof` against a real class that is never
 * instantiated returns false and the original exception is peeled normally.
 * Without it, that method would throw NoClassDefFoundError on Android.
 */
public class ReflectionException extends Exception {

    private static final long serialVersionUID = 1L;

    public ReflectionException(Throwable targetException) {
        super(targetException);
    }

    public Throwable getTargetException() {
        return getCause();
    }
}
