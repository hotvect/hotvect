package com.eshioji.hotvect.vw;

class Utils {
    private Utils(){}
    //Source code apdated from https://stackoverflow.com/a/8555166/234901
    // Under CC-BY-SA 3.0 Courtesy of Peter Lawrey
    public static void append(StringBuilder sb, double d) {
        if (d < 0) {
            sb.append('-');
            d = -d;
        }
        if (d * 1e6 + 0.5 > Long.MAX_VALUE) {
            // TODO write a fall back.
            throw new IllegalArgumentException("number too large");
        }
        long scaled = (long) (d * 1e6 + 0.5);
        long factor = 1000000;
        int scale = 7;
        long scaled2 = scaled / 10;
        while (factor <= scaled2) {
            factor *= 10;
            scale++;
        }
        while (scale > 0) {
            if (scale == 6)
                sb.append('.');
            long c = scaled / factor % 10;
            factor /= 10;
            sb.append((char) ('0' + c));
            scale--;
        }
    }
}
