package com.hotvect.vw;

// Source adapted from https://github.com/plutext/ae-xmlgraphics-commons/blob/master/src/java/org/apache/xmlgraphics/util/DoubleFormatUtil.java
// revision: 4bcb767
// Courtesy of Jason Harrop licensed under Apache 2.0
// Source code was changed to use StringBuilder instead of StringBuilder

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/* $Id$ */

/**
 * This class implements fast, thread-safe format of a double value
 * with a given number of decimal digits.
 * <p>
 * The contract for the format methods is this one:
 * if the source is greater than or equal to 1 (in absolute value),
 * use the decimals parameter to define the number of decimal digits; else,
 * use the precision parameter to define the number of decimal digits.
 * <p>
 * A few examples (consider decimals being 4 and precision being 8):
 * <ul>
 * <li>0.0 should be rendered as "0"
 * <li>0.1 should be rendered as "0.1"
 * <li>1234.1 should be rendered as "1234.1"
 * <li>1234.1234567 should be rendered as "1234.1235" (note the trailing 5! Rounding!)
 * <li>1234.00001 should be rendered as "1234"
 * <li>0.00001 should be rendered as "0.00001" (here you see the effect of the "precision" parameter)
 * <li>0.00000001 should be rendered as "0.00000001"
 * <li>0.000000001 should be rendered as "0"
 * </ul>
 *
 * Originally authored by Julien Aym&eacute;.
 */
public class DoubleFormatUtils {

    /**
     * Rounds the given source value at the given precision
     * and writes the rounded value into the given target
     *
     * @param source the source value to round
     * @param decimals the decimals to round at (use if abs(source) &ge; 1.0)
     * @param precision the precision to round at (use if abs(source) &lt; 1.0)
     * @param target the buffer to write to
     */
    public static void formatDouble(double source, int decimals, int precision, StringBuilder target) {
        int scale = (Math.abs(source) >= 1.0) ? decimals : precision;
        if (tooManyDigitsUsed(source, scale) || tooCloseToRound(source, scale)) {
            formatDoublePrecise(source, decimals, precision, target);
        } else {
            formatDoubleFast(source, decimals, precision, target);
        }
    }

    /**
     * Rounds the given source value at the given precision
     * and writes the rounded value into the given target
     * <p>
     * This method internally uses the String representation of the source value,
     * in order to avoid any double precision computation error.
     *
     * @param source the source value to round
     * @param decimals the decimals to round at (use if abs(source) &ge; 1.0)
     * @param precision the precision to round at (use if abs(source) &lt; 1.0)
     * @param target the buffer to write to
     */
    public static void formatDoublePrecise(double source, int decimals, int precision, StringBuilder target) {
        if (isRoundedToZero(source, decimals, precision)) {
            // Will always be rounded to 0
            target.append('0');
            return;
        }

        boolean negative = source < 0.0;
        if (negative) {
            source = -source;
            // Done once and for all
            target.append('-');
        }
        int scale = (source >= 1.0) ? decimals : precision;

        // The only way to format precisely the double is to use the String
        // representation of the double, and then to do mathematical integer operation on it.
        String s = Double.toString(source);
        if (source >= 10e-3 && source < 1e7) {
            // Plain representation of double: "intPart.decimalPart"
            int dot = s.indexOf('.');
            String decS = s.substring(dot + 1);
            int decLength = decS.length();
            if (scale >= decLength) {
                if ("0".equals(decS)) {
                    // source is a mathematical integer
                    target.append(s.substring(0, dot));
                } else {
                    target.append(s);
                }
                return;
            } else if (scale + 1 < decLength) {
                // ignore unnecessary digits
                decLength = scale + 1;
                decS = decS.substring(0, decLength);
            }
            long intP = Long.parseLong(s.substring(0, dot));
            long decP = Long.parseLong(decS);
            format(target, scale, intP, decP);
        } else {
            // Scientific representation of double: "x.xxxxxEyyy"
            int dot = s.indexOf('.');
            int exp = s.indexOf('E');
            int exposant = Integer.parseInt(s.substring(exp + 1));
            String intS = s.substring(0, dot);
            String decS = s.substring(dot + 1, exp);
            int decLength = decS.length();
            if (exposant >= 0) {
                int digits = decLength - exposant;
                if (digits <= 0) {
                    // no decimal part,
                    // no rounding involved
                    target.append(intS);
                    target.append(decS);
                    for (int i = -digits; i > 0; i--) {
                        target.append('0');
                    }
                } else if (digits <= scale) {
                    // decimal part precision is lower than scale,
                    // no rounding involved
                    target.append(intS);
                    target.append(decS.substring(0, exposant));
                    target.append('.');
                    target.append(decS.substring(exposant));
                } else {
                    // decimalDigits > scale,
                    // Rounding involved
                    long intP = Long.parseLong(intS) * tenPow(exposant) + Long.parseLong(decS.substring(0, exposant));
                    long decP = Long.parseLong(decS.substring(exposant, exposant + scale + 1));
                    format(target, scale, intP, decP);
                }
            } else {
                // Only a decimal part is supplied
                exposant = -exposant;
                int digits = scale - exposant + 1;
                if (digits < 0) {
                    target.append('0');
                } else if (digits == 0) {
                    long decP = Long.parseLong(intS);
                    format(target, scale, 0L, decP);
                } else {
                    long decP = Long.parseLong(intS) * tenPow(digits) + Long.parseLong(decS.substring(0, Math.min(decLength, digits)));
                    format(target, scale, 0L, decP);
                }
            }
        }
    }

    /**
     * Returns true if the given source value will be rounded to zero
     *
     * @param source the source value to round
     * @param decimals the decimals to round at (use if abs(source) &ge; 1.0)
     * @param precision the precision to round at (use if abs(source) &lt; 1.0)
     * @return true if the source value will be rounded to zero
     */
    private static boolean isRoundedToZero(double source, int decimals, int precision) {
        return Math.abs(source) < 5.0 / tenPow(Math.max(decimals, precision) + 1);
    }

    /**
     * Most used power of ten (to avoid the cost of Math.pow(10, n)
     */
    private static final long[] tenPows = new long[20];
    static {
        tenPows[0] = 1L;
        for (int i = 1; i < tenPows.length; i++) {
            tenPows[i] = tenPows[i - 1] * 10L;
        }
    }

    /**
     * Returns ten to the power of n
     *
     * @param n the nth power of ten to get
     * @return ten to the power of n
     */
    public static long tenPow(int n) {
        assert n >= 0;
        return n < tenPows.length ? tenPows[n] : (long) Math.pow(10, n);
    }

    /**
     * Helper method to do the custom rounding used within formatDoublePrecise
     *
     * @param target the buffer to write to
     * @param scale the expected rounding scale
     * @param intP the source integer part
     * @param decP the source decimal part, truncated to scale + 1 digit
     */
    private static void format(StringBuilder target, int scale, long intP, long decP) {
        long scaleTen = tenPow(scale);
        if (decP != 0L) {
            // decP is the decimal part of source, truncated to scale + 1 digit.
            // Custom rounding: add 5
            decP += 5L;
            decP /= 10L;
            if (decP >= scaleTen) {
                intP++;
                decP -= scaleTen;
            }
            if (decP != 0L) {
                // Remove trailing zeroes
                while (decP % 10L == 0L) {
                    decP = decP / 10L;
                    scale--;
                }
            }
        }
        target.append(intP);
        if (decP != 0L) {
            target.append('.');
            while (scale > 0 && decP < tenPow(--scale)) {
                // Insert leading zeroes
                target.append('0');
            }
            target.append(decP);
        }
    }

    /**
     * Rounds the given source value at the given precision
     * and writes the rounded value into the given target
     * <p>
     * This method internally uses double precision computation and rounding,
     * so the result may not be accurate (see formatDouble method for conditions).
     *
     * @param source the source value to round
     * @param decimals the decimals to round at (use if abs(source) &ge; 1.0)
     * @param precision the precision to round at (use if abs(source) &lt; 1.0)
     * @param target the buffer to write to
     */
    public static void formatDoubleFast(double source, int decimals, int precision, StringBuilder target) {
        if (isRoundedToZero(source, decimals, precision)) {
            // Will always be rounded to 0
            target.append('0');
            return;
        }

        boolean isPositive = source >= 0.0;
        source = Math.abs(source);
        int scale = (source >= 1.0) ? decimals : precision;

        long intPart = (long) Math.floor(source);
        long tenScale = tenPow(scale);
        double fracUnroundedPart = (source - intPart) * tenScale;
        long fracPart = Math.round(fracUnroundedPart);
        if (fracPart >= tenScale) {
            intPart++;
            fracPart -= tenScale;
        }
        if (fracPart != 0L) {
            // Remove trailing zeroes
            while (fracPart % 10L == 0L) {
                fracPart = fracPart / 10L;
                scale--;
            }
        }

        if (intPart != 0L || fracPart != 0L) {
            // non-zero value
            if (!isPositive) {
                // negative value, insert sign
                target.append('-');
            }
            // append integer part
            target.append(intPart);
            if (fracPart != 0L) {
                // append fractional part
                target.append('.');
                // insert leading zeroes
                while (scale > 0 && fracPart < tenPow(--scale)) {
                    target.append('0');
                }
                target.append(fracPart);
            }
        } else {
            target.append('0');
        }
    }

    /**
     * Returns the exponent of the given value
     *
     * @param value the value to get the exponent from
     * @return the value's exponent
     */
    public static int getExponant(double value) {
        // See Double.doubleToRawLongBits javadoc or IEEE-754 spec
        // to have this algorithm
        long exp = Double.doubleToRawLongBits(value) & 0x7ff0000000000000L;
        exp = exp >> 52;
        return (int) (exp - 1023L);
    }

    /**
     * Returns true if the rounding is considered to use too many digits
     * of the double for a fast rounding
     *
     * @param source the source to round
     * @param scale the scale to round at
     * @return true if the rounding will potentially use too many digits
     */
    private static boolean tooManyDigitsUsed(double source, int scale) {
        return getExponant(source) + scale >= 14;
    }

    /**
     * Returns true if the given source is considered to be too close
     * of a rounding value for the given scale.
     *
     * @param source the source to round
     * @param scale the scale to round at
     * @return true if the source will be potentially rounded at the scale
     */
    private static boolean tooCloseToRound(double source, int scale) {
        source = Math.abs(source);
        long intPart = (long) Math.floor(source);
        double fracPart = (source - intPart) * tenPow(scale);
        double distanceToRound = Math.abs(fracPart - Math.floor(fracPart) - 0.5);
        // For huge scale, we have to reduce the range.
        double range = scale > 12 ? .1 : .001;
        return distanceToRound <= range;
        // .001 range: Totally arbitrary range,
        // I never had a failure in 10e7 random tests with this value
        // May be JVM dependent or architecture dependent
    }
}