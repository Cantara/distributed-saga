package no.cantara.saga.execution.adapter;

import org.testng.annotations.Test;

import java.math.BigDecimal;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

/**
 * se-11: a failed deserialization says which class it could not build, and keeps the constructor's own exception.
 */
public class ActionOutputSerializerToStringAndStringConstructorTest {

    public static class NoStringConstructor {
    }

    public static class ThrowingConstructor {
        static final IllegalArgumentException FAILURE = new IllegalArgumentException("bad data");

        public ThrowingConstructor(String data) {
            throw FAILURE;
        }
    }

    @Test
    public void thatAStringConstructorIsUsed() {
        assertEquals(new ActionOutputSerializerToStringAndStringConstructor<>(BigDecimal.class).deserialize("1.5"), new BigDecimal("1.5"));
        assertEquals(new ActionOutputSerializerToStringAndStringConstructor<>(String.class).deserialize("text"), "text");
    }

    @Test
    public void thatATypeWithoutAStringConstructorFailsWithItsName() {
        IllegalStateException e = expectThrows(IllegalStateException.class,
                () -> new ActionOutputSerializerToStringAndStringConstructor<>(NoStringConstructor.class).deserialize("x"));
        assertTrue(e.getMessage().contains(NoStringConstructor.class.getName()), e.getMessage());
        assertTrue(e.getCause() instanceof NoSuchMethodException, String.valueOf(e.getCause()));
    }

    @Test
    public void thatAFailingConstructorIsTheCause() {
        IllegalStateException e = expectThrows(IllegalStateException.class,
                () -> new ActionOutputSerializerToStringAndStringConstructor<>(ThrowingConstructor.class).deserialize("x"));
        assertTrue(e.getMessage().contains(ThrowingConstructor.class.getName()), e.getMessage());
        assertSame(e.getCause(), ThrowingConstructor.FAILURE);
    }
}
