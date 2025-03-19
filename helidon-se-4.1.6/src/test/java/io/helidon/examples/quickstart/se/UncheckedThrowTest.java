package io.helidon.examples.quickstart.se;

import java.io.IOException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Test for
 * https://www.gamlor.info/wordpress/2010/02/throwing-checked-excpetions-like-unchecked-exceptions-in-java/
 * https://stackoverflow.com/questions/15496/hidden-features-of-java/2131355#2131355
 */
final class UncheckedThrowTest {

    @Test
    void test() {
        Assertions.assertThrows(
            IOException.class,
            () -> this.throwException(new IOException())
        );
    }

    private void throwException(final IOException exception) {
        UncheckedThrowTest.throwsUnchecked(exception);
    }

    public static RuntimeException throwUnchecked(final Exception ex){
        // Now we use the 'generic' method. Normally the type T is inferred
        // from the parameters. However you can specify the type also explicit!
        // Now we du just that! We use the RuntimeException as type!
        // That means the throwsUnchecked throws an unchecked exception!
        // Since the types are erased, no type-information is there to prevent this!
        UncheckedThrowTest.<RuntimeException>throwsUnchecked(ex);

        // This is here is only to satisfy the compiler. It's actually unreachable code!
        throw new AssertionError("This code should be unreachable. Something went terrible wrong here!");
    }

    /**
     * Remember, Generics are erased in Java. So this basically throws an Exception. The real
     * Type of T is lost during the compilation
     */
    @SuppressWarnings("unchecked")
    private static <T extends Exception> void throwsUnchecked(Exception toThrow) throws T {
        // Since the type is erased, this cast actually does nothing!!!
        // we can throw any exception
        throw (T) toThrow;
    }
}
