/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import static org.junit.Assert.assertThrows;

import org.junit.Test;

import java.math.BigDecimal;

/**
 * Checks the limits of the binary {@code numeric} format: the weight must fit a short and the
 * scale must fit 14 bits, otherwise {@link ByteConverter#numeric(BigDecimal)} would silently
 * encode a different number.
 */
public class BigDecimalByteConverterRangeTest {

  @Test
  public void largestScaleAndWeightThatFit() {
    BigDecimalByteConverterTest.testBinary(new BigDecimal("1e-16383"));
    BigDecimalByteConverterTest.testBinary(new BigDecimal("1e131071"));
  }

  @Test
  public void valueOutsideTheBinaryFormatIsRefused() {
    for (String value : new String[]{
        "999999.99e-262144", "1e131072", "1e-200000000", "0e-16384", "-1e-16384"}) {
      final BigDecimal number = new BigDecimal(value);
      assertThrows(value, IllegalArgumentException.class, () -> ByteConverter.numeric(number));
    }
  }
}
